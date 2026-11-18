// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import "time"

// directModeRetry 是「把运行模式切到 direct」失败后的重试状态机。
// 旧实现是一个布尔锁存：一次失败后永不重试，直到动作离开 direct 再进入；
// 瞬时失败（核心刚重启、Clash API 短暂 5xx、模块 reload 中）就会让用户在
// 明确要求直连的网络上一直走代理（P2-3）。
//
// 现在改为有界退避重试：15s → 1min → 5min，之后保持 5 分钟一次的慢速重试。
// 既不重试风暴（最多 5 分钟一次），也不会永久放弃。
type directModeRetry struct {
	attempts int
	retryAt  time.Time
}

var directModeRetryBackoff = []time.Duration{15 * time.Second, time.Minute, 5 * time.Minute}

// allow 报告此刻是否应再次尝试切到 direct。
func (d *directModeRetry) allow(now time.Time) bool {
	return !now.Before(d.retryAt)
}

// failed 记录一次失败并安排下一次尝试（退避封顶 5 分钟，长尾不停）。
func (d *directModeRetry) failed(now time.Time) {
	index := d.attempts
	if index >= len(directModeRetryBackoff) {
		index = len(directModeRetryBackoff) - 1
	}
	d.attempts++
	d.retryAt = now.Add(directModeRetryBackoff[index])
}

// reset 在切换成功或动作离开 direct 时清零退避状态。
func (d *directModeRetry) reset() {
	d.attempts = 0
	d.retryAt = time.Time{}
}
