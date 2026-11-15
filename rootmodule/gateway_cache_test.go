package module

import (
	"net"
	"reflect"
	"testing"
	"time"
)

func TestGatewayCacheReturnsIndependentGateways(t *testing.T) {
	for _, path := range []string{"refresh", "hit"} {
		t.Run(path, func(t *testing.T) {
			var c gatewayCache
			now := time.Unix(100, 0)
			o := observation("192.0.2.9")
			discover := func() map[string][]net.IP {
				return map[string][]net.IP{
					"wlan0":  {net.ParseIP("192.0.2.1"), net.ParseIP("fe80::1")},
					"rmnet0": {net.ParseIP("198.51.100.1")},
				}
			}
			want := discover()
			returned := c.read(o, now, discover)
			if path == "hit" {
				returned = c.read(o, now.Add(time.Second), discover)
			}
			assertUnchanged := func(stage string) {
				t.Helper()
				got := c.read(o, now.Add(2*time.Second), func() map[string][]net.IP {
					t.Fatal("unexpected discovery within TTL")
					return nil
				})
				if !reflect.DeepEqual(got, want) {
					t.Errorf("%s: got %v, want %v", stage, got, want)
				}
			}
			returned["other"] = []net.IP{net.ParseIP("203.0.113.1")}
			delete(returned, "rmnet0")
			assertUnchanged("map keys")
			returned["wlan0"][0] = net.ParseIP("203.0.113.2")
			assertUnchanged("slice member")
			returned["wlan0"][1][15] = 2
			assertUnchanged("IP bytes")
		})
	}
}
