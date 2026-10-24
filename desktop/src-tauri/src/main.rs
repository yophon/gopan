// 发布版不带控制台窗口
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    gopan_desktop_lib::run()
}
