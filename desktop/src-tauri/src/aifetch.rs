//! 助手直连模型服务(doc/33 四):窗口里的网页同样有跨域限制,直连时请求交给外壳发。
//!
//! 网页只说「用哪个服务、拼什么路径、发什么」,外壳自己用登录凭证向服务端取这个服务的地址与 key,
//! 只放行对话接口,换上真 key 发出去,响应一块块经 desktop://ai 事件交回网页。key 不进网页,
//! 也不能拿它当任意地址的代理。站点提供的服务只能经服务器转发,这里拒绝。

use crate::api::{user_agent, Api};
use base64::Engine;
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager, WebviewWindow};
use tokio::sync::oneshot;

pub const AI_EVENT: &str = "desktop://ai";

/// 进行中的请求:id → 取消
#[derive(Default)]
pub struct AiFetches(Mutex<HashMap<String, oneshot::Sender<()>>>);

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AiFetchArgs {
    id: String,
    provider: String,
    path: String,
    #[serde(default)]
    query: String,
    #[serde(default)]
    headers: HashMap<String, String>,
    #[serde(default)]
    body: String,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase", tag = "kind")]
enum AiEvent {
    Head { id: String, status: u16, content_type: String },
    Chunk { id: String, data: String },
    End { id: String },
    Error { id: String, message: String },
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ProviderRow {
    id: String,
    kind: String,
    base_url: String,
    site: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ProvidersData {
    ai_providers: Vec<ProviderRow>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct KeyData {
    ai_provider_key: String,
}

/// 这类服务的对话接口(与服务端 httpx/ai_proxy.go 的 aiChatPath 一致)
fn chat_path(kind: &str, path: &str) -> bool {
    match kind {
        "OPENAI" => path == "chat/completions",
        "OPENAI_RESPONSES" => path == "responses",
        "ANTHROPIC" => path == "v1/messages",
        "GOOGLE" => {
            let p = path.strip_prefix("v1beta/").or_else(|| path.strip_prefix("v1/")).unwrap_or(path);
            match p.strip_prefix("models/") {
                Some(rest) => match rest.split_once(':') {
                    Some((model, method)) => {
                        !model.is_empty()
                            && !model.contains('/')
                            && matches!(method, "streamGenerateContent" | "generateContent" | "StreamGenerateContent" | "GenerateContent")
                    }
                    None => false,
                },
                None => false,
            }
        }
        _ => false,
    }
}

const FORWARD_HEADERS: &[&str] = &["content-type", "accept", "anthropic-version", "anthropic-beta", "anthropic-dangerous-direct-browser-access"];

async fn target(app: &AppHandle, provider: &str) -> Result<(ProviderRow, String), String> {
    let api = app.state::<Api>();
    let data: ProvidersData = api
        .gql(app, "query { aiProviders { id kind baseUrl site } }", json!({}))
        .await
        .map_err(|e| e.to_string())?;
    let row = data.ai_providers.into_iter().find(|p| p.id == provider).ok_or("找不到这个模型服务")?;
    if row.site {
        return Err("站点提供的模型服务只能经服务器转发".into());
    }
    let key: KeyData = api
        .gql(app, "query($id: ID!) { aiProviderKey(id: $id) }", json!({ "id": provider }))
        .await
        .map_err(|e| e.to_string())?;
    Ok((row, key.ai_provider_key))
}

async fn run(app: &AppHandle, window: &WebviewWindow, args: AiFetchArgs, cancel: oneshot::Receiver<()>) -> Result<(), String> {
    let (row, key) = target(app, &args.provider).await?;
    if !chat_path(&row.kind, &args.path) {
        return Err("只能调对话接口".into());
    }
    let mut url = format!("{}/{}", row.base_url.trim_end_matches('/'), args.path);
    let query: Vec<&str> = args.query.split('&').filter(|p| !p.is_empty() && !p.starts_with("key=")).collect();
    if !query.is_empty() {
        url = format!("{url}?{}", query.join("&"));
    }
    let http = reqwest::Client::builder()
        .user_agent(user_agent(app))
        .connect_timeout(Duration::from_secs(15))
        .read_timeout(Duration::from_secs(300))
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .map_err(|e| e.to_string())?;
    let mut req = http.post(&url).body(args.body);
    for (k, v) in &args.headers {
        if FORWARD_HEADERS.contains(&k.to_ascii_lowercase().as_str()) {
            req = req.header(k, v);
        }
    }
    if !key.is_empty() {
        req = match row.kind.as_str() {
            "ANTHROPIC" => req.header("x-api-key", &key),
            "GOOGLE" => req.header("x-goog-api-key", &key),
            _ => req.bearer_auth(&key),
        };
    }
    let label = window.label().to_string();
    let send = |ev: AiEvent| {
        let _ = app.emit_to(label.as_str(), AI_EVENT, ev);
    };
    let fut = async {
        let resp = req.send().await.map_err(|e| format!("连不上模型服务:{e}"))?;
        let content_type = resp.headers().get("content-type").and_then(|v| v.to_str().ok()).unwrap_or("").to_string();
        send(AiEvent::Head { id: args.id.clone(), status: resp.status().as_u16(), content_type });
        let mut stream = resp.bytes_stream();
        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(|e| format!("读回答时断了:{e}"))?;
            send(AiEvent::Chunk { id: args.id.clone(), data: base64::engine::general_purpose::STANDARD.encode(&chunk) });
        }
        Ok::<(), String>(())
    };
    tokio::select! {
        r = fut => r,
        _ = cancel => Err("已取消".into()),
    }
}

#[tauri::command]
pub async fn desktop_ai_fetch(app: AppHandle, window: WebviewWindow, args: AiFetchArgs) -> Result<(), String> {
    let (tx, rx) = oneshot::channel();
    let id = args.id.clone();
    app.state::<AiFetches>().0.lock().unwrap().insert(id.clone(), tx);
    let result = run(&app, &window, args, rx).await;
    app.state::<AiFetches>().0.lock().unwrap().remove(&id);
    let ev = match &result {
        Ok(()) => AiEvent::End { id },
        Err(message) => AiEvent::Error { id, message: message.clone() },
    };
    let _ = app.emit_to(window.label(), AI_EVENT, ev);
    Ok(())
}

#[tauri::command]
pub fn desktop_ai_abort(app: AppHandle, id: String) {
    if let Some(tx) = app.state::<AiFetches>().0.lock().unwrap().remove(&id) {
        let _ = tx.send(());
    }
}

#[cfg(test)]
mod tests {
    use super::chat_path;

    #[test]
    fn only_chat_paths() {
        assert!(chat_path("OPENAI", "chat/completions"));
        assert!(!chat_path("OPENAI", "models"));
        assert!(chat_path("ANTHROPIC", "v1/messages"));
        assert!(!chat_path("ANTHROPIC", "v1/messages/batches"));
        assert!(chat_path("GOOGLE", "models/gemini-2.5-flash:streamGenerateContent"));
        assert!(chat_path("GOOGLE", "v1beta/models/x:generateContent"));
        assert!(!chat_path("GOOGLE", "models/x:countTokens"));
        assert!(!chat_path("GOOGLE", "models/a/b:generateContent"));
        assert!(chat_path("OPENAI_RESPONSES", "responses"));
        assert!(!chat_path("NOPE", "responses"));
    }
}
