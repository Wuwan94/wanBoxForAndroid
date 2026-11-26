// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package urltest

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// controlledTCPSuccessOutbound blocks its first TCP connection until the test
// advances the recovery-gate epoch, then returns a successful connection.
type controlledTCPSuccessOutbound struct {
	outbound.Adapter
	entered chan struct{}
	release chan struct{}
	target  string
	once    sync.Once
}

func newControlledTCPSuccessOutbound(tag, target string) *controlledTCPSuccessOutbound {
	return &controlledTCPSuccessOutbound{
		Adapter: outbound.NewAdapter("loopback", tag, []string{"tcp"}, nil),
		entered: make(chan struct{}),
		release: make(chan struct{}),
		target:  target,
	}
}

func (o *controlledTCPSuccessOutbound) DialContext(ctx context.Context, network string, _ M.Socksaddr) (net.Conn, error) {
	o.once.Do(func() { close(o.entered) })
	select {
	case <-o.release:
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	if o.target != "" {
		return (&net.Dialer{}).DialContext(ctx, network, o.target)
	}
	client, peer := net.Pipe()
	_ = peer.Close()
	return client, nil
}

func (o *controlledTCPSuccessOutbound) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, net.ErrClosed
}

func releaseOnce(t *testing.T, ch chan struct{}) func() {
	t.Helper()
	var once sync.Once
	release := func() { once.Do(func() { close(ch) }) }
	t.Cleanup(release)
	return release
}

func memberFailureCount(group *URLTestGroup, tag string) int {
	group.failAccess.Lock()
	defer group.failAccess.Unlock()
	return group.failCount[tag]
}

func awaitTCPAttempt(t *testing.T, entered <-chan struct{}) {
	t.Helper()
	select {
	case <-entered:
	case <-time.After(3 * time.Second):
		t.Fatal("TCP attempt did not reach the controlled outbound")
	}
}

// immediateSuccessOutbound lets positive-control tests exercise URLTest.DialContext
// without network I/O or waiting for the group's startup probe.
type immediateSuccessOutbound struct {
	outbound.Adapter
}

func newImmediateSuccessOutbound(tag string) *immediateSuccessOutbound {
	return &immediateSuccessOutbound{
		Adapter: outbound.NewAdapter("loopback", tag, []string{"tcp", "udp"}, nil),
	}
}

func (o *immediateSuccessOutbound) DialContext(ctx context.Context, _ string, _ M.Socksaddr) (net.Conn, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	client, peer := net.Pipe()
	_ = peer.Close()
	return client, nil
}

func (o *immediateSuccessOutbound) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, net.ErrClosed
}

func TestCurrentEpochTCPDialSuccessResetsMemberFailures(t *testing.T) {
	primary := newImmediateSuccessOutbound("primary [T1]")
	group := tierGroup(t, primary)
	storeTierHistory(t, group, primary.Tag(), 100)
	group.selectedOutboundTCP = primary
	if got := group.applyMemberFailure(primary.Tag()); got != 1 {
		t.Fatalf("initial consecutive failure count = %d, want 1", got)
	}

	group.updateAccess.RLock()
	startEpoch := group.tierTCP.gate.epoch
	group.updateAccess.RUnlock()
	urlTest := &URLTest{group: group, logger: log.NewNOPFactory().Logger()}
	conn, err := urlTest.DialContext(context.Background(), N.NetworkTCP, M.ParseSocksaddr("127.0.0.1:443"))
	if conn != nil {
		defer conn.Close()
	}
	if err != nil {
		t.Fatalf("TCP DialContext returned an error: %v", err)
	}

	group.updateAccess.RLock()
	endEpoch := group.tierTCP.gate.epoch
	group.updateAccess.RUnlock()
	if endEpoch != startEpoch {
		t.Fatalf("TCP recovery-gate epoch changed during successful dial: %d -> %d", startEpoch, endEpoch)
	}
	if got := memberFailureCount(group, primary.Tag()); got != 0 {
		t.Fatalf("successful TCP dial at unchanged epoch left %d consecutive failures, want 0", got)
	}
}

func TestUDPDialSuccessResetsMemberFailures(t *testing.T) {
	primary := newImmediateSuccessOutbound("primary [T1]")
	group := tierGroup(t, primary)
	storeTierHistory(t, group, primary.Tag(), 100)
	group.selectedOutboundUDP = primary
	if got := group.applyMemberFailure(primary.Tag()); got != 1 {
		t.Fatalf("initial consecutive failure count = %d, want 1", got)
	}

	urlTest := &URLTest{group: group, logger: log.NewNOPFactory().Logger()}
	conn, err := urlTest.DialContext(context.Background(), N.NetworkUDP, M.ParseSocksaddr("127.0.0.1:443"))
	if conn != nil {
		defer conn.Close()
	}
	if err != nil {
		t.Fatalf("UDP DialContext returned an error: %v", err)
	}
	if got := memberFailureCount(group, primary.Tag()); got != 0 {
		t.Fatalf("successful UDP dial left %d consecutive failures, want 0", got)
	}
}

func TestLateTCPProbeDoesNotOverwriteHistoryOrResetFailures(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	probe := newControlledTCPSuccessOutbound("probe [T1]", server.Listener.Addr().String())
	releaseProbe := releaseOnce(t, probe.release)
	backup := tierMemberTCP("backup [T2]")
	group := tierGroup(t, probe, backup)
	if got := group.applyMemberFailure(probe.Tag()); got != 1 {
		t.Fatalf("initial consecutive failure count = %d, want 1", got)
	}
	seedTime := time.Now().Add(-time.Minute)
	group.history.StoreURLTestHistory(probe.Tag(), &adapter.URLTestHistory{Time: seedTime, Delay: 432})
	group.selectedOutboundTCP = probe
	group.tierTCP.currentTier = 0

	finished := make(chan struct{})
	go func() {
		URLTestOutboundsWithGroup(
			context.Background(), nil, group.history, log.NewNOPFactory().Logger(),
			[]adapter.Outbound{probe}, server.URL, group.interval, true, group,
		)
		close(finished)
	}()
	awaitTCPAttempt(t, probe.entered)

	// A concurrent real TCP failover invalidates the probe round's epoch.
	group.promoteFailover(backup, N.NetworkTCP)
	releaseProbe()
	select {
	case <-finished:
	case <-time.After(3 * time.Second):
		t.Fatal("TCP probe batch did not finish after the controlled dial was released")
	}

	gotHistory := group.history.LoadURLTestHistory(probe.Tag())
	if gotHistory == nil || !gotHistory.Time.Equal(seedTime) || gotHistory.Delay != 432 {
		t.Fatalf("late TCP probe overwrote the existing history: got %+v, want time %v delay 432", gotHistory, seedTime)
	}
	if got := memberFailureCount(group, probe.Tag()); got != 1 {
		t.Fatalf("late TCP probe reset consecutive failures to %d, want 1", got)
	}
}

func TestLateTCPDialSuccessDoesNotResetFailures(t *testing.T) {
	primary := newControlledTCPSuccessOutbound("primary [T1]", "")
	releasePrimary := releaseOnce(t, primary.release)
	backup := tierMemberTCP("backup [T2]")
	group := tierGroup(t, primary, backup)
	storeTierHistory(t, group, primary.Tag(), 100)
	storeTierHistory(t, group, backup.Tag(), 200)
	group.selectedOutboundTCP = primary
	group.tierTCP.currentTier = 0
	if got := group.applyMemberFailure(primary.Tag()); got != 1 {
		t.Fatalf("initial consecutive failure count = %d, want 1", got)
	}

	urlTest := &URLTest{group: group, logger: log.NewNOPFactory().Logger()}
	result := make(chan error, 1)
	go func() {
		conn, err := urlTest.DialContext(context.Background(), "tcp", M.ParseSocksaddr("127.0.0.1:443"))
		if conn != nil {
			_ = conn.Close()
		}
		result <- err
	}()
	awaitTCPAttempt(t, primary.entered)

	// The in-flight connection succeeds after another TCP operation demotes T1.
	group.promoteFailover(backup, N.NetworkTCP)
	releasePrimary()
	select {
	case err := <-result:
		if err != nil {
			t.Fatalf("late successful TCP dial returned an error: %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("TCP DialContext did not finish after the controlled dial was released")
	}

	if got := memberFailureCount(group, primary.Tag()); got != 1 {
		t.Fatalf("late successful TCP dial reset consecutive failures to %d, want 1", got)
	}
}
