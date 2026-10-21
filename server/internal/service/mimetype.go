package service

import (
	"mime"
	"path/filepath"
	"strings"
)

// extraMime 先于 mime.TypeByExtension 查。运行镜像(debian-slim)没有 /etc/mime.types,
// Go 内置表只认 mp4 / mp3 / m4a / flac / ogg / wav,还把 .webm 当成音频(doc/22 二.1)。
// .ts 不在里面:网盘里更常见的是 TypeScript 源码(textExts 按文本预览)。
// 迁移 00024 按同一张表回填存量,改这里时同步改那边。
var extraMime = map[string]string{
	".mkv":  "video/x-matroska",
	".mov":  "video/quicktime",
	".m4v":  "video/x-m4v",
	".webm": "video/webm",
	".avi":  "video/x-msvideo",
	".m2ts": "video/mp2t",
	".mts":  "video/mp2t",
	".3gp":  "video/3gpp",
	".flv":  "video/x-flv",
	".wmv":  "video/x-ms-wmv",
	".mpg":  "video/mpeg",
	".mpeg": "video/mpeg",

	".aac":  "audio/aac",
	".weba": "audio/webm",
	".wma":  "audio/x-ms-wma",
	".ape":  "audio/x-ape",
	".aif":  "audio/aiff",
	".aiff": "audio/aiff",
	".mka":  "audio/x-matroska",
	".m4b":  "audio/mp4",
	".amr":  "audio/amr",

	".srt": "application/x-subrip",
	".ass": "text/x-ssa",
	".ssa": "text/x-ssa",
	".vtt": "text/vtt",
}

func mimeByName(name string) string {
	ext := strings.ToLower(filepath.Ext(name))
	if t, ok := extraMime[ext]; ok {
		return t
	}
	if t := mime.TypeByExtension(ext); t != "" {
		return t
	}
	return "application/octet-stream"
}
