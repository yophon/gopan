//! 网页 ↔ 原生(doc/29 三):本地页面(连接、设置)与远程网页(主窗口、传输助手小窗)各自能调的命令。
//! 远程网页的权限运行时按服务器地址加,只给 desktop_* 这一小组与事件监听,拿不到文件读写、命令执行。

use crate::auth::Auth;
use crate::settings::{Settings, SettingsStore};
use crate::ui::{self, Origins};
use serde::Serialize;
use std::collections::HashSet;
use std::sync::Mutex;
use std::time::Duration;
use tauri::ipc::CapabilityBuilder;
use tauri::{AppHandle, Manager, WebviewWindow};

/// 远程网页能调的命令(与 build.rs 的 COMMANDS 后半段一致)
const REMOTE_PERMISSIONS: &[&str] = &[
    "core:event:allow-listen",
    "core:event:allow-unlisten",
    "allow-desktop-hello",
    "allow-desktop-set-token",
    "allow-desktop-download",
    "allow-desktop-tasks",
    "allow-desktop-task-action",
    "allow-desktop-pick-folder-done",
    "allow-desktop-open-settings",
    "allow-desktop-open-mini",
    "allow-desktop-open-main",
    "allow-desktop-upload-files",
    "allow-desktop-open-local",
    "allow-desktop-edits",
    "allow-desktop-edit-action",
    "allow-desktop-play-external",
];

/// 已经给过权限的服务器来源(换了服务器地址再加一份,旧的留着无害)
#[derive(Default)]
pub struct Granted(Mutex<HashSet<String>>);

fn grant_remote(app: &AppHandle, origin: &str) -> Result<(), String> {
    let granted = app.state::<Granted>();
    let mut set = granted.0.lock().unwrap();
    if set.contains(origin) {
        return Ok(());
    }
    let mut cap =
        CapabilityBuilder::new(format!("remote-{}", set.len())).remote(format!("{origin}/*")).local(false).windows([ui::MAIN, ui::MINI]);
    for p in REMOTE_PERMISSIONS {
        cap = cap.permission(*p);
    }
    app.add_capability(cap).map_err(|e| e.to_string())?;
    set.insert(origin.to_string());
    Ok(())
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppStatus {
    version: String,
    server_url: String,
}

#[tauri::command]
pub fn app_status(app: AppHandle) -> AppStatus {
    AppStatus { version: app.package_info().version.to_string(), server_url: app.state::<SettingsStore>().get().server_url }
}

/// 连接页:(可选换个服务器地址)检查服务器通不通,通了就放行它与它的 Yophon ID,返回要打开的地址
#[tauri::command]
pub async fn server_connect(app: AppHandle, server: Option<String>) -> Result<String, String> {
    let store = app.state::<SettingsStore>();
    if let Some(input) = server {
        let normalized = Settings::normalize_server(&input).ok_or("服务器地址不对,应该像 https://drive.example.com")?;
        let mut s = store.get();
        if s.server_url != normalized {
            s.server_url = normalized;
            store.save(s).map_err(|e| format!("保存设置失败:{e}"))?;
        }
    }
    let base = store.get().server_url;
    let http = reqwest::Client::builder()
        .timeout(Duration::from_secs(8))
        .user_agent(crate::api::user_agent(&app))
        .build()
        .map_err(|e| e.to_string())?;
    let ok = http.get(format!("{base}/healthz")).send().await.map(|r| r.status().is_success()).unwrap_or(false);
    if !ok {
        return Err(format!("连不上 {base}。检查网络,或者在下面改成别的服务器地址"));
    }
    // 用 Yophon ID 登录时要跳到账号中心再回来,它的域名也放行
    let mut origins = vec![base.clone()];
    if let Ok(res) = http.get(format!("{base}/api/auth/id/config")).send().await {
        if let Ok(v) = res.json::<serde_json::Value>().await {
            if let Some(issuer) = v.get("issuer").and_then(|i| i.as_str()).and_then(|i| url::Url::parse(i).ok()) {
                origins.push(issuer.origin().ascii_serialization());
            }
        }
    }
    app.state::<Origins>().set(origins);
    grant_remote(&app, &base)?;
    Ok(format!("{base}/"))
}

// 会开窗口的命令都用 async:同步命令跑在主线程上,在里面建窗口 Windows 上会卡死

#[tauri::command]
pub async fn open_main(app: AppHandle) {
    ui::show_main(&app);
}

// ---- 远程网页 ----

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Hello {
    version: String,
    window: String,
    /// 右键菜单上传还等着选云端文件夹的(只给主窗口)
    picks: Vec<crate::shell::PickRequest>,
}

/// 网页一加载就打个招呼:知道自己在桌面端里、在哪个窗口
#[tauri::command]
pub fn desktop_hello(app: AppHandle, window: WebviewWindow) -> Hello {
    let picks = if window.label() == ui::MAIN { crate::shell::pending_picks(&app) } else { Vec::new() };
    Hello { version: app.package_info().version.to_string(), window: window.label().to_string(), picks }
}

/// 网页交来当前的 access token;null = 登出了(停掉后台的事)
#[tauri::command]
pub fn desktop_set_token(app: AppHandle, window: WebviewWindow, token: Option<String>) {
    // 小窗里的网页也有自己的会话:只认主窗口的,免得两边来回覆盖
    if window.label() != ui::MAIN && token.is_some() {
        return;
    }
    let signed_out = token.is_none();
    app.state::<Auth>().set(token);
    if signed_out {
        crate::on_signed_out(&app);
    }
}

#[tauri::command]
pub async fn desktop_open_settings(app: AppHandle) {
    ui::open_settings(&app);
}

#[tauri::command]
pub async fn desktop_open_mini(app: AppHandle) {
    ui::open_mini(&app);
}

/// 从小窗里打开主窗口(可带网页里的路径,如 /drive/<id>)
#[tauri::command]
pub async fn desktop_open_main(app: AppHandle, path: Option<String>) {
    ui::show_main(&app);
    if let Some(p) = path.filter(|p| p.starts_with('/')) {
        crate::navigate_main(&app, &p);
    }
}
