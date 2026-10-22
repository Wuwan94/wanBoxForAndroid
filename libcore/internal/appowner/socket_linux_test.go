//go:build linux

// @author 雾晚
package appowner

import (
	"context"
	"net"
	"net/netip"
	"os"
	"testing"

	"github.com/sagernet/sing-box/common/process"
	"github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
	"libcore/procfs"
)

// TCP uses the core socket_diag searcher; Android UDP uses the production procfs path. @author 雾晚
func TestRootSocketOwner(t *testing.T) {
	searcher, err := process.NewSearcher(process.Config{Logger: log.NewNOPFactory().NewLogger("test")})
	if err != nil {
		t.Fatal(err)
	}
	defer searcher.Close()
	for _, tc := range []struct {
		name      string
		tcpAddr   string
		udpAddr   string
		packetSrc netip.Addr
	}{
		{name: "ipv4", tcpAddr: "127.0.0.1:0", udpAddr: "0.0.0.0:0", packetSrc: netip.MustParseAddr("127.0.0.1")},
		{name: "ipv6", tcpAddr: "[::1]:0", udpAddr: "[::]:0", packetSrc: netip.MustParseAddr("::1")},
	} {
		t.Run(tc.name, func(t *testing.T) {
			listener, err := net.Listen("tcp", tc.tcpAddr)
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()
			conn, err := net.Dial("tcp", listener.Addr().String())
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			owner, err := searcher.FindProcessInfo(context.Background(), "tcp", M.AddrPortFromNet(conn.LocalAddr()), M.AddrPortFromNet(conn.RemoteAddr()))
			if err != nil || owner == nil || owner.UserId != int32(os.Getuid()) {
				t.Fatalf("TCP owner=%v error=%v", owner, err)
			}
			// Android Root mode resolves owners from /proc/net. A wildcard-bound UDP
			// socket's packet source is the selected interface address, not 0.0.0.0/::.
			udp, err := net.ListenPacket("udp", tc.udpAddr)
			if err != nil {
				t.Fatal(err)
			}
			defer udp.Close()
			port := uint16(udp.LocalAddr().(*net.UDPAddr).Port)
			source := netip.AddrPortFrom(tc.packetSrc, port)
			uid := procfs.ResolveSocketByProcSearch("udp", source, netip.AddrPort{})
			if uid != int32(os.Getuid()) {
				t.Fatalf("UDP owner UID=%d want=%d", uid, os.Getuid())
			}
		})
	}
}
