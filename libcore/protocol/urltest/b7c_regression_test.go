// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package urltest

import (
	"context"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	N "github.com/sagernet/sing/common/network"
)

// B7c/O7 「按优先级顺序」多层级调度 Go 侧回归测试：层级 tag 解析、
// 层内延迟 + 容差择优、高层全灭回退、恢复后双重门槛受控回切、
// 流量只在当前最低健康层级内分散、未分层组行为不变。
//
// 测试只用 history 注入 + Select()/performRoundEndCheck()，不拨号：
// 层级成员在真实部署里是内层 urltest 组（tag 带 [T<n>] / [FB] 后缀），
// 此处用 startupOutbound 扮演层级组——RealTag 对非组出站直接返回其 tag，
// history key 即成员 tag，与真实部署的外层选路等价。

func tierMember(tag string) *startupOutbound {
	return &startupOutbound{Adapter: outbound.NewAdapter("loopback", tag, []string{"tcp", "udp"}, nil)}
}

func storeTierHistory(t *testing.T, grp *URLTestGroup, tag string, delay uint16) {
	t.Helper()
	grp.history.StoreURLTestHistory(tag, &adapter.URLTestHistory{Time: time.Now(), Delay: delay})
}

func tierGroup(t *testing.T, members ...adapter.Outbound) *URLTestGroup {
	t.Helper()
	return startupGroupWithMembers(t, context.Background(), "http://example.invalid/204", members...)
}

func TestParseTierTagIndex(t *testing.T) {
	cases := []struct {
		tag      string
		wantTier int
		wantOK   bool
	}{
		{"Balancer 01 [T1]", 0, true},
		{"Balancer 01 [T2]", 1, true},
		{"Balancer 01 [T12]", 11, true},
		{"Balancer 01 [FB]", tierFallbackIndex, true},
		{"Balancer 01", 0, false},
		{"node-a", 0, false},
		{"Balancer 01 [T0]", 0, false},
		{"Balancer 01 [Tx]", 0, false},
		{"Balancer 01 [T1", 0, false},
		{"Balancer 01 [T1] ", 0, false},
		{"weird [T2] name", 0, false},
	}
	for _, tc := range cases {
		tier, ok := parseTierTagIndex(tc.tag)
		if tier != tc.wantTier || ok != tc.wantOK {
			t.Fatalf("parseTierTagIndex(%q) = (%d, %v), want (%d, %v)", tc.tag, tier, ok, tc.wantTier, tc.wantOK)
		}
	}
	if tierFallbackIndex <= 1000 {
		t.Fatalf("fallback tier index must sort after any real tier, got %d", tierFallbackIndex)
	}
}

func TestTieredSelectionPrefersHigherTier(t *testing.T) {
	grp := tierGroup(t,
		tierMember("a [T1]"),
		tierMember("b [T1]"),
		tierMember("c [T2]"),
		tierMember("d [FB]"),
	)
	storeTierHistory(t, grp, "a [T1]", 500)
	storeTierHistory(t, grp, "b [T1]", 120)
	storeTierHistory(t, grp, "c [T2]", 50)
	storeTierHistory(t, grp, "d [FB]", 40)

	// 层级 1 内 120ms 比 500ms 快出容差（50ms）→ 选中 b；
	// 层级 2 的 50ms 与保底的 40ms 再快也不得越层。
	selected, healthy := grp.Select(N.NetworkTCP)
	if selected == nil || selected.Tag() != "b [T1]" || !healthy {
		t.Fatalf("expected tier-1 member b selected, got %v healthy=%v", selected, healthy)
	}
	if tier, _ := grp.effectiveTierLocked(N.NetworkTCP); tier != 0 {
		t.Fatalf("expected effective tier 0, got %d", tier)
	}
}

func TestTieredSelectionFallsBackWhenTierDown(t *testing.T) {
	grp := tierGroup(t,
		tierMember("a [T1]"),
		tierMember("b [T1]"),
		tierMember("c [T2]"),
		tierMember("d [FB]"),
	)
	storeTierHistory(t, grp, "a [T1]", 100)
	storeTierHistory(t, grp, "b [T1]", 120)
	storeTierHistory(t, grp, "c [T2]", 50)
	storeTierHistory(t, grp, "d [FB]", 40)

	selected, _ := grp.Select(N.NetworkTCP)
	if selected == nil || selected.Tag() != "a [T1]" {
		t.Fatalf("expected tier-1 member a selected first, got %v", selected)
	}

	// 层级 1 全灭（删历史）：回退层级 2 的 c，而非保底的 d。
	grp.history.DeleteURLTestHistory("a [T1]")
	grp.history.DeleteURLTestHistory("b [T1]")
	selected, healthy := grp.Select(N.NetworkTCP)
	if selected == nil || selected.Tag() != "c [T2]" || !healthy {
		t.Fatalf("expected fallback to tier-2 member c, got %v healthy=%v", selected, healthy)
	}

	// 层级 2 也全灭：回退保底的 d。
	grp.history.DeleteURLTestHistory("c [T2]")
	selected, healthy = grp.Select(N.NetworkTCP)
	if selected == nil || selected.Tag() != "d [FB]" || !healthy {
		t.Fatalf("expected fallback to fallback-tier member d, got %v healthy=%v", selected, healthy)
	}

	// 全层全灭：回落列表首位，healthy=false（既有行为）。
	grp.history.DeleteURLTestHistory("d [FB]")
	selected, healthy = grp.Select(N.NetworkTCP)
	if selected == nil || selected.Tag() != "a [T1]" || healthy {
		t.Fatalf("expected all-dead fallback to first member unhealthy, got %v healthy=%v", selected, healthy)
	}
}

func TestTieredSwitchBackGatedByRecoveryGate(t *testing.T) {
	grp := tierGroup(t,
		tierMember("a [T1]"),
		tierMember("c [T2]"),
	)
	// 先让层级 1 全灭、层级 2 健康：轮次结束把选择推到层级 2，currentTier=1。
	storeTierHistory(t, grp, "c [T2]", 90)
	grp.performRoundEndCheck()
	selected, _ := grp.Select(N.NetworkTCP)
	if selected == nil || selected.Tag() != "c [T2]" {
		t.Fatalf("expected tier-2 member c selected while tier 1 is down, got %v", selected)
	}
	if grp.currentTier != 1 {
		t.Fatalf("expected currentTier 1, got %d", grp.currentTier)
	}

	// 层级 1 恢复（新鲜历史），但只过 1 轮检查：门未通过，留在层级 2。
	storeTierHistory(t, grp, "a [T1]", 100)
	grp.performRoundEndCheck()
	selected, _ = grp.Select(N.NetworkTCP)
	if selected == nil || selected.Tag() != "c [T2]" {
		t.Fatalf("expected gated switch-back to stay on tier 2 after 1 healthy round, got %v", selected)
	}

	// 再过 2 轮（共 3 轮健康），但距失败仅数毫秒（< 60 秒）：仍不回切。
	grp.performRoundEndCheck()
	grp.performRoundEndCheck()
	if grp.tierGate.consecutiveOK[0] != 3 {
		t.Fatalf("expected 3 consecutive healthy rounds for tier 0, got %d", grp.tierGate.consecutiveOK[0])
	}
	selected, _ = grp.Select(N.NetworkTCP)
	if selected == nil || selected.Tag() != "c [T2]" {
		t.Fatalf("expected gated switch-back to stay on tier 2 within failure-free window, got %v", selected)
	}

	// 距失败满 60 秒（把失败时间拨回 61 秒前）且 3 轮健康：回切层级 1。
	grp.tierGate.lastFailure[0] = time.Now().Add(-61 * time.Second)
	grp.performRoundEndCheck()
	selected, healthy := grp.Select(N.NetworkTCP)
	if selected == nil || selected.Tag() != "a [T1]" || !healthy {
		t.Fatalf("expected switch-back to tier-1 member a after both gates pass, got %v healthy=%v", selected, healthy)
	}
	if grp.currentTier != 0 {
		t.Fatalf("expected currentTier 0 after switch-back, got %d", grp.currentTier)
	}

	// 当前层也全灭时门控不得把流量留在死层：直接回退到有健康成员的层。
	// 先回到“卡在层级 2”的状态：层级 1 全灭一轮（门清零、选择回到层级 2），
	// 再恢复层级 1，同时让层级 2 全灭。
	grp.history.DeleteURLTestHistory("a [T1]")
	grp.performRoundEndCheck()
	selected, _ = grp.Select(N.NetworkTCP)
	if selected == nil || selected.Tag() != "c [T2]" {
		t.Fatalf("expected tier-2 member c selected after tier 1 went down again, got %v", selected)
	}
	storeTierHistory(t, grp, "a [T1]", 100)
	grp.history.DeleteURLTestHistory("c [T2]")
	selected, _ = grp.Select(N.NetworkTCP)
	if selected == nil || selected.Tag() != "a [T1]" {
		t.Fatalf("expected forced fallback to tier 1 when current tier is dead, got %v", selected)
	}
}

func TestUntieredGroupBehaviorUnchanged(t *testing.T) {
	grp := tierGroup(t,
		tierMember("a"),
		tierMember("b"),
	)
	storeTierHistory(t, grp, "a", 500)
	storeTierHistory(t, grp, "b", 120)
	// 未分层组：与改动前一致，按延迟 + 容差择优。
	selected, healthy := grp.Select(N.NetworkTCP)
	if selected == nil || selected.Tag() != "b" || !healthy {
		t.Fatalf("expected untiered group to keep latency selection, got %v healthy=%v", selected, healthy)
	}
	if tiers := grp.distinctTiers(); len(tiers) != 1 || tiers[0] != 0 {
		t.Fatalf("expected untiered group to have a single tier 0, got %v", tiers)
	}
}

func TestPartialTierTagsDoNotActivateTiering(t *testing.T) {
	// 只有一个成员带层级后缀（普通节点名恰好以 [T2] 结尾）：不得激活分层，
	// 全部视为同一层，按延迟择优（改动前行为）。
	grp := tierGroup(t,
		tierMember("x [T2]"),
		tierMember("y"),
	)
	storeTierHistory(t, grp, "x [T2]", 10)
	storeTierHistory(t, grp, "y", 500)
	selected, healthy := grp.Select(N.NetworkTCP)
	if selected == nil || selected.Tag() != "x [T2]" || !healthy {
		t.Fatalf("expected partial tier tags to fall back to plain latency selection, got %v healthy=%v", selected, healthy)
	}
	if tiers := grp.distinctTiers(); len(tiers) != 1 || tiers[0] != 0 {
		t.Fatalf("expected partial tier tags to collapse to a single tier 0, got %v", tiers)
	}
}
