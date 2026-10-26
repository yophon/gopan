//! 用播放器播放(doc/31 三):视频、音乐交给电脑上的播放器(PotPlayer、VLC、mpv、MPC …)边下边放,
//! 不用先整个下下来,mkv / HEVC 这类网页放不了的也能看。地址是服务端给的 12 小时内联地址(streamUrl),
//! 播放器拖进度时重新请求不会因为签名过期失败;同文件夹的外挂字幕下到本地一起交过去。

use crate::api::Api;
use crate::auth::Auth;
use serde::Deserialize;
use serde_json::json;
use std::path::{Path, PathBuf};
use std::process::Command;
use tauri::{AppHandle, Manager};

/// 播放器认参数的方式
#[derive(Clone, Copy, Debug, PartialEq)]
enum Kind {
    Vlc,
    Pot,
    Mpv,
    Mpc,
    /// 只认一个地址
    Plain,
}

/// 认得的播放器:可执行文件名(小写)→ 显示名与参数方式
fn known(exe: &str) -> Option<(&'static str, Kind)> {
    let e = exe.to_ascii_lowercase();
    let e = e.as_str();
    Some(match e {
        "vlc.exe" => ("VLC", Kind::Vlc),
        "potplayermini64.exe" | "potplayermini.exe" | "potplayer64.exe" | "potplayer.exe" => ("PotPlayer", Kind::Pot),
        "mpv.exe" | "mpvnet.exe" => ("mpv", Kind::Mpv),
        "mpc-hc64.exe" | "mpc-hc.exe" | "mpc-be64.exe" | "mpc-be.exe" => ("MPC", Kind::Mpc),
        "kmplayer.exe" | "kmplayer64x.exe" => ("KMPlayer", Kind::Plain),
        "smplayer.exe" => ("SMPlayer", Kind::Plain),
        "wmplayer.exe" => ("Windows Media Player", Kind::Plain),
        _ => return None,
    })
}

/// 没设默认播放器时去这些地方找
const SEARCH: &[&str] = &[
    "PotPlayerMini64.exe",
    "PotPlayerMini.exe",
    "vlc.exe",
    "mpv.exe",
    "mpvnet.exe",
    "mpc-hc64.exe",
    "mpc-be64.exe",
];
const INSTALL_DIRS: &[&str] = &[r"DAUM\PotPlayer", r"PotPlayer", r"VideoLAN\VLC", r"MPC-HC", r"MPC-BE x64", r"mpv"];

/// 这个扩展名默认用什么打开(不是认得的播放器、或者是商店应用时返回 None)
fn default_for(ext: &str) -> Option<PathBuf> {
    use windows::core::{HSTRING, PCWSTR, PWSTR};
    use windows::Win32::UI::Shell::{AssocQueryStringW, ASSOCF_NONE, ASSOCSTR_EXECUTABLE};
    let assoc = HSTRING::from(format!(".{ext}"));
    let verb = HSTRING::from("open");
    let mut buf = vec![0u16; 1024];
    let mut len = buf.len() as u32;
    let ok = unsafe {
        AssocQueryStringW(ASSOCF_NONE, ASSOCSTR_EXECUTABLE, PCWSTR(assoc.as_ptr()), PCWSTR(verb.as_ptr()), Some(PWSTR(buf.as_mut_ptr())), &mut len)
    };
    if ok.is_err() {
        return None;
    }
    let end = buf.iter().position(|&c| c == 0).unwrap_or(buf.len());
    let p = PathBuf::from(String::from_utf16_lossy(&buf[..end]));
    p.exists().then_some(p)
}

fn app_path(exe: &str) -> Option<PathBuf> {
    use winreg::enums::{HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE};
    use winreg::RegKey;
    let sub = format!(r"Software\Microsoft\Windows\CurrentVersion\App Paths\{exe}");
    for root in [HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE] {
        if let Ok(k) = RegKey::predef(root).open_subkey(&sub) {
            if let Ok(v) = k.get_value::<String, _>("") {
                let p = PathBuf::from(v.trim_matches('"'));
                if p.exists() {
                    return Some(p);
                }
            }
        }
    }
    None
}

fn installed(exe: &str) -> Option<PathBuf> {
    for var in ["ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"] {
        let Ok(base) = std::env::var(var) else { continue };
        for dir in INSTALL_DIRS {
            let p = Path::new(&base).join(dir).join(exe);
            if p.exists() {
                return Some(p);
            }
        }
    }
    let path = std::env::var_os("PATH")?;
    std::env::split_paths(&path).map(|d| d.join(exe)).find(|p| p.exists())
}

/// 系统自带的旧版 Windows Media Player(Win11 默认的「媒体播放器」是商店应用,拿不到程序路径、也不收命令行地址)
fn legacy_wmp() -> Option<PathBuf> {
    ["ProgramFiles", "ProgramFiles(x86)"]
        .iter()
        .filter_map(|v| std::env::var(v).ok())
        .map(|b| Path::new(&b).join("Windows Media Player").join("wmplayer.exe"))
        .find(|p| p.exists())
}

/// 找一个能放网络地址的播放器:默认程序是认得的播放器就用它,否则按 SEARCH 的顺序找装了的;
/// 都没有时用系统自带的 Windows Media Player 兜底(mkv 里是 H.264 之类的大多能放)
fn find_player(ext: &str) -> Option<(PathBuf, &'static str, Kind)> {
    let def = default_for(ext).and_then(|p| {
        let k = p.file_name().and_then(|f| f.to_str()).and_then(known)?;
        Some((p, k.0, k.1))
    });
    if let Some(d) = def.as_ref().filter(|d| d.1 != "Windows Media Player") {
        return Some(d.clone());
    }
    for exe in SEARCH {
        if let Some(p) = app_path(exe).or_else(|| installed(exe)) {
            let (name, kind) = known(exe)?;
            return Some((p, name, kind));
        }
    }
    def.or_else(|| legacy_wmp().map(|p| (p, "Windows Media Player", Kind::Plain)))
}

fn args(kind: Kind, url: &str, title: &str, sub: Option<&Path>) -> Vec<String> {
    let mut a = vec![url.to_string()];
    match (kind, sub) {
        (Kind::Vlc, s) => {
            a.push(format!("--meta-title={title}"));
            if let Some(s) = s {
                a.push(format!("--sub-file={}", s.display()));
            }
        }
        (Kind::Mpv, s) => {
            a.push(format!("--force-media-title={title}"));
            if let Some(s) = s {
                a.push(format!("--sub-file={}", s.display()));
            }
        }
        (Kind::Pot, Some(s)) => a.push(format!("/sub={}", s.display())),
        (Kind::Mpc, Some(s)) => {
            a.push("/sub".into());
            a.push(s.display().to_string());
        }
        _ => {}
    }
    a
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct StreamNode {
    name: String,
    stream_url: Option<String>,
    preview: StreamPreview,
}

#[derive(Deserialize)]
struct StreamPreview {
    subtitles: Vec<StreamSub>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct StreamSub {
    node_id: String,
    name: String,
    default: bool,
}

#[derive(Deserialize)]
struct Data<T> {
    node: T,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct DownloadNode {
    download_url: Option<String>,
}

/// 默认那条外挂字幕下到本地(原格式,ASS 的样式也保留);下不来就不带字幕
async fn fetch_subtitle(app: &AppHandle, subs: &[StreamSub]) -> Option<PathBuf> {
    let s = subs.iter().find(|s| s.default).or_else(|| subs.first())?;
    let api = app.state::<Api>();
    let d: Data<DownloadNode> = api
        .gql(app, "query DesktopSubUrl($id: ID!) { node(id: $id) { downloadUrl } }", json!({ "id": s.node_id }))
        .await
        .ok()?;
    let bytes = api.http.get(d.node.download_url?).send().await.ok()?.error_for_status().ok()?.bytes().await.ok()?;
    let dir = app.path().app_local_data_dir().ok()?.join("play").join(&s.node_id);
    std::fs::create_dir_all(&dir).ok()?;
    let p = dir.join(crate::transfer::safe_name(&s.name));
    std::fs::write(&p, &bytes).ok()?;
    Some(p)
}

/// 用电脑上的播放器放这个音视频;返回播放器的名字(提示「已交给 PotPlayer」)
#[tauri::command]
pub async fn desktop_play_external(app: AppHandle, id: String) -> Result<String, String> {
    if !app.state::<Auth>().ready() {
        return Err("还没登录".into());
    }
    let d: Data<StreamNode> = app
        .state::<Api>()
        .gql(
            &app,
            "query DesktopStream($id: ID!) { node(id: $id) { name streamUrl preview { subtitles { nodeId name default } } } }",
            json!({ "id": id }),
        )
        .await
        .map_err(|e| e.to_string())?;
    let n = d.node;
    let url = n.stream_url.ok_or("只有视频和音乐能用播放器播放")?;
    let ext = Path::new(&n.name).extension().and_then(|e| e.to_str()).unwrap_or("mp4").to_ascii_lowercase();
    let (exe, name, kind) = find_player(&ext).ok_or("没找到能放网络视频的播放器,装一个 PotPlayer、VLC 或 mpv 再试")?;
    let sub = fetch_subtitle(&app, &n.preview.subtitles).await;
    let title = Path::new(&n.name).file_stem().and_then(|s| s.to_str()).unwrap_or(&n.name).to_string();
    Command::new(&exe)
        .args(args(kind, &url, &title, sub.as_deref()))
        .spawn()
        .map_err(|e| format!("{name} 没能启动:{e}"))?;
    Ok(name.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn players_get_their_own_subtitle_flags() {
        let s = Path::new(r"C:\t\a b.srt");
        assert_eq!(known("PotPlayerMini64.exe").unwrap().1, Kind::Pot);
        assert_eq!(known("vlc.exe").unwrap().0, "VLC");
        assert!(known("Video.UI.exe").is_none());
        assert_eq!(args(Kind::Vlc, "U", "片名", Some(s)), vec!["U", "--meta-title=片名", r"--sub-file=C:\t\a b.srt"]);
        assert_eq!(args(Kind::Pot, "U", "片名", Some(s)), vec!["U", r"/sub=C:\t\a b.srt"]);
        assert_eq!(args(Kind::Mpc, "U", "片名", Some(s)), vec!["U", "/sub", r"C:\t\a b.srt"]);
        assert_eq!(args(Kind::Mpv, "U", "片名", None), vec!["U", "--force-media-title=片名"]);
        assert_eq!(args(Kind::Plain, "U", "片名", Some(s)), vec!["U"]);
    }

    /// 本机实际找到的播放器(手动看:cargo test player_on_this_pc -- --ignored --nocapture)
    #[test]
    #[ignore]
    fn player_on_this_pc() {
        println!("mkv default: {:?}", default_for("mkv"));
        println!("picked: {:?}", find_player("mkv"));
    }
}
