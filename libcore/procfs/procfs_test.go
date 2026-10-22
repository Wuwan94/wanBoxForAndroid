package procfs

import (
	"fmt"
	"net"
	"net/netip"
	"os"
	"strings"
	"testing"
)

func TestResolveSocketByProcSearchUDPWildcard(t *testing.T) {
	for _, tc := range []struct {
		name   string
		bind   string
		source netip.Addr
	}{
		{name: "ipv4-or-dual-stack", bind: "0.0.0.0:0", source: netip.MustParseAddr("127.0.0.1")},
		{name: "ipv6", bind: "[::]:0", source: netip.MustParseAddr("::1")},
	} {
		t.Run(tc.name, func(t *testing.T) {
			conn, err := net.ListenPacket("udp", tc.bind)
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()

			port := uint16(conn.LocalAddr().(*net.UDPAddr).Port)
			got := ResolveSocketByProcSearch("udp", netip.AddrPortFrom(tc.source, port), netip.AddrPort{})
			if got != int32(os.Getuid()) {
				t.Fatalf("owner UID=%d, want %d (socket bound at %s)", got, os.Getuid(), conn.LocalAddr())
			}
		})
	}
}

func TestResolveSocketOwnerPrefersExactAndRejectsAmbiguous(t *testing.T) {
	local := "0100007F:1234"
	wildcard := "00000000:1234"
	candidates := map[string]int{local: 4, wildcard: 2}

	t.Run("exact beats earlier wildcard row", func(t *testing.T) {
		input := procRow(wildcard, 2000) + "\n" + procRow(local, 1000) + "\n"
		uid, priority, ambiguous := resolveSocketOwner(strings.NewReader(input), candidates)
		if uid != 1000 || priority != 4 || ambiguous {
			t.Fatalf("got uid=%d priority=%d ambiguous=%v", uid, priority, ambiguous)
		}
	})

	t.Run("conflicting exact owners are not guessed", func(t *testing.T) {
		input := procRow(local, 1000) + "\n" + procRow(local, 1001) + "\n"
		uid, priority, ambiguous := resolveSocketOwner(strings.NewReader(input), candidates)
		if uid != 1000 || priority != 4 || !ambiguous {
			t.Fatalf("got uid=%d priority=%d ambiguous=%v", uid, priority, ambiguous)
		}
	})
}

func procRow(local string, uid int) string {
	length := netIndexOfUid + 1
	if netIndexOfLocal+1 > length {
		length = netIndexOfLocal + 1
	}
	fields := make([]string, length)
	for i := range fields {
		fields[i] = "x"
	}
	fields[netIndexOfLocal] = local
	fields[netIndexOfUid] = fmt.Sprint(uid)
	return strings.Join(fields, " ")
}
