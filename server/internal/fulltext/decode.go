package fulltext

import (
	"bytes"
	"path/filepath"
	"strings"
	"unicode/utf8"

	"golang.org/x/text/encoding/simplifiedchinese"
	"golang.org/x/text/encoding/unicode"
)

// Kind 内容类型(按字节嗅探,参考文件名)。
type Kind int

const (
	KindUnsupported Kind = iota
	KindText
	KindPDF
	KindZip // docx / xlsx / pptx / odt / ods / odp 要打开 zip 才分得清
	KindOLE // 旧版二进制 Office(doc / xls / ppt)
)

var textExts = map[string]bool{
	".txt": true, ".md": true, ".markdown": true, ".log": true, ".json": true, ".xml": true, ".yaml": true,
	".yml": true, ".toml": true, ".ini": true, ".cfg": true, ".conf": true, ".properties": true, ".csv": true,
	".tsv": true, ".srt": true, ".vtt": true, ".rst": true, ".tex": true, ".html": true, ".htm": true,
	".css": true, ".scss": true, ".less": true, ".js": true, ".mjs": true, ".ts": true, ".jsx": true,
	".tsx": true, ".vue": true, ".go": true, ".py": true, ".java": true, ".kt": true, ".kts": true,
	".gradle": true, ".scala": true, ".swift": true, ".dart": true, ".c": true, ".h": true, ".cpp": true,
	".hpp": true, ".cc": true, ".cs": true, ".rs": true, ".rb": true, ".php": true, ".lua": true, ".pl": true,
	".r": true, ".sh": true, ".bash": true, ".zsh": true, ".bat": true, ".ps1": true, ".sql": true,
}

// 常见二进制格式的文件头:命中就不必再往下猜
var binaryMagic = [][]byte{
	{0xFF, 0xD8, 0xFF},       // jpeg
	[]byte("\x89PNG"),        // png
	[]byte("GIF8"),           // gif
	[]byte("RIFF"),           // wav / webp / avi
	[]byte("ID3"),            // mp3
	[]byte("OggS"),           // ogg
	[]byte("fLaC"),           // flac
	[]byte("\x1F\x8B"),       // gzip
	[]byte("7z\xBC\xAF"),     // 7z
	[]byte("Rar!"),           // rar
	[]byte("\x7FELF"),        // elf
	[]byte("MZ"),             // exe
	{0x1A, 0x45, 0xDF, 0xA3}, // mkv / webm
}

// Sniff 看文件头决定怎么提取。names 是引用这份内容的文件名(blob 本身没有名字),mime 是 blob 记录的类型。
func Sniff(head []byte, names []string, mime string) Kind {
	switch {
	case bytes.HasPrefix(head, []byte("%PDF-")):
		return KindPDF
	case bytes.HasPrefix(head, []byte("PK\x03\x04")):
		return KindZip
	case bytes.HasPrefix(head, []byte{0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1}):
		return KindOLE
	}
	for _, m := range binaryMagic {
		if bytes.HasPrefix(head, m) {
			return KindUnsupported
		}
	}
	if len(head) >= 8 && string(head[4:8]) == "ftyp" { // mp4 / mov / heic
		return KindUnsupported
	}
	textual := strings.HasPrefix(mime, "text/")
	for _, n := range names {
		if textExts[strings.ToLower(filepath.Ext(n))] {
			textual = true
		}
	}
	// 没扩展名的 README、Makefile、LICENSE 之类:头部是干净的文本也收
	if textual || looksText(head) {
		return KindText
	}
	return KindUnsupported
}

// looksText 头部没有 NUL、是合法 UTF-8 或带 UTF-16 BOM。
func looksText(head []byte) bool {
	if len(head) == 0 {
		return false
	}
	if bytes.HasPrefix(head, []byte{0xFF, 0xFE}) || bytes.HasPrefix(head, []byte{0xFE, 0xFF}) {
		return true
	}
	return bytes.IndexByte(head, 0) < 0 && utf8.Valid(trimPartialRune(head))
}

var utf8BOM = []byte{0xEF, 0xBB, 0xBF}

// DecodeText 把文本文件的字节解成字符串:BOM → UTF-8 → GB18030 依次尝试(与安卓 TextFetcher 同一策略)。
// b 可能是从大文件中间截断的,末尾不完整的字符会被丢掉。ok 为假表示不像文本(含 NUL 或解不出来)。
func DecodeText(b []byte) (string, bool) {
	switch {
	case bytes.HasPrefix(b, utf8BOM):
		b = b[3:]
	case bytes.HasPrefix(b, []byte{0xFF, 0xFE}):
		s, err := unicode.UTF16(unicode.LittleEndian, unicode.ExpectBOM).NewDecoder().Bytes(evenLen(b))
		return string(s), err == nil
	case bytes.HasPrefix(b, []byte{0xFE, 0xFF}):
		s, err := unicode.UTF16(unicode.BigEndian, unicode.ExpectBOM).NewDecoder().Bytes(evenLen(b))
		return string(s), err == nil
	}
	if bytes.IndexByte(b[:min(len(b), 8192)], 0) >= 0 {
		return "", false
	}
	if t := trimPartialRune(b); utf8.Valid(t) {
		return string(t), true
	}
	s, err := simplifiedchinese.GB18030.NewDecoder().Bytes(b)
	if err != nil {
		return "", false
	}
	// GB18030 几乎什么字节都能解;解出来大量替换符说明其实是别的编码或二进制
	out := string(s)
	if bad := strings.Count(out, "�"); bad*50 > utf8.RuneCountInString(out) {
		return "", false
	}
	return out, true
}

// DecodeName 解文件名一类的短字节串:合法 UTF-8 原样返回,否则按 GB18030 解
// (Windows 中文系统打的 zip、tar 里的名字,以及老网站 Content-Disposition 里的 filename)。
func DecodeName(b []byte) string {
	if utf8.Valid(b) {
		return string(b)
	}
	if s, err := simplifiedchinese.GB18030.NewDecoder().Bytes(b); err == nil {
		return string(s)
	}
	return strings.ToValidUTF8(string(b), "_")
}

// trimPartialRune 去掉末尾被截断的半个 UTF-8 字符(最多 3 个字节)。
func trimPartialRune(b []byte) []byte {
	for i := 0; i < 3 && len(b) > 0; i++ {
		r, size := utf8.DecodeLastRune(b)
		if r != utf8.RuneError || size != 1 {
			break
		}
		// 是否是一个合法字符的开头(只是后面被截了)
		start := len(b) - 1
		for start > 0 && len(b)-start < 4 && !utf8.RuneStart(b[start]) {
			start--
		}
		if utf8.RuneStart(b[start]) && !utf8.FullRune(b[start:]) {
			return b[:start]
		}
		break
	}
	return b
}

func evenLen(b []byte) []byte {
	return b[:len(b)&^1]
}
