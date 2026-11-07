// @author 雾晚
package urltest_test

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	history "github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/log"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
	custom "libcore/protocol/urltest"
)

type deadOutbound struct{ outbound.Adapter }

func (*deadOutbound) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	return nil, E.New("fixture unreachable")
}

func (*deadOutbound) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, E.New("fixture unreachable")
}

// Start must probe once before the first Select, otherwise the first connection
// is pinned to the first member even when it is unreachable. @author 雾晚
func TestPostStartProbesBeforeSelectingUnavailableMember(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	dead := &deadOutbound{outbound.NewAdapter("loopback", "dead", []string{"tcp", "udp"}, nil)}
	live := &loopbackOutbound{outbound.NewAdapter("loopback", "live", []string{"tcp", "udp"}, nil)}

	ctx := service.ContextWithDefaultRegistry(context.Background())
	ctx = service.ContextWithPtr(ctx, history.NewHistoryStorage())
	ctx = pause.WithDefaultManager(ctx)
	logger := log.NewNOPFactory().Logger()

	group, err := custom.NewURLTestGroup(ctx, nil, logger,
		[]adapter.Outbound{dead, live}, server.URL, time.Minute, 50, time.Minute, false)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = group.Close() })

	group.PostStart()

	selected, ok := group.Select("tcp")
	if !ok || selected == nil || selected.Tag() != "live" {
		t.Fatalf("selected=%v ok=%v, want live", selected, ok)
	}
}
