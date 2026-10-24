//! 原生这边调服务端:GraphQL(带网页交来的 token,401 时向网页要新的再试一次)与普通 HTTP。
//! UA 末尾带 gopan-desktop/<版本>:服务端据此把传输助手消息记成「电脑」发的(doc/29)。

use crate::auth::{Auth, AuthError};
use crate::settings::SettingsStore;
use serde::de::DeserializeOwned;
use serde_json::{json, Value};
use std::time::Duration;
use tauri::{AppHandle, Manager};

#[derive(Debug, thiserror::Error)]
pub enum ApiError {
    #[error(transparent)]
    Auth(#[from] AuthError),
    #[error("网络错误:{0}")]
    Network(String),
    #[error("{message}")]
    Server { code: String, message: String },
    #[error("服务器回的内容看不懂:{0}")]
    Decode(String),
}

impl ApiError {
    pub fn code(&self) -> &str {
        match self {
            ApiError::Auth(AuthError::SignedOut) => "SIGNED_OUT",
            ApiError::Auth(AuthError::Timeout) => "NO_TOKEN",
            ApiError::Network(_) => "NETWORK",
            ApiError::Server { code, .. } => code,
            ApiError::Decode(_) => "DECODE",
        }
    }

    /// 断网、超时这类过一会儿再试就好的
    pub fn is_transient(&self) -> bool {
        matches!(self, ApiError::Network(_) | ApiError::Auth(AuthError::Timeout))
            || matches!(self, ApiError::Server { code, .. } if code == "INTERNAL" || code == "HTTP_5XX" || code == "RATE_LIMITED")
    }
}

pub fn user_agent(app: &AppHandle) -> String {
    format!("gopan-desktop/{} (Windows)", app.package_info().version)
}

pub struct Api {
    pub http: reqwest::Client,
}

impl Api {
    pub fn new(app: &AppHandle) -> Self {
        let http = reqwest::Client::builder()
            .user_agent(user_agent(app))
            .connect_timeout(Duration::from_secs(15))
            .read_timeout(Duration::from_secs(60))
            .build()
            .expect("reqwest client");
        Self { http }
    }

    pub fn base(app: &AppHandle) -> String {
        app.state::<SettingsStore>().get().server_url
    }

    /// 一次 GraphQL 调用,返回 data;UNAUTHENTICATED 时换个 token 再试一次
    pub async fn gql<T: DeserializeOwned>(&self, app: &AppHandle, query: &str, variables: Value) -> Result<T, ApiError> {
        let auth = app.state::<Auth>();
        for attempt in 0..2 {
            let token = auth.get(app).await?;
            match self.gql_once(app, &token, query, &variables).await {
                Err(ApiError::Server { code, .. }) if code == "UNAUTHENTICATED" && attempt == 0 => {
                    auth.invalidate(&token);
                    continue;
                }
                other => return other,
            }
        }
        Err(ApiError::Auth(AuthError::Timeout))
    }

    async fn gql_once<T: DeserializeOwned>(&self, app: &AppHandle, token: &str, query: &str, variables: &Value) -> Result<T, ApiError> {
        let res = self
            .http
            .post(format!("{}/query", Self::base(app)))
            .bearer_auth(token)
            .json(&json!({ "query": query, "variables": variables }))
            .send()
            .await
            .map_err(|e| ApiError::Network(e.to_string()))?;
        let status = res.status();
        if status.as_u16() == 401 {
            return Err(ApiError::Server { code: "UNAUTHENTICATED".into(), message: "登录已失效".into() });
        }
        if status.is_server_error() {
            return Err(ApiError::Server { code: "HTTP_5XX".into(), message: format!("服务器出错(HTTP {})", status.as_u16()) });
        }
        let body: Value = res.json().await.map_err(|e| ApiError::Decode(e.to_string()))?;
        if let Some(err) = body.get("errors").and_then(|e| e.as_array()).and_then(|a| a.first()) {
            let code = err.pointer("/extensions/code").and_then(|c| c.as_str()).unwrap_or("ERROR").to_string();
            let message = err.get("message").and_then(|m| m.as_str()).unwrap_or("出错了").to_string();
            return Err(ApiError::Server { code, message });
        }
        let data = body.get("data").cloned().unwrap_or(Value::Null);
        serde_json::from_value(data).map_err(|e| ApiError::Decode(e.to_string()))
    }
}
