//! 托盘(doc/29):单击打开传输助手小窗,双击打开主窗口;右键菜单里是常用入口与退出。
//! 单击要等一会儿看是不是双击,免得双击时小窗也跳出来。

use crate::{capture, ui};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::AppHandle;

pub const TRAY_ID: &str = "gopan";

/// 每次单击 / 双击加一:单击排队打开小窗时看它变没变
static CLICKS: AtomicU64 = AtomicU64::new(0);

pub fn create(app: &AppHandle) -> tauri::Result<()> {
    let menu = Menu::with_items(
        app,
        &[
            &MenuItem::with_id(app, "open", "打开 gopan", true, None::<&str>)?,
            &MenuItem::with_id(app, "chat", "传输助手", true, None::<&str>)?,
            &MenuItem::with_id(app, "capture", "截图上传", true, None::<&str>)?,
            &PredefinedMenuItem::separator(app)?,
            &MenuItem::with_id(app, "settings", "设置", true, None::<&str>)?,
            &PredefinedMenuItem::separator(app)?,
            &MenuItem::with_id(app, "quit", "退出 gopan", true, None::<&str>)?,
        ],
    )?;
    let icon = app.default_window_icon().cloned().ok_or_else(|| tauri::Error::AssetNotFound("图标".into()))?;
    TrayIconBuilder::with_id(TRAY_ID)
        .icon(icon)
        .tooltip("gopan")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id().as_ref() {
            "open" => ui::show_main(app),
            "chat" => ui::open_mini(app),
            "capture" => capture::start_capture(app),
            "settings" => ui::open_settings(app),
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            let app = tray.app_handle().clone();
            match event {
                TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } => {
                    let mine = CLICKS.fetch_add(1, Ordering::SeqCst) + 1;
                    tauri::async_runtime::spawn(async move {
                        tokio::time::sleep(Duration::from_millis(320)).await;
                        if CLICKS.load(Ordering::SeqCst) == mine {
                            let handle = app.clone();
                            let _ = app.run_on_main_thread(move || ui::open_mini(&handle));
                        }
                    });
                }
                TrayIconEvent::DoubleClick { button: MouseButton::Left, .. } => {
                    CLICKS.fetch_add(1, Ordering::SeqCst);
                    ui::show_main(&app);
                }
                _ => {}
            }
        })
        .build(app)?;
    Ok(())
}

/// 托盘提示文字(传输进度等)
pub fn set_tooltip(app: &AppHandle, text: &str) {
    if let Some(tray) = app.tray_by_id(TRAY_ID) {
        let _ = tray.set_tooltip(Some(text));
    }
}
