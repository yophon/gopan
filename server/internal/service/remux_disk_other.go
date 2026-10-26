//go:build !linux

package service

// diskFree 只在 Linux(生产)上查;开发机上返回 -1,不拦。
func diskFree(string) int64 { return -1 }
