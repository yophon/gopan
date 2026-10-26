//! 网页把用户选的、拖进来的文件交给外壳(doc/29):桌面端里网页发起的上传也走原生传输引擎,
//! 缩到托盘照常跑,退出再打开接着传。
//!
//! 网页用 WebView2 的 `chrome.webview.postMessageWithAdditionalObjects("gopan-desktop-files:<编号>", [File…])`
//! 把 File / FileSystemHandle 交过来,这里从中读出本地路径;接着网页调 `desktop_upload_files` 说传到哪。
//! 网页造不出指向任意路径的 File,所以路径只可能是用户自己选的、拖进来的;网页本身也不能直接报路径。
//! 网页自己的拖放(包括把文件拖进文件夹「移动」)照旧,主窗口不开 Tauri 的拖放处理。

use crate::settings::SettingsStore;
use crate::{transfer, ui};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::{AppHandle, Manager, WebviewWindow};
use webview2_com::Microsoft::Web::WebView2::Win32::{
    ICoreWebView2File, ICoreWebView2FileSystemHandle, ICoreWebView2WebMessageReceivedEventArgs, ICoreWebView2WebMessageReceivedEventArgs2,
};
use webview2_com::{CoTaskMemPWSTR, WebMessageReceivedEventHandler};
use windows::core::{Interface, PWSTR};

pub const PREFIX: &str = "gopan-desktop-files:";
/// 交过来的文件留多久等网页说传到哪
const KEEP: Duration = Duration::from_secs(300);

#[derive(Default)]
pub struct Handoffs(Mutex<HashMap<String, (Instant, Vec<PathBuf>)>>);

/// 给主窗口 / 小窗的网页接上「交文件」的通道
pub fn attach(w: &WebviewWindow) {
    let app = w.app_handle().clone();
    let _ = w.with_webview(move |pv| unsafe {
        let Ok(core) = pv.controller().CoreWebView2() else { return };
        let handler = WebMessageReceivedEventHandler::create(Box::new(move |_wv, args| {
            if let Some(args) = args {
                on_message(&app, &args);
            }
            Ok(())
        }));
        let mut token = Default::default();
        if let Err(e) = core.add_WebMessageReceived(&handler, &mut token) {
            log::warn!("接不上网页交文件的通道:{e}");
        }
    });
}

fn read(get: impl FnOnce(*mut PWSTR) -> windows::core::Result<()>) -> Option<String> {
    let mut p = PWSTR::null();
    get(&mut p).ok()?;
    if p.is_null() {
        return None;
    }
    Some(CoTaskMemPWSTR::from(p).to_string())
}

unsafe fn on_message(app: &AppHandle, args: &ICoreWebView2WebMessageReceivedEventArgs) {
    // Tauri 自己的 IPC 也走这条通道:只认带前缀的、从服务器本站发来的
    let Some(msg) = read(|p| unsafe { args.TryGetWebMessageAsString(p) }) else { return };
    let Some(id) = msg.strip_prefix(PREFIX) else { return };
    if id.is_empty() || id.len() > 64 {
        return;
    }
    let Some(source) = read(|p| unsafe { args.Source(p) }) else { return };
    let from_server = url::Url::parse(&source).map(|u| u.origin().ascii_serialization() == app.state::<SettingsStore>().get().server_url);
    if !from_server.unwrap_or(false) {
        return;
    }
    let Ok(args2) = args.cast::<ICoreWebView2WebMessageReceivedEventArgs2>() else { return };
    let Ok(objects) = (unsafe { args2.AdditionalObjects() }) else { return };
    let mut count = 0u32;
    if unsafe { objects.Count(&mut count) }.is_err() {
        return;
    }
    let mut paths = Vec::new();
    for i in 0..count.min(10_000) {
        let Ok(obj) = (unsafe { objects.GetValueAtIndex(i) }) else { continue };
        let path = if let Ok(f) = obj.cast::<ICoreWebView2File>() {
            read(|p| unsafe { f.Path(p) })
        } else if let Ok(h) = obj.cast::<ICoreWebView2FileSystemHandle>() {
            read(|p| unsafe { h.Path(p) })
        } else {
            None
        };
        if let Some(p) = path.filter(|p| !p.is_empty()) {
            paths.push(PathBuf::from(p));
        }
    }
    let state = app.state::<Handoffs>();
    let mut map = state.0.lock().unwrap();
    map.retain(|_, (at, _)| at.elapsed() < KEEP);
    map.insert(id.to_string(), (Instant::now(), paths));
}

fn take(app: &AppHandle, id: &str) -> Option<Vec<PathBuf>> {
    app.state::<Handoffs>().0.lock().unwrap().remove(id).map(|(_, p)| p)
}

/// 选文件夹上传(`webkitdirectory`)时网页只交第一个文件:它往上 [depth]-1 层就是选的那个文件夹
fn folder_root(first: &std::path::Path, depth: u32) -> PathBuf {
    let mut p = first.to_path_buf();
    for _ in 1..depth {
        p.pop();
    }
    p
}

/// 网页交过文件之后说传到哪:[parent_id] 云端文件夹(null = 根目录),[to_chat] 发到传输助手,
/// [replace] 这些名字的文件替换同名的(网页已经问过),[root_depth] 见 [folder_root]。返回入队了几项
#[tauri::command]
#[allow(clippy::too_many_arguments)]
pub async fn desktop_upload_files(
    app: AppHandle,
    window: WebviewWindow,
    request_id: String,
    parent_id: Option<String>,
    to_chat: Option<bool>,
    replace: Option<Vec<String>>,
    root_depth: Option<u32>,
) -> Result<usize, String> {
    if window.label() != ui::MAIN && window.label() != ui::MINI {
        return Err("这个窗口不能上传".into());
    }
    // 交文件的消息和这条命令走同一条通道,一般已经到了;稍等一下以防万一
    let mut paths = None;
    for _ in 0..30 {
        if let Some(p) = take(&app, &request_id) {
            paths = Some(p);
            break;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    let paths = paths.ok_or("没收到要上传的文件,再试一次")?;
    if paths.is_empty() {
        return Ok(0);
    }
    let paths = match root_depth {
        Some(d) if d > 1 => vec![folder_root(&paths[0], d)],
        _ => paths,
    };
    transfer::enqueue_uploads(&app, &paths, parent_id, to_chat.unwrap_or(false), &replace.unwrap_or_default())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn folder_root_walks_up_to_the_picked_folder() {
        let first = std::path::Path::new(r"C:\Users\a\照片\2024\一月\1.jpg");
        assert_eq!(folder_root(first, 3), PathBuf::from(r"C:\Users\a\照片\2024"));
        assert_eq!(folder_root(first, 1), first.to_path_buf());
    }
}
