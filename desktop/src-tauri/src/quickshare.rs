//! 上传并复制分享链接(doc/31 四):资源管理器右键 / 「发送到」选的文件传进云端根目录的「快传」文件夹,
//! 传完建一个 7 天的分享,链接放进剪贴板,直接贴进微信。
//! 一个文件:分享这个文件本身(对方看到的是文件页);多个、或有文件夹:先在「快传」里建一个文件夹装这一批,
//! 全部传完再分享它,对方打开时不会看到传了一半的。
//! 等待中的批次只记在内存里:中途退出程序,文件照样续传,只是不会自动出链接(在网页里点分享就行)。

use crate::api::Api;
use crate::auth::Auth;
use crate::notify;
use crate::transfer::{self, State};
use serde::Deserialize;
use serde_json::json;
use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::Mutex;
use tauri::{AppHandle, Manager};
use tauri_plugin_opener::OpenerExt;

const SHARE_DAYS: i64 = 7;

enum Target {
    /// 分享传上去的那个文件(任务做完才知道它的节点)
    File(Option<String>),
    /// 分享这一批的文件夹
    Folder(String),
}

struct Job {
    tasks: HashSet<i64>,
    failed: bool,
    target: Target,
    label: String,
}

#[derive(Default)]
pub struct QuickShares {
    jobs: Mutex<Vec<Job>>,
}

#[derive(Deserialize)]
struct Id {
    id: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct FolderData {
    quick_share_folder: Id,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CreateFolderData {
    create_folder: Id,
}

#[derive(Deserialize)]
struct Token {
    token: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ShareData {
    create_share: Token,
}

fn file_name(p: &std::path::Path) -> String {
    p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| p.to_string_lossy().into_owned())
}

/// 一批的文件夹名、通知里的称呼:「报告.pdf 等 3 项」
fn batch_label(paths: &[PathBuf]) -> String {
    let first = paths.first().map(|p| file_name(p)).unwrap_or_default();
    if paths.len() > 1 {
        format!("{first} 等 {} 项", paths.len())
    } else {
        first
    }
}

/// 右键菜单 / 发送到交来的路径
pub fn start(app: &AppHandle, paths: Vec<PathBuf>) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        if let Err(e) = begin(&app, paths).await {
            notify::toast(&app, "没能开始上传", &e, None);
        }
    });
}

async fn begin(app: &AppHandle, paths: Vec<PathBuf>) -> Result<(), String> {
    if paths.is_empty() {
        return Ok(());
    }
    if !app.state::<Auth>().ready() {
        return Err("还没登录:打开 gopan 登录后再试".into());
    }
    let api = app.state::<Api>();
    let root: FolderData = api
        .gql(app, "mutation DesktopQuickShareFolder { quickShareFolder { id } }", json!({}))
        .await
        .map_err(|e| e.to_string())?;
    let label = batch_label(&paths);
    let single = paths.len() == 1 && paths[0].is_file();
    let (parent, target) = if single {
        (root.quick_share_folder.id, Target::File(None))
    } else {
        let f: CreateFolderData = api
            .gql(
                app,
                "mutation DesktopQuickShareBatch($parentId: ID, $name: String!) { createFolder(parentId: $parentId, name: $name) { id } }",
                json!({ "parentId": root.quick_share_folder.id, "name": label }),
            )
            .await
            .map_err(|e| e.to_string())?;
        (f.create_folder.id.clone(), Target::Folder(f.create_folder.id))
    };
    let shares = app.state::<QuickShares>();
    {
        // 入队和登记在同一把锁里:小文件可能一入队就传完,完成回调要等登记好了才查得到
        let mut jobs = shares.jobs.lock().unwrap();
        let ids = transfer::enqueue_uploads_ids(app, &paths, Some(parent), false, &[])?;
        if ids.is_empty() {
            return Err("这些文件读不到了".into());
        }
        jobs.push(Job { tasks: ids.into_iter().collect(), failed: false, target, label: label.clone() });
    }
    notify::toast(app, "正在上传到「快传」", &format!("{label} · 传完把分享链接放进剪贴板"), None);
    Ok(())
}

/// 传输引擎:一个顶层任务到了终态。是这里登记的返回 true(普通的「传完了」通知就不弹了)
pub fn task_finished(app: &AppHandle, task_id: i64) -> bool {
    let Some(shares) = app.try_state::<QuickShares>() else { return false };
    let mut jobs = shares.jobs.lock().unwrap();
    let Some(i) = jobs.iter().position(|j| j.tasks.contains(&task_id)) else { return false };
    let task = transfer::task(app, task_id);
    let job = &mut jobs[i];
    job.tasks.remove(&task_id);
    match &task {
        Some(t) if t.state == State::Done => {
            if let Target::File(node) = &mut job.target {
                *node = t.node_id.clone();
            }
        }
        _ => job.failed = true,
    }
    if job.tasks.is_empty() {
        let done = jobs.remove(i);
        let a = app.clone();
        tauri::async_runtime::spawn(async move { finish(&a, done).await });
    }
    true
}

async fn finish(app: &AppHandle, job: Job) {
    if job.failed {
        notify::toast(app, "有文件没传上去", &format!("{} · 没生成分享链接,在传输列表里重试", job.label), None);
        return;
    }
    let node = match job.target {
        Target::File(Some(n)) | Target::Folder(n) => n,
        Target::File(None) => {
            notify::toast(app, "没生成分享链接", &job.label, None);
            return;
        }
    };
    let expires = time_after_days(SHARE_DAYS);
    let res: Result<ShareData, _> = app
        .state::<Api>()
        .gql(
            app,
            "mutation DesktopQuickShare($nodeId: ID!, $expiresAt: Time) { createShare(nodeId: $nodeId, expiresAt: $expiresAt) { token } }",
            json!({ "nodeId": node, "expiresAt": expires }),
        )
        .await;
    let token = match res {
        Ok(d) => d.create_share.token,
        Err(e) => {
            notify::toast(app, "传好了,但没建成分享", &e.to_string(), None);
            return;
        }
    };
    let url = format!("{}/s/{}", Api::base(app).trim_end_matches('/'), token);
    let text = url.clone();
    // 本机自动化测试时不碰剪贴板(那是正在用电脑的人的);只有开发版认这个开关,链接照样写进日志
    let copied = if cfg!(debug_assertions) && std::env::var_os("GOPAN_DESKTOP_NO_CLIPBOARD").is_some() {
        log::info!("快传链接(没放进剪贴板):{url}");
        true
    } else {
        tauri::async_runtime::spawn_blocking(move || arboard::Clipboard::new().and_then(|mut c| c.set_text(text)).is_ok())
            .await
            .unwrap_or(false)
    };
    let opener = app.clone();
    let open = url.clone();
    notify::toast(
        app,
        if copied { "分享链接已复制" } else { "分享建好了" },
        &if copied {
            format!("{} · {SHARE_DAYS} 天有效,直接粘贴发出去", job.label)
        } else {
            format!("没能放进剪贴板:{url}")
        },
        Some(Box::new(move || {
            let _ = opener.opener().open_url(&open, None::<&str>);
        })),
    );
}

/// RFC 3339(UTC):n 天以后
fn time_after_days(n: i64) -> String {
    let secs = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0) + n * 86_400;
    rfc3339(secs)
}

fn rfc3339(secs: i64) -> String {
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    // 公历日期(Howard Hinnant 的 civil_from_days)
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}Z", rem / 3600, rem % 3600 / 60, rem % 60)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn labels_and_times() {
        assert_eq!(batch_label(&[PathBuf::from(r"C:\d\报告.pdf")]), "报告.pdf");
        assert_eq!(batch_label(&[PathBuf::from(r"C:\d\报告.pdf"), PathBuf::from(r"C:\d\图")]), "报告.pdf 等 2 项");
        assert_eq!(rfc3339(0), "1970-01-01T00:00:00Z");
        assert_eq!(rfc3339(1_792_990_542), "2026-10-26T04:55:42Z");
        assert_eq!(rfc3339(951_782_400), "2000-02-29T00:00:00Z");
    }
}
