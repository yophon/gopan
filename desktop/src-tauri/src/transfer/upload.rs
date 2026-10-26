//! 上传一个文件(协议同网页 / Android,doc/03):算 sha256 → initUpload(秒传直接完)→ 缺的分片并发 PUT → completeUpload 轮询定稿。
//!
//! - 续传靠服务端:库里有会话 id 就先 uploadSession 对账,只传它说缺的片;本地文件改过(大小或修改时间变了)就重算哈希开新会话;
//! - 预签名 1 小时过期:取到 50 分钟后换一批,PUT 403 时也换;
//! - 撞名、会话过期、传输助手文件夹没了:关掉旧会话重来,最多 3 次;
//! - 传到传输助手文件夹里的文件,服务端自动记成一条消息,这边不用再发。

use super::{engine, io_fail, Kind, Live, NewTask, Outcome, RunError, Task};
use crate::api::{Api, ApiError};
use futures_util::StreamExt;
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::io::{Read, SeekFrom};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant, UNIX_EPOCH};
use tauri::{AppHandle, Manager};
use tokio::io::{AsyncReadExt, AsyncSeekExt};

const SESSION_FIELDS: &str = "id partSize partUrls { partNumber url } uploadedParts status";
const MAX_RESTARTS: u32 = 3;
const PART_CONCURRENCY: usize = 3;
const URL_MAX_AGE: Duration = Duration::from_secs(50 * 60);

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct Session {
    id: String,
    part_size: i64,
    part_urls: Vec<PartUrl>,
    uploaded_parts: Vec<i64>,
    status: String,
}

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct PartUrl {
    part_number: i64,
    url: String,
}

#[derive(Deserialize)]
struct IdOnly {
    id: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct InitData {
    init_upload: Init,
}

#[derive(Deserialize)]
struct Init {
    instant: bool,
    node: Option<IdOnly>,
    session: Option<Session>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SessionData {
    upload_session: Session,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CompleteData {
    complete_upload: IdOnly,
}

/// 这一轮怎么收场
enum Step {
    /// 关掉会话从 initUpload 重来;[refresh_chat] 先重新确认传输助手文件夹
    Restart {
        reason: String,
        refresh_chat: bool,
    },
    Run(RunError),
}

impl From<ApiError> for Step {
    fn from(e: ApiError) -> Self {
        Step::Run(e.into())
    }
}

impl From<RunError> for Step {
    fn from(e: RunError) -> Self {
        Step::Run(e)
    }
}

fn restart(reason: impl Into<String>) -> Step {
    Step::Restart { reason: reason.into(), refresh_chat: false }
}

fn gone(e: &ApiError) -> bool {
    matches!(e.code(), "NOT_FOUND" | "UPLOAD_EXPIRED")
}

pub async fn run(app: &AppHandle, task: &Task, live: &Arc<Live>) -> Result<Outcome, RunError> {
    let eng = engine(app);
    let path = PathBuf::from(&task.local_path);
    let meta = tokio::fs::metadata(&path).await.map_err(|_| RunError::Fail("本地文件不见了(被移走或删掉了)".into()))?;
    if !meta.is_file() {
        return Err(RunError::Fail("不是文件".into()));
    }
    let size = meta.len() as i64;
    if size == 0 {
        return Err(RunError::Fail("空文件传不上去".into()));
    }
    let mtime = meta.modified().ok().and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map(|d| d.as_millis() as i64).unwrap_or(0);

    let mut session = task.session_id.clone();
    let sha = match &task.sha256 {
        Some(s) if size == task.size && mtime == task.src_mtime => s.clone(),
        _ => {
            if let Some(s) = session.take() {
                abort_session(app, &s).await;
            }
            live.phase("hashing");
            live.total.store(size, Ordering::Relaxed);
            live.done.store(0, Ordering::Relaxed);
            let h = hash_file(path.clone(), live.clone()).await.map_err(io_fail)?;
            eng.db.set_upload_source(task.id, &h, size, mtime);
            h
        }
    };
    live.total.store(size, Ordering::Relaxed);
    live.done.store(0, Ordering::Relaxed);

    let mut parent = task.parent_id.clone();
    if task.to_chat && parent.is_none() {
        parent = Some(chat_folder(app).await?);
        eng.db.set_parent(task.id, parent.as_deref());
    }
    let mut restarts = 0;
    loop {
        live.phase("preparing");
        match attempt(app, task, live, &path, &sha, size, parent.as_deref(), &mut session).await {
            Ok(node_id) => return Ok(Outcome { node_id: Some(node_id), local_path: None, size }),
            Err(Step::Restart { reason, refresh_chat }) => {
                log::info!("上传任务 {} 重来:{reason}", task.id);
                if let Some(s) = session.take() {
                    abort_session(app, &s).await;
                    eng.db.set_session(task.id, None);
                }
                restarts += 1;
                if restarts > MAX_RESTARTS {
                    return Err(RunError::Fail(reason));
                }
                if refresh_chat && task.to_chat {
                    parent = Some(chat_folder(app).await?);
                    eng.db.set_parent(task.id, parent.as_deref());
                }
            }
            // 会话留着,下次接着传
            Err(Step::Run(e)) => return Err(e),
        }
    }
}

#[allow(clippy::too_many_arguments)]
async fn attempt(
    app: &AppHandle,
    task: &Task,
    live: &Arc<Live>,
    path: &Path,
    sha: &str,
    size: i64,
    parent: Option<&str>,
    session: &mut Option<String>,
) -> Result<String, Step> {
    let api = app.state::<Api>();
    let eng = engine(app);
    let mut s = match session.clone() {
        Some(id) => match fetch_session(app, &id).await {
            Ok(s) if matches!(s.status.as_str(), "uploading" | "completing" | "done" | "failed") => s,
            Ok(s) => return Err(restart(format!("上传会话已失效({})", s.status))),
            Err(e) if gone(&e) => return Err(restart("上传会话过期了")),
            Err(e) => return Err(e.into()),
        },
        None => {
            let vars = json!({ "parentId": parent, "name": task.name, "sha256": sha, "size": size, "replace": task.replace });
            let q = format!(
                "mutation DesktopInitUpload($parentId: ID, $name: String!, $sha256: String!, $size: Int64!, $replace: Boolean) {{ initUpload(parentId: $parentId, name: $name, sha256: $sha256, size: $size, replace: $replace) {{ instant node {{ id }} session {{ {SESSION_FIELDS} }} }} }}"
            );
            let init = match api.gql::<InitData>(app, &q, vars).await {
                Ok(d) => d.init_upload,
                // 秒传在改名与建节点之间撞名:重来一次服务端会挑下一个空名字
                Err(e) if e.code() == "NAME_CONFLICT" => return Err(restart(e.to_string())),
                Err(e) if task.to_chat && matches!(e.code(), "NOT_FOUND" | "NOT_A_FOLDER") => {
                    return Err(Step::Restart { reason: e.to_string(), refresh_chat: true })
                }
                Err(e) => return Err(e.into()),
            };
            if init.instant {
                live.done.store(size, Ordering::Relaxed);
                return init.node.map(|n| n.id).ok_or_else(|| Step::Run(RunError::Retry("服务器没回文件信息".into())));
            }
            let s = init.session.ok_or_else(|| Step::Run(RunError::Retry("服务器没回上传会话".into())))?;
            *session = Some(s.id.clone());
            eng.db.set_session(task.id, Some(&s.id));
            s
        }
    };
    let mut missing_rounds = 0;
    loop {
        if s.status == "uploading" && !s.part_urls.is_empty() {
            live.phase("transferring");
            upload_parts(app, live, path, size, &s).await?;
        }
        live.phase("verifying");
        live.done.store(size, Ordering::Relaxed);
        match complete(app, task, &s.id).await {
            Ok(id) => return Ok(id),
            Err(None) => {
                missing_rounds += 1;
                if missing_rounds > 3 {
                    return Err(Step::Run(RunError::Retry("服务器一直说缺分片".into())));
                }
                s = fetch_session(app, &s.id).await.map_err(|e| if gone(&e) { restart("上传会话过期了") } else { e.into() })?;
            }
            Err(Some(step)) => return Err(step),
        }
    }
}

async fn fetch_session(app: &AppHandle, id: &str) -> Result<Session, ApiError> {
    let q = format!("query DesktopUploadSession($id: ID!) {{ uploadSession(id: $id) {{ {SESSION_FIELDS} }} }}");
    let d: SessionData = app.state::<Api>().gql(app, &q, json!({ "id": id })).await?;
    Ok(d.upload_session)
}

pub async fn abort_session(app: &AppHandle, id: &str) {
    let r = app
        .state::<Api>()
        .gql::<Value>(app, "mutation DesktopAbortUpload($id: ID!) { abortUpload(sessionId: $id) }", json!({ "id": id }))
        .await;
    if let Err(e) = r {
        log::debug!("abortUpload 失败(忽略):{e}");
    }
}

async fn chat_folder(app: &AppHandle) -> Result<String, RunError> {
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct D {
        ensure_chat_folder: IdOnly,
    }
    let d: D = app.state::<Api>().gql(app, "mutation DesktopChatFolder { ensureChatFolder { id } }", json!({})).await?;
    Ok(d.ensure_chat_folder.id)
}

/// 文件夹发到传输助手:整个文件夹传完后发一条指向它的消息(文件自动成消息,文件夹不会)
pub async fn send_chat_node(app: &AppHandle, node_id: &str) -> Result<(), ApiError> {
    app.state::<Api>()
        .gql::<Value>(
            app,
            "mutation DesktopSendNode($nodeId: ID) { sendChatMessage(nodeId: $nodeId) { id } }",
            json!({ "nodeId": node_id }),
        )
        .await
        .map(|_| ())
}

/// 定稿;Err(None) = 服务端说缺分片,对账后再传
async fn complete(app: &AppHandle, task: &Task, sid: &str) -> Result<String, Option<Step>> {
    let api = app.state::<Api>();
    let q = "mutation DesktopCompleteUpload($id: ID!) { completeUpload(sessionId: $id, etags: []) { id } }";
    let started = Instant::now();
    let mut polls = 0u32;
    let mut failures = 0u64;
    loop {
        let e = match api.gql::<CompleteData>(app, q, json!({ "id": sid })).await {
            Ok(d) => return Ok(d.complete_upload.id),
            Err(e) => e,
        };
        match e.code() {
            "UPLOAD_PROCESSING" => {
                tokio::time::sleep(poll_delay(polls, started.elapsed())).await;
                polls += 1;
            }
            "MISSING_PART" => return Err(None),
            "COMPLETE_FAILED" => {
                failures += 1;
                if failures >= 3 {
                    return Err(Some(restart(e.to_string())));
                }
                tokio::time::sleep(Duration::from_secs(2 * failures)).await;
            }
            // 定稿时目标位置被占(init 不预留名字):重来,服务端会挑下一个空名字
            "NAME_CONFLICT" | "UPLOAD_EXPIRED" | "BAD_SESSION_STATE" => return Err(Some(restart(e.to_string()))),
            "HASH_MISMATCH" | "SIZE_MISMATCH" => {
                // 上传途中文件被改了:下次重新算哈希、开新会话
                abort_session(app, sid).await;
                engine(app).db.reset_upload(task.id);
                return Err(Some(Step::Run(RunError::Retry("上传途中文件被改动了,重新传".into()))));
            }
            "NOT_FOUND" | "NOT_A_FOLDER" => {
                if task.to_chat {
                    return Err(Some(Step::Restart { reason: e.to_string(), refresh_chat: true }));
                }
                return Err(Some(match fetch_session(app, sid).await {
                    Err(ge) if gone(&ge) => restart("上传会话过期了"),
                    Ok(s) if s.status == "done" => Step::Run(RunError::Fail("文件传完后在云端被删了".into())),
                    _ => Step::Run(RunError::Fail("目标文件夹已经不在了(删了或移到回收站)".into())),
                }));
            }
            _ => return Err(Some(e.into())),
        }
    }
}

/// UPLOAD_PROCESSING 的轮询间隔:前 10 次 1 秒,之后 ×1.5 封顶 5 秒;10 分钟后 30 秒(与 Android 一致)
fn poll_delay(n: u32, elapsed: Duration) -> Duration {
    if elapsed > Duration::from_secs(600) {
        return Duration::from_secs(30);
    }
    if n < 10 {
        return Duration::from_secs(1);
    }
    let ms = (1000.0 * 1.5f64.powi((n - 9) as i32)).min(5000.0);
    Duration::from_millis(ms as u64)
}

struct Urls {
    map: HashMap<i64, String>,
    fetched: Instant,
    generation: u64,
}

async fn upload_parts(app: &AppHandle, live: &Arc<Live>, path: &Path, size: i64, s: &Session) -> Result<(), Step> {
    let part_size = s.part_size.max(1);
    let part_len = |n: i64| part_size.min(size - (n - 1) * part_size);
    let done: i64 = s.uploaded_parts.iter().map(|n| part_len(*n)).sum();
    live.done.store(done, Ordering::Relaxed);
    let urls = tokio::sync::Mutex::new(Urls {
        map: s.part_urls.iter().map(|p| (p.part_number, p.url.clone())).collect(),
        fetched: Instant::now(),
        generation: 0,
    });
    let todo: Vec<i64> = s.part_urls.iter().map(|p| p.part_number).filter(|n| !s.uploaded_parts.contains(n)).collect();
    let urls = &urls;
    let mut results = futures_util::stream::iter(todo)
        .map(|n| put_part(app, live, path, &s.id, n, (n - 1) * part_size, part_len(n), urls))
        .buffer_unordered(PART_CONCURRENCY);
    while let Some(r) = results.next().await {
        r?;
    }
    Ok(())
}

/// 换一批分片地址(单飞:等锁时别人已经换过就不再请求)
async fn refresh_urls(app: &AppHandle, sid: &str, urls: &tokio::sync::Mutex<Urls>, seen: u64, force: bool) -> Result<(), Step> {
    let mut u = urls.lock().await;
    if u.generation != seen || (!force && u.fetched.elapsed() < URL_MAX_AGE) {
        return Ok(());
    }
    let s = fetch_session(app, sid).await.map_err(|e| if gone(&e) { restart("上传会话过期了") } else { e.into() })?;
    if s.status != "uploading" {
        return Err(restart(format!("上传会话已失效({})", s.status)));
    }
    for p in s.part_urls {
        u.map.insert(p.part_number, p.url);
    }
    for n in s.uploaded_parts {
        u.map.remove(&n);
    }
    u.fetched = Instant::now();
    u.generation += 1;
    Ok(())
}

#[allow(clippy::too_many_arguments)]
async fn put_part(
    app: &AppHandle,
    live: &Arc<Live>,
    path: &Path,
    sid: &str,
    n: i64,
    offset: i64,
    len: i64,
    urls: &tokio::sync::Mutex<Urls>,
) -> Result<(), Step> {
    let http = app.state::<Api>().http.clone();
    let mut transient = 0u32;
    let mut signature = 0u32;
    let mut refreshed_for_missing = false;
    loop {
        let (url, generation, stale) = {
            let u = urls.lock().await;
            (u.map.get(&n).cloned(), u.generation, u.fetched.elapsed() >= URL_MAX_AGE)
        };
        let url = match url {
            Some(_) if stale => {
                refresh_urls(app, sid, urls, generation, false).await?;
                continue;
            }
            Some(u) => u,
            None if !refreshed_for_missing => {
                refreshed_for_missing = true;
                refresh_urls(app, sid, urls, generation, true).await?;
                continue;
            }
            // 换过一批还没有它的地址:服务端说这片已经有了
            None => return Ok(()),
        };
        let sent = Arc::new(AtomicI64::new(0));
        let result = put_once(&http, &url, path, offset, len, live, &sent).await;
        if matches!(result, Ok(s) if (200..300).contains(&s)) {
            return Ok(());
        }
        live.done.fetch_sub(sent.load(Ordering::Relaxed), Ordering::Relaxed);
        match result {
            Ok(403) => {
                if urls.lock().await.generation != generation {
                    continue;
                }
                signature += 1;
                if signature > 2 {
                    return Err(Step::Run(RunError::Fail("存储服务一直拒绝上传签名".into())));
                }
                refresh_urls(app, sid, urls, generation, true).await?;
            }
            Ok(404) => return Err(restart("存储里的上传会话不在了")),
            Ok(s) if s == 408 || s == 429 || s >= 500 => {
                transient += 1;
                if transient > 3 {
                    return Err(Step::Run(RunError::Retry(format!("分片上传失败(HTTP {s})"))));
                }
                tokio::time::sleep(Duration::from_secs(1 << (transient - 1))).await;
            }
            Ok(s) => return Err(Step::Run(RunError::Fail(format!("存储服务拒绝了分片(HTTP {s})")))),
            Err(e) => {
                transient += 1;
                if transient > 3 {
                    return Err(Step::Run(RunError::Retry(format!("分片上传失败:{e}"))));
                }
                tokio::time::sleep(Duration::from_secs(1 << (transient - 1))).await;
            }
        }
    }
}

/// PUT 文件的 [offset, offset+len) 段;边读边发,进度加到 [live]
async fn put_once(
    http: &reqwest::Client,
    url: &str,
    path: &Path,
    offset: i64,
    len: i64,
    live: &Arc<Live>,
    sent: &Arc<AtomicI64>,
) -> Result<u16, String> {
    let mut f = tokio::fs::File::open(path).await.map_err(|e| e.to_string())?;
    f.seek(SeekFrom::Start(offset as u64)).await.map_err(|e| e.to_string())?;
    let reader = f.take(len as u64);
    let (live, sent) = (live.clone(), sent.clone());
    let stream = futures_util::stream::unfold(reader, move |mut r| {
        let (live, sent) = (live.clone(), sent.clone());
        async move {
            let mut buf = vec![0u8; 256 * 1024];
            match r.read(&mut buf).await {
                Ok(0) => None,
                Ok(n) => {
                    buf.truncate(n);
                    live.done.fetch_add(n as i64, Ordering::Relaxed);
                    sent.fetch_add(n as i64, Ordering::Relaxed);
                    Some((Ok::<Vec<u8>, std::io::Error>(buf), r))
                }
                Err(e) => Some((Err(e), r)),
            }
        }
    });
    let res = http
        .put(url)
        .header(reqwest::header::CONTENT_LENGTH, len)
        .body(reqwest::Body::wrap_stream(stream))
        .send()
        .await
        .map_err(|e| e.to_string())?;
    let status = res.status().as_u16();
    let _ = res.bytes().await;
    Ok(status)
}

/// 整个文件的 sha256(阻塞线程里读),进度报给 [live]
async fn hash_file(path: PathBuf, live: Arc<Live>) -> std::io::Result<String> {
    tokio::task::spawn_blocking(move || {
        let mut f = std::fs::File::open(&path)?;
        let mut h = Sha256::new();
        let mut buf = vec![0u8; 1 << 20];
        let mut done = 0i64;
        loop {
            let n = f.read(&mut buf)?;
            if n == 0 {
                break;
            }
            h.update(&buf[..n]);
            done += n as i64;
            live.done.store(done, Ordering::Relaxed);
        }
        Ok(hex::encode(h.finalize()))
    })
    .await
    .map_err(std::io::Error::other)?
}

async fn create_folder(app: &AppHandle, parent: Option<&str>, name: &str) -> Result<String, RunError> {
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct D {
        create_folder: IdOnly,
    }
    let d: D = app
        .state::<Api>()
        .gql(
            app,
            "mutation DesktopCreateFolder($parentId: ID, $name: String!) { createFolder(parentId: $parentId, name: $name) { id } }",
            json!({ "parentId": parent, "name": name }),
        )
        .await?;
    Ok(d.create_folder.id)
}

/// [parent] 下名叫 [name] 的文件夹;没有就建一个(与网页上传文件夹一样:同名文件夹合并进去)
async fn find_or_create_folder(app: &AppHandle, parent: Option<&str>, name: &str) -> Result<String, RunError> {
    #[derive(Deserialize)]
    struct D {
        children: Page,
    }
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Page {
        items: Vec<Item>,
        next_cursor: Option<String>,
    }
    #[derive(Deserialize)]
    struct Item {
        id: String,
        name: String,
        kind: String,
    }
    let api = app.state::<Api>();
    let mut cursor: Option<String> = None;
    loop {
        let d: D = api
            .gql(
                app,
                "query DesktopFolderChildren($parentId: ID, $cursor: String) { children(parentId: $parentId, cursor: $cursor) { items { id name kind } nextCursor } }",
                json!({ "parentId": parent, "cursor": cursor }),
            )
            .await?;
        if let Some(hit) = d.children.items.into_iter().find(|i| i.kind == "FOLDER" && i.name == name) {
            return Ok(hit.id);
        }
        match d.children.next_cursor {
            Some(c) if !c.is_empty() => cursor = Some(c),
            _ => break,
        }
    }
    create_folder(app, parent, name).await
}

/// 本地文件夹:云端有同名文件夹就合并进去,没有就建;子目录同样处理,文件成子任务(和已有文件撞名时服务端自动改名)
pub async fn expand(app: &AppHandle, batch: &Task) -> Result<(Vec<NewTask>, Option<String>, Option<String>), RunError> {
    let root_local = PathBuf::from(&batch.local_path);
    if !tokio::fs::metadata(&root_local).await.map(|m| m.is_dir()).unwrap_or(false) {
        return Err(RunError::Fail("本地文件夹不见了".into()));
    }
    let parent = if batch.to_chat { Some(chat_folder(app).await?) } else { batch.parent_id.clone() };
    let root_id = find_or_create_folder(app, parent.as_deref(), &batch.name).await?;
    let mut kids = Vec::new();
    let mut stack = vec![(root_local, root_id.clone())];
    while let Some((dir, cloud)) = stack.pop() {
        let mut rd = tokio::fs::read_dir(&dir).await.map_err(io_fail)?;
        let mut entries = Vec::new();
        while let Some(e) = rd.next_entry().await.map_err(io_fail)? {
            entries.push(e);
        }
        entries.sort_by_key(|e| e.file_name());
        for e in entries {
            let Ok(ft) = e.file_type().await else { continue };
            // 链接、快捷方式指向别处,不跟进去(免得绕圈)
            if ft.is_symlink() {
                continue;
            }
            let name = e.file_name().to_string_lossy().into_owned();
            if ft.is_dir() {
                let id = find_or_create_folder(app, Some(&cloud), &name).await?;
                stack.push((e.path(), id));
            } else if ft.is_file() {
                let size = e.metadata().await.map(|m| m.len() as i64).unwrap_or(0);
                // 空文件服务端不收,跳过
                if size == 0 {
                    continue;
                }
                kids.push(NewTask {
                    kind: Some(Kind::Upload),
                    batch_id: Some(batch.id),
                    name,
                    parent_id: Some(cloud.clone()),
                    local_path: e.path().to_string_lossy().into_owned(),
                    size,
                    ..Default::default()
                });
            }
        }
    }
    Ok((kids, None, Some(root_id)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn processing_polls_back_off_like_android() {
        let s = Duration::from_secs;
        assert_eq!(poll_delay(0, s(0)), s(1));
        assert_eq!(poll_delay(9, s(5)), s(1));
        assert_eq!(poll_delay(10, s(10)), Duration::from_millis(1500));
        assert_eq!(poll_delay(30, s(60)), s(5));
        assert_eq!(poll_delay(3, s(700)), s(30));
    }
}
