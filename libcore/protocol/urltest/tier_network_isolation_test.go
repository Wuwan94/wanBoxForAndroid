// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package urltest

import (
	"reflect"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter/outbound"
	N "github.com/sagernet/sing/common/network"
)

// TCP/UDP 层级状态隔离回归：currentTier 与恢复门按网络各存一份
// （tierTCP / tierUDP）。一个网络的故障转移、promoteFailover 与回切门
// 推进不得污染另一个网络；performUpdateCheckLocked 必须同时同步
// 两个网络的层级，不得用互斥的 else if 跳过 UDP。

func tierMemberTCP(tag string) *startupOutbound {
	return &startupOutbound{Adapter: outbound.NewAdapter("loopback", tag, []string{"tcp"}, nil)}
}

func tierMemberUDP(tag string) *startupOutbound {
	return &startupOutbound{Adapter: outbound.NewAdapter("loopback", tag, []string{"udp"}, nil)}
}

// splitTierGroup 的成员按网络拆分：T1 与 T2 各有一个 TCP-only 与一个
// UDP-only 成员，两个网络的选择与层级可以完全独立演进。
func splitTierGroup(t *testing.T) *URLTestGroup {
	t.Helper()
	return tierGroup(t,
		tierMemberTCP("a-tcp [T1]"),
		tierMemberUDP("a-udp [T1]"),
		tierMemberTCP("c-tcp [T2]"),
		tierMemberUDP("c-udp [T2]"),
	)
}

func storeAllSplitHistories(t *testing.T, grp *URLTestGroup) {
	t.Helper()
	storeTierHistory(t, grp, "a-tcp [T1]", 100)
	storeTierHistory(t, grp, "a-udp [T1]", 100)
	storeTierHistory(t, grp, "c-tcp [T2]", 90)
	storeTierHistory(t, grp, "c-udp [T2]", 90)
}

// copyGateState 深拷贝一份恢复门状态，用于断言另一网络的操作前后
// 门状态完全不变（map 按值复制，比较的是内容而非实例）。
func copyGateState(gate tierRecoveryGateState) tierRecoveryGateState {
	okCopy := make(map[int]int, len(gate.consecutiveOK))
	for tier, count := range gate.consecutiveOK {
		okCopy[tier] = count
	}
	failCopy := make(map[int]time.Time, len(gate.lastFailure))
	for tier, at := range gate.lastFailure {
		failCopy[tier] = at
	}
	return tierRecoveryGateState{consecutiveOK: okCopy, lastFailure: failCopy}
}

func TestTCPAndUDPCurrentTierAreIndependent(t *testing.T) {
	grp := splitTierGroup(t)
	// TCP 的 T1 成员没有历史（不可用），UDP 的 T1 成员健康：
	// TCP 必须落到 T2，UDP 必须留在 T1，两者互不影响。
	storeTierHistory(t, grp, "a-udp [T1]", 100)
	storeTierHistory(t, grp, "c-tcp [T2]", 90)
	storeTierHistory(t, grp, "c-udp [T2]", 90)
	grp.performUpdateCheck()

	if grp.selectedOutboundTCP == nil || grp.selectedOutboundTCP.Tag() != "c-tcp [T2]" {
		t.Fatalf("expected TCP selection on tier 2, got %v", grp.selectedOutboundTCP)
	}
	if grp.selectedOutboundUDP == nil || grp.selectedOutboundUDP.Tag() != "a-udp [T1]" {
		t.Fatalf("expected UDP selection on tier 1, got %v", grp.selectedOutboundUDP)
	}
	if grp.tierTCP.currentTier != 1 {
		t.Fatalf("expected TCP currentTier 1, got %d", grp.tierTCP.currentTier)
	}
	if grp.tierUDP.currentTier != 0 {
		t.Fatalf("expected UDP currentTier 0, got %d", grp.tierUDP.currentTier)
	}
	grp.updateAccess.RLock()
	tcpTier, _ := grp.effectiveTierLocked(N.NetworkTCP)
	udpTier, _ := grp.effectiveTierLocked(N.NetworkUDP)
	grp.updateAccess.RUnlock()
	if tcpTier != 1 || udpTier != 0 {
		t.Fatalf("expected effective tiers TCP=1 UDP=0, got TCP=%d UDP=%d", tcpTier, udpTier)
	}
}

func TestTCPFailoverDoesNotChangeUDPTier(t *testing.T) {
	grp := splitTierGroup(t)
	storeAllSplitHistories(t, grp)
	grp.performUpdateCheck()
	if grp.tierTCP.currentTier != 0 || grp.tierUDP.currentTier != 0 {
		t.Fatalf("expected both networks on tier 0, got TCP=%d UDP=%d", grp.tierTCP.currentTier, grp.tierUDP.currentTier)
	}
	udpGateBefore := copyGateState(grp.tierUDP.gate)

	// TCP 的 T1 成员失败：TCP 向下故障转移到 T2（立即切换，不经回切门）。
	grp.history.DeleteURLTestHistory("a-tcp [T1]")
	grp.performUpdateCheck()

	if grp.selectedOutboundTCP == nil || grp.selectedOutboundTCP.Tag() != "c-tcp [T2]" {
		t.Fatalf("expected TCP failover to tier 2, got %v", grp.selectedOutboundTCP)
	}
	if grp.tierTCP.currentTier != 1 {
		t.Fatalf("expected TCP currentTier 1 after failover, got %d", grp.tierTCP.currentTier)
	}
	if grp.selectedOutboundUDP == nil || grp.selectedOutboundUDP.Tag() != "a-udp [T1]" {
		t.Fatalf("expected UDP selection to stay on tier 1, got %v", grp.selectedOutboundUDP)
	}
	if grp.tierUDP.currentTier != 0 {
		t.Fatalf("expected UDP currentTier to stay 0, got %d", grp.tierUDP.currentTier)
	}
	if !reflect.DeepEqual(udpGateBefore, copyGateState(grp.tierUDP.gate)) {
		t.Fatal("TCP failover must not change UDP gate state")
	}
}

func TestUDPFailoverDoesNotChangeTCPTier(t *testing.T) {
	grp := splitTierGroup(t)
	storeAllSplitHistories(t, grp)
	grp.performUpdateCheck()
	if grp.tierTCP.currentTier != 0 || grp.tierUDP.currentTier != 0 {
		t.Fatalf("expected both networks on tier 0, got TCP=%d UDP=%d", grp.tierTCP.currentTier, grp.tierUDP.currentTier)
	}
	tcpGateBefore := copyGateState(grp.tierTCP.gate)

	// UDP 的 T1 成员失败：UDP 向下故障转移到 T2。
	grp.history.DeleteURLTestHistory("a-udp [T1]")
	grp.performUpdateCheck()

	if grp.selectedOutboundUDP == nil || grp.selectedOutboundUDP.Tag() != "c-udp [T2]" {
		t.Fatalf("expected UDP failover to tier 2, got %v", grp.selectedOutboundUDP)
	}
	if grp.tierUDP.currentTier != 1 {
		t.Fatalf("expected UDP currentTier 1 after failover, got %d", grp.tierUDP.currentTier)
	}
	if grp.selectedOutboundTCP == nil || grp.selectedOutboundTCP.Tag() != "a-tcp [T1]" {
		t.Fatalf("expected TCP selection to stay on tier 1, got %v", grp.selectedOutboundTCP)
	}
	if grp.tierTCP.currentTier != 0 {
		t.Fatalf("expected TCP currentTier to stay 0, got %d", grp.tierTCP.currentTier)
	}
	if !reflect.DeepEqual(tcpGateBefore, copyGateState(grp.tierTCP.gate)) {
		t.Fatal("UDP failover must not change TCP gate state")
	}
}

func TestPerformUpdateCheckUpdatesBothNetworkTiers(t *testing.T) {
	grp := splitTierGroup(t)
	// 预置两个错误的层级值：一次更新必须把两个网络都纠正过来，
	// 不能因为 TCP 已有选择就跳过 UDP 的同步。
	grp.tierTCP.currentTier = tierFallbackIndex
	grp.tierUDP.currentTier = tierFallbackIndex
	storeTierHistory(t, grp, "c-tcp [T2]", 90)
	storeTierHistory(t, grp, "a-udp [T1]", 100)
	grp.performUpdateCheck()

	if grp.tierTCP.currentTier != 1 {
		t.Fatalf("expected TCP currentTier synced to 1, got %d", grp.tierTCP.currentTier)
	}
	if grp.tierUDP.currentTier != 0 {
		t.Fatalf("expected UDP currentTier synced to 0, got %d", grp.tierUDP.currentTier)
	}
}

// promoteFailover 的向下容灾只许下移被拨网络的生效层：TCP 拨号失败
// 切到 T2 时，仍在 T1 健康运行的 UDP 不得被连带下移（反之亦然）。
func TestPromoteFailoverOnlyMovesDialedNetworkTier(t *testing.T) {
	aTCP := tierMemberTCP("a-tcp [T1]")
	aUDP := tierMemberUDP("a-udp [T1]")
	cTCP := tierMemberTCP("c-tcp [T2]")
	cUDP := tierMemberUDP("c-udp [T2]")
	grp := tierGroup(t, aTCP, aUDP, cTCP, cUDP)
	storeAllSplitHistories(t, grp)
	grp.performUpdateCheck()
	if grp.tierTCP.currentTier != 0 || grp.tierUDP.currentTier != 0 {
		t.Fatalf("expected both networks on tier 0, got TCP=%d UDP=%d", grp.tierTCP.currentTier, grp.tierUDP.currentTier)
	}

	grp.promoteFailover(cTCP, N.NetworkTCP)
	if grp.selectedOutboundTCP != cTCP {
		t.Fatalf("expected TCP selection promoted to tier 2, got %v", grp.selectedOutboundTCP)
	}
	if grp.tierTCP.currentTier != 1 {
		t.Fatalf("expected TCP currentTier moved down to 1, got %d", grp.tierTCP.currentTier)
	}
	if grp.selectedOutboundUDP != aUDP || grp.tierUDP.currentTier != 0 {
		t.Fatalf("TCP promote must not move UDP: selected=%v tier=%d", grp.selectedOutboundUDP, grp.tierUDP.currentTier)
	}

	grp.promoteFailover(cUDP, N.NetworkUDP)
	if grp.selectedOutboundUDP != cUDP || grp.tierUDP.currentTier != 1 {
		t.Fatalf("expected UDP promoted to tier 2, got selected=%v tier=%d", grp.selectedOutboundUDP, grp.tierUDP.currentTier)
	}
	if grp.selectedOutboundTCP != cTCP || grp.tierTCP.currentTier != 1 {
		t.Fatalf("UDP promote must not move TCP: selected=%v tier=%d", grp.selectedOutboundTCP, grp.tierTCP.currentTier)
	}
}

func TestTCPRecoveryGateDoesNotUseUDPObservations(t *testing.T) {
	grp := tierGroup(t,
		tierMemberUDP("a-udp [T1]"),
		tierMember("c [T2]"),
	)
	// 只有 T2 健康：两个网络都先落到 T2。
	storeTierHistory(t, grp, "c [T2]", 90)
	grp.performRoundEndCheck(newObservedRound(observedFailed("a-udp [T1]"), observedOK("c [T2]")))
	if grp.tierTCP.currentTier != 1 || grp.tierUDP.currentTier != 1 {
		t.Fatalf("expected both networks on tier 1, got TCP=%d UDP=%d", grp.tierTCP.currentTier, grp.tierUDP.currentTier)
	}

	// T1 只在 UDP 侧恢复，连续 3 轮新成功：只能推进 UDP 的回切门。
	storeTierHistory(t, grp, "a-udp [T1]", 100)
	for i := 0; i < 3; i++ {
		grp.performRoundEndCheck(newObservedRound(observedOKUDP("a-udp [T1]"), observedOK("c [T2]"), observedOKUDP("c [T2]")))
	}
	if got := grp.tierUDP.gate.consecutiveOK[0]; got != 3 {
		t.Fatalf("expected UDP gate at 3 healthy rounds, got %d", got)
	}
	if got := grp.tierTCP.gate.consecutiveOK[0]; got != 0 {
		t.Fatalf("UDP observations must not advance the TCP gate, got %d", got)
	}

	// 距上次失败满 60 秒后：UDP 可以回切，TCP 仍被自己的门挡住。
	grp.tierUDP.gate.lastFailure[0] = time.Now().Add(-61 * time.Second)
	grp.tierTCP.gate.lastFailure[0] = time.Now().Add(-61 * time.Second)
	now := time.Now()
	if !grp.canSwitchBackTo(N.NetworkUDP, 0, now) {
		t.Fatal("expected UDP gate to allow switch-back after its own 3 healthy rounds")
	}
	if grp.canSwitchBackTo(N.NetworkTCP, 0, now) {
		t.Fatal("TCP gate must stay closed: it has no healthy rounds of its own")
	}
	grp.updateAccess.RLock()
	tcpTier, _ := grp.effectiveTierLocked(N.NetworkTCP)
	grp.updateAccess.RUnlock()
	if tcpTier != 1 {
		t.Fatalf("expected TCP effective tier to stay 1, got %d", tcpTier)
	}
}

func TestUDPRecoveryGateDoesNotUseTCPObservations(t *testing.T) {
	grp := tierGroup(t,
		tierMemberTCP("a-tcp [T1]"),
		tierMember("c [T2]"),
	)
	// 只有 T2 健康：两个网络都先落到 T2。
	storeTierHistory(t, grp, "c [T2]", 90)
	grp.performRoundEndCheck(newObservedRound(observedFailed("a-tcp [T1]"), observedOK("c [T2]")))
	if grp.tierTCP.currentTier != 1 || grp.tierUDP.currentTier != 1 {
		t.Fatalf("expected both networks on tier 1, got TCP=%d UDP=%d", grp.tierTCP.currentTier, grp.tierUDP.currentTier)
	}

	// T1 只在 TCP 侧恢复，连续 3 轮新成功：只能推进 TCP 的回切门。
	storeTierHistory(t, grp, "a-tcp [T1]", 100)
	for i := 0; i < 3; i++ {
		grp.performRoundEndCheck(newObservedRound(observedOK("a-tcp [T1]"), observedOK("c [T2]")))
	}
	if got := grp.tierTCP.gate.consecutiveOK[0]; got != 3 {
		t.Fatalf("expected TCP gate at 3 healthy rounds, got %d", got)
	}
	if got := grp.tierUDP.gate.consecutiveOK[0]; got != 0 {
		t.Fatalf("TCP observations must not advance the UDP gate, got %d", got)
	}

	// 距上次失败满 60 秒后：TCP 可以回切，UDP 仍被自己的门挡住。
	grp.tierTCP.gate.lastFailure[0] = time.Now().Add(-61 * time.Second)
	grp.tierUDP.gate.lastFailure[0] = time.Now().Add(-61 * time.Second)
	now := time.Now()
	if !grp.canSwitchBackTo(N.NetworkTCP, 0, now) {
		t.Fatal("expected TCP gate to allow switch-back after its own 3 healthy rounds")
	}
	if grp.canSwitchBackTo(N.NetworkUDP, 0, now) {
		t.Fatal("UDP gate must stay closed: it has no healthy rounds of its own")
	}
	grp.updateAccess.RLock()
	udpTier, _ := grp.effectiveTierLocked(N.NetworkUDP)
	grp.updateAccess.RUnlock()
	if udpTier != 1 {
		t.Fatalf("expected UDP effective tier to stay 1, got %d", udpTier)
	}
}

// 一个网络的失败只能清零并盖戳自己网络的门：另一网络的 consecutiveOK
// 只能被它自己的成功推进，lastFailure 不得被跨网络盖上。

func TestTCPFailureDoesNotResetUDPGate(t *testing.T) {
	grp := tierGroup(t,
		tierMemberTCP("a-tcp [T1]"),
		tierMemberUDP("a-udp [T1]"),
		tierMember("c [T2]"),
	)
	// UDP 的 T1 连续健康 2 轮：UDP 门 consecutiveOK[0]=2，且从未失败。
	storeTierHistory(t, grp, "a-udp [T1]", 100)
	storeTierHistory(t, grp, "c [T2]", 90)
	grp.performRoundEndCheck(newObservedRound(observedOKUDP("a-udp [T1]"), observedOKUDP("c [T2]")))
	grp.performRoundEndCheck(newObservedRound(observedOKUDP("a-udp [T1]"), observedOKUDP("c [T2]")))
	if got := grp.tierUDP.gate.consecutiveOK[0]; got != 2 {
		t.Fatalf("expected UDP gate at 2 healthy rounds, got %d", got)
	}

	// 这一轮 TCP 的 T1 成员新失败（UDP 的 T1 同时成功）：
	// TCP 门清零并记自己的失败时间；UDP 门只能被自己的成功推进到 3。
	grp.performRoundEndCheck(newObservedRound(
		observedFailed("a-tcp [T1]"), observedOKUDP("a-udp [T1]"), observedOKUDP("c [T2]")))
	if got := grp.tierTCP.gate.consecutiveOK[0]; got != 0 {
		t.Fatalf("expected TCP gate reset to 0 after its own failure, got %d", got)
	}
	if _, failed := grp.tierTCP.gate.lastFailure[0]; !failed {
		t.Fatal("expected TCP gate to record its own failure time")
	}
	if got := grp.tierUDP.gate.consecutiveOK[0]; got != 3 {
		t.Fatalf("expected UDP gate to advance only by its own success to 3, got %d", got)
	}
	if _, failed := grp.tierUDP.gate.lastFailure[0]; failed {
		t.Fatal("UDP gate must not record a failure from TCP's failed member")
	}
}

func TestUDPFailureDoesNotResetTCPGate(t *testing.T) {
	grp := tierGroup(t,
		tierMemberTCP("a-tcp [T1]"),
		tierMemberUDP("a-udp [T1]"),
		tierMember("c [T2]"),
	)
	// TCP 的 T1 连续健康 2 轮：TCP 门 consecutiveOK[0]=2，且从未失败。
	storeTierHistory(t, grp, "a-tcp [T1]", 100)
	storeTierHistory(t, grp, "c [T2]", 90)
	grp.performRoundEndCheck(newObservedRound(observedOK("a-tcp [T1]"), observedOK("c [T2]")))
	grp.performRoundEndCheck(newObservedRound(observedOK("a-tcp [T1]"), observedOK("c [T2]")))
	if got := grp.tierTCP.gate.consecutiveOK[0]; got != 2 {
		t.Fatalf("expected TCP gate at 2 healthy rounds, got %d", got)
	}

	// 这一轮 UDP 的 T1 成员新失败（TCP 的 T1 同时成功）：
	// UDP 门清零并记自己的失败时间；TCP 门只能被自己的成功推进到 3。
	grp.performRoundEndCheck(newObservedRound(
		observedOK("a-tcp [T1]"), observedFailedUDP("a-udp [T1]"), observedOK("c [T2]"), observedOKUDP("c [T2]")))
	if got := grp.tierUDP.gate.consecutiveOK[0]; got != 0 {
		t.Fatalf("expected UDP gate reset to 0 after its own failure, got %d", got)
	}
	if _, failed := grp.tierUDP.gate.lastFailure[0]; !failed {
		t.Fatal("expected UDP gate to record its own failure time")
	}
	if got := grp.tierTCP.gate.consecutiveOK[0]; got != 3 {
		t.Fatalf("expected TCP gate to advance only by its own success to 3, got %d", got)
	}
	if _, failed := grp.tierTCP.gate.lastFailure[0]; failed {
		t.Fatal("TCP gate must not record a failure from UDP's failed member")
	}
}

func TestUnknownNetworkDoesNotMutateTierState(t *testing.T) {
	grp := splitTierGroup(t)
	storeAllSplitHistories(t, grp)
	grp.performUpdateCheck()
	tcpTierBefore := grp.tierTCP.currentTier
	udpTierBefore := grp.tierUDP.currentTier
	tcpSelection := grp.selectedOutboundTCP
	udpSelection := grp.selectedOutboundUDP

	// 未知网络没有状态可取：不悄悄映射到 TCP 或 UDP，也不 panic。
	if state := grp.tierStateLocked("unknown"); state != nil {
		t.Fatalf("expected nil tier state for unknown network, got %v", state)
	}
	if grp.canSwitchBackTo("unknown", 0, time.Now()) {
		t.Fatal("unknown network must not pass the switch-back gate")
	}
	if selected, healthy := grp.Select("unknown"); selected != nil || healthy {
		t.Fatalf("expected no selection for unknown network, got %v healthy=%v", selected, healthy)
	}
	grp.promoteFailover(grp.outbounds[0], "unknown")

	// 两侧选择、层级与门必须完全不变。
	if grp.tierTCP.currentTier != tcpTierBefore || grp.tierUDP.currentTier != udpTierBefore {
		t.Fatalf("unknown network mutated tier state: TCP %d->%d, UDP %d->%d",
			tcpTierBefore, grp.tierTCP.currentTier, udpTierBefore, grp.tierUDP.currentTier)
	}
	if grp.selectedOutboundTCP != tcpSelection || grp.selectedOutboundUDP != udpSelection {
		t.Fatal("unknown network mutated selections")
	}
	if len(grp.tierTCP.gate.consecutiveOK) != 0 || len(grp.tierUDP.gate.consecutiveOK) != 0 ||
		len(grp.tierTCP.gate.lastFailure) != 0 || len(grp.tierUDP.gate.lastFailure) != 0 {
		t.Fatal("unknown network mutated gate state")
	}
}

func TestUnlayeredGroupKeepsExistingBehavior(t *testing.T) {
	grp := tierGroup(t,
		tierMember("a"),
		tierMember("b"),
	)
	// 未分层组：两个网络的层级状态都从 -1 开始。
	if grp.tierTCP.currentTier != -1 || grp.tierUDP.currentTier != -1 {
		t.Fatalf("expected both currentTiers to start at -1, got TCP=%d UDP=%d",
			grp.tierTCP.currentTier, grp.tierUDP.currentTier)
	}
	storeTierHistory(t, grp, "a", 500)
	storeTierHistory(t, grp, "b", 120)
	grp.performUpdateCheck()

	// TCP 与 UDP 都按原有的延迟 + 容差逻辑选中 b，且层级同步为 0。
	for _, network := range []string{N.NetworkTCP, N.NetworkUDP} {
		selected, healthy := grp.Select(network)
		if selected == nil || selected.Tag() != "b" || !healthy {
			t.Fatalf("expected unlayered %s selection to stay b, got %v healthy=%v", network, selected, healthy)
		}
	}
	if grp.tierTCP.currentTier != 0 || grp.tierUDP.currentTier != 0 {
		t.Fatalf("expected both currentTiers synced to 0, got TCP=%d UDP=%d",
			grp.tierTCP.currentTier, grp.tierUDP.currentTier)
	}
}
