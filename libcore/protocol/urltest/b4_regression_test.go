// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package urltest

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// delayedDialOutbound 模拟建连前固定延迟的成员：先等 delay（上下文可打断），
// 再拨向自己的 httptest 服务器，与 link 无关。用于 B4 流式就绪测试中
// 制造“快成员已成功、慢成员还在跑”的时间窗口。
type delayedDialOutbound struct {
	outbound.Adapter
	server *httptest.Server
	delay  time.Duration
}

func (d *delayedDialOutbound) DialContext(ctx context.Context, network string, dst M.Socksaddr) (net.Conn, error) {
	select {
	case <-time.After(d.delay):
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	return (&net.Dialer{}).DialContext(ctx, network, d.server.Listener.Addr().String())
}

func (d *delayedDialOutbound) ListenPacket(ctx context.Context, dst M.Socksaddr) (net.PacketConn, error) {
	return (&net.ListenConfig{}).ListenPacket(ctx, "udp", "127.0.0.1:0")
}

// TestSelectIgnoresExpiredHistory 是 B4/O1 的种子过期测试：缓存延迟超过
// 3*interval 即视为无种子——过期低延迟不得压过新鲜高延迟；只剩过期
// 历史时 Select() 回落到列表首位（B1 预排序的顺序），healthy=false；
// 选择过程 MUST NOT 改写 history.Time（种子只读）。
func TestSelectIgnoresExpiredHistory(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	fast := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "fast", []string{"tcp", "udp"}, nil)}
	slow := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "slow", []string{"tcp", "udp"}, nil)}
	grp := startupGroupWithMembers(t, context.Background(), server.URL, fast, slow)
	// interval 1 分钟，新鲜窗口 3 分钟：4 分钟前为过期，当前为新鲜。
	expiredTime := time.Now().Add(-4 * time.Minute)
	freshTime := time.Now()
	grp.history.StoreURLTestHistory("fast", &adapter.URLTestHistory{Time: expiredTime, Delay: 10})
	grp.history.StoreURLTestHistory("slow", &adapter.URLTestHistory{Time: freshTime, Delay: 500})

	selected, healthy := grp.Select(N.NetworkTCP)
	if selected != slow || !healthy {
		t.Fatalf("expected expired 10ms seed to be ignored and fresh 500ms member selected, got %v healthy=%v", selected, healthy)
	}
	if h := grp.history.LoadURLTestHistory("fast"); h == nil || !h.Time.Equal(expiredTime) || h.Delay != 10 {
		t.Fatalf("expected Select() to leave expired history untouched, got %+v", h)
	}
	if h := grp.history.LoadURLTestHistory("slow"); h == nil || !h.Time.Equal(freshTime) || h.Delay != 500 {
		t.Fatalf("expected Select() to leave fresh history untouched, got %+v", h)
	}

	// 只剩过期历史：视为无种子，回落到列表首位，healthy=false。
	grp.history.StoreURLTestHistory("slow", &adapter.URLTestHistory{Time: expiredTime, Delay: 500})
	selected, healthy = grp.Select(N.NetworkTCP)
	if selected != fast || healthy {
		t.Fatalf("expected all-expired history to fall back to first outbound unhealthy, got %v healthy=%v", selected, healthy)
	}
}

// TestSeedSkipsStartupProbeWait 是 B4/O1 的流式就绪建连测试：首轮探针
// 进行中、已有成员成功（流式就绪写入新鲜历史并更新选择）时，首连
// MUST NOT 再等整轮跑完；仍无任何新鲜选择时则必须等（既有测试
// TestStartupProbeIsAsyncAndFirstConnectionWaits 覆盖后者）。
func TestSeedSkipsStartupProbeWait(t *testing.T) {
	fastServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer fastServer.Close()
	blockedServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer blockedServer.Close()

	live := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "live", []string{"tcp", "udp"}, nil)}
	// 慢成员建连前睡 30 秒（会被单成员 3 秒探针超时掐断），首轮整轮
	// 约 3 秒才结束；快成员立即成功。
	blocked := &delayedDialOutbound{Adapter: outbound.NewAdapter("loopback", "blocked", []string{"tcp", "udp"}, nil), server: blockedServer, delay: 30 * time.Second}
	grp := startupGroupWithMembers(t, context.Background(), fastServer.URL, blocked, live)
	grp.PostStart()

	// 等快成员流式就绪（新鲜历史已写入），再拨号。
	deadline := time.Now().Add(5 * time.Second)
	for grp.history.LoadURLTestHistory("live") == nil {
		if time.Now().After(deadline) {
			t.Fatal("timed out waiting for fast member to become stream-ready")
		}
		time.Sleep(20 * time.Millisecond)
	}

	u := &URLTest{group: grp, logger: log.NewNOPFactory().Logger()}
	dest := M.ParseSocksaddrHostPort("127.0.0.1", uint16(fastServer.Listener.Addr().(*net.TCPAddr).Port))
	parentCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	start := time.Now()
	conn, err := u.DialContext(parentCtx, N.NetworkTCP, dest)
	elapsed := time.Since(start)
	if err != nil {
		t.Fatalf("expected stream-ready dial to succeed, got %v", err)
	}
	_ = conn.Close()
	// 整轮约 3 秒才结束：2 秒内返回即证明跳过了等待。
	if elapsed >= 2*time.Second {
		t.Fatalf("expected dial to skip the running first probe round, elapsed=%s", elapsed)
	}
	select {
	case <-grp.startupDone:
		t.Fatal("expected first probe round to still be running when the dial returned")
	default:
	}
}

// TestStreamingReadinessUpdatesSelectionMidRound 是 B4/O1 的 4.4 测试：
// 批量探针中快成员成功后，选择 MUST 在慢成员跑完前就更新为快成员
// （流式就绪），且流式更新 MUST NOT 触碰 lastActive（不重置探测间隔时钟）。
func TestStreamingReadinessUpdatesSelectionMidRound(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	slow := &delayedDialOutbound{Adapter: outbound.NewAdapter("loopback", "slow", []string{"tcp", "udp"}, nil), server: server, delay: 1500 * time.Millisecond}
	fast := &delayedDialOutbound{Adapter: outbound.NewAdapter("loopback", "fast", []string{"tcp", "udp"}, nil), server: server, delay: 0}
	grp := startupGroupWithMembers(t, context.Background(), server.URL, slow, fast)
	grp.interval = 5 * time.Minute
	grp.lastActive.Store(time.Now())
	beforeActive := grp.lastActive.Load()

	done := make(chan map[string]uint16, 1)
	go func() {
		done <- URLTestOutboundsWithGroup(context.Background(), nil, grp.history, grp.logger, grp.outbounds, grp.link, grp.interval, true, grp)
	}()

	// 快成员约毫秒级完成，慢成员还在睡 1.5 秒：此时选择必须已是快成员。
	time.Sleep(500 * time.Millisecond)
	if selected := grp.selectedFresh(N.NetworkTCP); selected != fast {
		t.Fatalf("expected streaming readiness to select fast member mid-round, got %v", selected)
	}
	if h := grp.history.LoadURLTestHistory("fast"); h == nil || h.Delay == 0 {
		t.Fatalf("expected fast member history to be stored mid-round, got %+v", h)
	}
	if h := grp.history.LoadURLTestHistory("slow"); h != nil {
		t.Fatalf("expected slow member history to be absent mid-round, got %+v", h)
	}
	if !grp.lastActive.Load().Equal(beforeActive) {
		t.Fatalf("expected streaming readiness to leave lastActive untouched, before=%v after=%v", beforeActive, grp.lastActive.Load())
	}

	// 等整轮跑完：选择仍是快成员（慢成员 1500ms 敌不过容差），两份历史都在。
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("timed out waiting for the probe round to finish")
	}
	if selected := grp.selectedFresh(N.NetworkTCP); selected != fast {
		t.Fatalf("expected fast member to stay selected after the round, got %v", selected)
	}
	if h := grp.history.LoadURLTestHistory("slow"); h == nil || h.Delay == 0 {
		t.Fatalf("expected slow member history to be stored after the round, got %+v", h)
	}
}

// TestListenPacketBlackholeTimeoutFailover 是 B1 TCP 黑洞测试的 UDP 对应物
// （B4 4.5 核查）：主选黑洞时单步 3 秒超时掐断当次尝试并转移到备用候选，
// 两次超时后故障史被清理。
func TestListenPacketBlackholeTimeoutFailover(t *testing.T) {
	blackhole := &blackholeOutbound{Adapter: outbound.NewAdapter("loopback", "blackhole-primary", []string{"tcp", "udp"}, nil)}
	backup := &stubDialOutbound{Adapter: outbound.NewAdapter("loopback", "healthy-backup", []string{"tcp", "udp"}, nil)}
	grp := startupGroupWithMembers(t, context.Background(), "http://127.0.0.1/generate_204", blackhole, backup)
	grp.interval = 3 * time.Minute
	// 备用延迟高于惩罚后的主选（40+300），保证两次都仍以黑洞为主选。
	grp.history.StoreURLTestHistory("blackhole-primary", &adapter.URLTestHistory{Time: time.Now(), Delay: 40})
	grp.history.StoreURLTestHistory("healthy-backup", &adapter.URLTestHistory{Time: time.Now(), Delay: 5000})
	grp.selectedOutboundUDP = blackhole
	close(grp.startupDone)

	u := &URLTest{group: grp, logger: log.NewNOPFactory().Logger()}
	dest := M.ParseSocksaddrHostPort("example.com", 443)
	parentCtx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	start := time.Now()
	conn1, err := u.ListenPacket(parentCtx, dest)
	elapsed := time.Since(start)
	if err != nil {
		t.Fatalf("expected first blackhole failover to succeed via backup, got %v", err)
	}
	_ = conn1.Close()
	if parentCtx.Err() != nil {
		t.Fatalf("expected caller parentCtx to remain valid after candidate timeout, got %v", parentCtx.Err())
	}
	if elapsed < urlTestDialAttemptTimeout || elapsed > 10*time.Second {
		t.Fatalf("expected single-step timeout (~3s) to cut the UDP attempt, elapsed=%s", elapsed)
	}
	if blackhole.timeouts.Load() != 1 || backup.calls.Load() != 1 {
		t.Fatalf("expected 1 primary attempt timeout and 1 backup call, got primary=%d backup=%d", blackhole.timeouts.Load(), backup.calls.Load())
	}
	if h := grp.history.LoadURLTestHistory("blackhole-primary"); h == nil || h.Delay != 340 {
		t.Fatalf("expected first timeout to penalize only (delay 340), got %+v", h)
	}
	// P2-7：UDP 故障转移成功后同样提升当前选择。
	if grp.selectedOutboundUDP != backup {
		t.Fatalf("expected UDP failover winner to become current selection, got %v", grp.selectedOutboundUDP)
	}
	grp.performUpdateCheck()
	if grp.selectedOutboundUDP != blackhole {
		t.Fatalf("expected round-end selection to reselect penalized primary, got %v", grp.selectedOutboundUDP)
	}

	conn2, err := u.ListenPacket(parentCtx, dest)
	if err != nil {
		t.Fatalf("expected second blackhole failover to succeed via backup, got %v", err)
	}
	_ = conn2.Close()
	if blackhole.timeouts.Load() != 2 || backup.calls.Load() != 2 {
		t.Fatalf("expected 2 primary attempt timeouts and 2 backup calls, got primary=%d backup=%d", blackhole.timeouts.Load(), backup.calls.Load())
	}
	if h := grp.history.LoadURLTestHistory("blackhole-primary"); h != nil {
		t.Fatalf("expected blackhole history to be evicted after 2 consecutive timeouts, got %+v", h)
	}
}

// closeSpy 记录 Close() 是否被调用，用于断言故障转移不中断现有连接。
type closeSpy struct {
	closed atomic.Bool
}

func (c *closeSpy) Close() error {
	c.closed.Store(true)
	return nil
}

// TestDialFailoverKeepsExistingConnections 是 B4/O1 的约束测试：单步超时
// 故障转移 MUST NOT 触发 interruptGroup.Interrupt，挂在组上的既有连接
// 不得被断开（设计约束“切换不中断现有连接”）。
func TestDialFailoverKeepsExistingConnections(t *testing.T) {
	blackhole := &blackholeOutbound{Adapter: outbound.NewAdapter("loopback", "blackhole-primary", []string{"tcp", "udp"}, nil)}
	backup := &stubDialOutbound{Adapter: outbound.NewAdapter("loopback", "healthy-backup", []string{"tcp", "udp"}, nil)}
	grp := startupGroupWithMembers(t, context.Background(), "http://127.0.0.1/generate_204", blackhole, backup)
	grp.interval = 3 * time.Minute
	grp.history.StoreURLTestHistory("blackhole-primary", &adapter.URLTestHistory{Time: time.Now(), Delay: 40})
	grp.history.StoreURLTestHistory("healthy-backup", &adapter.URLTestHistory{Time: time.Now(), Delay: 5000})
	grp.selectedOutboundTCP = blackhole
	// lastActive 新鲜，避免 AttachConnection/DialContext 经 Touch() 触发
	// 立即补探；本测试不跑启动探针。
	grp.lastActive.Store(time.Now())
	close(grp.startupDone)

	u := &URLTest{group: grp, logger: log.NewNOPFactory().Logger()}
	spy := &closeSpy{}
	detach := u.AttachConnection(spy)
	defer detach()
	if spy.closed.Load() {
		t.Fatal("expected attached connection to start unclosed")
	}

	dest := M.ParseSocksaddrHostPort("example.com", 443)
	parentCtx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	conn, err := u.DialContext(parentCtx, N.NetworkTCP, dest)
	if err != nil {
		t.Fatalf("expected blackhole failover to succeed via backup, got %v", err)
	}
	_ = conn.Close()
	if spy.closed.Load() {
		t.Fatal("expected failover to keep existing attached connections alive")
	}
	if blackhole.timeouts.Load() != 1 || backup.calls.Load() != 1 {
		t.Fatalf("expected 1 primary attempt timeout and 1 backup call, got primary=%d backup=%d", blackhole.timeouts.Load(), backup.calls.Load())
	}
}
