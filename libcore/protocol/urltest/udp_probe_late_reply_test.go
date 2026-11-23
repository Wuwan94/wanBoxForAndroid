// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package urltest

import (
	"context"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter/outbound"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// v3.0.18-preview.3 复审 P2（U2）的回归：截止时刻之后才被消费的回包，即使内容与
// 来源都完全合法，也不得记成成功。
//
// 既有用例覆盖了两端——静默端点（读超时、根本没有回包）与正常回显（早于截止返回）。
// 真正要钉住的是中间那种时序：数据报已经在路上，回包在 deadline 烧完之后才被读到
// （阻塞读没被叫醒，或回包刚好迟到）。这时 probe.go 的读后判定、outbound.go 的
// select 之后判定都必须把它降级为未知，UDP 回切门不得推进。
//
// 成员与连接都是替身：ReadFrom 一直等到上下文结束，然后照旧交出一条“合法回包”，
// 让判定点与“合法的迟到结果”精确对撞，不依赖真实代理服务端，也没有时钟竞态。
type udpLateReplyConn struct {
	ctx     context.Context
	payload []byte
	from    net.Addr
	writes  atomic.Int32
	closed  atomic.Bool
	once    sync.Once
}

func (c *udpLateReplyConn) WriteTo(p []byte, _ net.Addr) (int, error) {
	c.writes.Add(1)
	return len(p), nil
}

// ReadFrom 阻塞到上下文结束（截止/取消都算），之后仍然返回逐字节相同、来源正确
// 的回包：这正是“结果合法但已经过期”的那种回包。
func (c *udpLateReplyConn) ReadFrom(p []byte) (int, net.Addr, error) {
	<-c.ctx.Done()
	return copy(p, c.payload), c.from, nil
}

func (c *udpLateReplyConn) Close() error {
	c.once.Do(func() { c.closed.Store(true) })
	return nil
}

func (c *udpLateReplyConn) LocalAddr() net.Addr              { return &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)} }
func (c *udpLateReplyConn) SetDeadline(time.Time) error      { return nil }
func (c *udpLateReplyConn) SetReadDeadline(time.Time) error  { return nil }
func (c *udpLateReplyConn) SetWriteDeadline(time.Time) error { return nil }

// udpLateReplyOutbound 的 UDP 面永远只回一条迟到的合法回包；TCP 面复用
// startupOutbound（真 loopback），好让轮次能正常收尾、单独观察 UDP 观测。
type udpLateReplyOutbound struct {
	*startupOutbound
	mu    sync.Mutex
	conns []*udpLateReplyConn
}

func newUDPLateReplyOutbound(tag string) *udpLateReplyOutbound {
	return &udpLateReplyOutbound{startupOutbound: &startupOutbound{
		Adapter: outbound.NewAdapter("loopback", tag, []string{"tcp", "udp"}, nil),
	}}
}

func (o *udpLateReplyOutbound) ListenPacket(ctx context.Context, dst M.Socksaddr) (net.PacketConn, error) {
	// 来源就是配置目标本身：内容与来源两道校验都会通过，只剩时限这一道。
	conn := &udpLateReplyConn{ctx: ctx, payload: []byte(DefaultUDPProbePayload), from: dst.UDPAddr()}
	o.mu.Lock()
	o.conns = append(o.conns, conn)
	o.mu.Unlock()
	return conn, nil
}

func (o *udpLateReplyOutbound) lastConn() *udpLateReplyConn {
	o.mu.Lock()
	defer o.mu.Unlock()
	if len(o.conns) == 0 {
		return nil
	}
	return o.conns[len(o.conns)-1]
}

// 探测内核：迟到的合法回包是未知，不是成功。
func TestUDPProbeLateValidReplyIsNotVerified(t *testing.T) {
	target := M.ParseSocksaddrHostPort("127.0.0.1", 5353)
	payload := []byte(DefaultUDPProbePayload)
	member := newUDPLateReplyOutbound("late")
	started := time.Now()
	outcome := ProbeOutboundUDP(context.Background(), member, target, payload, 100*time.Millisecond)
	elapsed := time.Since(started)
	if outcome != UDPProbeUnverified {
		t.Fatalf("a valid reply consumed after the deadline must stay unknown, got %s", outcome)
	}
	if elapsed > time.Second {
		t.Fatalf("the probe must stay bounded by its own deadline, took %s", elapsed)
	}
	conn := member.lastConn()
	if conn == nil {
		t.Fatal("the probe never opened a packet connection")
	}
	if conn.writes.Load() != 1 {
		t.Fatalf("the probe must send exactly one datagram, got %d", conn.writes.Load())
	}
	if !conn.closed.Load() {
		t.Fatal("the packet connection must be released after the probe")
	}
}

// 调用方取消之后才到手的合法回包同样是未知，且这条结论优先于“内容正确”。
func TestUDPProbeReplyAfterCallerCancellationIsCanceled(t *testing.T) {
	target := M.ParseSocksaddrHostPort("127.0.0.1", 5353)
	payload := []byte(DefaultUDPProbePayload)
	member := newUDPLateReplyOutbound("late-cancel")
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan UDPProbeOutcome, 1)
	go func() {
		done <- ProbeOutboundUDP(ctx, member, target, payload, 5*time.Second)
	}()
	deadline := time.Now().Add(time.Second)
	for member.lastConn() == nil && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if member.lastConn() == nil {
		t.Fatal("the probe never opened a packet connection")
	}
	cancel()
	select {
	case outcome := <-done:
		if outcome == UDPProbeVerified {
			t.Fatal("a reply that only arrived after cancellation must never be verified")
		}
		if outcome != UDPProbeCanceled {
			t.Fatalf("a canceled round must be reported as canceled, got %s", outcome)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("cancellation did not wake the blocked probe")
	}
}

// 轮次集成：迟到的合法回包记未知，连续健康计数不涨、也不清零。
func TestUDPLateValidReplyNeverAdvancesTheGate(t *testing.T) {
	target := M.ParseSocksaddrHostPort("127.0.0.1", 5353)
	member := newUDPLateReplyOutbound("a [T1]")
	probe := &udpProbeConfig{target: target, payload: []byte(DefaultUDPProbePayload), timeout: 100 * time.Millisecond}
	grp := startupGroupWithMembersAndProbe(t, context.Background(), "http://example.invalid/204", probe, member)
	storeTierHistory(t, grp, "a [T1]", 100)
	grp.tierUDP.gate.consecutiveOK[0] = 2
	_, round, err := grp.urlTest(context.Background(), true)
	if err != nil {
		t.Fatalf("round: %v", err)
	}
	if got := round.snapshotNetwork(N.NetworkUDP)["a [T1]"]; got != urlTestUnverified {
		t.Fatalf("a late legal reply must be recorded as unverified, got %v", got)
	}
	if got := grp.tierUDP.gate.consecutiveOK[0]; got != 2 {
		t.Fatalf("a late legal reply must not advance the UDP gate, got %d", got)
	}
}
