//! 下载一个文件:先写到「名字.gopan-<任务号>.part」,断了用 Range 接着下;下完核对 sha256,再改成正式名字(同名加 (1))。
//! 预签名地址 15 分钟有效,每次开跑现取;云端内容在两次之间被覆盖写(sha256 变了)就从头下。

use super::names::{exists, safe_name, unique_path};
use super::{engine, io_fail, net_retry, Live, NewTask, Outcome, RunError, Task};
use crate::api::Api;
use futures_util::StreamExt;
use serde::Deserialize;
use serde_json::json;
use sha2::{Digest, Sha256};
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::atomic::Ordering;
use std::sync::Mutex;
use std::time::Instant;
use tauri::{AppHandle, Manager};
use tokio::io::AsyncWriteExt;

const NODE: &str = "query DesktopDownloadTarget($id: ID!) { node(id: $id) { id name kind size sha256 downloadUrl } }";
const CHILDREN: &str =
    "query DesktopDownloadTree($parentId: ID!, $cursor: String) { children(parentId: $parentId, cursor: $cursor) { items { id name kind size } nextCursor } }";

/// 改成正式名字这一步串行:两个同名文件同时下完时别都挑中同一个 "(1)"
static FINALIZE: Mutex<()> = Mutex::new(());

#[derive(Deserialize)]
struct NodeData {
    node: Target,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Target {
    kind: String,
    size: Option<i64>,
    sha256: Option<String>,
    download_url: Option<String>,
}

pub fn part_path(intended: &Path, id: i64) -> PathBuf {
    let name = intended.file_name().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
    intended.with_file_name(format!("{name}.gopan-{id}.part"))
}

pub async fn run(app: &AppHandle, task: &Task, live: &Live) -> Result<Outcome, RunError> {
    let eng = engine(app);
    let api = app.state::<Api>();
    let node_id = task.node_id.as_deref().ok_or_else(|| RunError::Fail("任务缺少文件 id".into()))?;
    live.phase("preparing");
    let t: NodeData = api.gql(app, NODE, json!({ "id": node_id })).await?;
    let t = t.node;
    if t.kind != "FILE" {
        return Err(RunError::Fail("这是个文件夹".into()));
    }
    let url = t.download_url.ok_or_else(|| RunError::Fail("拿不到下载地址".into()))?;
    let size = t.size.unwrap_or(0);
    let sha = t.sha256.unwrap_or_default().to_ascii_lowercase();

    let intended = PathBuf::from(&task.local_path);
    let dir = intended.parent().ok_or_else(|| RunError::Fail("下载目录不对".into()))?.to_path_buf();
    tokio::fs::create_dir_all(&dir).await.map_err(io_fail)?;
    let part = part_path(&intended, task.id);

    let mut have = tokio::fs::metadata(&part).await.map(|m| m.len() as i64).unwrap_or(0);
    let same_content = task.sha256.as_deref() == Some(sha.as_str()) && task.size == size;
    if have > 0 && (!same_content || have > size) {
        let _ = tokio::fs::remove_file(&part).await;
        have = 0;
    }
    if !same_content {
        eng.db.set_download_target(task.id, &sha, size);
    }
    live.total.store(size, Ordering::Relaxed);
    live.done.store(have, Ordering::Relaxed);

    // 接着下:已有的那段先算进哈希
    let mut hasher = Sha256::new();
    if have > 0 {
        live.phase("verifying");
        hasher = hash_prefix(part.clone(), have).await.map_err(io_fail)?;
    }

    if have < size || size == 0 {
        live.phase("transferring");
        let mut req = api.http.get(&url);
        if have > 0 {
            req = req.header(reqwest::header::RANGE, format!("bytes={have}-"));
        }
        let res = req.send().await.map_err(net_retry)?;
        let status = res.status().as_u16();
        let file = match status {
            206 if have > 0 => tokio::fs::OpenOptions::new().append(true).open(&part).await.map_err(io_fail)?,
            200 => {
                // 不认 Range 或从头开始:清掉重来
                have = 0;
                hasher = Sha256::new();
                live.done.store(0, Ordering::Relaxed);
                tokio::fs::File::create(&part).await.map_err(io_fail)?
            }
            416 => {
                let _ = tokio::fs::remove_file(&part).await;
                return Err(RunError::Retry("续传位置不对,从头下".into()));
            }
            403 => return Err(RunError::Retry("下载地址过期了".into())),
            404 => return Err(RunError::Fail("云端文件不在了".into())),
            s if s == 408 || s == 429 || s >= 500 => return Err(RunError::Retry(format!("存储服务忙(HTTP {s})"))),
            s => return Err(RunError::Fail(format!("下载失败(HTTP {s})"))),
        };
        let mut out = tokio::io::BufWriter::with_capacity(1 << 20, file);
        let mut stream = res.bytes_stream();
        let mut saved = Instant::now();
        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(net_retry)?;
            out.write_all(&chunk).await.map_err(io_fail)?;
            hasher.update(&chunk);
            have += chunk.len() as i64;
            live.done.store(have, Ordering::Relaxed);
            if saved.elapsed().as_secs() >= 2 {
                eng.db.set_progress(task.id, have);
                saved = Instant::now();
            }
        }
        out.flush().await.map_err(io_fail)?;
        out.into_inner().sync_all().await.map_err(io_fail)?;
        eng.db.set_progress(task.id, have);
    }

    if have < size {
        return Err(RunError::Retry("连接断了".into()));
    }
    if have > size {
        let _ = tokio::fs::remove_file(&part).await;
        return Err(RunError::Retry("下载的大小不对,从头下".into()));
    }
    live.phase("verifying");
    let got = hex::encode(hasher.finalize());
    if !sha.is_empty() && got != sha {
        let _ = tokio::fs::remove_file(&part).await;
        return Err(RunError::Retry("下载的内容校验不对,从头下".into()));
    }

    let name = intended.file_name().map(|s| s.to_string_lossy().into_owned()).unwrap_or_else(|| safe_name(&task.name));
    let final_path = {
        let _g = FINALIZE.lock().unwrap();
        let p = unique_path(&dir, &name, exists);
        std::fs::rename(&part, &p).map_err(io_fail)?;
        p
    };
    Ok(Outcome { node_id: None, local_path: Some(final_path.to_string_lossy().into_owned()), size })
}

/// 已下的前 [len] 字节的哈希状态(读本地,放到阻塞线程里)
async fn hash_prefix(path: PathBuf, len: i64) -> std::io::Result<Sha256> {
    tokio::task::spawn_blocking(move || {
        let mut f = std::fs::File::open(&path)?.take(len as u64);
        let mut h = Sha256::new();
        let mut buf = vec![0u8; 1 << 20];
        loop {
            let n = f.read(&mut buf)?;
            if n == 0 {
                break;
            }
            h.update(&buf[..n]);
        }
        Ok(h)
    })
    .await
    .map_err(std::io::Error::other)?
}

#[derive(Deserialize)]
struct ChildrenData {
    children: Page,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Page {
    items: Vec<Child>,
    next_cursor: Option<String>,
}

#[derive(Deserialize)]
struct Child {
    id: String,
    name: String,
    kind: String,
    size: Option<i64>,
}

/// 文件夹:在本地建同名目录(重名加 (1)),列出整棵树,子目录先建好,文件成子任务
pub async fn expand(app: &AppHandle, batch: &Task) -> Result<(Vec<NewTask>, Option<String>, Option<String>), RunError> {
    let api = app.state::<Api>();
    let root_id = batch.node_id.clone().ok_or_else(|| RunError::Fail("任务缺少文件夹 id".into()))?;
    let base = PathBuf::from(&batch.local_path);
    tokio::fs::create_dir_all(&base).await.map_err(io_fail)?;
    let root = {
        let _g = FINALIZE.lock().unwrap();
        let p = unique_path(&base, &safe_name(&batch.name), exists);
        std::fs::create_dir_all(&p).map_err(io_fail)?;
        p
    };
    let mut kids = Vec::new();
    let mut stack = vec![(root_id, root.clone())];
    while let Some((folder, local)) = stack.pop() {
        let mut cursor: Option<String> = None;
        loop {
            let page: ChildrenData = api.gql(app, CHILDREN, json!({ "parentId": folder, "cursor": cursor })).await?;
            for c in page.children.items {
                if c.kind == "FOLDER" {
                    let p = unique_path(&local, &safe_name(&c.name), exists);
                    tokio::fs::create_dir_all(&p).await.map_err(io_fail)?;
                    stack.push((c.id, p));
                } else {
                    kids.push(NewTask {
                        kind: Some(super::Kind::Download),
                        batch_id: Some(batch.id),
                        name: c.name.clone(),
                        node_id: Some(c.id),
                        local_path: local.join(safe_name(&c.name)).to_string_lossy().into_owned(),
                        size: c.size.unwrap_or(0),
                        ..Default::default()
                    });
                }
            }
            match page.children.next_cursor {
                Some(c) if !c.is_empty() => cursor = Some(c),
                _ => break,
            }
        }
    }
    Ok((kids, Some(root.to_string_lossy().into_owned()), None))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn part_file_sits_next_to_the_target() {
        let p = part_path(Path::new("C:/d/报告.pdf"), 42);
        assert_eq!(p, Path::new("C:/d/报告.pdf.gopan-42.part"));
    }
}
