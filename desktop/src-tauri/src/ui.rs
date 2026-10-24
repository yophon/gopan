//! 窗口:主窗口(先打开本地连接页,连上后换成网页版)、设置窗口、传输助手小窗。
//! 主窗口与小窗关闭 = 隐藏(程序留在托盘);网页里点到站外的链接交给系统浏览器。

use std::sync::RwLock;
use tauri::webview::NewWindowResponse;
use tauri::{AppHandle, Manager, PhysicalPosition, WebviewUrl, WebviewWindow, WebviewWindowBuilder, WindowEvent};
use tauri_plugin_opener::OpenerExt;
use url::Url;

pub const MAIN: &str = "main";
pub const SETTINGS: &str = "settings";
pub const MINI: &str = "mini";

/// 主窗口与小窗里允许打开的站点(服务器、Yophon ID);其余链接交给系统浏览器
#[derive(Default)]
pub struct Origins(RwLock<Vec<String>>);

impl Origins {
    pub fn set(&self, list: Vec<String>) {
        *self.0.write().unwrap() = list;
    }

    fn allows(&self, url: &Url) -> bool {
        match url.scheme() {
            "tauri" | "about" | "data" | "blob" => return true,
            _ => {}
        }
        let origin = url.origin().ascii_serialization();
        // 本地页面在 Windows 上是 http://tauri.localhost
        if origin == "http://tauri.localhost" || origin == "https://tauri.localhost" {
            return true;
        }
        self.0.read().unwrap().contains(&origin)
    }
}

/// WebView 的 UA:Edge WebView2 原本的格式,末尾加 gopan-desktop/<版本>,
/// 服务端据此认出「Windows 桌面端」(登录设备、传输助手消息来源)
pub fn webview_user_agent(app: &AppHandle) -> String {
    let edge = webview2_version().unwrap_or_else(|| "130.0.0.0".into());
    format!(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/{edge} Safari/537.36 Edg/{edge} gopan-desktop/{}",
        app.package_info().version
    )
}

fn webview2_version() -> Option<String> {
    use winreg::enums::{HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE};
    use winreg::RegKey;
    const CLIENT: &str = r"Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}";
    let candidates = [
        (HKEY_LOCAL_MACHINE, format!(r"SOFTWARE\WOW6432Node\{CLIENT}")),
        (HKEY_LOCAL_MACHINE, format!(r"SOFTWARE\{CLIENT}")),
        (HKEY_CURRENT_USER, format!(r"Software\{CLIENT}")),
    ];
    candidates.iter().find_map(|(hive, path)| {
        let v: String = RegKey::predef(*hive).open_subkey(path).ok()?.get_value("pv").ok()?;
        (!v.is_empty() && v != "0.0.0.0").then_some(v)
    })
}

fn guard_navigation(app: &AppHandle) -> impl Fn(&Url) -> bool + Send + 'static {
    let app = app.clone();
    move |url: &Url| {
        if app.state::<Origins>().allows(url) {
            return true;
        }
        let _ = app.opener().open_url(url.as_str(), None::<&str>);
        false
    }
}

fn hide_on_close(w: &WebviewWindow) {
    let win = w.clone();
    w.on_window_event(move |e| {
        if let WindowEvent::CloseRequested { api, .. } = e {
            api.prevent_close();
            let _ = win.hide();
        }
    });
}

/// 主窗口:没有就建(先是本地连接页,由它连上服务器后换成网页版)
pub fn ensure_main(app: &AppHandle, visible: bool) -> tauri::Result<WebviewWindow> {
    if let Some(w) = app.get_webview_window(MAIN) {
        return Ok(w);
    }
    let opener = app.clone();
    let w = WebviewWindowBuilder::new(app, MAIN, WebviewUrl::App("index.html".into()))
        .title("gopan")
        .inner_size(1240.0, 820.0)
        .min_inner_size(820.0, 560.0)
        .visible(visible)
        .user_agent(&webview_user_agent(app))
        .on_navigation(guard_navigation(app))
        .on_new_window(move |url, _features| {
            let _ = opener.opener().open_url(url.as_str(), None::<&str>);
            NewWindowResponse::Deny
        })
        .build()?;
    hide_on_close(&w);
    Ok(w)
}

pub fn show(w: &WebviewWindow) {
    let _ = w.unminimize();
    let _ = w.show();
    let _ = w.set_focus();
}

pub fn show_main(app: &AppHandle) {
    if let Ok(w) = ensure_main(app, true) {
        show(&w);
    }
}

/// 设置窗口(本地页面);已经开着就叫到前面
pub fn open_settings(app: &AppHandle) {
    if let Some(w) = app.get_webview_window(SETTINGS) {
        show(&w);
        return;
    }
    let _ = WebviewWindowBuilder::new(app, SETTINGS, WebviewUrl::App("settings.html".into()))
        .title("gopan 设置")
        .inner_size(520.0, 680.0)
        .min_inner_size(440.0, 520.0)
        .center()
        .build();
}

/// 传输助手小窗(doc/29 D3):网页版的传输助手,精简模式;贴着屏幕右下角。
/// 关掉拖放处理器,让网页自己接住拖进来的文件(HTML5 drop)
pub fn open_mini(app: &AppHandle) {
    if let Some(w) = app.get_webview_window(MINI) {
        show(&w);
        return;
    }
    let server = crate::api::Api::base(app);
    let Ok(url) = Url::parse(&format!("{server}/chat?embed=1")) else { return };
    let opener = app.clone();
    let builder = WebviewWindowBuilder::new(app, MINI, WebviewUrl::External(url))
        .title("传输助手 · gopan")
        .inner_size(400.0, 640.0)
        .min_inner_size(340.0, 420.0)
        .visible(false)
        .user_agent(&webview_user_agent(app))
        .disable_drag_drop_handler()
        .on_navigation(guard_navigation(app))
        .on_new_window(move |url, _features| {
            let _ = opener.opener().open_url(url.as_str(), None::<&str>);
            NewWindowResponse::Deny
        });
    if let Ok(w) = builder.build() {
        place_bottom_right(&w);
        hide_on_close(&w);
        show(&w);
    }
}

fn place_bottom_right(w: &WebviewWindow) {
    let (Ok(Some(monitor)), Ok(size)) = (w.primary_monitor(), w.outer_size()) else { return };
    let area = monitor.work_area();
    let margin = (12.0 * monitor.scale_factor()) as i32;
    let x = area.position.x + area.size.width as i32 - size.width as i32 - margin;
    let y = area.position.y + area.size.height as i32 - size.height as i32 - margin;
    let _ = w.set_position(PhysicalPosition::new(x, y));
}
