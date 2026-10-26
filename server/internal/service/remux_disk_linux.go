package service

import "syscall"

// diskFree 目录所在磁盘还剩多少字节(非 root 可用的);取不到返回 -1。
func diskFree(dir string) int64 {
	var st syscall.Statfs_t
	if err := syscall.Statfs(dir, &st); err != nil {
		return -1
	}
	return int64(st.Bavail) * int64(st.Bsize)
}
