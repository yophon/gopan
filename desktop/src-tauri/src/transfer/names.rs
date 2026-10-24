//! 本地文件名:云端名字在 Windows 上可能不合法(冒号、问号、CON……),落盘前换掉;同名不覆盖,加 (1)、(2)。

use std::path::{Path, PathBuf};

const RESERVED: &[&str] = &[
    "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4",
    "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
];

/// 换成 Windows 能用的单段文件名
pub fn safe_name(name: &str) -> String {
    let mut s: String = name.chars().map(|c| if c < ' ' || "<>:\"/\\|?*".contains(c) { '_' } else { c }).collect();
    // 结尾的点和空格 Windows 会悄悄去掉,干脆先去
    while s.ends_with('.') || s.ends_with(' ') {
        s.pop();
    }
    let s = s.trim_start().to_string();
    if s.is_empty() {
        return "未命名".into();
    }
    let stem = s.split('.').next().unwrap_or("").trim_end().to_ascii_uppercase();
    if RESERVED.contains(&stem.as_str()) {
        return format!("_{s}");
    }
    // 单段最长 255 个 UTF-16 单元,留点余量给 (n) 与 .part
    if s.encode_utf16().count() > 200 {
        let (base, ext) = split_ext(&s);
        let mut b: String = base.chars().collect();
        while b.encode_utf16().count() + ext.encode_utf16().count() > 200 {
            b.pop();
        }
        return format!("{b}{ext}");
    }
    s
}

/// "a.tar.gz" 这类只把最后一段当扩展名;以点开头的(.gitignore)没有扩展名
fn split_ext(name: &str) -> (&str, &str) {
    match name.rfind('.') {
        Some(i) if i > 0 && i < name.len() - 1 => (&name[..i], &name[i..]),
        _ => (name, ""),
    }
}

/// 目录里没被占用的路径:a.txt → a (1).txt → a (2).txt
pub fn unique_path(dir: &Path, name: &str, taken: impl Fn(&Path) -> bool) -> PathBuf {
    let first = dir.join(name);
    if !taken(&first) {
        return first;
    }
    let (base, ext) = split_ext(name);
    for n in 1.. {
        let p = dir.join(format!("{base} ({n}){ext}"));
        if !taken(&p) {
            return p;
        }
    }
    unreachable!()
}

pub fn exists(p: &Path) -> bool {
    std::fs::symlink_metadata(p).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_become_windows_safe() {
        assert_eq!(safe_name("a:b?.txt"), "a_b_.txt");
        assert_eq!(safe_name("con.txt"), "_con.txt");
        assert_eq!(safe_name("COM1"), "_COM1");
        assert_eq!(safe_name("console.log"), "console.log");
        assert_eq!(safe_name("报告. "), "报告");
        assert_eq!(safe_name("..."), "未命名");
        assert_eq!(safe_name(".gitignore"), ".gitignore");
        let long = format!("{}.pdf", "长".repeat(300));
        let s = safe_name(&long);
        assert!(s.ends_with(".pdf") && s.encode_utf16().count() <= 200);
    }

    #[test]
    fn unique_path_counts_up() {
        let dir = Path::new("C:/d");
        let taken = [dir.join("a.txt"), dir.join("a (1).txt")];
        assert_eq!(unique_path(dir, "a.txt", |p| taken.contains(&p.to_path_buf())), dir.join("a (2).txt"));
        assert_eq!(unique_path(dir, "b", |p| taken.contains(&p.to_path_buf())), dir.join("b"));
        let taken = [dir.join("x.tar.gz")];
        assert_eq!(unique_path(dir, "x.tar.gz", |p| taken.contains(&p.to_path_buf())), dir.join("x.tar (1).gz"));
    }
}
