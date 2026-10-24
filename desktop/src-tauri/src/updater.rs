//! 自动更新(doc/29 D1):启动一会儿后与之后每 6 小时检查一次服务器的 /api/app/desktop/latest,
//! 有新版本就问一句,同意了下载安装包(签名校验在 updater 插件里)、装完重启。

use crate::api::Api;
use std::sync::Mutex;
use std::time::Duration;
use tauri::AppHandle;
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};
use tauri_plugin_updater::UpdaterExt;

const CHECK_EVERY: Duration = Duration::from_secs(6 * 3600);
/// 定时检查时点了「以后再说」的版本:这次运行里不再追着问(设置里手动检查照样问)
static DECLINED: Mutex<Option<String>> = Mutex::new(None);

pub fn spawn_periodic(app: &AppHandle) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(Duration::from_secs(20)).await;
        loop {
            if let Err(e) = check(&app, false).await {
                log::warn!("检查更新失败:{e}");
            }
            tokio::time::sleep(CHECK_EVERY).await;
        }
    });
}

/// 检查一次;有新版本就问要不要更新,返回新版本号。[manual] = 设置里点的(没有新版本时不弹框,由调用方提示)
pub async fn check(app: &AppHandle, manual: bool) -> Result<Option<String>, String> {
    let endpoint = format!("{}/api/app/desktop/latest", Api::base(app));
    let url = url::Url::parse(&endpoint).map_err(|e| e.to_string())?;
    let updater = app
        .updater_builder()
        .endpoints(vec![url])
        .map_err(|e| e.to_string())?
        .timeout(Duration::from_secs(60))
        .build()
        .map_err(|e| e.to_string())?;
    let Some(update) = updater.check().await.map_err(|e| e.to_string())? else {
        return Ok(None);
    };
    let version = update.version.clone();
    if !manual && DECLINED.lock().unwrap().as_deref() == Some(version.as_str()) {
        return Ok(Some(version));
    }
    let notes = update.body.clone().unwrap_or_default();
    let message = if notes.trim().is_empty() {
        format!("gopan 桌面端有新版本 {version}(现在是 {})。现在更新吗?更新完会自动重新打开。", update.current_version)
    } else {
        format!("gopan 桌面端有新版本 {version}(现在是 {}):\n\n{notes}\n\n现在更新吗?更新完会自动重新打开。", update.current_version)
    };
    let dialog_app = app.clone();
    let agree = tauri::async_runtime::spawn_blocking(move || {
        dialog_app
            .dialog()
            .message(message)
            .title("发现新版本")
            .kind(MessageDialogKind::Info)
            .buttons(MessageDialogButtons::OkCancelCustom("更新".into(), "以后再说".into()))
            .blocking_show()
    })
    .await
    .unwrap_or(false);
    if !agree {
        *DECLINED.lock().unwrap() = Some(version.clone());
        return Ok(Some(version));
    }
    update.download_and_install(|_, _| {}, || {}).await.map_err(|e| format!("下载或安装失败:{e}"))?;
    app.restart();
}
