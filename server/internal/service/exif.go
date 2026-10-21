package service

import (
	"bytes"
	"encoding/binary"
)

// exifScanBytes JPEG 开头读这么多找 EXIF / XMP(APP1 段最长 64 KB,前面可能还有 JFIF、ICC)。
const exifScanBytes = 256 << 10

var (
	exifHeader = []byte("Exif\x00\x00")
	xmpHeader  = []byte("http://ns.adobe.com/xap/1.0/\x00")
)

// JPEGGPS 看 JPEG 开头(b 是文件前 exifScanBytes 字节)有没有拍摄位置:EXIF 的 GPS IFD 里有条目,
// 或 XMP 里写了 GPSLatitude / GPSLongitude。strip 为 true 时就地抹掉(doc/22 四.3):
// GPS IFD 的条目与它们指向的数据清零、条目数归零;XMP 整段换成空格。不改任何长度,不重新压缩,
// 方向等其他信息原样保留。返回抹之前有没有位置。
func JPEGGPS(b []byte, strip bool) bool {
	if len(b) < 4 || b[0] != 0xFF || b[1] != 0xD8 {
		return false
	}
	found := false
	for i := 2; i+4 <= len(b); {
		if b[i] != 0xFF {
			return found
		}
		marker := b[i+1]
		if marker == 0xFF { // 填充字节
			i++
			continue
		}
		if marker == 0xD8 || marker >= 0xD0 && marker <= 0xD7 || marker == 0x01 {
			i += 2
			continue
		}
		if marker == 0xDA || marker == 0xD9 { // 图像数据开始 / 结束:元数据段都在前面
			return found
		}
		n := int(binary.BigEndian.Uint16(b[i+2:]))
		if n < 2 || i+2+n > len(b) {
			return found
		}
		seg := b[i+4 : i+2+n]
		if marker == 0xE1 {
			switch {
			case bytes.HasPrefix(seg, exifHeader):
				if exifGPS(seg[len(exifHeader):], strip) {
					found = true
				}
			case bytes.HasPrefix(seg, xmpHeader):
				body := seg[len(xmpHeader):]
				if bytes.Contains(body, []byte("GPSLatitude")) || bytes.Contains(body, []byte("GPSLongitude")) {
					found = true
					if strip {
						for j := range body {
							body[j] = ' '
						}
					}
				}
			}
		}
		i += 2 + n
	}
	return found
}

// exifGPS t 是 TIFF 结构(字节序标记开头)。在 IFD0 里找 GPSInfo(0x8825)指针,GPS IFD 有条目算有位置。
func exifGPS(t []byte, strip bool) bool {
	if len(t) < 8 {
		return false
	}
	var bo binary.ByteOrder
	switch string(t[:2]) {
	case "II":
		bo = binary.LittleEndian
	case "MM":
		bo = binary.BigEndian
	default:
		return false
	}
	if bo.Uint16(t[2:]) != 42 {
		return false
	}
	ifd0 := int(bo.Uint32(t[4:]))
	gps := -1
	if ifd0 < 8 || ifd0+2 > len(t) {
		return false
	}
	cnt := int(bo.Uint16(t[ifd0:]))
	for k := 0; k < cnt; k++ {
		e := ifd0 + 2 + k*12
		if e+12 > len(t) {
			return false
		}
		if bo.Uint16(t[e:]) == 0x8825 {
			gps = int(bo.Uint32(t[e+8:]))
			break
		}
	}
	if gps < 8 || gps+2 > len(t) {
		return false
	}
	n := int(bo.Uint16(t[gps:]))
	if n == 0 {
		return false
	}
	if !strip {
		return true
	}
	for k := 0; k < n; k++ {
		e := gps + 2 + k*12
		if e+12 > len(t) {
			break
		}
		typ, count := bo.Uint16(t[e+2:]), bo.Uint32(t[e+4:])
		if size := uint64(tiffTypeSize(typ)) * uint64(count); size > 4 {
			off := uint64(bo.Uint32(t[e+8:]))
			if off+size <= uint64(len(t)) {
				clear(t[off : off+size])
			}
		}
		clear(t[e : e+12])
	}
	bo.PutUint16(t[gps:], 0)
	return true
}

func tiffTypeSize(typ uint16) int {
	switch typ {
	case 1, 2, 6, 7:
		return 1
	case 3, 8:
		return 2
	case 4, 9, 11:
		return 4
	case 5, 10, 12:
		return 8
	}
	return 0
}
