//! 设置窗口的命令:读写设置、选下载目录、开机启动、检查更新。

use crate::settings::{Settings, SettingsStore};
use serde::Serialize;
use tauri::{AppHandle, Manager};
use tauri_plugin_autostart::ManagerExt as _;
use tauri_plugin_dialog::DialogExt;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SettingsView {
    settings: Settings,
    version: String,
    autostart: bool,
    /// 实际用的下载目录(设置里留空时是默认的那个)
    download_dir_effective: String,
}

fn view(app: &AppHandle) -> SettingsView {
    let store = app.state::<SettingsStore>();
    SettingsView {
        settings: store.get(),
        version: app.package_info().version.to_string(),
        autostart: app.autolaunch().is_enabled().unwrap_or(false),
        download_dir_effective: store.download_dir(app).to_string_lossy().into_owned(),
    }
}

#[tauri::command]
pub fn settings_get(app: AppHandle) -> SettingsView {
    view(&app)
}

/// 保存设置。服务器地址变了要重新连接(主窗口回到连接页);快捷键注册不上(被别的程序占了)直接报出来
#[tauri::command]
pub async fn settings_save(app: AppHandle, settings: Settings, autostart: bool) -> Result<SettingsView, String> {
    let store = app.state::<SettingsStore>();
    let old = store.get();
    let mut next = settings;
    next.server_url = Settings::normalize_server(&next.server_url).ok_or("服务器地址不对,应该像 https://drive.example.com")?;
    if !next.copy_format_valid() {
        return Err("复制格式只能是 markdown、url、html".into());
    }
    next.autostart_initialized = true;
    if let Err(e) = crate::capture::apply_shortcuts(&app, &next) {
        // 新的注册不上(被别的程序占了):换回原来的,别让两个都失效
        let _ = crate::capture::apply_shortcuts(&app, &old);
        return Err(e);
    }
    store.save(next.clone()).map_err(|e| format!("保存设置失败:{e}"))?;
    let launcher = app.autolaunch();
    let _ = if autostart { launcher.enable() } else { launcher.disable() };
    if old.shell_menu != next.shell_menu {
        crate::shell::sync(next.shell_menu);
    }
    if old.server_url != next.server_url {
        crate::reconnect(&app);
    }
    Ok(view(&app))
}

#[tauri::command]
pub async fn pick_directory(app: AppHandle) -> Option<String> {
    let picked = tauri::async_runtime::spawn_blocking(move || app.dialog().file().set_title("选择下载目录").blocking_pick_folder())
        .await
        .ok()
        .flatten()?;
    picked.into_path().ok().map(|p| p.to_string_lossy().into_owned())
}

/// 设置窗口里的「检查更新」:有就问要不要装,返回给页面一句结果
#[tauri::command]
pub async fn update_check(app: AppHandle) -> String {
    match crate::updater::check(&app, true).await {
        Ok(Some(v)) => format!("发现新版本 {v}"),
        Ok(None) => "已经是最新版本".into(),
        Err(e) => format!("检查更新失败:{e}"),
    }
}

/// 第一次启动:按默认打开开机启动(之后以设置里的选择为准)。
/// 开发版不动:开机启动项与安装版同名,会把它指到 target 里的程序
pub fn init_autostart(app: &AppHandle) {
    let store = app.state::<SettingsStore>();
    let mut s = store.get();
    if s.autostart_initialized || cfg!(debug_assertions) {
        return;
    }
    let _ = app.autolaunch().enable();
    s.autostart_initialized = true;
    let _ = store.save(s);
}
