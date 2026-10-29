package service

import "strings"

// 随对话发的图(doc/35):客户端先缩到长边 1568 的 JPEG;这里只认几种图片 data URL,限张数与总大小。
// 对话记录每条上限 1 MB(maxAIEntryBytes),图跟着消息存进去,所以合计也按 1 MB 算。
const (
	MaxChatImages     = 4
	maxChatImageBytes = 1 << 20
)

var chatImagePrefixes = []string{"data:image/jpeg;base64,", "data:image/png;base64,", "data:image/webp;base64,", "data:image/gif;base64,"}

// CheckChatImages 张数、格式、总大小(按 data URL 的长度算)。
func CheckChatImages(images []string) error {
	if len(images) > MaxChatImages {
		return errf("INVALID_INPUT", "一次最多发 4 张图")
	}
	total := 0
	for _, img := range images {
		ok := false
		for _, p := range chatImagePrefixes {
			if strings.HasPrefix(img, p) {
				ok = true
				break
			}
		}
		if !ok {
			return errf("INVALID_INPUT", "只能发 JPEG、PNG、WebP、GIF 图片")
		}
		total += len(img)
	}
	if total > maxChatImageBytes {
		return errf("INVALID_INPUT", "图片太大了,合计不能超过 1 MB")
	}
	return nil
}
