# 压缩包测试样本

- `*.rar`:取自 [rarfile](https://github.com/markokr/rarfile) 4.5 的 `test/files/`(ISC License, Copyright (c) 2005-2024 Marko Kreen)。
- `aes7z.7z`、`bzip2.7z`、`copy.7z`、`empty.7z`、`lzma2.7z`、`ppmd.7z`、`t1.7z`、`zstd.7z`:取自 [bodgit/sevenzip](https://github.com/bodgit/sevenzip) v1.6.5 的 `testdata/`(BSD-3-Clause, Copyright (c) 2020 Matt Dainty)。
- `zh-solid.7z`:Windows 自带 bsdtar 3.8.4 生成(`tar --format 7zip --options 7zip:compression=lzma2`),中文文件名、固实。
- `zh.tar.bz2`:Python tarfile 生成(PAX 格式),中文名与一个指向 /etc/passwd 的软链接。

zip、tar、tar.gz、tar.xz 在测试里现生成。
