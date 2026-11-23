// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package urltest

import (
	"context"
	"errors"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter/outbound"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// v3.0.18-preview.3 复审 P2 的回归：探测回包必须与本轮目标**同源**且内容一致。
// 这里用 PacketConn 替身精确控制 ReadFrom 返回的来源，避免依赖真实代理服务端；
// 真机/真实代理的缺口在交付报告单独说明。
type udpSourceReply struct {
	payload []byte
	from    net.Addr
	err     error
}

type udpSourceReplyConn struct {
	ctx         context.Context
	reply       udpSourceReply
	writes      atomic.Int32
	closed      atomic.Bool
	readStarted atomic.Bool
}

func (c *udpSourceReplyConn) ReadFrom(p []byte) (int, net.Addr, error) {
	c.readStarted.Store(true)
	select {
	case <-c.ctx.Done():
		return 0, nil, c.ctx.Err()
	default:
	}
	if c.reply.err != nil {
		return 0, nil, c.reply.err
	}
	return copy(p, c.reply.payload), c.reply.from, nil
}

func (c *udpSourceReplyConn) WriteTo(p []byte, _ net.Addr) (int, error) {
	c.writes.Add(1)
	return len(p), nil
}
func (c *udpSourceReplyConn) Close() error {
	c.closed.Store(true)
	return nil
}
func (c *udpSourceReplyConn) LocalAddr() net.Addr              { return &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)} }
func (c *udpSourceReplyConn) SetDeadline(time.Time) error      { return nil }
func (c *udpSourceReplyConn) SetReadDeadline(time.Time) error  { return nil }
func (c *udpSourceReplyConn) SetWriteDeadline(time.Time) error { return nil }

type udpSourceReplyOutbound struct {
	outbound.Adapter
	reply udpSourceReply
	mu    sync.Mutex
	conns []*udpSourceReplyConn
}

func (o *udpSourceReplyOutbound) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	return nil, errors.New("udpSourceReplyOutbound: tcp not used")
}

func (o *udpSourceReplyOutbound) ListenPacket(ctx context.Context, _ M.Socksaddr) (net.PacketConn, error) {
	conn := &udpSourceReplyConn{ctx: ctx, reply: o.reply}
	o.mu.Lock()
	o.conns = append(o.conns, conn)
	o.mu.Unlock()
	return conn, nil
}

func (o *udpSourceReplyOutbound) connections() []*udpSourceReplyConn {
	o.mu.Lock()
	defer o.mu.Unlock()
	return append([]*udpSourceReplyConn(nil), o.conns...)
}

func newUDPSourceReplyOutbound(tag string, reply udpSourceReply) *udpSourceReplyOutbound {
	return &udpSourceReplyOutbound{Adapter: outbound.NewAdapter("loopback", tag, []string{"tcp", "udp"}, nil), reply: reply}
}

var udpSourceProbeTarget = M.ParseSocksaddrHostPort("192.0.2.1", 7000)

func TestUDPProbeRejectsSamePayloadFromWrongSource(t *testing.T) {
	payload := []byte(DefaultUDPProbePayload)
	cases := []struct {
		name string
		from net.Addr
	}{
		{"same-ip-different-port", &net.UDPAddr{IP: net.ParseIP("192.0.2.1"), Port: 7001}},
		{"different-ip-same-port", &net.UDPAddr{IP: net.ParseIP("192.0.2.2"), Port: 7000}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			member := newUDPSourceReplyOutbound(tc.name, udpSourceReply{payload: payload, from: tc.from})
			outcome := ProbeOutboundUDP(context.Background(), member, udpSourceProbeTarget, payload, time.Second)
			if outcome != UDPProbeRejected {
				t.Fatalf("same payload from a different source must not verify, got %s", outcome)
			}
			conns := member.connections()
			if len(conns) != 1 || conns[0].writes.Load() != 1 {
				t.Fatalf("expected exactly one probe datagram, got %d connections", len(conns))
			}
			if !conns[0].closed.Load() {
				t.Fatal("packet connection must be released after a rejected reply")
			}
		})
	}
}

func TestUDPProbeUnverifiedWhenSourceCannotBeMatched(t *testing.T) {
	payload := []byte(DefaultUDPProbePayload)
	cases := []struct {
		name string
		from net.Addr
	}{
		{"nil-source", nil},
		{"ip-without-port", &net.IPAddr{IP: net.ParseIP("192.0.2.1")}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			member := newUDPSourceReplyOutbound(tc.name, udpSourceReply{payload: payload, from: tc.from})
			outcome := ProbeOutboundUDP(context.Background(), member, udpSourceProbeTarget, payload, time.Second)
			if outcome != UDPProbeUnverified {
				t.Fatalf("an unmatchable source must be unknown, got %s", outcome)
			}
			if conns := member.connections(); len(conns) != 1 || !conns[0].closed.Load() {
				t.Fatal("packet connection must be released when source cannot be matched")
			}
		})
	}
}

func TestUDPProbeStillVerifiesMatchingSourceAndContent(t *testing.T) {
	payload := []byte(DefaultUDPProbePayload)
	member := newUDPSourceReplyOutbound("good", udpSourceReply{
		payload: payload,
		from:    &net.UDPAddr{IP: net.ParseIP("192.0.2.1"), Port: 7000},
	})
	if outcome := ProbeOutboundUDP(context.Background(), member, udpSourceProbeTarget, payload, time.Second); outcome != UDPProbeVerified {
		t.Fatalf("matching source and content must verify, got %s", outcome)
	}
	wrongContent := newUDPSourceReplyOutbound("bad-content", udpSourceReply{
		payload: []byte("not-the-probe-datagram"),
		from:    &net.UDPAddr{IP: net.ParseIP("192.0.2.1"), Port: 7000},
	})
	if outcome := ProbeOutboundUDP(context.Background(), wrongContent, udpSourceProbeTarget, payload, time.Second); outcome != UDPProbeRejected {
		t.Fatalf("correct source with wrong content must be rejected, got %s", outcome)
	}
}

func TestWrongSourceProbeNeverAdvancesUDPGate(t *testing.T) {
	payload := []byte(DefaultUDPProbePayload)
	member := newUDPSourceReplyOutbound("wrong-source [T1]", udpSourceReply{
		payload: payload,
		from:    &net.UDPAddr{IP: net.ParseIP("192.0.2.99"), Port: 7000},
	})
	probe := &udpProbeConfig{target: udpSourceProbeTarget, payload: payload, timeout: time.Second}
	grp := startupGroupWithMembersAndProbe(t, context.Background(), "http://example.invalid/204", probe, member)
	storeTierHistory(t, grp, member.Tag(), 100)
	grp.tierUDP.currentTier = 1
	grp.tierUDP.gate.consecutiveOK[0] = tierSwitchBackMinChecks - 1
	grp.tierUDP.gate.lastFailure[0] = time.Now().Add(-61 * time.Second)

	if _, round, err := grp.urlTest(context.Background(), true); err != nil {
		t.Fatalf("round: %v", err)
	} else if got := round.snapshotNetwork(N.NetworkUDP)[member.Tag()]; got != urlTestFailure {
		t.Fatalf("a wrong-source reply must be a UDP failure, got %v", got)
	}
	if got := grp.tierUDP.gate.consecutiveOK[0]; got != 0 {
		t.Fatalf("a wrong-source reply must clear UDP consecutive successes, got %d", got)
	}
	if grp.canSwitchBackTo(N.NetworkUDP, 0, time.Now()) {
		t.Fatal("a wrong-source reply must not grant switch-back to the higher tier")
	}
}

func TestUDPProbeSourceMatchingHandlesAddressFamilies(t *testing.T) {
	target := M.ParseSocksaddrHostPort("192.0.2.1", 7000)
	if source, ok := udpProbeSourceFromAddr(&net.UDPAddr{IP: net.ParseIP("192.0.2.1"), Port: 7000}); !ok || source.Addr.Unmap() != target.Addr.Unmap() || source.Port != target.Port {
		t.Fatal("matching IPv4 source")
	}
	if source, ok := udpProbeSourceFromAddr(&net.UDPAddr{IP: net.ParseIP("192.0.2.2"), Port: 7000}); !ok || source.Addr.Unmap() == target.Addr.Unmap() {
		t.Fatal("different IPv4 source must not match")
	}
	v6 := M.ParseSocksaddrHostPort("2001:db8::1", 7000)
	if source, ok := udpProbeSourceFromAddr(&net.UDPAddr{IP: net.ParseIP("2001:db8::1"), Port: 7000}); !ok || source.Addr.Unmap() != v6.Addr.Unmap() || source.Port != v6.Port {
		t.Fatal("matching IPv6 source")
	}
	if source, ok := udpProbeSourceFromAddr(&net.UDPAddr{IP: net.ParseIP("2001:db8::2"), Port: 7000}); !ok || source.Addr.Unmap() == v6.Addr.Unmap() {
		t.Fatal("different IPv6 source must not match")
	}
	if source, ok := udpProbeSourceFromAddr(&net.UDPAddr{IP: net.ParseIP("2001:db8::1"), Port: 7001}); !ok || source.Port == v6.Port {
		t.Fatal("same IPv6 address with a different port must not match")
	}
	// 域名来源不属于当前 IP:port 契约，必须保持未知而不是成功。
	if _, ok := udpProbeSourceFromAddr(udpDomainAddr("example.invalid:7000")); ok {
		t.Fatal("a domain source cannot be matched against an IP target")
	}
}

// udpDomainAddr 模拟某些代理返回的 FQDN:port 来源（非 IP:port）。
type udpDomainAddr string

func (a udpDomainAddr) Network() string { return "udp" }
func (a udpDomainAddr) String() string  { return string(a) }
