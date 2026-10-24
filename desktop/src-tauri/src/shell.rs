//! 资源管理器右键菜单与「发送到」(doc/29 D5)。
//!
//! - 经典右键菜单(Win11 在「显示更多选项」里):文件、文件夹上加「上传到 gopan…」「发到传输助手」,写在 HKCU,不用管理员;
//! - 「发送到」里放两个快捷方式,多选很多文件时用它(一次把所有路径交过来);
//! - 右键菜单每个选中项各起一个进程,单实例插件把参数转给已经在跑的那个,这里攒一小会儿合成一批;
//! - 「上传到 gopan…」让主窗口里的网页选云端文件夹(desktop://pick-folder),选好交回来(desktop_pick_folder_done)再入队;
//!   「发到传输助手」直接传进传输助手文件夹。
//!
//! 卸载时安装包的钩子(windows/hooks.nsh)把这些删掉。

use crate::{notify, transfer, ui};
use serde::Serialize;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager, WebviewWindow};
use winreg::enums::HKEY_CURRENT_USER;
use winreg::RegKey;

pub const UPLOAD_ARG: &str = "--upload";
pub const SEND_ARG: &str = "--send";
pub const PICK_EVENT: &str = "desktop://pick-folder";

/// (注册表里的键名, 菜单上的字, 参数)
const VERBS: &[(&str, &str, &str)] = &[("gopan.upload", "上传到 gopan…", UPLOAD_ARG), ("gopan.send", "发到传输助手", SEND_ARG)];
const VERB_PARENTS: &[&str] = &[r"Software\Classes\*\shell", r"Software\Classes\Directory\shell"];
/// (「发送到」里的文件名, 参数, 说明)
const SEND_TO: &[(&str, &str, &str)] =
    &[("gopan.lnk", UPLOAD_ARG, "上传到 gopan"), ("gopan 传输助手.lnk", SEND_ARG, "发到 gopan 传输助手")];
/// 右键菜单多选时各进程陆续到达,攒这么久合成一批
const GATHER: Duration = Duration::from_millis(700);

#[derive(Default)]
pub struct Shell {
    pending: Mutex<Pending>,
    /// 等网页选文件夹的批次
    picks: Mutex<HashMap<u64, Vec<PathBuf>>>,
    next_pick: AtomicU64,
}

#[derive(Default)]
struct Pending {
    upload: Vec<PathBuf>,
    send: Vec<PathBuf>,
    generation: u64,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct PickRequest {
    request_id: u64,
    count: usize,
    names: Vec<String>,
}

/// 开发版默认不动注册表(免得指向 target 里的程序),设了 GOPAN_DESKTOP_SHELL 才注册,方便本地试
fn allowed() -> bool {
    !cfg!(debug_assertions) || std::env::var_os("GOPAN_DESKTOP_SHELL").is_some()
}

/// 按设置加上或去掉右键菜单与「发送到」;每次启动都写一遍,程序挪了位置(升级、重装)也指得对
pub fn sync(enabled: bool) {
    if !allowed() {
        return;
    }
    tauri::async_runtime::spawn_blocking(move || {
        let r = if enabled { register() } else { unregister() };
        if let Err(e) = r {
            log::warn!("右键菜单{}失败:{e}", if enabled { "注册" } else { "移除" });
        }
    });
}

fn register() -> Result<(), String> {
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let exe_s = exe.to_string_lossy();
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    for parent in VERB_PARENTS {
        for (key, label, arg) in VERBS {
            let (k, _) = hkcu.create_subkey(format!(r"{parent}\{key}")).map_err(|e| e.to_string())?;
            k.set_value("", &label.to_string()).map_err(|e| e.to_string())?;
            k.set_value("MUIVerb", &label.to_string()).map_err(|e| e.to_string())?;
            k.set_value("Icon", &format!("\"{exe_s}\",0")).map_err(|e| e.to_string())?;
            let (c, _) = k.create_subkey("command").map_err(|e| e.to_string())?;
            c.set_value("", &format!("\"{exe_s}\" {arg} \"%1\"")).map_err(|e| e.to_string())?;
        }
    }
    let dir = send_to_dir().ok_or("找不到「发送到」文件夹")?;
    for (file, arg, desc) in SEND_TO {
        create_shortcut(&dir.join(file), &exe, arg, desc).map_err(|e| format!("建「发送到」快捷方式失败:{e}"))?;
    }
    Ok(())
}

fn unregister() -> Result<(), String> {
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    for parent in VERB_PARENTS {
        for (key, _, _) in VERBS {
            let _ = hkcu.delete_subkey_all(format!(r"{parent}\{key}"));
        }
    }
    if let Some(dir) = send_to_dir() {
        for (file, _, _) in SEND_TO {
            let _ = std::fs::remove_file(dir.join(file));
        }
    }
    Ok(())
}

fn send_to_dir() -> Option<PathBuf> {
    let appdata = std::env::var_os("APPDATA")?;
    let dir = PathBuf::from(appdata).join(r"Microsoft\Windows\SendTo");
    dir.is_dir().then_some(dir)
}

fn create_shortcut(path: &Path, target: &Path, args: &str, desc: &str) -> windows::core::Result<()> {
    use windows::core::{Interface, HSTRING};
    use windows::Win32::System::Com::{CoCreateInstance, CoInitializeEx, IPersistFile, CLSCTX_INPROC_SERVER, COINIT_APARTMENTTHREADED};
    use windows::Win32::UI::Shell::{IShellLinkW, ShellLink};
    unsafe {
        let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED);
        let link: IShellLinkW = CoCreateInstance(&ShellLink, None, CLSCTX_INPROC_SERVER)?;
        link.SetPath(&HSTRING::from(target.as_os_str()))?;
        link.SetArguments(&HSTRING::from(args))?;
        link.SetDescription(&HSTRING::from(desc))?;
        link.SetIconLocation(&HSTRING::from(target.as_os_str()), 0)?;
        let file: IPersistFile = link.cast()?;
        file.Save(&HSTRING::from(path.as_os_str()), true)?;
    }
    Ok(())
}

// ---- 参数 ----

/// 从命令行里挑出要上传 / 发送的路径;不是右键菜单来的返回 None
fn parse(argv: &[String]) -> Option<(Vec<PathBuf>, Vec<PathBuf>)> {
    let mut upload = Vec::new();
    let mut send = Vec::new();
    let mut mode: Option<&str> = None;
    for a in argv.iter().skip(1) {
        match a.as_str() {
            UPLOAD_ARG | SEND_ARG => mode = Some(if a == UPLOAD_ARG { UPLOAD_ARG } else { SEND_ARG }),
            s if s.starts_with("--") => {}
            path => match mode {
                Some(UPLOAD_ARG) => upload.push(PathBuf::from(path)),
                Some(_) => send.push(PathBuf::from(path)),
                None => {}
            },
        }
    }
    mode.map(|_| (upload, send))
}

/// 启动或又被打开时的命令行;是右键菜单 / 发送到来的就收下并返回 true
pub fn handle_args(app: &AppHandle, argv: &[String]) -> bool {
    let Some((upload, send)) = parse(argv) else { return false };
    let shell = app.state::<Shell>();
    let generation = {
        let mut p = shell.pending.lock().unwrap();
        for f in upload {
            if !p.upload.contains(&f) {
                p.upload.push(f);
            }
        }
        for f in send {
            if !p.send.contains(&f) {
                p.send.push(f);
            }
        }
        p.generation += 1;
        p.generation
    };
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(GATHER).await;
        let shell = app.state::<Shell>();
        let (upload, send) = {
            let mut p = shell.pending.lock().unwrap();
            if p.generation != generation {
                return; // 后面又来了,等最后一个
            }
            (std::mem::take(&mut p.upload), std::mem::take(&mut p.send))
        };
        flush(&app, upload, send);
    });
    true
}

fn flush(app: &AppHandle, upload: Vec<PathBuf>, send: Vec<PathBuf>) {
    let send: Vec<PathBuf> = send.into_iter().filter(|p| p.exists()).collect();
    let upload: Vec<PathBuf> = upload.into_iter().filter(|p| p.exists()).collect();
    if !send.is_empty() {
        match transfer::enqueue_uploads(app, &send, None, true) {
            Ok(n) => {
                let body = if app.state::<crate::auth::Auth>().ready() {
                    "传完会出现在传输助手里"
                } else {
                    "打开 gopan 登录后开始传"
                };
                notify::toast(app, &format!("正在发到传输助手:{n} 项"), body, None);
            }
            Err(e) => notify::toast(app, "发到传输助手失败", &e, None),
        }
    }
    if !upload.is_empty() {
        let shell = app.state::<Shell>();
        let id = shell.next_pick.fetch_add(1, Ordering::Relaxed) + 1;
        let req = request_of(id, &upload);
        shell.picks.lock().unwrap().insert(id, upload);
        let h = app.clone();
        let _ = app.run_on_main_thread(move || {
            ui::show_main(&h);
            let _ = h.emit_to(ui::MAIN, PICK_EVENT, req);
        });
    }
}

fn request_of(id: u64, paths: &[PathBuf]) -> PickRequest {
    PickRequest {
        request_id: id,
        count: paths.len(),
        names: paths.iter().take(3).map(|p| p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default()).collect(),
    }
}

/// 还没答复的「选文件夹」(主窗口里的网页刚打开时取)
pub fn pending_picks(app: &AppHandle) -> Vec<PickRequest> {
    let mut list: Vec<PickRequest> = app.state::<Shell>().picks.lock().unwrap().iter().map(|(id, p)| request_of(*id, p)).collect();
    list.sort_by_key(|r| r.request_id);
    list
}

/// 网页选好了云端文件夹(folder_id 为 null = 根目录)或取消了
#[tauri::command]
pub fn desktop_pick_folder_done(
    app: AppHandle,
    window: WebviewWindow,
    request_id: u64,
    folder_id: Option<String>,
    cancelled: bool,
) -> Result<usize, String> {
    if window.label() != ui::MAIN {
        return Err("只能在主窗口里选".into());
    }
    let Some(paths) = app.state::<Shell>().picks.lock().unwrap().remove(&request_id) else { return Ok(0) };
    if cancelled {
        return Ok(0);
    }
    transfer::enqueue_uploads(&app, &paths, folder_id, false)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(list: &[&str]) -> Vec<String> {
        list.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn shell_arguments_are_split_by_mode() {
        assert!(parse(&args(&["gopan.exe"])).is_none());
        assert!(parse(&args(&["gopan.exe", "--background"])).is_none());
        let (up, send) = parse(&args(&["gopan.exe", "--upload", "C:/a.txt", "C:/b"])).unwrap();
        assert_eq!(up, vec![PathBuf::from("C:/a.txt"), PathBuf::from("C:/b")]);
        assert!(send.is_empty());
        let (up, send) = parse(&args(&["gopan.exe", "--send", "C:/x.png", "--upload", "D:/y"])).unwrap();
        assert_eq!(send, vec![PathBuf::from("C:/x.png")]);
        assert_eq!(up, vec![PathBuf::from("D:/y")]);
    }

    #[test]
    fn pick_requests_name_the_first_few_items() {
        let r = request_of(7, &[PathBuf::from("C:/d/报告.pdf"), PathBuf::from("C:/d/图"), PathBuf::from("C:/x"), PathBuf::from("C:/y")]);
        assert_eq!(r.count, 4);
        assert_eq!(r.names, vec!["报告.pdf", "图", "x"]);
    }
}
