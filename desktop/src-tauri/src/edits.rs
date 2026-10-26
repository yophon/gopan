//! 用本地程序打开(doc/29):文件先下到缓存目录,用系统默认程序打开(双击 .docx 就是 Word);
//! 之后每 2 秒看一眼这份副本,存盘后(停下来不再变)自动传成云端这个文件的新版本,旧内容进历史版本(doc/24)。
//!
//! - 打开期间云端被别处改过:不覆盖,另存一份「名字(电脑上的修改)」并提醒,这一项不再跟踪;
//! - 副本还在、内容没变时再打开不重下;云端更新了就重下;
//! - 关掉程序再打开照样接着盯,期间存过的会补传;登出时整个缓存清掉。

use crate::api::Api;
use crate::transfer::{self, EditRow};
use crate::{notify, ui};
use serde::{Deserialize, Serialize};
use serde_json::json;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{Duration, UNIX_EPOCH};
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_opener::OpenerExt;

pub const EDITS_EVENT: &str = "desktop://edits";
const POLL: Duration = Duration::from_secs(2);
/// 冲突、停掉的记录留几天(之后连缓存一起清)
const KEEP_DONE_MS: i64 = 7 * 24 * 3600 * 1000;

#[derive(Default)]
pub struct Edits {
    /// 看到变了、还在等它停下来的:节点 → (修改时间, 大小)
    settling: Mutex<HashMap<String, (i64, i64)>>,
}

pub fn init(app: &AppHandle) {
    app.manage(Edits::default());
    prune(app);
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        loop {
            tokio::time::sleep(POLL).await;
            if app.state::<crate::auth::Auth>().ready() {
                tick(&app).await;
            }
        }
    });
}

fn db(app: &AppHandle) -> &transfer::Engine {
    // Engine 由 transfer::init 先管起来
    app.state::<transfer::Engine>().inner()
}

fn cache_root(app: &AppHandle) -> PathBuf {
    app.path().app_local_data_dir().unwrap_or_else(|_| std::env::temp_dir().join("gopan-desktop")).join("open")
}

fn cache_path(app: &AppHandle, node_id: &str, name: &str) -> PathBuf {
    let dir: String = node_id.chars().filter(|c| c.is_ascii_alphanumeric() || *c == '-').collect();
    cache_root(app).join(dir).join(transfer::safe_name(name))
}

fn stat(p: &Path) -> Option<(i64, i64)> {
    let m = std::fs::metadata(p).ok()?;
    let mtime = m.modified().ok()?.duration_since(UNIX_EPOCH).ok()?.as_millis() as i64;
    Some((mtime, m.len() as i64))
}

fn hash_file(p: &Path) -> std::io::Result<String> {
    let mut f = std::fs::File::open(p)?;
    let mut h = Sha256::new();
    let mut buf = vec![0u8; 1 << 20];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
    }
    Ok(hex::encode(h.finalize()))
}

/// 「报告.docx」→「报告(电脑上的修改).docx」
fn conflict_name(name: &str) -> String {
    match name.rfind('.') {
        Some(i) if i > 0 => format!("{}(电脑上的修改){}", &name[..i], &name[i..]),
        _ => format!("{name}(电脑上的修改)"),
    }
}

/// 本地副本有没有还没传回去的改动
fn unsynced(e: &EditRow) -> bool {
    let p = PathBuf::from(&e.local_path);
    stat(&p).map(|s| s != (e.mtime, e.size)).unwrap_or(false) || e.synced_sha != e.base_sha
}

fn changed(app: &AppHandle) {
    let _ = app.emit(EDITS_EVENT, views(app));
}

// ---- 打开 ----

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct NodeInfo {
    name: String,
    kind: String,
    parent_id: Option<String>,
    sha256: Option<String>,
    size: Option<i64>,
}

async fn node(app: &AppHandle, id: &str) -> Result<NodeInfo, crate::api::ApiError> {
    #[derive(Deserialize)]
    struct D {
        node: NodeInfo,
    }
    let d: D = app
        .state::<Api>()
        .gql(app, "query DesktopEditNode($id: ID!) { node(id: $id) { name kind parentId sha256 size } }", json!({ "id": id }))
        .await?;
    Ok(d.node)
}

fn open_file(app: &AppHandle, p: &Path) -> Result<(), String> {
    // 本机自动化测试时不真的打开(免得在桌面上弹出程序);只有开发版认这个开关
    if cfg!(debug_assertions) && std::env::var_os("GOPAN_DESKTOP_NO_OPEN").is_some() {
        return Ok(());
    }
    app.opener().open_path(p.to_string_lossy(), None::<&str>).map_err(|e| format!("打不开:{e}"))
}

/// 用本地程序打开云端的一个文件。返回 true = 直接打开了,false = 先下载,下完自动打开
#[tauri::command]
pub async fn desktop_open_local(app: AppHandle, id: String) -> Result<bool, String> {
    let n = node(&app, &id).await.map_err(|e| e.to_string())?;
    if n.kind != "FILE" {
        return Err("文件夹不能用本地程序打开".into());
    }
    let sha = n.sha256.unwrap_or_default().to_ascii_lowercase();
    let eng = db(&app);
    if let Some(mut e) = eng.db.edit(&id) {
        let p = PathBuf::from(&e.local_path);
        let tracking = matches!(e.state.as_str(), "watching" | "uploading");
        if p.exists() && tracking && (unsynced(&e) || sha == e.base_sha) {
            // 副本和云端一样,或者本地有改动还没传回(由轮询接着传):直接打开
            e.name = n.name.clone();
            e.parent_id = n.parent_id.clone();
            eng.db.put_edit(&e);
            open_file(&app, &p)?;
            changed(&app);
            return Ok(true);
        }
        if eng.db.active_purpose(&id, "open").is_some() {
            return Ok(false); // 已经在下了
        }
    }
    let path = cache_path(&app, &id, &n.name);
    eng.db.put_edit(&EditRow {
        node_id: id.clone(),
        name: n.name.clone(),
        parent_id: n.parent_id.clone(),
        local_path: path.to_string_lossy().into_owned(),
        base_sha: sha,
        synced_sha: String::new(),
        mtime: 0,
        size: n.size.unwrap_or(0),
        uploads: 0,
        state: "downloading".into(),
        note: None,
        updated_at: 0,
    });
    transfer::enqueue_open(&app, &id, &n.name, n.size.unwrap_or(0), &path)?;
    changed(&app);
    Ok(false)
}

// ---- 传输引擎做完「打开」「传回」的任务 ----

pub fn task_done(app: &AppHandle, task: &transfer::Task) {
    let Some(node_id) = task.node_id.clone() else { return };
    let eng = db(app);
    let Some(mut e) = eng.db.edit(&node_id) else { return };
    let sha = task.sha256.clone().unwrap_or_default().to_ascii_lowercase();
    match task.purpose.as_str() {
        "open" => {
            let p = PathBuf::from(&e.local_path);
            let (mtime, size) = stat(&p).unwrap_or((0, 0));
            e.base_sha = sha.clone();
            e.synced_sha = sha;
            e.mtime = mtime;
            e.size = size;
            e.state = "watching".into();
            e.note = None;
            eng.db.put_edit(&e);
            if let Err(msg) = open_file(app, &p) {
                notify::toast(app, "打不开下载好的文件", &msg, None);
            }
        }
        "edit" if task.replace => {
            e.base_sha = sha.clone();
            e.synced_sha = sha;
            e.uploads += 1;
            e.state = "watching".into();
            e.note = None;
            eng.db.put_edit(&e);
        }
        "edit" => {
            // 冲突时另存的那一份传好了:这一项不再跟踪
            e.state = "conflict".into();
            e.note = Some(format!("云端在你编辑期间被改过,你的修改另存成了「{}」", task.name));
            eng.db.put_edit(&e);
            let (a, folder) = (app.clone(), e.parent_id.clone());
            notify::toast(
                app,
                &format!("「{}」有冲突", e.name),
                &format!("云端在你编辑期间被改过,没有覆盖;你的修改另存成了「{}」", task.name),
                Some(Box::new(move || {
                    let h = a.clone();
                    let path = folder.as_ref().map(|f| format!("/drive/{f}")).unwrap_or_else(|| "/drive".into());
                    let _ = a.run_on_main_thread(move || {
                        ui::show_main(&h);
                        crate::navigate_main(&h, &path);
                    });
                })),
            );
        }
        _ => {}
    }
    changed(app);
}

pub fn task_failed(app: &AppHandle, task: &transfer::Task, msg: &str) {
    let Some(node_id) = task.node_id.clone() else { return };
    let eng = db(app);
    let Some(mut e) = eng.db.edit(&node_id) else { return };
    if task.purpose == "open" {
        e.state = "stopped".into();
        e.note = Some(format!("没下载下来:{msg}"));
    } else {
        // 传回失败:回到盯着的状态,任务留在传输列表里能重试;下次存盘也会再传
        e.state = "watching".into();
        e.mtime = 0;
        e.note = Some(format!("传回失败:{msg}"));
        notify::toast(app, &format!("「{}」没传回去", e.name), msg, None);
    }
    eng.db.put_edit(&e);
    changed(app);
}

// ---- 轮询 ----

async fn tick(app: &AppHandle) {
    let eng = db(app);
    for mut e in eng.db.edits().into_iter().filter(|e| e.state == "watching") {
        let p = PathBuf::from(&e.local_path);
        let Some(now) = stat(&p) else {
            // 缓存里的副本被删了:不再跟踪
            eng.db.delete_edit(&e.node_id);
            changed(app);
            continue;
        };
        if now == (e.mtime, e.size) {
            app.state::<Edits>().settling.lock().unwrap().remove(&e.node_id);
            continue;
        }
        // 变了:等它停下来(两次看到一样)再动,免得传到写了一半的
        {
            let edits = app.state::<Edits>();
            let mut settling = edits.settling.lock().unwrap();
            if settling.get(&e.node_id) != Some(&now) {
                settling.insert(e.node_id.clone(), now);
                continue;
            }
            settling.remove(&e.node_id);
        }
        let hp = p.clone();
        // 打不开(程序正锁着写)就下次再看
        let Ok(Ok(sha)) = tauri::async_runtime::spawn_blocking(move || hash_file(&hp)).await else { continue };
        if sha == e.synced_sha {
            e.mtime = now.0;
            e.size = now.1;
            eng.db.put_edit(&e);
            continue;
        }
        if eng.db.active_purpose(&e.node_id, "edit").is_some() {
            continue;
        }
        let conflict = match node(app, &e.node_id).await {
            Ok(n) => {
                e.name = n.name;
                e.parent_id = n.parent_id;
                n.sha256.unwrap_or_default().to_ascii_lowercase() != e.base_sha
            }
            // 云端那个文件没了(删了、进了回收站):按原来的名字传回去,等于重新建一个
            Err(err) if err.code() == "NOT_FOUND" => false,
            Err(_) => continue,
        };
        e.mtime = now.0;
        e.size = now.1;
        e.state = "uploading".into();
        e.note = None;
        eng.db.put_edit(&e);
        let r = if conflict {
            transfer::enqueue_save_back(app, &e, &conflict_name(&e.name), false)
        } else {
            transfer::enqueue_save_back(app, &e, &e.name, true)
        };
        if let Err(msg) = r {
            e.state = "watching".into();
            e.note = Some(msg);
            eng.db.put_edit(&e);
        }
        changed(app);
    }
}

/// 启动时清掉:缓存里已经没有文件的、冲突或停掉超过一星期的
fn prune(app: &AppHandle) {
    let eng = db(app);
    let now = crate::transfer::now_ms();
    for e in eng.db.edits() {
        let p = PathBuf::from(&e.local_path);
        let old = matches!(e.state.as_str(), "conflict" | "stopped") && now - e.updated_at > KEEP_DONE_MS;
        if old || (!p.exists() && e.state != "downloading") {
            eng.db.delete_edit(&e.node_id);
            if let Some(dir) = p.parent() {
                let _ = std::fs::remove_dir_all(dir);
            }
        }
    }
}

/// 登出:缓存整个删掉(下一个登录的可能是别人)
pub fn clear_cache(app: &AppHandle) {
    let _ = std::fs::remove_dir_all(cache_root(app));
    if let Some(edits) = app.try_state::<Edits>() {
        edits.settling.lock().unwrap().clear();
    }
    changed(app);
}

// ---- 给网页看的 ----

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct EditView {
    node_id: String,
    name: String,
    parent_id: Option<String>,
    local_path: String,
    /// downloading / watching / uploading / conflict / stopped
    state: String,
    note: Option<String>,
    uploads: i64,
    /// 本地有还没传回的改动
    unsynced: bool,
    updated_at: i64,
}

pub fn views(app: &AppHandle) -> Vec<EditView> {
    let Some(eng) = app.try_state::<transfer::Engine>() else { return Vec::new() };
    eng.db
        .edits()
        .into_iter()
        .map(|e| EditView {
            unsynced: e.state == "watching" && unsynced(&e),
            node_id: e.node_id,
            name: e.name,
            parent_id: e.parent_id,
            local_path: e.local_path,
            state: e.state,
            note: e.note,
            uploads: e.uploads,
            updated_at: e.updated_at,
        })
        .collect()
}

#[tauri::command]
pub fn desktop_edits(app: AppHandle) -> Vec<EditView> {
    views(&app)
}

/// open / reveal / stop。stop:本地没有没传回的改动就连副本一起删;有的话副本留着(返回它的位置)
#[tauri::command]
pub fn desktop_edit_action(app: AppHandle, node_id: String, action: String) -> Result<Option<String>, String> {
    let eng = db(&app);
    let e = eng.db.edit(&node_id).ok_or("这一项已经不在了")?;
    let p = PathBuf::from(&e.local_path);
    match action.as_str() {
        "open" => {
            if !p.exists() {
                return Err("本地副本已经不在了".into());
            }
            open_file(&app, &p)?;
            Ok(None)
        }
        "reveal" => {
            app.opener().reveal_item_in_dir(&p).map_err(|e| e.to_string())?;
            Ok(None)
        }
        "stop" => {
            let keep = e.state == "conflict" || (p.exists() && unsynced(&e));
            eng.db.delete_edit(&node_id);
            app.state::<Edits>().settling.lock().unwrap().remove(&node_id);
            let kept = if keep {
                Some(e.local_path.clone())
            } else {
                if let Some(dir) = p.parent() {
                    let _ = std::fs::remove_dir_all(dir);
                }
                None
            };
            changed(&app);
            Ok(kept)
        }
        _ => Err(format!("不认识的操作 {action}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn conflict_copies_keep_the_extension() {
        assert_eq!(conflict_name("报告.docx"), "报告(电脑上的修改).docx");
        assert_eq!(conflict_name("a.tar.gz"), "a.tar(电脑上的修改).gz");
        assert_eq!(conflict_name("Makefile"), "Makefile(电脑上的修改)");
        assert_eq!(conflict_name(".env"), ".env(电脑上的修改)");
    }
}
