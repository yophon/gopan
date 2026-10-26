//! 截图上传(doc/29 D4):全局快捷键调出 Windows 自带的截图框选(ms-screenclip:),截好的图进剪贴板后
//! 传到图床(/api/img/upload,用网页交来的登录 token),按设置把直链 / Markdown / HTML 复制到剪贴板。
//! 另一个快捷键直接传剪贴板里现有的图片(先用别的工具截好的)。

use crate::api::{Api, ApiError};
use crate::auth::Auth;
use crate::notify;
use crate::settings::{Settings, SettingsStore};
use serde::Deserialize;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};
use tauri::{AppHandle, Manager};
use tauri_plugin_global_shortcut::{GlobalShortcutExt, ShortcutState};
use tauri_plugin_opener::OpenerExt;

/// 同一时间只截一张
static BUSY: AtomicBool = AtomicBool::new(false);
/// 等用户框选多久
const WAIT_FOR_SNIP: Duration = Duration::from_secs(120);

/// 按设置注册快捷键(先清掉旧的);注册不上(格式不对、被别的程序占了)返回原因
pub fn apply_shortcuts(app: &AppHandle, s: &Settings) -> Result<(), String> {
    let gs = app.global_shortcut();
    let _ = gs.unregister_all();
    let capture = s.hotkey_capture.trim();
    let clipboard = s.hotkey_clipboard.trim();
    let send = s.hotkey_send.trim();
    let keys: Vec<String> = [capture, clipboard, send].iter().filter(|k| !k.is_empty()).map(|k| k.to_ascii_lowercase()).collect();
    if keys.iter().enumerate().any(|(i, k)| keys[..i].contains(k)) {
        return Err("几个快捷键不能一样".into());
    }
    if !send.is_empty() {
        gs.on_shortcut(send, |app, _shortcut, event| {
            if event.state == ShortcutState::Pressed {
                crate::clipsend::send_clipboard(app);
            }
        })
        .map_err(|e| format!("发到传输助手的快捷键 {send} 用不了(格式不对或被别的程序占了):{e}"))?;
    }
    if !capture.is_empty() {
        gs.on_shortcut(capture, |app, _shortcut, event| {
            if event.state == ShortcutState::Pressed {
                start_capture(app);
            }
        })
        .map_err(|e| format!("截图快捷键 {capture} 用不了(格式不对或被别的程序占了):{e}"))?;
    }
    if !clipboard.is_empty() {
        gs.on_shortcut(clipboard, |app, _shortcut, event| {
            if event.state == ShortcutState::Pressed {
                upload_clipboard(app);
            }
        })
        .map_err(|e| format!("上传剪贴板图片的快捷键 {clipboard} 用不了(格式不对或被别的程序占了):{e}"))?;
    }
    Ok(())
}

/// 启动时按已保存的设置注册;失败只记日志(设置窗口里改)
pub fn init(app: &AppHandle) {
    let s = app.state::<SettingsStore>().get();
    if let Err(e) = apply_shortcuts(app, &s) {
        log::warn!("{e}");
        notify::toast(app, "快捷键没注册上", &e, None);
    }
}

fn clipboard_seq() -> u32 {
    unsafe { windows::Win32::System::DataExchange::GetClipboardSequenceNumber() }
}

/// 调出系统截图框选,等新图进剪贴板,传上去
pub fn start_capture(app: &AppHandle) {
    if BUSY.swap(true, Ordering::SeqCst) {
        return;
    }
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let before = clipboard_seq();
        if let Err(e) = app.opener().open_url("ms-screenclip:", None::<&str>) {
            notify::toast(&app, "截图没打开", &e.to_string(), None);
            BUSY.store(false, Ordering::SeqCst);
            return;
        }
        let started = Instant::now();
        let mut seen = before;
        while started.elapsed() < WAIT_FOR_SNIP {
            tokio::time::sleep(Duration::from_millis(250)).await;
            let now = clipboard_seq();
            if now == seen {
                continue;
            }
            seen = now;
            // 剪贴板变了:是图就传(复制了文字之类的接着等)
            if let Some(png) = read_clipboard_png().await {
                upload_and_copy(&app, png).await;
                break;
            }
        }
        BUSY.store(false, Ordering::SeqCst);
    });
}

/// 直接传剪贴板里现有的图片
pub fn upload_clipboard(app: &AppHandle) {
    if BUSY.swap(true, Ordering::SeqCst) {
        return;
    }
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        match read_clipboard_png().await {
            Some(png) => upload_and_copy(&app, png).await,
            None => notify::toast(&app, "剪贴板里没有图片", "先截一张图或复制一张图片再试", None),
        }
        BUSY.store(false, Ordering::SeqCst);
    });
}

/// 剪贴板里的图片编码成 PNG;没有图片返回 None
async fn read_clipboard_png() -> Option<Vec<u8>> {
    tauri::async_runtime::spawn_blocking(|| {
        let img = arboard::Clipboard::new().ok()?.get_image().ok()?;
        encode_png(img.width as u32, img.height as u32, &img.bytes)
    })
    .await
    .ok()
    .flatten()
}

pub fn encode_png(width: u32, height: u32, rgba: &[u8]) -> Option<Vec<u8>> {
    if width == 0 || height == 0 || rgba.len() != (width as usize) * (height as usize) * 4 {
        return None;
    }
    let mut out = Vec::new();
    {
        let mut enc = png::Encoder::new(&mut out, width, height);
        enc.set_color(png::ColorType::Rgba);
        enc.set_depth(png::BitDepth::Eight);
        let mut writer = enc.write_header().ok()?;
        writer.write_image_data(rgba).ok()?;
    }
    Some(out)
}

#[derive(Debug, Deserialize)]
pub struct ImgResult {
    pub url: String,
    pub markdown: String,
    pub html: String,
}

/// 按复制格式挑一条
pub fn pick_format<'a>(r: &'a ImgResult, format: &str) -> (&'a str, &'static str) {
    match format {
        "url" => (&r.url, "直链"),
        "html" => (&r.html, "HTML"),
        _ => (&r.markdown, "Markdown 链接"),
    }
}

async fn upload_and_copy(app: &AppHandle, png: Vec<u8>) {
    match upload_png(app, png).await {
        Ok(r) => {
            let format = app.state::<SettingsStore>().get().copy_format;
            let (text, label) = pick_format(&r, &format);
            let text = text.to_string();
            let copied = tauri::async_runtime::spawn_blocking(move || arboard::Clipboard::new().and_then(|mut c| c.set_text(text)).is_ok())
                .await
                .unwrap_or(false);
            let opener = app.clone();
            let url = r.url.clone();
            notify::toast(
                app,
                "截图已上传",
                &if copied {
                    format!("{label}已复制,可以直接粘贴")
                } else {
                    format!("传好了,但没能复制到剪贴板:{}", r.url)
                },
                Some(Box::new(move || {
                    let _ = opener.opener().open_url(&url, None::<&str>);
                })),
            );
        }
        Err(e) => notify::toast(app, "截图没传上去", &e.to_string(), None),
    }
}

/// POST /api/img/upload;401 换个 token 再试一次
async fn upload_png(app: &AppHandle, png: Vec<u8>) -> Result<ImgResult, ApiError> {
    let auth = app.state::<Auth>();
    let http = &app.state::<Api>().http;
    for attempt in 0..2 {
        let token = auth.get(app).await?;
        // 「image.png」这类没名字的,服务端会改成 日期-时间-随机.png 放进图床文件夹的年-月子目录
        let part = reqwest::multipart::Part::bytes(png.clone())
            .file_name("image.png")
            .mime_str("image/png")
            .map_err(|e| ApiError::Network(e.to_string()))?;
        let form = reqwest::multipart::Form::new().part("file", part);
        let res = http
            .post(format!("{}/api/img/upload", Api::base(app)))
            .bearer_auth(&token)
            .multipart(form)
            .send()
            .await
            .map_err(|e| ApiError::Network(e.to_string()))?;
        let status = res.status().as_u16();
        let body = res.text().await.map_err(|e| ApiError::Network(e.to_string()))?;
        if status == 401 && attempt == 0 {
            auth.invalidate(&token);
            continue;
        }
        return parse_upload(status, &body);
    }
    Err(ApiError::Server { code: "UNAUTHENTICATED".into(), message: "登录已失效".into() })
}

pub fn parse_upload(status: u16, body: &str) -> Result<ImgResult, ApiError> {
    if (200..300).contains(&status) {
        return serde_json::from_str(body).map_err(|_| ApiError::Decode("图床接口返回的内容看不懂".into()));
    }
    let v: serde_json::Value = serde_json::from_str(body).unwrap_or_default();
    let code = v.pointer("/error/code").and_then(|c| c.as_str()).unwrap_or("HTTP").to_string();
    let message = match code.as_str() {
        "QUOTA_EXCEEDED" => "云盘空间不足".to_string(),
        "LINKS_DISABLED" => "这个站点没有开启直链与图床".to_string(),
        "RATE_LIMITED" | "BUSY" => "传得太频繁了,稍后再试".to_string(),
        _ => v.pointer("/error/message").and_then(|m| m.as_str()).map(str::to_string).unwrap_or_else(|| format!("上传失败(HTTP {status})")),
    };
    Err(ApiError::Server { code, message })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn png_encoding_checks_the_buffer_size() {
        let px = vec![255u8; 2 * 3 * 4];
        let png = encode_png(2, 3, &px).unwrap();
        assert_eq!(&png[1..4], b"PNG");
        assert!(encode_png(2, 3, &px[..10]).is_none());
        assert!(encode_png(0, 3, &[]).is_none());
    }

    #[test]
    fn upload_responses_are_parsed_and_formats_picked() {
        let ok = parse_upload(200, r#"{"url":"https://d/x/t/a.png","markdown":"![a](https://d/x/t/a.png)","html":"<img>","bbcode":"","name":"a.png","size":1,"node_id":"n","link_id":"l"}"#).unwrap();
        assert_eq!(pick_format(&ok, "url").0, "https://d/x/t/a.png");
        assert_eq!(pick_format(&ok, "markdown").1, "Markdown 链接");
        assert_eq!(pick_format(&ok, "html").0, "<img>");
        let err = parse_upload(507, r#"{"error":{"code":"QUOTA_EXCEEDED","message":"x"}}"#).unwrap_err();
        assert_eq!(err.code(), "QUOTA_EXCEEDED");
        assert_eq!(err.to_string(), "云盘空间不足");
        assert_eq!(parse_upload(502, "<html>").unwrap_err().to_string(), "上传失败(HTTP 502)");
    }
}
