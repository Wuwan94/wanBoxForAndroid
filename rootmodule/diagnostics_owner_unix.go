//go:build !windows

package module

import (
	"os"
	"syscall"
)

// applyDiagnosticsOwnership 把导出包交给目标目录的属主/属组。
// Android 上模块以 root 写 /storage/emulated/0/Download，若不改属主，
// Android 12+ 的文件管理器与分享面板无权读取。非 root 环境 chown 会失败，
// 这里安全降级（权限仍为 0644）；目录 stat 失败同样不阻断导出。
func applyDiagnosticsOwnership(path, dir string) {
	info, err := os.Stat(dir)
	if err != nil {
		return
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok {
		return
	}
	_ = os.Chown(path, int(stat.Uid), int(stat.Gid))
}
