// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package urltest

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter/outbound"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// 2026-11-20 第二轮复审 P1 的回归用例：UDP 观测的唯一证据是“探测数据报经成员
// 到达目标并带着原样回显返回”。关联建立、首次写入成功、打开后立即关闭都
// 不再被当成 UDP 健康，也不得推进 UDP 回切计数。
//
// 这里的所有端点都是本机可控的 UDP 服务：回显 / 丢弃 / 回错内容 / 慢回。
// 它们是“真实请求与真实响应”，但仍然是本机模拟端点，不等于真实代理服务端
// 通过——真机与服务端验证的缺口在报告里单独列出。

type udpEndpointMode int

const (
	udpEndpointEcho udpEndpointMode = iota
	// udpEndpointSilent 收到数据报就丢掉，永不回包（模拟服务端接受会话但不通）。
	udpEndpointSilent
	// udpEndpointGarbage 回一段与探测数据报不同的内容（模拟回包错误/串话）。
	udpEndpointGarbage
)

// udpTestEndpoint 是本机可控的 UDP 端点。
type udpTestEndpoint struct {
	conn    net.PacketConn
	addr    string
	mode    udpEndpointMode
	reads   atomic.Int32
	replies atomic.Int32
	closed  chan struct{}
	once    sync.Once
}

func newUDPTestEndpoint(t *testing.T, mode udpEndpointMode) *udpTestEndpoint {
	t.Helper()
	conn, err := (&net.ListenConfig{}).ListenPacket(context.Background(), "udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen udp: %v", err)
	}
	endpoint := &udpTestEndpoint{conn: conn, addr: conn.LocalAddr().String(), mode: mode, closed: make(chan struct{})}
	go endpoint.serve()
	t.Cleanup(endpoint.Close)
	return endpoint
}

func (e *udpTestEndpoint) serve() {
	buffer := make([]byte, 2048)
	for {
		n, from, err := e.conn.ReadFrom(buffer)
		if err != nil {
			return
		}
		e.reads.Add(1)
		switch e.mode {
		case udpEndpointEcho:
			_, _ = e.conn.WriteTo(buffer[:n], from)
			e.replies.Add(1)
		case udpEndpointGarbage:
			_, _ = e.conn.WriteTo([]byte("not-the-probe-datagram"), from)
			e.replies.Add(1)
		case udpEndpointSilent:
		}
	}
}

func (e *udpTestEndpoint) Close() {
	e.once.Do(func() {
		_ = e.conn.Close()
		close(e.closed)
	})
}

// target 返回探测目标（已是 IP:port，与配置校验一致）。
func (e *udpTestEndpoint) target() M.Socksaddr {
	return M.ParseSocksaddr(e.addr)
}

// probeConfig 是本组使用的 UDP 探测配置（与 URLTestUDPProbeOptions.build 等价）。
func (e *udpTestEndpoint) probeConfig(timeout time.Duration) *udpProbeConfig {
	return &udpProbeConfig{
		target:  e.target(),
		payload: []byte(DefaultUDPProbePayload),
		timeout: timeout,
	}
}

// ---- 探测内核：证据等级 ----

// 收到原样回显才 verified；回包内容不符是 rejected 而不是 unverified。
func TestUDPProbeVerifiedOnlyOnEchoedDatagram(t *testing.T) {
	echo := newUDPTestEndpoint(t, udpEndpointEcho)
	probe := &probeOutbound{}
	outcome := ProbeOutboundUDP(context.Background(), probe, echo.target(), []byte(DefaultUDPProbePayload), time.Second)
	if outcome != UDPProbeVerified {
		t.Fatalf("echo endpoint must verify the round trip, got %s", outcome)
	}
	if echo.reads.Load() == 0 {
		t.Fatal("probe did not send a datagram")
	}

	garbage := newUDPTestEndpoint(t, udpEndpointGarbage)
	outcome = ProbeOutboundUDP(context.Background(), probe, garbage.target(), []byte(DefaultUDPProbePayload), time.Second)
	if outcome != UDPProbeRejected {
		t.Fatalf("a reply that is not the probe datagram must be rejected, got %s", outcome)
	}
}

// 发出去了但没有有效响应（静默丢弃 / 超时）是 unknown：既不是成功也不是失败。
func TestUDPProbeWithoutValidReplyIsUnverified(t *testing.T) {
	silent := newUDPTestEndpoint(t, udpEndpointSilent)
	probe := &probeOutbound{}
	started := time.Now()
	outcome := ProbeOutboundUDP(context.Background(), probe, silent.target(), []byte(DefaultUDPProbePayload), 200*time.Millisecond)
	elapsed := time.Since(started)
	if outcome != UDPProbeUnverified {
		t.Fatalf("a silent target must yield unverified, got %s", outcome)
	}
	if silent.reads.Load() == 0 {
		t.Fatal("the probe datagram never reached the endpoint")
	}
	if elapsed > time.Second {
		t.Fatalf("the probe must be bounded by its timeout, took %s", elapsed)
	}
}

// 关联建立成功但首次发送失败：不能是成功，必须是明确失败。
func TestUDPProbeFirstWriteFailureIsRejected(t *testing.T) {
	member := &lazySessionOutbound{
		Adapter:   outbound.NewAdapter("loopback", "lazy", []string{"tcp", "udp"}, nil),
		writeFail: errors.New("session_handshake_rejected"),
	}
	outcome := ProbeOutboundUDP(context.Background(), member, M.ParseSocksaddrHostPort("127.0.0.1", 7), []byte(DefaultUDPProbePayload), time.Second)
	if outcome != UDPProbeRejected {
		t.Fatalf("a failed first write must be rejected, got %s", outcome)
	}
	if !member.connClosed.Load() {
		t.Fatal("the packet connection must be released after a failed probe")
	}
}

// 关联本身报错（成员明确不给 UDP）：失败。
func TestUDPProbeAssociationFailureIsRejected(t *testing.T) {
	member := &lazySessionOutbound{Adapter: outbound.NewAdapter("loopback", "noudp", []string{"tcp", "udp"}, nil), associationErr: errors.New("udp_associate_unavailable")}
	outcome := ProbeOutboundUDP(context.Background(), member, M.ParseSocksaddrHostPort("127.0.0.1", 7), []byte(DefaultUDPProbePayload), time.Second)
	if outcome != UDPProbeRejected {
		t.Fatalf("an association error must be rejected, got %s", outcome)
	}
}

// 没有目标 / nil 成员 / 空数据报：不做任何 UDP 操作，也不产生流量。
func TestUDPProbeSkippedWithoutTarget(t *testing.T) {
	member := &lazySessionOutbound{Adapter: outbound.NewAdapter("loopback", "skip", []string{"tcp", "udp"}, nil)}
	if outcome := ProbeOutboundUDP(context.Background(), member, M.Socksaddr{}, []byte(DefaultUDPProbePayload), time.Second); outcome != UDPProbeSkipped {
		t.Fatalf("no target must be skipped, got %s", outcome)
	}
	if outcome := ProbeOutboundUDP(context.Background(), nil, M.ParseSocksaddrHostPort("127.0.0.1", 7), []byte("x"), time.Second); outcome != UDPProbeSkipped {
		t.Fatalf("nil member must be skipped, got %s", outcome)
	}
	if outcome := ProbeOutboundUDP(context.Background(), member, M.ParseSocksaddrHostPort("127.0.0.1", 7), nil, time.Second); outcome != UDPProbeSkipped {
		t.Fatalf("empty payload must be skipped, got %s", outcome)
	}
	if member.listenCalls.Load() != 0 {
		t.Fatalf("skipped probes must not open any packet connection, got %d", member.listenCalls.Load())
	}
}

// 父上下文取消：canceled，并且阻塞在读取上的连接被关闭释放。
func TestUDPProbeParentCancellationReleasesConnection(t *testing.T) {
	silent := newUDPTestEndpoint(t, udpEndpointSilent)
	member := &lazySessionOutbound{Adapter: outbound.NewAdapter("loopback", "member", []string{"tcp", "udp"}, nil)}
	ctx, cancel := context.WithCancel(context.Background())
	result := make(chan UDPProbeOutcome, 1)
	go func() {
		result <- ProbeOutboundUDP(ctx, member, silent.target(), []byte(DefaultUDPProbePayload), 5*time.Second)
	}()
	deadline := time.Now().Add(time.Second)
	for !member.readStarted.Load() && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if !member.readStarted.Load() {
		t.Fatal("probe never reached the reply read")
	}
	cancel()
	select {
	case outcome := <-result:
		if outcome != UDPProbeCanceled {
			t.Fatalf("a canceled probe must be canceled, got %s", outcome)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("cancellation did not wake the blocked probe")
	}
	if !member.connClosed.Load() {
		t.Fatal("the packet connection must be released on cancellation")
	}
}

// ---- 轮次集成：证据如何影响 UDP 恢复门 ----

// 关联成功但目标不回包：本轮 UDP 是未知，不得推进回切计数，也不得清零。
func TestUDPAssociationAloneDoesNotAdvanceGate(t *testing.T) {
	silent := newUDPTestEndpoint(t, udpEndpointSilent)
	member := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "a [T1]", []string{"tcp", "udp"}, nil)}
	grp := startupGroupWithMembersAndProbe(t, context.Background(), "http://example.invalid/204", silent.probeConfig(200*time.Millisecond), member)
	storeTierHistory(t, grp, "a [T1]", 100)
	grp.tierUDP.gate.consecutiveOK[0] = 2

	if _, round, err := grp.urlTest(context.Background(), true); err != nil {
		t.Fatalf("round: %v", err)
	} else if got := round.snapshotNetwork(N.NetworkUDP)["a [T1]"]; got != urlTestUnverified {
		t.Fatalf("a silent target must be recorded as unverified, got %v", got)
	}
	if got := grp.tierUDP.gate.consecutiveOK[0]; got != 2 {
		t.Fatalf("unverified UDP rounds must be neutral, got %d", got)
	}
}

// TCP 成功、UDP 未知（目标不回包）：两个网络的恢复状态互不污染。
func TestTCPSuccessWithUnknownUDPKeepsGatesApart(t *testing.T) {
	silent := newUDPTestEndpoint(t, udpEndpointSilent)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	member := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "a [T1]", []string{"tcp", "udp"}, nil)}
	grp := startupGroupWithMembersAndProbe(t, context.Background(), server.URL, silent.probeConfig(200*time.Millisecond), member)
	storeTierHistory(t, grp, "a [T1]", 100)
	grp.tierUDP.currentTier = 1
	grp.tierUDP.gate.consecutiveOK[0] = 2
	grp.tierUDP.gate.lastFailure[0] = time.Now()
	udpFailure := grp.tierUDP.gate.lastFailure[0]

	for i := 0; i < tierSwitchBackMinChecks; i++ {
		if _, round, err := grp.urlTest(context.Background(), true); err != nil {
			t.Fatalf("round %d: %v", i, err)
		} else if got := round.snapshotNetwork(N.NetworkTCP)["a [T1]"]; got != urlTestSuccess {
			t.Fatalf("round %d: expected TCP success, got %v", i, got)
		}
	}
	if got := grp.tierTCP.gate.consecutiveOK[0]; got != tierSwitchBackMinChecks {
		t.Fatalf("TCP must advance on its own evidence, got %d", got)
	}
	if got := grp.tierUDP.gate.consecutiveOK[0]; got != 2 {
		t.Fatalf("TCP success must not advance the UDP gate, got %d", got)
	}
	if got := grp.tierUDP.gate.lastFailure[0]; !got.Equal(udpFailure) {
		t.Fatalf("an unknown UDP result must not stamp a new failure time, got %v", got)
	}
}

// 目标回显：连续三轮 verified 之后 UDP 才能回切；TCP 只按自己的证据计数。
func TestUDPGateAdvancesOnlyOnVerifiedRoundTrips(t *testing.T) {
	echo := newUDPTestEndpoint(t, udpEndpointEcho)
	upper := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "a [T1]", []string{"tcp", "udp"}, nil)}
	lower := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "b [T2]", []string{"tcp", "udp"}, nil)}
	grp := startupGroupWithMembersAndProbe(t, context.Background(), "http://example.invalid/204", echo.probeConfig(time.Second), upper, lower)
	storeTierHistory(t, grp, "a [T1]", 100)
	storeTierHistory(t, grp, "b [T2]", 90)
	grp.tierTCP.currentTier = 0
	grp.tierUDP.currentTier = 1
	grp.tierUDP.gate.lastFailure[0] = time.Now().Add(-61 * time.Second)

	for i := 0; i < tierSwitchBackMinChecks; i++ {
		if _, round, err := grp.urlTest(context.Background(), true); err != nil {
			t.Fatalf("round %d: %v", i, err)
		} else if got := round.snapshotNetwork(N.NetworkUDP)["a [T1]"]; got != urlTestSuccess {
			t.Fatalf("round %d: expected a verified UDP round trip, got %v", i, got)
		}
	}
	if got := grp.tierUDP.gate.consecutiveOK[0]; got != tierSwitchBackMinChecks {
		t.Fatalf("expected %d verified UDP rounds, got %d", tierSwitchBackMinChecks, got)
	}
	grp.updateAccess.RLock()
	udpTier, _ := grp.effectiveTierLocked(N.NetworkUDP)
	grp.updateAccess.RUnlock()
	if udpTier != 0 {
		t.Fatalf("verified UDP rounds must allow switch-back, got tier %d", udpTier)
	}
}

// 未配置 UDP 探测端点：本轮没有任何 UDP 流量，UDP 观测是跳过，门不动。
func TestUDPProbeUnconfiguredSkipsWithoutTraffic(t *testing.T) {
	member := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "a", []string{"tcp", "udp"}, nil)}
	grp := startupGroupWithMembers(t, context.Background(), "http://example.invalid/204", member)
	grp.tierUDP.gate.consecutiveOK[0] = 1
	_, round, err := grp.urlTest(context.Background(), true)
	if err != nil {
		t.Fatalf("round: %v", err)
	}
	if got := round.snapshotNetwork(N.NetworkUDP)["a"]; got != urlTestSkipped {
		t.Fatalf("expected UDP to be skipped without a probe endpoint, got %v", got)
	}
	if got := grp.tierUDP.gate.consecutiveOK[0]; got != 1 {
		t.Fatalf("a skipped UDP round must be neutral, got %d", got)
	}
	if member.listenCalls.Load() != 0 {
		t.Fatalf("no UDP association may be opened without a probe endpoint, got %d", member.listenCalls.Load())
	}
}

// 组关闭会取消在途 UDP 探测并释放连接：关闭后不再有任何结论被记成成功。
func TestGroupCloseCancelsUDPProbe(t *testing.T) {
	silent := newUDPTestEndpoint(t, udpEndpointSilent)
	member := &lazySessionOutbound{Adapter: outbound.NewAdapter("loopback", "a", []string{"tcp", "udp"}, nil)}
	grp := startupGroupWithMembersAndProbe(t, context.Background(), "http://example.invalid/204", silent.probeConfig(5*time.Second), member)
	result := make(chan *urlTestRound, 1)
	go func() {
		_, round, _ := grp.urlTest(context.Background(), true)
		result <- round
	}()
	deadline := time.Now().Add(2 * time.Second)
	for !member.readStarted.Load() && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if !member.readStarted.Load() {
		t.Fatal("UDP probe did not start")
	}
	if err := grp.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}
	select {
	case round := <-result:
		if round != nil {
			if got := round.snapshotNetwork(N.NetworkUDP)["a"]; got == urlTestSuccess {
				t.Fatal("a closed group must not record a UDP success")
			}
		}
	case <-time.After(2 * time.Second):
		t.Fatal("group close did not finish the probe round")
	}
	if !member.connClosed.Load() {
		t.Fatal("the packet connection must be released after group close")
	}
	if got := grp.tierUDP.gate.consecutiveOK[0]; got != 0 {
		t.Fatalf("a canceled UDP probe must not advance the gate, got %d", got)
	}
}

// ---- 可控成员：模拟“首次写入才建立会话”的 early packet conn ----

// lazyForwardOutbound 的 ListenPacket 立刻返回一个**尚未建立会话**的连接：
// 真正的会话（到目标的数据报通道）在第一次 WriteTo 时才建立——VMess/VLESS/
// Trojan/XUDP 的 early packet conn 就是这个时序。它比 lazySessionOutbound 更真实：
// 会话建立后会真的把数据报发出去并读回包，因此能验证“建立成功才 verified、
// 目标不回包仍是 unverified”。
type lazyForwardOutbound struct {
	outbound.Adapter
	listenCalls atomic.Int32
}

func (l *lazyForwardOutbound) DialContext(ctx context.Context, network string, dst M.Socksaddr) (net.Conn, error) {
	return nil, errors.New("lazyForwardOutbound: tcp not used")
}

func (l *lazyForwardOutbound) ListenPacket(ctx context.Context, dst M.Socksaddr) (net.PacketConn, error) {
	l.listenCalls.Add(1)
	return &lazyForwardConn{ctx: ctx}, nil
}

type lazyForwardConn struct {
	ctx  context.Context
	once sync.Once
	mu   sync.Mutex
	conn net.Conn
	err  error
}

func (c *lazyForwardConn) session(addr net.Addr) (net.Conn, error) {
	c.once.Do(func() {
		// 首次写入才建立会话：到目标地址的 UDP 通道。
		c.conn, c.err = (&net.Dialer{}).DialContext(c.ctx, "udp", addr.String())
	})
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.conn, c.err
}

func (c *lazyForwardConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	conn, err := c.session(addr)
	if err != nil {
		return 0, err
	}
	return conn.Write(p)
}

func (c *lazyForwardConn) ReadFrom(p []byte) (int, net.Addr, error) {
	c.mu.Lock()
	conn := c.conn
	c.mu.Unlock()
	if conn == nil {
		return 0, nil, errors.New("lazy session not established")
	}
	n, err := conn.Read(p)
	return n, conn.RemoteAddr(), err
}

func (c *lazyForwardConn) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.conn == nil {
		return nil
	}
	return c.conn.Close()
}

func (c *lazyForwardConn) LocalAddr() net.Addr                { return &net.UDPAddr{IP: net.ParseIP("127.0.0.1")} }
func (c *lazyForwardConn) SetDeadline(t time.Time) error      { return nil }
func (c *lazyForwardConn) SetReadDeadline(t time.Time) error  { return nil }
func (c *lazyForwardConn) SetWriteDeadline(t time.Time) error { return nil }

// 首次写入才建立会话的成员：真能往返就 verified，目标不回包就是 unverified。
func TestUDPProbeVerifiesLazySessionEstablishedOnFirstWrite(t *testing.T) {
	echo := newUDPTestEndpoint(t, udpEndpointEcho)
	member := &lazyForwardOutbound{Adapter: outbound.NewAdapter("loopback", "lazy", []string{"tcp", "udp"}, nil)}
	if outcome := ProbeOutboundUDP(context.Background(), member, echo.target(), []byte(DefaultUDPProbePayload), time.Second); outcome != UDPProbeVerified {
		t.Fatalf("a lazy session that really round-trips must verify, got %s", outcome)
	}
	if echo.reads.Load() == 0 {
		t.Fatal("the datagram never reached the endpoint through the lazy session")
	}

	silent := newUDPTestEndpoint(t, udpEndpointSilent)
	lazySilent := &lazyForwardOutbound{Adapter: outbound.NewAdapter("loopback", "lazy-silent", []string{"tcp", "udp"}, nil)}
	if outcome := ProbeOutboundUDP(context.Background(), lazySilent, silent.target(), []byte(DefaultUDPProbePayload), 200*time.Millisecond); outcome != UDPProbeUnverified {
		t.Fatalf("a lazy session without a reply must stay unverified, got %s", outcome)
	}

	// 会话建立失败（目标地址不可达）就是明确失败。
	dead := &lazyForwardOutbound{Adapter: outbound.NewAdapter("loopback", "lazy-dead", []string{"tcp", "udp"}, nil)}
	if outcome := ProbeOutboundUDP(context.Background(), dead, M.ParseSocksaddrHostPort("127.0.0.1", 1), []byte(DefaultUDPProbePayload), time.Second); outcome != UDPProbeRejected && outcome != UDPProbeUnverified {
		t.Fatalf("a failed lazy session must not verify, got %s", outcome)
	}
}

// lazySessionOutbound 的 ListenPacket 立刻返回一个未建立会话的连接：
// 真正的会话在第一次 WriteTo 时才建立（VMess/VLESS/Trojan/XUDP 的 early
// packet conn 就是这种时序）。writeFail 模拟服务端拒绝会话。
type lazySessionOutbound struct {
	outbound.Adapter
	associationErr error
	writeFail      error
	listenCalls    atomic.Int32
	readStarted    atomic.Bool
	connClosed     atomic.Bool
}

func (l *lazySessionOutbound) DialContext(ctx context.Context, network string, dst M.Socksaddr) (net.Conn, error) {
	return nil, errors.New("lazySessionOutbound: tcp not used")
}

func (l *lazySessionOutbound) ListenPacket(ctx context.Context, dst M.Socksaddr) (net.PacketConn, error) {
	l.listenCalls.Add(1)
	if l.associationErr != nil {
		return nil, l.associationErr
	}
	return &lazySessionConn{owner: l, ctx: ctx}, nil
}

// lazySessionConn 不持有真实网络连接：写入即“建立会话”，读取一直等到上下文结束，
// 用来验证“关联成功 + 首次写入失败/无回包”不会被当成 UDP 健康。
type lazySessionConn struct {
	owner *lazySessionOutbound
	ctx   context.Context
	once  sync.Once
}

func (c *lazySessionConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	if c.owner.writeFail != nil {
		return 0, c.owner.writeFail
	}
	return len(p), nil
}

func (c *lazySessionConn) ReadFrom(p []byte) (int, net.Addr, error) {
	c.owner.readStarted.Store(true)
	<-c.ctx.Done()
	return 0, nil, c.ctx.Err()
}

func (c *lazySessionConn) Close() error {
	c.once.Do(func() { c.owner.connClosed.Store(true) })
	return nil
}

func (c *lazySessionConn) LocalAddr() net.Addr                { return &net.UDPAddr{IP: net.ParseIP("127.0.0.1")} }
func (c *lazySessionConn) SetDeadline(t time.Time) error      { return nil }
func (c *lazySessionConn) SetReadDeadline(t time.Time) error  { return nil }
func (c *lazySessionConn) SetWriteDeadline(t time.Time) error { return nil }
