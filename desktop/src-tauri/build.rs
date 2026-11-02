// 应用自己的命令都在这里登记:tauri-build 据此生成 allow-<命令> / deny-<命令> 权限,
// 没在能力(capability)里允许的窗口调不到。本地页面与远程网页各自只拿自己用得到的(见 capabilities/ 与 bridge.rs)。
const COMMANDS: &[&str] = &[
    // 本地页面:连接、设置
    "app_status",
    "server_connect",
    "settings_get",
    "settings_save",
    "pick_directory",
    "update_check",
    "open_main",
    // 远程网页(主窗口、传输助手小窗)
    "desktop_hello",
    "desktop_set_token",
    "desktop_download",
    "desktop_tasks",
    "desktop_task_action",
    "desktop_pick_folder_done",
    "desktop_open_settings",
    "desktop_open_mini",
    "desktop_open_main",
    "desktop_upload_files",
    "desktop_open_local",
    "desktop_edits",
    "desktop_edit_action",
    "desktop_play_external",
    // 助手直连模型服务(doc/33 四)
    "desktop_ai_fetch",
    "desktop_ai_abort",
];

/// 第一次启动时填好的默认服务器:仓库根 client.env 的 GOPAN_SERVER_URL(见 client.env.example),没有就用占位值
fn default_server() -> String {
    let path = std::path::Path::new(&std::env::var("CARGO_MANIFEST_DIR").unwrap()).join("../../client.env");
    println!("cargo:rerun-if-changed={}", path.display());
    std::fs::read_to_string(&path)
        .ok()
        .and_then(|text| {
            text.lines()
                .map(str::trim)
                .filter(|line| !line.starts_with('#'))
                .filter_map(|line| line.split_once('='))
                .find(|(key, _)| key.trim() == "GOPAN_SERVER_URL")
                .map(|(_, value)| value.trim().to_string())
        })
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| "https://pan.example.com".into())
}

fn main() {
    println!("cargo:rustc-env=GOPAN_DEFAULT_SERVER={}", default_server());
    tauri_build::try_build(tauri_build::Attributes::new().app_manifest(tauri_build::AppManifest::new().commands(COMMANDS)))
        .expect("tauri-build 失败");
}
