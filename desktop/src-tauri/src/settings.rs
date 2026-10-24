//! 设置:存在程序数据目录的 settings.json。只放这台电脑上的偏好,账号相关的都在服务端。

use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::RwLock;
use tauri::{AppHandle, Manager};

/// 默认服务器:gopan 的生产站点;自部署的在设置里改
pub const DEFAULT_SERVER: &str = "https://drive.979687.xyz";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default, rename_all = "camelCase")]
pub struct Settings {
    /// 服务器地址(不带结尾的 /)
    pub server_url: String,
    /// 下载目录;空 = 系统「下载」里的 gopan 文件夹
    pub download_dir: String,
    /// 截图上传的快捷键(Tauri 的写法,如 "Ctrl+Alt+Q");空 = 不用
    pub hotkey_capture: String,
    /// 上传剪贴板里的图片
    pub hotkey_clipboard: String,
    /// 截图传完复制什么:markdown / url / html
    pub copy_format: String,
    /// 传输助手的新消息弹通知
    pub notify_chat: bool,
    /// 资源管理器右键菜单与「发送到」里的 gopan
    pub shell_menu: bool,
    /// 第一次启动时已经按默认打开过开机启动(之后以用户的选择为准)
    pub autostart_initialized: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            server_url: DEFAULT_SERVER.into(),
            download_dir: String::new(),
            hotkey_capture: "Ctrl+Alt+Q".into(),
            hotkey_clipboard: "Ctrl+Alt+W".into(),
            copy_format: "markdown".into(),
            notify_chat: true,
            shell_menu: true,
            autostart_initialized: false,
        }
    }
}

impl Settings {
    /// 规整服务器地址:补 https://、去结尾的 /;不是 http(s) 地址返回 None
    pub fn normalize_server(input: &str) -> Option<String> {
        let t = input.trim().trim_end_matches('/');
        if t.is_empty() {
            return None;
        }
        let with_scheme = if t.starts_with("http://") || t.starts_with("https://") {
            t.to_string()
        } else if t.contains("://") {
            return None;
        } else {
            format!("https://{t}")
        };
        let url = url::Url::parse(&with_scheme).ok()?;
        if url.host_str().is_none() || !(url.scheme() == "http" || url.scheme() == "https") {
            return None;
        }
        Some(url.origin().ascii_serialization())
    }

    pub fn copy_format_valid(&self) -> bool {
        matches!(self.copy_format.as_str(), "markdown" | "url" | "html")
    }
}

pub struct SettingsStore {
    path: PathBuf,
    current: RwLock<Settings>,
}

impl SettingsStore {
    pub fn load(app: &AppHandle) -> Self {
        let dir = app.path().app_config_dir().unwrap_or_else(|_| std::env::temp_dir().join("gopan-desktop"));
        let _ = std::fs::create_dir_all(&dir);
        let path = dir.join("settings.json");
        let current = std::fs::read(&path).ok().and_then(|b| serde_json::from_slice::<Settings>(&b).ok()).unwrap_or_default();
        Self { path, current: RwLock::new(current) }
    }

    pub fn get(&self) -> Settings {
        self.current.read().expect("settings lock").clone()
    }

    pub fn save(&self, next: Settings) -> std::io::Result<()> {
        let body = serde_json::to_vec_pretty(&next).map_err(std::io::Error::other)?;
        let tmp = self.path.with_extension("json.tmp");
        std::fs::write(&tmp, body)?;
        std::fs::rename(&tmp, &self.path)?;
        *self.current.write().expect("settings lock") = next;
        Ok(())
    }

    /// 默认下载目录:系统「下载」\gopan
    pub fn download_dir(&self, app: &AppHandle) -> PathBuf {
        let s = self.get();
        if !s.download_dir.trim().is_empty() {
            return PathBuf::from(s.download_dir.trim());
        }
        app.path().download_dir().unwrap_or_else(|_| std::env::temp_dir()).join("gopan")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn server_addresses_are_normalized_to_an_origin() {
        assert_eq!(Settings::normalize_server("drive.example.com/").as_deref(), Some("https://drive.example.com"));
        assert_eq!(Settings::normalize_server(" http://127.0.0.1:8097/drive ").as_deref(), Some("http://127.0.0.1:8097"));
        assert_eq!(Settings::normalize_server("ftp://x").as_deref(), None);
        assert_eq!(Settings::normalize_server("").as_deref(), None);
    }

    #[test]
    fn missing_fields_fall_back_to_defaults() {
        let s: Settings = serde_json::from_str(r#"{"serverUrl":"https://a.example"}"#).unwrap();
        assert_eq!(s.server_url, "https://a.example");
        assert_eq!(s.hotkey_capture, "Ctrl+Alt+Q");
        assert!(s.notify_chat);
    }
}
