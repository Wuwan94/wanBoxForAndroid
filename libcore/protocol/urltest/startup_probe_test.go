// @author 雾晚
package urltest

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	history "github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

type startupOutbound struct {
	outbound.Adapter
	calls atomic.Int32
	dead  bool
}

func (s *startupOutbound) DialContext(ctx context.Context, network string, dst M.Socksaddr) (net.Conn, error) {
	s.calls.Add(1)
	if s.dead {
		return nil, errors.New("fixture unreachable")
	}
	return (&net.Dialer{}).DialContext(ctx, network, dst.String())
}
func (s *startupOutbound) ListenPacket(ctx context.Context, dst M.Socksaddr) (net.PacketConn, error) {
	return (&net.ListenConfig{}).ListenPacket(ctx, "udp", "127.0.0.1:0")
}
func startupGroup(t *testing.T, link string) (*URLTestGroup, *startupOutbound) {
	t.Helper()
	dead := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "dead", []string{"tcp", "udp"}, nil), dead: true}
	live := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "live", []string{"tcp", "udp"}, nil)}
	return startupGroupWithMembers(t, context.Background(), link, dead, live), live
}

func startupGroupWithMembers(t *testing.T, parent context.Context, link string, members ...adapter.Outbound) *URLTestGroup {
	t.Helper()
	ctx := service.ContextWithDefaultRegistry(parent)
	ctx = service.ContextWithPtr(ctx, history.NewHistoryStorage())
	ctx = pause.WithDefaultManager(ctx)
	group, err := NewURLTestGroup(ctx, nil, log.NewNOPFactory().Logger(),
		members, link, time.Minute, 50, time.Minute, false)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = group.Close() })
	return group
}

// Core startup returns while probes are blocked, but the first user connection
// must not fall back to the first (dead) member before history is available.
func TestStartupProbeIsAsyncAndFirstConnectionWaits(t *testing.T) {
	entered, release := make(chan struct{}), make(chan struct{})
	var enteredOnce, releaseOnce sync.Once
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		enteredOnce.Do(func() { close(entered) })
		select {
		case <-release:
		case <-r.Context().Done():
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	defer releaseOnce.Do(func() { close(release) })
	group, live := startupGroup(t, server.URL)
	started := make(chan struct{})
	go func() { group.PostStart(); close(started) }()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("PostStart blocked on network")
	}
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("initial probe did not start")
	}
	s := &URLTest{group: group, logger: log.NewNOPFactory().Logger()}
	result := make(chan error, 1)
	go func() {
		conn, err := s.DialContext(context.Background(), "tcp", M.ParseSocksaddr(server.Listener.Addr().String()))
		if conn != nil {
			conn.Close()
		}
		result <- err
	}()
	udpResult := make(chan error, 1)
	go func() {
		conn, err := s.ListenPacket(context.Background(), M.ParseSocksaddr("127.0.0.1:53"))
		if conn != nil {
			conn.Close()
		}
		udpResult <- err
	}()
	select {
	case err := <-result:
		t.Fatalf("connection bypassed initial probe: %v", err)
	case <-time.After(30 * time.Millisecond):
	}
	select {
	case err := <-udpResult:
		t.Fatalf("UDP connection bypassed initial probe: %v", err)
	case <-time.After(30 * time.Millisecond):
	}
	if got := live.calls.Load(); got != 1 {
		t.Fatalf("live dial count=%d before readiness", got)
	}
	releaseOnce.Do(func() { close(release) })
	select {
	case err := <-result:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("connection did not resume after initial probe")
	}
	select {
	case err := <-udpResult:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("UDP connection did not resume after initial probe")
	}
	selected, ok := group.Select("tcp")
	if !ok || selected == nil || selected.Tag() != "live" {
		t.Fatalf("selected=%v ok=%v, want live", selected, ok)
	}
}

func TestStartupWaitRespectsCallerCancellationAndShutdown(t *testing.T) {
	entered := make(chan struct{})
	var enteredOnce sync.Once
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		enteredOnce.Do(func() { close(entered) })
		<-r.Context().Done()
	}))
	defer server.Close()
	group, _ := startupGroup(t, server.URL)
	group.PostStart()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("probe did not start")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := group.waitForStartupProbe(ctx); !errors.Is(err, context.Canceled) {
		t.Fatalf("got %v", err)
	}
	if err := group.Close(); err != nil {
		t.Fatal(err)
	}
	if err := group.waitForStartupProbe(context.Background()); !errors.Is(err, context.Canceled) {
		t.Fatalf("got %v", err)
	}
	select {
	case <-group.startupDone:
	case <-time.After(time.Second):
		t.Fatal("shutdown left probe running")
	}
	group.PostStart() // shutdown cannot restart probes or double-close readiness
}

// Startup publishes selection concurrently with the app's status/selector reads.
func TestStartupSelectionCanBeObservedConcurrently(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	group, _ := startupGroup(t, server.URL)
	s := &URLTest{group: group}
	readDone := make(chan struct{})
	var readers sync.WaitGroup
	for i := 0; i < 3; i++ {
		readers.Add(1)
		go func() {
			defer readers.Done()
			for {
				select {
				case <-readDone:
					return
				default:
					_ = s.Now()
					_ = s.Selected("tcp")
					_, _ = group.Select("udp")
				}
			}
		}()
	}
	group.PostStart()
	err := group.waitForStartupProbe(context.Background())
	close(readDone)
	readers.Wait()
	if err != nil {
		t.Fatal(err)
	}
}

// More members than the six-slot batch force a second wave: the per-member
// three-second timeout alone must not extend startup past its five-second budget.
func TestStartupBudgetExhaustionKeepsFallback(t *testing.T) {
	if probeBudget != 5*time.Second {
		t.Fatalf("startup budget=%v, want five seconds", probeBudget)
	}
	entered, stopped := make(chan struct{}, 7), make(chan struct{}, 7)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		entered <- struct{}{}
		<-r.Context().Done()
		stopped <- struct{}{}
	}))
	defer server.Close()
	members := make([]adapter.Outbound, 7)
	for i := range members {
		members[i] = &startupOutbound{Adapter: outbound.NewAdapter("loopback", string(rune('a'+i)), []string{"tcp", "udp"}, nil)}
	}
	group := startupGroupWithMembers(t, context.Background(), server.URL, members...)
	started := time.Now()
	group.PostStart()
	select {
	case <-group.startupDone:
	case <-time.After(probeBudget + time.Second):
		t.Fatal("startup exceeded its total budget")
	}
	elapsed := time.Since(started)
	if elapsed < probeBudget-500*time.Millisecond {
		t.Fatalf("probe finished before the budget was exhausted: %v", elapsed)
	}
	for range members {
		awaitStartupEvent(t, entered, "member probe start")
		awaitStartupEvent(t, stopped, "member probe cancellation")
	}
	if err := group.waitForStartupProbe(context.Background()); err != nil {
		t.Fatalf("budget expiry disabled the group: %v", err)
	}
	assertStartupFallback(t, group, members[0])
	// The waiting application connection can still use that fallback, even
	// though no health result was produced before the total deadline.
	s := &URLTest{group: group, logger: log.NewNOPFactory().Logger()}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	conn, err := s.DialContext(ctx, "tcp", M.ParseSocksaddr(server.Listener.Addr().String()))
	if err != nil {
		t.Fatalf("fallback dial after budget expiry: %v", err)
	}
	_ = conn.Close()
}

func TestStartupAllMembersUnavailableKeepsFallback(t *testing.T) {
	group, live := startupGroup(t, "http://127.0.0.1:1")
	live.dead = true
	group.PostStart()
	if err := group.waitForStartupProbe(context.Background()); err != nil {
		t.Fatal(err)
	}
	assertStartupFallback(t, group, group.outbounds[0])
	// A missing health result must not turn into "missing supported outbound":
	// the existing TCP fallback still attempts both configured members.
	s := &URLTest{group: group, logger: log.NewNOPFactory().Logger()}
	_, err := s.DialContext(context.Background(), "tcp", M.ParseSocksaddr("127.0.0.1:1"))
	if err == nil || !strings.Contains(err.Error(), "fixture unreachable") {
		t.Fatalf("want member dial error, got %v", err)
	}
	for _, member := range group.outbounds {
		if calls := member.(*startupOutbound).calls.Load(); calls != 2 {
			t.Fatalf("%s calls=%d, want probe plus fallback dial", member.Tag(), calls)
		}
	}
}

func TestStartupCancellationPreservesHistory(t *testing.T) {
	for _, closeGroup := range []bool{false, true} {
		name := "parent cancellation"
		if closeGroup {
			name = "group closure"
		}
		t.Run(name, func(t *testing.T) {
			entered, stopped := make(chan struct{}, 2), make(chan struct{}, 2)
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				entered <- struct{}{}
				<-r.Context().Done()
				stopped <- struct{}{}
			}))
			defer server.Close()
			parent, cancel := context.WithCancel(context.Background())
			defer cancel()
			members := []adapter.Outbound{
				&startupOutbound{Adapter: outbound.NewAdapter("loopback", "recent", []string{"tcp", "udp"}, nil)},
				&startupOutbound{Adapter: outbound.NewAdapter("loopback", "expired", []string{"tcp", "udp"}, nil)},
			}
			group := startupGroupWithMembers(t, parent, server.URL, members...)
			before := map[string]adapter.URLTestHistory{
				"recent":  {Time: time.Now(), Delay: 42},
				"expired": {Time: time.Now().Add(-time.Hour), Delay: 123},
			}
			for tag, value := range before {
				group.history.StoreURLTestHistory(tag, &value)
			}
			group.PostStart()
			for range members {
				awaitStartupEvent(t, entered, "probe start")
			}
			if closeGroup {
				_ = group.Close()
			} else {
				cancel()
			}
			awaitStartupEvent(t, group.startupDone, "startup completion")
			for range members {
				awaitStartupEvent(t, stopped, "probe cancellation")
			}
			for tag, value := range before {
				got := group.history.LoadURLTestHistory(tag)
				if got == nil || *got != value {
					t.Errorf("cancellation changed %s history: got %+v, want %+v", tag, got, value)
				}
			}
		})
	}
}

func TestClosedGroupDoesNotRestartProbes(t *testing.T) {
	entered, stopped := make(chan struct{}, 1), make(chan struct{}, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		entered <- struct{}{}
		<-r.Context().Done()
		stopped <- struct{}{}
	}))
	defer server.Close()
	member := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "member", []string{"tcp", "udp"}, nil)}
	group := startupGroupWithMembers(t, context.Background(), server.URL, member)
	group.PostStart()
	awaitStartupEvent(t, entered, "probe start")
	_ = group.Close()
	awaitStartupEvent(t, group.startupDone, "startup completion")
	awaitStartupEvent(t, stopped, "probe cancellation")
	group.PostStart()
	group.Touch()
	group.CheckOutbounds(context.Background(), true)
	if calls := member.calls.Load(); calls != 1 {
		t.Fatalf("closed group restarted probes: calls=%d", calls)
	}
	if group.ticker != nil {
		t.Fatal("closed group recreated ticker")
	}
}

func awaitStartupEvent(t *testing.T, event <-chan struct{}, label string) {
	t.Helper()
	select {
	case <-event:
	case <-time.After(time.Second):
		t.Fatalf("timed out waiting for %s", label)
	}
}

func assertStartupFallback(t *testing.T, group *URLTestGroup, want adapter.Outbound) {
	t.Helper()
	for _, network := range []string{"tcp", "udp"} {
		got, healthy := group.Select(network)
		if got != want || healthy {
			t.Fatalf("%s selection=%v healthy=%v, want fallback %s", network, got, healthy, want.Tag())
		}
	}
}

// A member-specific timeout is still a real failed observation; only the
// enclosing batch's cancellation must be ignored when publishing history.
func TestMemberTimeoutStillPenalizesRecentHistory(t *testing.T) {
	stopped := make(chan struct{}, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-r.Context().Done()
		stopped <- struct{}{}
	}))
	defer server.Close()
	member := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "member", []string{"tcp", "udp"}, nil)}
	group := startupGroupWithMembers(t, context.Background(), server.URL, member)
	before := adapter.URLTestHistory{Time: time.Now(), Delay: 42}
	group.history.StoreURLTestHistory(member.Tag(), &before)
	group.PostStart()
	select {
	case <-group.startupDone:
	case <-time.After(probeBudget + time.Second):
		t.Fatal("member timeout did not complete startup")
	}
	awaitStartupEvent(t, stopped, "member timeout")
	got := group.history.LoadURLTestHistory(member.Tag())
	if got == nil || got.Delay != before.Delay+300 || !got.Time.After(before.Time) {
		t.Fatalf("member timeout did not retain existing failure policy: %+v", got)
	}
}

func TestCloseCancelsIndependentManualProbe(t *testing.T) {
	entered, stopped := make(chan struct{}, 1), make(chan struct{}, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		entered <- struct{}{}
		<-r.Context().Done()
		stopped <- struct{}{}
	}))
	defer server.Close()
	member := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "member", []string{"tcp", "udp"}, nil)}
	group := startupGroupWithMembers(t, context.Background(), server.URL, member)
	before := adapter.URLTestHistory{Time: time.Now(), Delay: 42}
	group.history.StoreURLTestHistory(member.Tag(), &before)
	result := make(chan error, 1)
	go func() { _, err := group.URLTest(context.Background()); result <- err }()
	awaitStartupEvent(t, entered, "manual probe start")
	_ = group.Close()
	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("manual probe returned %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("group close left independent manual probe running")
	}
	awaitStartupEvent(t, stopped, "manual probe cancellation")
	got := group.history.LoadURLTestHistory(member.Tag())
	if got == nil || *got != before {
		t.Fatalf("manual probe cancellation changed history: %+v", got)
	}
}
