//go:build !windows

// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"os"
	"syscall"
)

// statOwner 返回 Unix 下文件属主，用于把凭据文件改属到管理 App 的 uid。
func statOwner(info os.FileInfo) (int, int, bool) {
	st, ok := info.Sys().(*syscall.Stat_t)
	if !ok {
		return 0, 0, false
	}
	return int(st.Uid), int(st.Gid), true
}
