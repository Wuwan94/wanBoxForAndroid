// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"testing"
	"time"
)

// P2-3：direct 模式切换失败后不能永久锁存。旧实现一次失败即 blocked，
// 直到离开 direct 网络才重试；瞬时失败（核心刚重启、Clash API 短暂 5xx、
// 模块 reload 中）会让手机在用户明确要求直连的网络上一直走代理。
// 期望：有界退避 15s → 1min → 5min，之后保持 5 分钟一次，永不放弃。
func TestDirectModeRetryBacksOffThenKeepsTrying(t *testing.T) {
	var retry directModeRetry
	now := time.Now()
	if !retry.allow(now) {
		t.Fatal("fresh state must allow the first attempt")
	}

	retry.failed(now)
	if retry.allow(now.Add(14 * time.Second)) {
		t.Fatal("expected the first retry to wait 15s")
	}
	if !retry.allow(now.Add(15 * time.Second)) {
		t.Fatal("expected the first retry to be allowed after 15s")
	}

	first := now.Add(15 * time.Second)
	retry.failed(first)
	if retry.allow(first.Add(59 * time.Second)) {
		t.Fatal("expected the second retry to wait 1 minute")
	}
	if !retry.allow(first.Add(time.Minute)) {
		t.Fatal("expected the second retry to be allowed after 1 minute")
	}

	second := first.Add(time.Minute)
	retry.failed(second)
	if retry.allow(second.Add(4 * time.Minute)) {
		t.Fatal("expected the third retry to wait 5 minutes")
	}
	if !retry.allow(second.Add(5 * time.Minute)) {
		t.Fatal("expected the third retry to be allowed after 5 minutes")
	}

	// 长尾：不再是“最多 N 次就放弃”，而是保持 5 分钟一次的慢速重试。
	third := second.Add(5 * time.Minute)
	retry.failed(third)
	if retry.allow(third.Add(4 * time.Minute)) {
		t.Fatal("expected the tail retry to keep the 5 minute cadence")
	}
	if !retry.allow(third.Add(5 * time.Minute)) {
		t.Fatal("tail retries must never stop while the network stays direct")
	}

	// 离开 direct / 切换成功后清零，下一次直连仍立即尝试。
	retry.reset()
	if !retry.allow(third.Add(5 * time.Minute)) {
		t.Fatal("reset must clear the latch so the next direct action tries immediately")
	}
}
