//! 剪贴板发到传输助手(3.16.0):快捷键或托盘菜单,剪贴板里是什么就发什么——
//! 资源管理器里复制的文件、文件夹 → 传进传输助手;图片 → 存成 PNG 发过去;文字 → 发一条消息(太长就存成 .txt 发)。

use crate::api::Api;
use crate::auth::Auth;
use crate::{capture, notify, transfer, ui};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime};
use tauri::{AppHandle, Manager};

/// 服务端一条消息最多这么多字节(service/chat.go 的 maxChatBodyBytes)
const MAX_BODY_BYTES: usize = 4000;
/// 临时存的截图、长文字留一天(早就传完了)
const KEEP_TEMP: Duration = Duration::from_secs(24 * 3600);

#[derive(Debug, PartialEq)]
enum Clip {
    Files(Vec<PathBuf>),
    Image(Vec<u8>),
    Text(String),
    Empty,
}

fn read_clipboard() -> Clip {
    let Ok(mut cb) = arboard::Clipboard::new() else { return Clip::Empty };
    if let Ok(files) = cb.get().file_list() {
        let files: Vec<PathBuf> = files.into_iter().filter(|p| p.exists()).collect();
        if !files.is_empty() {
            return Clip::Files(files);
        }
    }
    if let Ok(img) = cb.get_image() {
        if let Some(png) = capture::encode_png(img.width as u32, img.height as u32, &img.bytes) {
            return Clip::Image(png);
        }
    }
    match cb.get_text() {
        Ok(t) if !t.trim().is_empty() => Clip::Text(t),
        _ => Clip::Empty,
    }
}

/// 「20261026-153012」:本地时间
fn stamp() -> String {
    let t = unsafe { windows::Win32::System::SystemInformation::GetLocalTime() };
    format!("{:04}{:02}{:02}-{:02}{:02}{:02}", t.wYear, t.wMonth, t.wDay, t.wHour, t.wMinute, t.wSecond)
}

fn temp_dir(app: &AppHandle) -> PathBuf {
    app.path().app_local_data_dir().unwrap_or_else(|_| std::env::temp_dir().join("gopan-desktop")).join("clip")
}

/// 删掉临时目录里一天以前的(上传早就完了)
fn prune(dir: &Path) {
    let Ok(rd) = std::fs::read_dir(dir) else { return };
    for e in rd.flatten() {
        let old = e.metadata().and_then(|m| m.modified()).map(|t| SystemTime::now().duration_since(t).unwrap_or_default() > KEEP_TEMP);
        if old.unwrap_or(false) {
            let _ = std::fs::remove_file(e.path());
        }
    }
}

fn write_temp(app: &AppHandle, name: &str, bytes: &[u8]) -> Result<PathBuf, String> {
    let dir = temp_dir(app);
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    prune(&dir);
    let p = dir.join(name);
    std::fs::write(&p, bytes).map_err(|e| e.to_string())?;
    Ok(p)
}

/// 长文字发成文件;短的直接一条消息
fn text_needs_file(text: &str) -> bool {
    text.len() > MAX_BODY_BYTES
}

fn preview(text: &str) -> String {
    let t: String = text.trim().chars().take(40).collect();
    if text.trim().chars().count() > 40 {
        format!("{t}…")
    } else {
        t
    }
}

fn open_mini_on_click(app: &AppHandle) -> Option<Box<dyn Fn() + Send + Sync>> {
    let a = app.clone();
    Some(Box::new(move || {
        let h = a.clone();
        let _ = a.run_on_main_thread(move || ui::open_mini(&h));
    }))
}

/// 快捷键 / 托盘菜单:把剪贴板里的东西发到传输助手
pub fn send_clipboard(app: &AppHandle) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        if !app.state::<Auth>().ready() {
            notify::toast(&app, "还没登录", "打开 gopan 登录后再发", None);
            return;
        }
        let clip = tauri::async_runtime::spawn_blocking(read_clipboard).await.unwrap_or(Clip::Empty);
        let result = match clip {
            Clip::Empty => {
                notify::toast(&app, "剪贴板是空的", "先复制文字、图片或文件再发", None);
                return;
            }
            Clip::Files(paths) => transfer::enqueue_uploads(&app, &paths, None, true, &[])
                .map(|n| ("正在发到传输助手".to_string(), format!("{n} 项文件,传完会出现在会话里"))),
            Clip::Image(png) => write_temp(&app, &format!("截图-{}.png", stamp()), &png)
                .and_then(|p| transfer::enqueue_uploads(&app, &[p], None, true, &[]))
                .map(|_| ("正在发到传输助手".to_string(), "剪贴板里的图片".to_string())),
            Clip::Text(text) if text_needs_file(&text) => write_temp(&app, &format!("文字-{}.txt", stamp()), text.as_bytes())
                .and_then(|p| transfer::enqueue_uploads(&app, &[p], None, true, &[]))
                .map(|_| ("文字太长,存成文件发了".to_string(), preview(&text))),
            Clip::Text(text) => app
                .state::<Api>()
                .gql::<Value>(
                    &app,
                    "mutation DesktopSendText($body: String) { sendChatMessage(body: $body) { id } }",
                    json!({ "body": text }),
                )
                .await
                .map(|_| ("已发到传输助手".to_string(), preview(&text)))
                .map_err(|e| e.to_string()),
        };
        match result {
            Ok((title, body)) => notify::toast(&app, &title, &body, open_mini_on_click(&app)),
            Err(e) => notify::toast(&app, "没发出去", &e, None),
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn long_text_goes_as_a_file_and_previews_are_short() {
        assert!(!text_needs_file(&"字".repeat(1333))); // 3999 字节
        assert!(text_needs_file(&"字".repeat(1334)));
        assert_eq!(preview("  短的  "), "短的");
        assert_eq!(preview(&"长".repeat(50)).chars().count(), 41);
    }
}
