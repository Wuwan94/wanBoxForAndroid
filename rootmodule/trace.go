// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"fmt"
	"os"
	"time"
)

// Trace 把启动与交付关键节点写到两处，便于用户在文件管理器里直接查看：
//
//	/sdcard/wanbox-serve.log —— 无需 root 权限即可打开
//	/data/adb/wanbox/serve.log —— root 侧
//
// 只做尽力而为，任何写入失败都不影响主流程。
func Trace(format string, args ...any) {
	line := time.Now().Format("2006-01-02 15:04:05") + " " + fmt.Sprintf(format, args...)
	for _, p := range []string{"/sdcard/wanbox-serve.log", "/data/adb/wanbox/serve.log"} {
		if info, e := os.Stat(p); e == nil && info.Size() > 256<<10 {
			_ = os.Remove(p)
		}
		f, e := os.OpenFile(p, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
		if e != nil {
			continue
		}
		_, _ = f.WriteString(line + "\n")
		_ = f.Close()
	}
}
