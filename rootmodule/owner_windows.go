//go:build windows

// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import "os"

// statOwner 在 Windows 开发构建下不可用：凭据交付只在设备上生效。
func statOwner(os.FileInfo) (int, int, bool) { return 0, 0, false }
