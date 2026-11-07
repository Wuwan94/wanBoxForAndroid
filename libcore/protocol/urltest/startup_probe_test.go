// @author 雾晚
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
	ctx := service.ContextWithDefaultRegistry(context.Background())
	ctx = service.ContextWithPtr(ctx, history.NewHistoryStorage())
	ctx = pause.WithDefaultManager(ctx)
	group, err := NewURLTestGroup(ctx, nil, log.NewNOPFactory().Logger(),
		[]adapter.Outbound{dead, live}, link, time.Minute, 50, time.Minute, false)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = group.Close() })
	return group, live
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
