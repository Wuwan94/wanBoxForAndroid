// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package urltest

import (
	"context"
	"errors"
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

// TestPenalizeURLTestDelaySaturates 是 B1/O3 的单元测试：惩罚延迟用 uint32 求和
// 后饱和封顶 65000，MUST NOT 发生 uint16 回绕。
func TestPenalizeURLTestDelaySaturates(t *testing.T) {
	if got := penalizeURLTestDelay(65000, urlTestProbePenalty); got != urlTestMaxPenalizedDelay {
		t.Fatalf("expected 65000 + %d to clamp at %d, got %d", urlTestProbePenalty, urlTestMaxPenalizedDelay, got)
	}
	if got := penalizeURLTestDelay(64500, 1000); got != urlTestMaxPenalizedDelay {
		t.Fatalf("expected 64500 + 1000 to clamp at %d, got %d", urlTestMaxPenalizedDelay, got)
	}
	if got := penalizeURLTestDelay(100, urlTestProbePenalty); got != 400 {
		t.Fatalf("expected 100 + %d = 400, got %d", urlTestProbePenalty, got)
	}
	if got := penalizeURLTestDelay(0, urlTestProbePenalty); got != urlTestProbePenalty {
		t.Fatalf("expected 0 + %d = %d, got %d", urlTestProbePenalty, urlTestProbePenalty, got)
	}
}

// TestProbeFailurePenalizeAndEvict 是 B1/O3 的批量探测测试：首次失败只记惩罚
// （延迟封顶、Time 按既有策略续期），连续第二次失败清理故障史；恢复后成功复位计数。
func TestProbeFailurePenalizeAndEvict(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	dead := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "dead", []string{"tcp", "udp"}, nil), dead: true}
	live := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "live", []string{"tcp", "udp"}, nil)}
	grp := startupGroupWithMembers(t, context.Background(), server.URL, dead, live)
	grp.interval = 5 * time.Minute

	seedTime := time.Now().Add(-30 * time.Second)
	grp.history.StoreURLTestHistory("dead", &adapter.URLTestHistory{Time: seedTime, Delay: 65000})
	grp.history.StoreURLTestHistory("live", &adapter.URLTestHistory{Time: seedTime, Delay: 60})

	probe := func() {
		URLTestOutboundsWithGroup(context.Background(), nil, grp.history, grp.logger, grp.outbounds, grp.link, grp.interval, true, grp)
	}

	// 第一次失败：延迟仍封顶在 65000（不回绕），Time 按既有策略续期为当前时间
	//（既有测试 TestMemberTimeoutStillPenalizesRecentHistory 要求续期）。
	probe()
	h1 := grp.history.LoadURLTestHistory("dead")
	if h1 == nil {
		t.Fatal("expected single transient failure to keep one penalized history entry")
	}
	if h1.Delay != 65000 {
		t.Fatalf("expected penalized delay to stay 65000, got %d", h1.Delay)
	}
	if !h1.Time.After(seedTime) {
		t.Fatalf("expected failure to refresh Time (got %v, seed %v)", h1.Time, seedTime)
	}

	// 第二次连续失败：故障史被清理，死节点不再被当作“近期可用”选回。
	probe()
	if h2 := grp.history.LoadURLTestHistory("dead"); h2 != nil {
		t.Fatalf("expected dead node history to be deleted on second failure, got %+v", h2)
	}
	if okHist := grp.history.LoadURLTestHistory("live"); okHist == nil || okHist.Delay == 0 || okHist.Delay >= 1000 {
		t.Fatalf("expected healthy live history to be updated, got %+v", okHist)
	}

	// 恢复后探测成功：历史重建，连续失败计数复位。
	dead.dead = false
	probe()
	h3 := grp.history.LoadURLTestHistory("dead")
	if h3 == nil || h3.Delay == 0 || h3.Delay >= 1000 {
		t.Fatalf("expected recovered node history to be restored, got %+v", h3)
	}

	// 复位已生效：再次失败只记惩罚，不直接清史。
	// Windows 的 time.Now 粒度可能让相邻两次写入落到同一时间戳；留出极短间隔，
	// 保持“失败续期 Time”这个断言的跨平台稳定性。
	time.Sleep(2 * time.Millisecond)
	dead.dead = true
	probe()
	h4 := grp.history.LoadURLTestHistory("dead")
	if h4 == nil {
		t.Fatal("expected failure after a success to penalize only, not evict")
	}
	if !h4.Time.After(h3.Time) {
		t.Fatalf("expected failure to refresh Time (got %v, previous %v)", h4.Time, h3.Time)
	}
}

// blackholeOutbound 模拟建连黑洞：一直等到上下文结束才返回。
type blackholeOutbound struct {
	outbound.Adapter
	timeouts atomic.Int32
}

func (b *blackholeOutbound) DialContext(ctx context.Context, network string, dst M.Socksaddr) (net.Conn, error) {
	<-ctx.Done()
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		b.timeouts.Add(1)
	}
	return nil, ctx.Err()
}

func (b *blackholeOutbound) ListenPacket(ctx context.Context, dst M.Socksaddr) (net.PacketConn, error) {
	<-ctx.Done()
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		b.timeouts.Add(1)
	}
	return nil, ctx.Err()
}

// stubDialOutbound 模拟可立即建连的健康候选。
type stubDialOutbound struct {
	outbound.Adapter
	calls atomic.Int32
}

func (s *stubDialOutbound) DialContext(ctx context.Context, network string, dst M.Socksaddr) (net.Conn, error) {
	s.calls.Add(1)
	return &stubConn{}, nil
}

func (s *stubDialOutbound) ListenPacket(ctx context.Context, dst M.Socksaddr) (net.PacketConn, error) {
	s.calls.Add(1)
	return (&net.ListenConfig{}).ListenPacket(ctx, "udp", "127.0.0.1:0")
}

type stubConn struct{}

func (*stubConn) Read(b []byte) (int, error)         { return 0, errors.New("stubConn: no data") }
func (*stubConn) Write(b []byte) (int, error)        { return len(b), nil }
func (*stubConn) Close() error                       { return nil }
func (*stubConn) LocalAddr() net.Addr                { return &net.TCPAddr{IP: net.ParseIP("127.0.0.1")} }
func (*stubConn) RemoteAddr() net.Addr               { return &net.TCPAddr{IP: net.ParseIP("127.0.0.1"), Port: 443} }
func (*stubConn) SetDeadline(t time.Time) error      { return nil }
func (*stubConn) SetReadDeadline(t time.Time) error  { return nil }
func (*stubConn) SetWriteDeadline(t time.Time) error { return nil }

// TestDialBlackholeTimeoutFailover 是 B1/O3 的建连测试：主选黑洞时单步 3 秒超时
// 掐断当次尝试并转移到备用候选，父上下文保持有效；两次超时后故障史被清理。
func TestDialBlackholeTimeoutFailover(t *testing.T) {
	blackhole := &blackholeOutbound{Adapter: outbound.NewAdapter("loopback", "blackhole-primary", []string{"tcp", "udp"}, nil)}
	backup := &stubDialOutbound{Adapter: outbound.NewAdapter("loopback", "healthy-backup", []string{"tcp", "udp"}, nil)}
	grp := startupGroupWithMembers(t, context.Background(), "http://127.0.0.1/generate_204", blackhole, backup)
	grp.interval = 3 * time.Minute
	// 备用延迟高于惩罚后的主选（40+300），保证两次拨号都仍以黑洞为主选。
	grp.history.StoreURLTestHistory("blackhole-primary", &adapter.URLTestHistory{Time: time.Now(), Delay: 40})
	grp.history.StoreURLTestHistory("healthy-backup", &adapter.URLTestHistory{Time: time.Now(), Delay: 5000})
	grp.selectedOutboundTCP = blackhole
	// 本测试不跑启动探针（连续失败计数须从 0 开始精确控制），直接关闭
	// startupDone；选择来自上面预置的 history。
	close(grp.startupDone)

	u := &URLTest{group: grp, logger: log.NewNOPFactory().Logger()}
	dest := M.ParseSocksaddrHostPort("example.com", 443)
	parentCtx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	start := time.Now()
	conn1, err := u.DialContext(parentCtx, N.NetworkTCP, dest)
	elapsed := time.Since(start)
	if err != nil {
		t.Fatalf("expected first blackhole failover to succeed via backup, got %v", err)
	}
	_ = conn1.Close()
	if parentCtx.Err() != nil {
		t.Fatalf("expected caller parentCtx to remain valid after candidate timeout, got %v", parentCtx.Err())
	}
	if elapsed < urlTestDialAttemptTimeout || elapsed > 10*time.Second {
		t.Fatalf("expected single-step timeout (~3s) to cut the attempt, elapsed=%s", elapsed)
	}
	if blackhole.timeouts.Load() != 1 || backup.calls.Load() != 1 {
		t.Fatalf("expected 1 primary attempt timeout and 1 backup call, got primary=%d backup=%d", blackhole.timeouts.Load(), backup.calls.Load())
	}
	if h := grp.history.LoadURLTestHistory("blackhole-primary"); h == nil || h.Delay != 340 {
		t.Fatalf("expected first timeout to penalize only (delay 340), got %+v", h)
	}
	// P2-7：故障转移成功后必须把成功者提升为当前选择。
	if grp.selectedOutboundTCP != backup {
		t.Fatalf("expected failover winner to become current selection, got %v", grp.selectedOutboundTCP)
	}
	// 下一轮择优仍会按惩罚后的延迟回到坏主选（340 < 5000），模拟轮次选择重算。
	grp.performUpdateCheck()
	if grp.selectedOutboundTCP != blackhole {
		t.Fatalf("expected round-end selection to reselect penalized primary, got %v", grp.selectedOutboundTCP)
	}

	// 第二次超时：连续失败计数到 2，故障史被清理。
	conn2, err := u.DialContext(parentCtx, N.NetworkTCP, dest)
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

// TestFailoverPrefersFreshCandidateAndPromotesSelection 复现 P2-7：故障转移后
// 旧实现仍把成功节点只当一次性备用，选择仍是黑洞主选，下一次连接还要再超时；
// 候选顺序也只看配置顺序，过期历史排在新鲜历史之前。修复后：有过期候选被
// 排除、新鲜候选按延迟升序尝试，成功者立即成为当前选择。
func TestFailoverPrefersFreshCandidateAndPromotesSelection(t *testing.T) {
	primary := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "primary-dead", []string{"tcp", "udp"}, nil), dead: true}
	stale := &stubDialOutbound{Adapter: outbound.NewAdapter("loopback", "stale-alt", []string{"tcp", "udp"}, nil)}
	fast := &stubDialOutbound{Adapter: outbound.NewAdapter("loopback", "fast-alt", []string{"tcp", "udp"}, nil)}
	slow := &stubDialOutbound{Adapter: outbound.NewAdapter("loopback", "slow-alt", []string{"tcp", "udp"}, nil)}
	grp := startupGroupWithMembers(t, context.Background(), "http://127.0.0.1/generate_204", primary, stale, fast, slow)
	grp.interval = 3 * time.Minute
	now := time.Now()
	grp.history.StoreURLTestHistory("primary-dead", &adapter.URLTestHistory{Time: now, Delay: 40})
	// stale-alt 在列表里排在前面，但历史已过期，不得作为转移候选。
	grp.history.StoreURLTestHistory("stale-alt", &adapter.URLTestHistory{Time: now.Add(-4 * grp.interval), Delay: 10})
	grp.history.StoreURLTestHistory("fast-alt", &adapter.URLTestHistory{Time: now, Delay: 300})
	grp.history.StoreURLTestHistory("slow-alt", &adapter.URLTestHistory{Time: now, Delay: 900})
	close(grp.startupDone)

	u := &URLTest{group: grp, logger: log.NewNOPFactory().Logger()}
	conn, err := u.DialContext(context.Background(), N.NetworkTCP, M.ParseSocksaddrHostPort("example.com", 443))
	if err != nil {
		t.Fatalf("expected failover to a fresh candidate, got %v", err)
	}
	_ = conn.Close()
	if stale.calls.Load() != 0 {
		t.Fatalf("stale candidate must be filtered out, got %d calls", stale.calls.Load())
	}
	if fast.calls.Load() != 1 {
		t.Fatalf("expected fresh fastest candidate to be tried once, got %d", fast.calls.Load())
	}
	if slow.calls.Load() != 0 {
		t.Fatalf("later fresh candidate must not be tried after success, got %d", slow.calls.Load())
	}
	if grp.selectedOutboundTCP != fast {
		t.Fatalf("expected failover winner promoted to current selection, got %v", grp.selectedOutboundTCP)
	}
	if tag := grp.observedTag.Load(); tag == nil || *tag != "fast-alt" {
		t.Fatalf("expected observed tag fast-alt, got %v", tag)
	}
}

// TestURLTestImplementsOutboundGroup 是 B1/O4 的防护断言：wanBox 无 autoselect，
// 不存在上游 8.15 的“解包绕过”同类缺陷；URLTest MUST 继续实现 OutboundGroup，
// 不得照抄上游对 AutoSelect 的移除。
func TestURLTestImplementsOutboundGroup(t *testing.T) {
	if _, ok := any(&URLTest{}).(adapter.OutboundGroup); !ok {
		t.Fatal("URLTest must continue to implement adapter.OutboundGroup (O4 guard)")
	}
}
