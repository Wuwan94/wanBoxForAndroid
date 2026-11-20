// @author 雾晚
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
	M "github.com/sagernet/sing/common/metadata"
)

type probeOutbound struct { adapter.Outbound }

func (*probeOutbound) DialContext(ctx context.Context, network string, address M.Socksaddr) (net.Conn, error) {
	return (&net.Dialer{}).DialContext(ctx, network, address.String())
}

// ListenPacket 用本机 UDP 套接字充当成员的 UDP 数据面：探测数据报直接去到
// 目标，目标的回包也直接回到这个套接字（本机模拟端点，不是真实代理服务端）。
func (*probeOutbound) ListenPacket(ctx context.Context, address M.Socksaddr) (net.PacketConn, error) {
	return (&net.ListenConfig{}).ListenPacket(ctx, "udp", "127.0.0.1:0")
}

func TestProbeSingleRequestAndCancellation(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		time.Sleep(30 * time.Millisecond)
		w.WriteHeader(204)
	}))
	defer server.Close()
	delay, err := ProbeOutbound(context.Background(), &probeOutbound{}, server.URL, time.Second)
	if err != nil || delay < 30 || calls.Load() != 1 {
		t.Fatalf("delay=%d calls=%d err=%v", delay, calls.Load(), err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := ProbeOutbound(ctx, &probeOutbound{}, server.URL, time.Second); err == nil {
		t.Fatal("ignored cancelled group")
	}
	if calls.Load() != 1 {
		t.Fatal("cancelled probe made a request")
	}
	if _, err := ProbeOutbound(ctx, nil, server.URL, time.Second); err == nil {
		t.Fatal("accepted nil outbound")
	}
}
