// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package urltest

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"reflect"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// 2026-11-20 复审四项修复的回归用例：
//  1. P1：TCP 探测不再冒充 UDP 证据（观测按网络分开）。
//  2. P2：真实拨号失败降级后，旧恢复资格作废，必须重新取得新成功证据。
//  3. P2：组关闭后不再发起备用拨号，迟到连接必须关闭释放。
//  4. 未分层组与单协议成员不被新逻辑误伤。

// ---- P1：TCP 观测不得推进 UDP 门 ----

// 双栈成员的同轮 TCP 成功只推进 TCP 门；UDP 没有自己的证据时保持在低层。
func TestTCPSuccessNeverAdvancesUDPRecoveryGate(t *testing.T) {
	upper := tierMember("a [T1]")
	lower := tierMember("b [T2]")
	grp := tierGroup(t, upper, lower)
	storeTierHistory(t, grp, "a [T1]", 100)
	storeTierHistory(t, grp, "b [T2]", 90)
	// UDP 先降级到 T2：T1 的历史仍新鲜，但 UDP 需要自己的回切证据。
	grp.tierTCP.currentTier = 0
	grp.tierUDP.currentTier = 1
	grp.selectedOutboundTCP = upper
	grp.selectedOutboundUDP = lower
	grp.tierTCP.gate.lastFailure[0] = time.Now().Add(-61 * time.Second)
	grp.tierUDP.gate.lastFailure[0] = time.Now().Add(-61 * time.Second)

	// 连续 3 轮：TCP 的 T1 成员真实新成功，UDP 侧没有观测（只有 TCP 结果）。
	for i := 0; i < tierSwitchBackMinChecks; i++ {
		grp.performRoundEndCheck(newObservedRound(observedOK("a [T1]"), observedOK("b [T2]")))
	}
	if got := grp.tierTCP.gate.consecutiveOK[0]; got != tierSwitchBackMinChecks {
		t.Fatalf("expected TCP gate at %d healthy rounds, got %d", tierSwitchBackMinChecks, got)
	}
	if got := grp.tierUDP.gate.consecutiveOK[0]; got != 0 {
		t.Fatalf("TCP-only success must not advance the UDP gate, got %d", got)
	}
	grp.updateAccess.RLock()
	udpTier, _ := grp.effectiveTierLocked(N.NetworkUDP)
	grp.updateAccess.RUnlock()
	if udpTier != 1 {
		t.Fatalf("UDP must stay on the lower tier without its own evidence, got tier %d", udpTier)
	}
	grp.updateAccess.RLock()
	tcpTier, _ := grp.effectiveTierLocked(N.NetworkTCP)
	grp.updateAccess.RUnlock()
	if tcpTier != 0 {
		t.Fatalf("TCP must still recover with its own evidence, got tier %d", tcpTier)
	}

	// UDP 侧每轮都是失败：只在 UDP 门清零并盖戳，TCP 不受影响。
	before := grp.tierTCP.gate.consecutiveOK[0]
	for i := 0; i < 2; i++ {
		grp.performRoundEndCheck(newObservedRound(
			observedOK("a [T1]"), observedFailedUDP("a [T1]"), observedOK("b [T2]"), observedOKUDP("b [T2]")))
	}
	if got := grp.tierTCP.gate.consecutiveOK[0]; got != before+2 {
		t.Fatalf("UDP failure must not reset the TCP gate: got %d, want %d", got, before+2)
	}
	if got := grp.tierUDP.gate.consecutiveOK[0]; got != 0 {
		t.Fatalf("UDP failure must reset the UDP gate, got %d", got)
	}
}

// UDP 只有拿到自己的连续新成功后才允许回切；失败/跳过/取消都不算证据。
func TestUDPRecoveryRequiresOwnNewSuccesses(t *testing.T) {
	upper := tierMember("a [T1]")
	lower := tierMember("b [T2]")
	grp := tierGroup(t, upper, lower)
	storeTierHistory(t, grp, "a [T1]", 100)
	storeTierHistory(t, grp, "b [T2]", 90)
	grp.tierTCP.currentTier = 0
	grp.tierUDP.currentTier = 1
	grp.selectedOutboundTCP = upper
	grp.selectedOutboundUDP = lower
	grp.tierUDP.gate.lastFailure[0] = time.Now().Add(-61 * time.Second)

	// 第一个 UDP 成功只能让 UDP 门到 1，还不够回切。
	grp.performRoundEndCheck(newObservedRound(observedOK("a [T1]"), observedOKUDP("a [T1]"), observedOKUDP("b [T2]")))
	if got := grp.tierUDP.gate.consecutiveOK[0]; got != 1 {
		t.Fatalf("expected UDP gate 1, got %d", got)
	}
	grp.updateAccess.RLock()
	udpTier, _ := grp.effectiveTierLocked(N.NetworkUDP)
	grp.updateAccess.RUnlock()
	if udpTier != 1 {
		t.Fatalf("one UDP success must not switch back, got tier %d", udpTier)
	}

	// 跳过/取消不是证据：门既不涨也不清零。
	grp.performRoundEndCheck(newObservedRound(observedSkippedUDP("a [T1]"), observedSkippedUDP("b [T2]")))
	grp.performRoundEndCheck(newObservedRound(observedSkipCancelUDP("a [T1]"), observedSkipCancelUDP("b [T2]")))
	if got := grp.tierUDP.gate.consecutiveOK[0]; got != 1 {
		t.Fatalf("skipped/canceled UDP rounds must be neutral, got %d", got)
	}

	// 达成 3 轮 UDP 新成功后才回切。
	grp.performRoundEndCheck(newObservedRound(observedOKUDP("a [T1]"), observedOKUDP("b [T2]")))
	grp.performRoundEndCheck(newObservedRound(observedOKUDP("a [T1]"), observedOKUDP("b [T2]")))
	if got := grp.tierUDP.gate.consecutiveOK[0]; got != tierSwitchBackMinChecks {
		t.Fatalf("expected UDP gate %d, got %d", tierSwitchBackMinChecks, got)
	}
	grp.updateAccess.RLock()
	udpTier, _ = grp.effectiveTierLocked(N.NetworkUDP)
	grp.updateAccess.RUnlock()
	if udpTier != 0 {
		t.Fatalf("expected UDP switch-back after its own 3 successes, got tier %d", udpTier)
	}
	// TCP 的 T1 只按自己的成功计数（本轮 1 次）。
	if got := grp.tierTCP.gate.consecutiveOK[0]; got != 1 {
		t.Fatalf("TCP gate must only count its own successes, got %d", got)
	}
}

// 单协议成员、不同层成员与非分层组：观测互不冒充。
func TestPerNetworkObservationsDoNotLeakAcrossMembersAndGroups(t *testing.T) {
	t.Run("single protocol members", func(t *testing.T) {
		grp := tierGroup(t, tierMemberTCP("a-tcp [T1]"), tierMemberUDP("b-udp [T1]"), tierMember("c [T2]"))
		storeTierHistory(t, grp, "c [T2]", 90)
		grp.performRoundEndCheck(newObservedRound(observedOK("a-tcp [T1]"), observedOK("c [T2]")))
		if got := grp.tierTCP.gate.consecutiveOK[0]; got != 1 {
			t.Fatalf("TCP-only member success must advance the TCP gate, got %d", got)
		}
		if got := grp.tierUDP.gate.consecutiveOK[0]; got != 0 {
			t.Fatalf("TCP-only member must not advance the UDP gate, got %d", got)
		}
		// UDP 成员自己成功：只推进 UDP 门。
		grp.performRoundEndCheck(newObservedRound(observedOKUDP("b-udp [T1]"), observedOKUDP("c [T2]")))
		if got := grp.tierUDP.gate.consecutiveOK[0]; got != 1 {
			t.Fatalf("UDP-only member success must advance the UDP gate, got %d", got)
		}
		if got := grp.tierTCP.gate.consecutiveOK[0]; got != 1 {
			t.Fatalf("UDP-only member must not advance the TCP gate, got %d", got)
		}
	})

	t.Run("different tiers in one row", func(t *testing.T) {
		grp := tierGroup(t, tierMember("a [T1]"), tierMember("b [T2]"))
		// 同一轮：TCP 高层成功、UDP 低层成功。
		grp.performRoundEndCheck(newObservedRound(observedOK("a [T1]"), observedOKUDP("b [T2]")))
		if grp.tierTCP.gate.consecutiveOK[0] != 1 || grp.tierTCP.gate.consecutiveOK[1] != 0 {
			t.Fatalf("TCP tiers borrowed the UDP result: %v", grp.tierTCP.gate.consecutiveOK)
		}
		if grp.tierUDP.gate.consecutiveOK[0] != 0 || grp.tierUDP.gate.consecutiveOK[1] != 1 {
			t.Fatalf("UDP tiers borrowed the TCP result: %v", grp.tierUDP.gate.consecutiveOK)
		}
	})

	t.Run("unlayered group", func(t *testing.T) {
		grp := tierGroup(t, tierMember("a"), tierMember("b"))
		storeTierHistory(t, grp, "a", 100)
		storeTierHistory(t, grp, "b", 120)
		grp.performRoundEndCheck(newObservedRound(observedOK("a"), observedOK("b")))
		if grp.tierTCP.gate.consecutiveOK[0] != 1 {
			t.Fatalf("unlayered group TCP gate must advance, got %v", grp.tierTCP.gate.consecutiveOK)
		}
		if got := grp.tierUDP.gate.consecutiveOK[0]; got != 0 {
			t.Fatalf("unlayered group UDP gate must not borrow TCP results, got %d", got)
		}
		selected, _ := grp.Select(N.NetworkUDP)
		if selected == nil || selected.Tag() != "a" {
			t.Fatalf("unlayered UDP selection must keep the delay-based behaviour, got %v", selected)
		}
	})
}

// 生产路径：同一轮里 TCP 探测成功而 UDP 关联失败，两层门必须各自裁决。
func TestRealProbeRoundKeepsTCPAndUDPObservationsApart(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	// tcpOK：TCP 可达、UDP 关联报错；deadTCP：TCP 不可达、UDP 关联可用。
	tcpOK := &udpBrokenOutbound{Adapter: outbound.NewAdapter("loopback", "a [T1]", []string{"tcp", "udp"}, nil), server: server}
	deadTCP := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "b [T2]", []string{"tcp", "udp"}, nil), dead: true}
	grp := startupGroupWithMembers(t, context.Background(), server.URL, tcpOK, deadTCP)

	_, round, err := grp.urlTest(context.Background(), true)
	if err != nil {
		t.Fatalf("round: %v", err)
	}
	if got := round.snapshotNetwork(N.NetworkTCP)["a [T1]"]; got != urlTestSuccess {
		t.Fatalf("expected TCP success for a [T1], got %v", got)
	}
	if got := round.snapshotNetwork(N.NetworkUDP)["a [T1]"]; got != urlTestFailure {
		t.Fatalf("expected UDP failure for a [T1], got %v", got)
	}
	if got := round.snapshotNetwork(N.NetworkUDP)["b [T2]"]; got != urlTestSuccess {
		t.Fatalf("expected UDP association success for b [T2], got %v", got)
	}
	if grp.tierTCP.gate.consecutiveOK[0] != 1 {
		t.Fatalf("TCP gate must advance by the real TCP success, got %v", grp.tierTCP.gate.consecutiveOK)
	}
	if got := grp.tierUDP.gate.consecutiveOK[0]; got != 0 {
		t.Fatalf("UDP gate must not borrow the TCP success, got %d", got)
	}
	if _, failed := grp.tierUDP.gate.lastFailure[0]; !failed {
		t.Fatal("the real UDP association failure must be recorded on the UDP gate")
	}
	// UDP 关联失败 MUST NOT 清掉 TCP 的选路历史，也不计 TCP 连续失败。
	if grp.history.LoadURLTestHistory("a [T1]") == nil {
		t.Fatal("UDP probe failure must not evict the TCP probe history")
	}
}

// ---- P2：降级后旧恢复资格作废 ----

func TestDialFailureFailoverInvalidatesStaleRecoveryQualification(t *testing.T) {
	primary := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "a [T1]", []string{"tcp", "udp"}, nil), dead: true}
	lower := &stubDialOutbound{Adapter: outbound.NewAdapter("loopback", "b [T2]", []string{"tcp", "udp"}, nil)}
	grp := tierGroup(t, primary, lower)
	storeTierHistory(t, grp, primary.Tag(), 100)
	storeTierHistory(t, grp, lower.Tag(), 90)
	grp.selectedOutboundTCP = primary
	grp.tierTCP.currentTier = 0
	close(grp.startupDone)
	// 降级前已经攒满 T1 的回切资格（3 轮健康 + 60 秒无失败）。
	grp.tierTCP.gate.consecutiveOK[0] = tierSwitchBackMinChecks
	grp.tierTCP.gate.lastFailure[0] = time.Now().Add(-61 * time.Second)

	u := &URLTest{group: grp, logger: log.NewNOPFactory().Logger()}
	conn, err := u.DialContext(context.Background(), N.NetworkTCP, M.ParseSocksaddrHostPort("example.com", 443))
	if err != nil {
		t.Fatalf("expected failover to the healthy lower tier, got %v", err)
	}
	_ = conn.Close()
	if grp.tierTCP.currentTier != 1 {
		t.Fatalf("expected currentTier 1 after failover, got %d", grp.tierTCP.currentTier)
	}
	if got := grp.tierTCP.gate.consecutiveOK[0]; got != 0 {
		t.Fatalf("a real dial failure must invalidate the stale T1 qualification, got %d", got)
	}
	if _, failed := grp.tierTCP.gate.lastFailure[0]; !failed {
		t.Fatal("the failed tier must carry a fresh failure stamp")
	}

	// 只有低层新观测：不得回切。
	grp.performRoundEndCheck(newObservedRound(observedOK("b [T2]")))
	if selected, _ := grp.Select(N.NetworkTCP); selected == nil || selected.Tag() != "b [T2]" {
		t.Fatalf("stale qualification must not switch back, got %v", selected)
	}

	// T1 重新取得规定次数的新成功后才回切。
	for i := 0; i < tierSwitchBackMinChecks; i++ {
		grp.performRoundEndCheck(newObservedRound(observedOK("a [T1]"), observedOK("b [T2]")))
	}
	if got := grp.tierTCP.gate.consecutiveOK[0]; got != tierSwitchBackMinChecks {
		t.Fatalf("expected %d fresh T1 successes, got %d", tierSwitchBackMinChecks, got)
	}
	// 新成功已攒满，但降级盖下的失败戳还没满 60 秒：仍不得回切。
	if selected, _ := grp.Select(N.NetworkTCP); selected == nil || selected.Tag() != "b [T2]" {
		t.Fatalf("the 60s failure-free window must still hold the selection down, got %v", selected)
	}
	grp.tierTCP.gate.lastFailure[0] = time.Now().Add(-61 * time.Second)
	if selected, _ := grp.Select(N.NetworkTCP); selected == nil || selected.Tag() != "a [T1]" {
		t.Fatalf("expected switch-back after fresh evidence and 60s, got %v", selected)
	}
}

func TestFailoverInvalidationIsPerNetworkAndPerTier(t *testing.T) {
	t.Run("other network untouched", func(t *testing.T) {
		aTCP := tierMemberTCP("a-tcp [T1]")
		aUDP := tierMemberUDP("a-udp [T1]")
		cTCP := tierMemberTCP("c-tcp [T2]")
		cUDP := tierMemberUDP("c-udp [T2]")
		grp := tierGroup(t, aTCP, aUDP, cTCP, cUDP)
		storeAllSplitHistories(t, grp)
		grp.performUpdateCheck()
		grp.tierTCP.gate.consecutiveOK[0] = 2
		grp.tierTCP.gate.lastFailure[0] = time.Now().Add(-61 * time.Second)
		udpGateBefore := copyGateState(grp.tierUDP.gate)

		grp.promoteFailover(cTCP, N.NetworkTCP)

		if got := grp.tierTCP.gate.consecutiveOK[0]; got != 0 {
			t.Fatalf("TCP demotion must invalidate the TCP tier qualification, got %d", got)
		}
		if !reflect.DeepEqual(udpGateBefore, copyGateState(grp.tierUDP.gate)) {
			t.Fatalf("TCP demotion must not touch the UDP gate: before=%v after=%v", udpGateBefore, grp.tierUDP.gate)
		}
		if grp.tierUDP.currentTier != 0 {
			t.Fatalf("TCP demotion must not move the UDP tier, got %d", grp.tierUDP.currentTier)
		}
	})

	t.Run("multi-tier demotion clears every higher tier", func(t *testing.T) {
		grp := tierGroup(t,
			tierMember("a [T1]"),
			tierMember("b [T2]"),
			tierMember("c [T3]"),
		)
		storeTierHistory(t, grp, "a [T1]", 100)
		storeTierHistory(t, grp, "b [T2]", 100)
		storeTierHistory(t, grp, "c [T3]", 100)
		grp.selectedOutboundTCP = grp.outbounds[0]
		grp.tierTCP.currentTier = 0
		grp.tierTCP.gate.consecutiveOK[0] = tierSwitchBackMinChecks
		grp.tierTCP.gate.consecutiveOK[1] = tierSwitchBackMinChecks
		grp.tierTCP.gate.lastFailure[0] = time.Now().Add(-61 * time.Second)
		grp.tierTCP.gate.lastFailure[1] = time.Now().Add(-61 * time.Second)

		grp.promoteFailover(grp.outbounds[2], N.NetworkTCP)
		if grp.tierTCP.currentTier != 2 {
			t.Fatalf("expected currentTier 2, got %d", grp.tierTCP.currentTier)
		}
		if grp.tierTCP.gate.consecutiveOK[0] != 0 || grp.tierTCP.gate.consecutiveOK[1] != 0 {
			t.Fatalf("both higher tiers must lose their qualification, got %v", grp.tierTCP.gate.consecutiveOK)
		}
		// 只有 T2 新成功：仍不得越过 T1 回切（T1 资格也是作废的）。
		for i := 0; i < tierSwitchBackMinChecks; i++ {
			grp.performRoundEndCheck(newObservedRound(observedOK("b [T2]"), observedOK("c [T3]")))
		}
		grp.updateAccess.RLock()
		effective, _ := grp.effectiveTierLocked(N.NetworkTCP)
		grp.updateAccess.RUnlock()
		if effective != 2 {
			t.Fatalf("mid-tier successes must not switch back past the invalidated top tier, got tier %d", effective)
		}
	})
}

// 降级前就开跑的轮次，降级后才收尾：它携带的成功观测不得重新授予资格。
func TestLateRoundCannotRegrantInvalidatedQualification(t *testing.T) {
	grp := tierGroup(t, tierMember("a [T1]"), tierMember("b [T2]"))
	storeTierHistory(t, grp, "a [T1]", 100)
	storeTierHistory(t, grp, "b [T2]", 90)
	grp.selectedOutboundTCP = grp.outbounds[0]
	grp.tierTCP.currentTier = 0
	grp.tierTCP.gate.consecutiveOK[0] = tierSwitchBackMinChecks - 1
	grp.tierTCP.gate.lastFailure[0] = time.Now().Add(-61 * time.Second)

	// 轮次开始时快照代次（与 urlTestOutboundsWithGroup 一致）。
	round := newURLTestRound()
	grp.updateAccess.RLock()
	round.bindGateEpoch(grp.tierTCP.gate.epoch, grp.tierUDP.gate.epoch)
	grp.updateAccess.RUnlock()
	round.record("a [T1]", N.NetworkTCP, urlTestSuccess)
	round.record("b [T2]", N.NetworkTCP, urlTestSuccess)

	// 轮次还在跑，数据面发生降级：旧资格作废。
	grp.promoteFailover(grp.outbounds[1], N.NetworkTCP)
	grp.performRoundEndCheck(round)

	if got := grp.tierTCP.gate.consecutiveOK[0]; got != 0 {
		t.Fatalf("a round started before the demotion must not regrant the T1 qualification, got %d", got)
	}
	grp.updateAccess.RLock()
	effective, _ := grp.effectiveTierLocked(N.NetworkTCP)
	grp.updateAccess.RUnlock()
	if effective != 1 {
		t.Fatalf("late success must not switch back, got tier %d", effective)
	}
}

// ---- P2：组关闭后不再发起备用拨号 ----

// enteredOutbound 阻塞在拨号上并给出进入信号，用于验证关闭能打断在途尝试。
type enteredOutbound struct {
	outbound.Adapter
	entered chan struct{}
	once    sync.Once
}

func newEnteredOutbound(tag string) *enteredOutbound {
	return &enteredOutbound{Adapter: outbound.NewAdapter("loopback", tag, []string{"tcp", "udp"}, nil), entered: make(chan struct{})}
}

func (e *enteredOutbound) DialContext(ctx context.Context, network string, dst M.Socksaddr) (net.Conn, error) {
	e.once.Do(func() { close(e.entered) })
	<-ctx.Done()
	return nil, ctx.Err()
}

func (e *enteredOutbound) ListenPacket(ctx context.Context, dst M.Socksaddr) (net.PacketConn, error) {
	e.once.Do(func() { close(e.entered) })
	<-ctx.Done()
	return nil, ctx.Err()
}

// lateConnOutbound 故意忽略 ctx、迟到返回一条连接：模拟实现不守 ctx 的畸形行为，
// 用来证明迟到连接会被关闭释放而不是交给已关闭的组。
type lateConnOutbound struct {
	outbound.Adapter
	delay  time.Duration
	closed atomic.Int32
}

func (l *lateConnOutbound) DialContext(ctx context.Context, network string, dst M.Socksaddr) (net.Conn, error) {
	time.Sleep(l.delay)
	return &trackedConn{onClose: func() { l.closed.Add(1) }}, nil
}

func (l *lateConnOutbound) ListenPacket(ctx context.Context, dst M.Socksaddr) (net.PacketConn, error) {
	time.Sleep(l.delay)
	conn, err := (&net.ListenConfig{}).ListenPacket(ctx, "udp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	return &trackedPacketConn{PacketConn: conn, onClose: func() { l.closed.Add(1) }}, nil
}

type trackedConn struct {
	once    sync.Once
	onClose func()
}

func (c *trackedConn) Close() error {
	c.once.Do(func() {
		if c.onClose != nil {
			c.onClose()
		}
	})
	return nil
}

func (*trackedConn) Read(b []byte) (int, error)  { return 0, errors.New("trackedConn: no data") }
func (*trackedConn) Write(b []byte) (int, error) { return len(b), nil }
func (*trackedConn) LocalAddr() net.Addr         { return &net.TCPAddr{IP: net.ParseIP("127.0.0.1")} }
func (*trackedConn) RemoteAddr() net.Addr {
	return &net.TCPAddr{IP: net.ParseIP("127.0.0.1"), Port: 443}
}
func (*trackedConn) SetDeadline(t time.Time) error      { return nil }
func (*trackedConn) SetReadDeadline(t time.Time) error  { return nil }
func (*trackedConn) SetWriteDeadline(t time.Time) error { return nil }

type trackedPacketConn struct {
	net.PacketConn
	once    sync.Once
	onClose func()
}

func (c *trackedPacketConn) Close() error {
	c.once.Do(func() {
		if c.onClose != nil {
			c.onClose()
		}
	})
	return c.PacketConn.Close()
}

// 主选阻塞时关闭组：在途尝试被取消，备用候选一个也不得发起。
func TestGroupCloseCancelsInFlightDialAndSkipsAlternates(t *testing.T) {
	for _, network := range []string{N.NetworkTCP, N.NetworkUDP} {
		t.Run(network, func(t *testing.T) {
			primary := newEnteredOutbound("a [T1]")
			lower := &stubDialOutbound{Adapter: outbound.NewAdapter("loopback", "b [T2]", []string{"tcp", "udp"}, nil)}
			grp := tierGroup(t, primary, lower)
			storeTierHistory(t, grp, primary.Tag(), 100)
			storeTierHistory(t, grp, lower.Tag(), 90)
			grp.selectedOutboundTCP = primary
			grp.selectedOutboundUDP = primary
			close(grp.startupDone)
			u := &URLTest{group: grp, logger: log.NewNOPFactory().Logger()}

			result := make(chan error, 1)
			go func() {
				if network == N.NetworkTCP {
					conn, err := u.DialContext(context.Background(), network, M.ParseSocksaddrHostPort("example.com", 443))
					if conn != nil {
						_ = conn.Close()
					}
					result <- err
					return
				}
				conn, err := u.ListenPacket(context.Background(), M.ParseSocksaddrHostPort("example.com", 443))
				if conn != nil {
					_ = conn.Close()
				}
				result <- err
			}()
			select {
			case <-primary.entered:
			case <-time.After(2 * time.Second):
				t.Fatal("primary dial did not start")
			}
			start := time.Now()
			if err := grp.Close(); err != nil {
				t.Fatalf("close: %v", err)
			}
			select {
			case err := <-result:
				if err == nil {
					t.Fatal("a closed group must not hand out a connection")
				}
			case <-time.After(2 * time.Second):
				t.Fatal("closing the group did not cancel the in-flight attempt")
			}
			if elapsed := time.Since(start); elapsed > time.Second {
				t.Fatalf("group close must cancel promptly, took %s", elapsed)
			}
			if lower.calls.Load() != 0 {
				t.Fatalf("no backup dial may start after the group closed, got %d", lower.calls.Load())
			}
		})
	}
}

// 关闭与成功并发：迟到的连接必须被关闭释放，调用方拿到错误而不是可用连接。
func TestLateConnectionAfterGroupCloseIsReleased(t *testing.T) {
	for _, network := range []string{N.NetworkTCP, N.NetworkUDP} {
		t.Run(network, func(t *testing.T) {
			late := &lateConnOutbound{Adapter: outbound.NewAdapter("loopback", "a [T1]", []string{"tcp", "udp"}, nil), delay: 150 * time.Millisecond}
			grp := tierGroup(t, late)
			storeTierHistory(t, grp, "a [T1]", 100)
			grp.selectedOutboundTCP = late
			grp.selectedOutboundUDP = late
			close(grp.startupDone)
			u := &URLTest{group: grp, logger: log.NewNOPFactory().Logger()}

			done := make(chan error, 1)
			go func() {
				if network == N.NetworkTCP {
					conn, err := u.DialContext(context.Background(), network, M.ParseSocksaddrHostPort("example.com", 443))
					if conn != nil {
						_ = conn.Close()
					}
					done <- err
					return
				}
				conn, err := u.ListenPacket(context.Background(), M.ParseSocksaddrHostPort("example.com", 443))
				if conn != nil {
					_ = conn.Close()
				}
				done <- err
			}()
			time.Sleep(30 * time.Millisecond)
			if err := grp.Close(); err != nil {
				t.Fatalf("close: %v", err)
			}
			select {
			case err := <-done:
				if err == nil {
					t.Fatal("a late connection must not be handed to a closed group")
				}
			case <-time.After(3 * time.Second):
				t.Fatal("DialContext did not return after the late dial finished")
			}
			// 等待迟到连接返回并被包装层关闭。
			deadline := time.Now().Add(2 * time.Second)
			for late.closed.Load() == 0 && time.Now().Before(deadline) {
				time.Sleep(10 * time.Millisecond)
			}
			if late.closed.Load() == 0 {
				t.Fatal("the late connection was leaked instead of closed")
			}
		})
	}
}

// 未关闭时仍能正常向下容灾：关闭逻辑不得误伤正常路径。
func TestOpenGroupStillFailsDownToLowerTier(t *testing.T) {
	primary := &deadBothOutbound{Adapter: outbound.NewAdapter("loopback", "a [T1]", []string{"tcp", "udp"}, nil)}
	lower := &stubDialOutbound{Adapter: outbound.NewAdapter("loopback", "b [T2]", []string{"tcp", "udp"}, nil)}
	grp := tierGroup(t, primary, lower)
	storeTierHistory(t, grp, primary.Tag(), 100)
	storeTierHistory(t, grp, lower.Tag(), 90)
	grp.selectedOutboundTCP = primary
	grp.selectedOutboundUDP = primary
	close(grp.startupDone)
	u := &URLTest{group: grp, logger: log.NewNOPFactory().Logger()}
	for _, network := range []string{N.NetworkTCP, N.NetworkUDP} {
		if network == N.NetworkTCP {
			conn, err := u.DialContext(context.Background(), network, M.ParseSocksaddrHostPort("example.com", 443))
			if err != nil {
				t.Fatalf("%s: expected failover, got %v", network, err)
			}
			_ = conn.Close()
			continue
		}
		conn, err := u.ListenPacket(context.Background(), M.ParseSocksaddrHostPort("example.com", 443))
		if err != nil {
			t.Fatalf("%s: expected failover, got %v", network, err)
		}
		_ = conn.Close()
	}
	if lower.calls.Load() < 2 {
		t.Fatalf("expected backup dials while the group is open, got %d", lower.calls.Load())
	}
}

// ---- 辅助构造 ----

// observedSkipCancelUDP 表示同一轮 UDP 被取消（中性观测）。
func observedSkipCancelUDP(tag string) testObservation {
	return testObservation{tag, N.NetworkUDP, urlTestCanceled}
}

// deadBothOutbound 的 TCP 与 UDP 都立即失败，用于验证正常组仍会向下容灾。
type deadBothOutbound struct {
	outbound.Adapter
}

func (d *deadBothOutbound) DialContext(ctx context.Context, network string, dst M.Socksaddr) (net.Conn, error) {
	return nil, errors.New("fixture unreachable")
}

func (d *deadBothOutbound) ListenPacket(ctx context.Context, dst M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("fixture unreachable")
}

// udpBrokenOutbound 是 TCP 可达、UDP 关联直接报错的成员。
type udpBrokenOutbound struct {
	outbound.Adapter
	server *httptest.Server
}

func (u *udpBrokenOutbound) DialContext(ctx context.Context, network string, dst M.Socksaddr) (net.Conn, error) {
	return (&net.Dialer{}).DialContext(ctx, "tcp", u.server.Listener.Addr().String())
}

func (u *udpBrokenOutbound) ListenPacket(ctx context.Context, dst M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("udp_associate_unavailable")
}
