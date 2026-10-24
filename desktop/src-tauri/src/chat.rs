//! 传输助手新消息提醒(doc/29 D3):登录着就每 10 秒看一眼最新的几条,别的设备发来的弹系统通知,点了打开小窗。
//! 这台电脑(桌面端)自己发的不提醒;小窗正开着、在前台时也不提醒(人就在看)。

use crate::api::Api;
use crate::auth::Auth;
use crate::settings::SettingsStore;
use crate::{notify, ui};
use serde::Deserialize;
use serde_json::json;
use std::time::Duration;
use tauri::{AppHandle, Manager};

const POLL: Duration = Duration::from_secs(10);
const LATEST: &str = "query DesktopChatLatest { chatMessages(limit: 20) { id body source node { name } } }";

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Data {
    chat_messages: Vec<Msg>,
}

#[derive(Deserialize, Clone)]
struct Msg {
    id: String,
    body: Option<String>,
    source: Option<String>,
    node: Option<NodeName>,
}

#[derive(Deserialize, Clone)]
struct NodeName {
    name: String,
}

pub fn spawn(app: &AppHandle) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        // 已经看过的最新一条(UUIDv7,字符串比较即时间先后);None = 还没拉过,第一次只记位置不提醒
        let mut seen: Option<String> = None;
        loop {
            tokio::time::sleep(POLL).await;
            if !app.state::<Auth>().ready() {
                seen = None;
                continue;
            }
            let res: Result<Data, _> = app.state::<Api>().gql(&app, LATEST, json!({})).await;
            let Ok(data) = res else { continue };
            let newest = data.chat_messages.iter().map(|m| m.id.clone()).max();
            let fresh = match &seen {
                None => Vec::new(),
                Some(s) => data.chat_messages.iter().filter(|m| m.id > *s).cloned().collect(),
            };
            if newest.is_some() {
                seen = newest.max(seen);
            } else if seen.is_none() {
                seen = Some(String::new());
            }
            let fresh = incoming(fresh);
            if fresh.is_empty() || !app.state::<SettingsStore>().get().notify_chat || mini_in_front(&app) {
                continue;
            }
            let (title, body) = describe(&fresh);
            let a = app.clone();
            notify::toast(
                &app,
                &title,
                &body,
                Some(Box::new(move || {
                    let h = a.clone();
                    let _ = a.run_on_main_thread(move || ui::open_mini(&h));
                })),
            );
        }
    });
}

/// 别的设备发来的(这台电脑发的不算)
fn incoming(list: Vec<Msg>) -> Vec<Msg> {
    let mut v: Vec<Msg> = list.into_iter().filter(|m| m.source.as_deref() != Some("desktop")).collect();
    v.sort_by(|a, b| a.id.cmp(&b.id));
    v
}

fn mini_in_front(app: &AppHandle) -> bool {
    app.get_webview_window(ui::MINI).map(|w| w.is_visible().unwrap_or(false) && w.is_focused().unwrap_or(false)).unwrap_or(false)
}

/// 与网页的 sourceLabel 一致;网页和手机 App 都记成 web,不标
fn source_label(s: Option<&str>) -> Option<&'static str> {
    match s {
        Some("mcp") => Some("Agent"),
        Some("webdav") => Some("WebDAV"),
        Some("fetch") => Some("离线下载"),
        Some("desktop") => Some("电脑"),
        _ => None,
    }
}

/// 通知的标题与正文:一条显示内容,多条显示条数与最后一条
fn describe(list: &[Msg]) -> (String, String) {
    let last = list.last().expect("非空");
    let text = match (&last.node, &last.body) {
        (Some(n), _) => format!("[文件] {}", n.name),
        (None, Some(b)) => {
            let t: String = b.trim().chars().take(80).collect();
            if b.trim().chars().count() > 80 {
                format!("{t}…")
            } else {
                t
            }
        }
        (None, None) => String::new(),
    };
    if list.len() == 1 {
        let title = match source_label(last.source.as_deref()) {
            Some(l) => format!("传输助手 · 来自{l}"),
            None => "传输助手 · 新消息".into(),
        };
        (title, text)
    } else {
        (format!("传输助手 · {} 条新消息", list.len()), text)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn msg(id: &str, body: Option<&str>, file: Option<&str>, source: &str) -> Msg {
        Msg { id: id.into(), body: body.map(Into::into), source: Some(source.into()), node: file.map(|n| NodeName { name: n.into() }) }
    }

    #[test]
    fn own_messages_are_skipped_and_text_is_trimmed() {
        let list = incoming(vec![
            msg("02", Some("hi"), None, "web"),
            msg("01", None, Some("a.pdf"), "mcp"),
            msg("03", Some("x"), None, "desktop"),
        ]);
        assert_eq!(list.iter().map(|m| m.id.as_str()).collect::<Vec<_>>(), vec!["01", "02"]);
        let (title, body) = describe(&list);
        assert_eq!(title, "传输助手 · 2 条新消息");
        assert_eq!(body, "hi");
        let long = "长".repeat(100);
        let (title, body) = describe(&[msg("09", Some(&long), None, "web")]);
        assert_eq!(title, "传输助手 · 新消息");
        assert_eq!(body.chars().count(), 81);
        let (title, body) = describe(&[msg("10", None, Some("a.pdf"), "fetch")]);
        assert_eq!(title, "传输助手 · 来自离线下载");
        assert_eq!(body, "[文件] a.pdf");
    }
}
