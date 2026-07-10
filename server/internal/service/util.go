package service

import "encoding/base64"

func encodeB64(s string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(s))
}

func decodeB64(s string) string {
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		return ""
	}
	return string(b)
}
