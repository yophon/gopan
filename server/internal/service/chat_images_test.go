package service

import (
	"strings"
	"testing"
)

func TestCheckChatImages(t *testing.T) {
	jpg := "data:image/jpeg;base64,/9j/AAA="
	if err := CheckChatImages(nil); err != nil {
		t.Fatal(err)
	}
	if err := CheckChatImages([]string{jpg, "data:image/png;base64,iVBO"}); err != nil {
		t.Fatal(err)
	}
	for name, images := range map[string][]string{
		"too many":   {jpg, jpg, jpg, jpg, jpg},
		"not image":  {"data:text/html;base64,PGI+"},
		"plain url":  {"https://example.com/a.jpg"},
		"too large":  {jpg + strings.Repeat("A", 1<<20)},
		"svg script": {"data:image/svg+xml;base64,PHN2Zz4="},
	} {
		if err := CheckChatImages(images); err == nil {
			t.Fatalf("%s: accepted", name)
		}
	}
}
