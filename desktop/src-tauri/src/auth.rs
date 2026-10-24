//! 登录凭据(doc/29 四):原生这边不存任何凭据。网页登录后把 access token 交过来(desktop_set_token),
//! 要用时快过期或没有就向主窗口里的网页要(事件 desktop://need-token),网页按原有逻辑刷新后再交过来。

use base64::Engine;
use std::sync::Mutex;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter};
use tokio::sync::Notify;

pub const NEED_TOKEN_EVENT: &str = "desktop://need-token";

#[derive(Default)]
pub struct Auth {
    token: Mutex<Option<String>>,
    /// 网页明确说登出了:后台的事都停下,不再要 token
    signed_out: Mutex<bool>,
    /// 这次启动以来网页交过 token(登录着):后台任务这才开跑
    seen: Mutex<bool>,
    changed: Notify,
}

#[derive(Debug, thiserror::Error)]
pub enum AuthError {
    #[error("还没登录")]
    SignedOut,
    #[error("网页没有给出登录凭据(窗口还没打开或网络不通)")]
    Timeout,
}

impl Auth {
    /// 网页交来新的 token;None = 登出
    pub fn set(&self, token: Option<String>) {
        *self.signed_out.lock().unwrap() = token.is_none();
        *self.seen.lock().unwrap() = token.is_some();
        *self.token.lock().unwrap() = token;
        self.changed.notify_waiters();
    }

    pub fn is_signed_out(&self) -> bool {
        *self.signed_out.lock().unwrap()
    }

    /// 登录着(网页交过 token、没登出)
    pub fn ready(&self) -> bool {
        *self.seen.lock().unwrap()
    }

    /// 接口回 401 时调:手上的作废,下次 [get] 会再向网页要
    pub fn invalidate(&self, used: &str) {
        let mut t = self.token.lock().unwrap();
        if t.as_deref() == Some(used) {
            *t = None;
        }
    }

    fn current(&self) -> Option<String> {
        self.token.lock().unwrap().clone().filter(|t| !expiring(t, 60))
    }

    /// 拿一个还能用至少一分钟的 token;没有就向网页要,最多等 15 秒
    pub async fn get(&self, app: &AppHandle) -> Result<String, AuthError> {
        if let Some(t) = self.current() {
            return Ok(t);
        }
        if self.is_signed_out() {
            return Err(AuthError::SignedOut);
        }
        let waiter = self.changed.notified();
        tokio::pin!(waiter);
        waiter.as_mut().enable();
        let _ = app.emit_to("main", NEED_TOKEN_EVENT, ());
        let _ = tokio::time::timeout(Duration::from_secs(15), waiter).await;
        if self.is_signed_out() {
            return Err(AuthError::SignedOut);
        }
        self.current().ok_or(AuthError::Timeout)
    }
}

/// JWT 的 exp 离现在不到 [margin] 秒(或解不出来)就算快过期
pub fn expiring(token: &str, margin: u64) -> bool {
    match jwt_exp(token) {
        Some(exp) => {
            let now = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
            exp <= now + margin
        }
        None => true,
    }
}

fn jwt_exp(token: &str) -> Option<u64> {
    let payload = token.split('.').nth(1)?;
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(payload.trim_end_matches('=')).ok()?;
    let v: serde_json::Value = serde_json::from_slice(&bytes).ok()?;
    v.get("exp")?.as_u64()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn token_with_exp(exp: u64) -> String {
        let payload = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(format!(r#"{{"sub":"u","exp":{exp}}}"#));
        format!("h.{payload}.s")
    }

    #[test]
    fn expiry_is_read_from_the_jwt() {
        let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs();
        assert!(!expiring(&token_with_exp(now + 3600), 60));
        assert!(expiring(&token_with_exp(now + 30), 60));
        assert!(expiring("not-a-jwt", 60));
    }

    #[test]
    fn invalidate_only_drops_the_token_that_failed() {
        let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs();
        let a = Auth::default();
        let t1 = token_with_exp(now + 3600);
        a.set(Some(t1.clone()));
        a.invalidate("other");
        assert_eq!(a.current().as_deref(), Some(t1.as_str()));
        a.invalidate(&t1);
        assert!(a.current().is_none());
        a.set(None);
        assert!(a.is_signed_out());
    }
}
