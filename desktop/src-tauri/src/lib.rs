//! gopan 桌面端(Windows,doc/29):主窗口是网页版,外壳补上托盘、后台传输、传输助手小窗、截图上传、右键菜单。

mod aifetch;
mod api;
mod auth;
mod bridge;
mod capture;
mod chat;
mod clipsend;
mod edits;
mod handoff;
mod notify;
mod player;
mod prefs;
mod quickshare;
mod settings;
mod shell;
mod transfer;
mod tray;
mod ui;
mod updater;

use tauri::{AppHandle, Emitter, Manager, RunEvent};
use tauri_plugin_window_state::{StateFlags, WindowExt};

/// 开机启动时带这个参数:只进托盘,不弹主窗口
const BACKGROUND_ARG: &str = "--background";

pub fn run() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let argv: Vec<String> = std::env::args().collect();
    // 开机启动、「发到传输助手」都不用弹主窗口(「上传到 gopan…」要在主窗口里选文件夹,照常弹)
    let background = argv.iter().any(|a| a == BACKGROUND_ARG || a == shell::SEND_ARG);

    tauri::Builder::default()
        // 单实例:再打开一次只是把窗口叫出来(右键菜单传来的参数也在这里接,见 D5)
        .plugin(tauri_plugin_single_instance::init(|app, argv, _cwd| on_second_instance(app, argv)))
        .plugin(
            tauri_plugin_window_state::Builder::new()
                .with_denylist(&[ui::SETTINGS, ui::MINI])
                .with_state_flags(StateFlags::all() - StateFlags::VISIBLE)
                .skip_initial_state(ui::MAIN)
                .build(),
        )
        .plugin(tauri_plugin_autostart::init(tauri_plugin_autostart::MacosLauncher::LaunchAgent, Some(vec![BACKGROUND_ARG])))
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_process::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_global_shortcut::Builder::new().build())
        .manage(auth::Auth::default())
        .manage(aifetch::AiFetches::default())
        .manage(ui::Origins::default())
        .manage(bridge::Granted::default())
        .manage(shell::Shell::default())
        .manage(handoff::Handoffs::default())
        .manage(quickshare::QuickShares::default())
        .invoke_handler(tauri::generate_handler![
            bridge::app_status,
            bridge::server_connect,
            bridge::open_main,
            bridge::desktop_hello,
            bridge::desktop_set_token,
            bridge::desktop_open_settings,
            bridge::desktop_open_mini,
            bridge::desktop_open_main,
            prefs::settings_get,
            prefs::settings_save,
            prefs::pick_directory,
            prefs::update_check,
            transfer::desktop_download,
            transfer::desktop_tasks,
            transfer::desktop_task_action,
            shell::desktop_pick_folder_done,
            handoff::desktop_upload_files,
            edits::desktop_open_local,
            edits::desktop_edits,
            edits::desktop_edit_action,
            player::desktop_play_external,
            aifetch::desktop_ai_fetch,
            aifetch::desktop_ai_abort,
        ])
        .setup(move |app| {
            let handle = app.handle().clone();
            app.manage(settings::SettingsStore::load(&handle));
            app.manage(api::Api::new(&handle));
            tray::create(&handle)?;
            transfer::init(&handle)?;
            edits::init(&handle);
            let main = ui::ensure_main(&handle, false)?;
            let _ = main.restore_state(StateFlags::all() - StateFlags::VISIBLE);
            if !background {
                ui::show(&main);
            }
            prefs::init_autostart(&handle);
            capture::init(&handle);
            updater::spawn_periodic(&handle);
            chat::spawn(&handle);
            shell::sync(handle.state::<settings::SettingsStore>().get().shell_menu);
            shell::handle_args(&handle, &argv);
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("gopan 桌面端启动失败")
        .run(|_app, event| {
            // 窗口都关了(其实是隐藏)也不退出:留在托盘里,退出走托盘菜单
            if let RunEvent::ExitRequested { api, code: None, .. } = event {
                api.prevent_exit();
            }
        });
}

/// 又被打开了一次:右键菜单 / 发送到交来的文件收下;否则(双击桌面图标、开始菜单)把主窗口叫出来
fn on_second_instance(app: &AppHandle, argv: Vec<String>) {
    if !shell::handle_args(app, &argv) {
        ui::show_main(app);
    }
}

/// 网页说登出了:后台的事停下(传输队列清空,下一个登录的可能是别人)
pub fn on_signed_out(app: &AppHandle) {
    transfer::clear_all(app);
}

/// 让主窗口里的网页跳到某个路径(网页自己的路由,不重新加载)
pub fn navigate_main(app: &AppHandle, path: &str) {
    let _ = app.emit_to(ui::MAIN, "desktop://navigate", path);
}

/// 服务器地址改了:主窗口回到连接页重新连
pub fn reconnect(app: &AppHandle) {
    if let Some(w) = app.get_webview_window(ui::MAIN) {
        if let Ok(url) = url::Url::parse("http://tauri.localhost/index.html") {
            let _ = w.navigate(url);
        }
    }
    if let Some(w) = app.get_webview_window(ui::MINI) {
        let _ = w.destroy();
    }
}
