//! Windows 系统通知(右下角的 toast)。用安装包建的开始菜单快捷方式上的 AppUserModelID(= 程序标识),
//! 开发版没装过时 Windows 可能不显示,以安装版为准(doc/29 八)。

use tauri::AppHandle;
use tauri_winrt_notification::{Duration, Toast};

/// 弹一条通知;[on_click] 点通知时在后台线程里调(要碰窗口请转回主线程)
pub fn toast(app: &AppHandle, title: &str, body: &str, on_click: Option<Box<dyn Fn() + Send + Sync + 'static>>) {
    let mut t = Toast::new(&app.config().identifier).title(title).text1(body).duration(Duration::Short);
    if let Some(cb) = on_click {
        t = t.on_activated(move |_action| {
            cb();
            Ok(())
        });
    }
    if let Err(e) = t.show() {
        log::warn!("通知没弹出来:{e}");
    }
}
