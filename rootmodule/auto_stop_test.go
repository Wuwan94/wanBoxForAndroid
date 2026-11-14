package module

import (
	"errors"
	"net"
	"strings"
	"testing"
	"time"
)

func TestSmartAutoStopSnapshotAndMatch(t *testing.T) {
	s, e := CheckSmartAutoStop([]byte(`{"enabled":true,"rules":["192.0.2.9; GATEWAY:2001:db8::/32"],"appPackage":"io.nekohasekai.sagernet","userId":10}`))
	if e != nil {
		t.Fatal(e)
	}
	o := NetworkObservation{Interfaces: map[string][]net.IP{"wlan0": {net.ParseIP("192.0.2.9")}}}
	if !AutoStopMatches(s, o) {
		t.Fatal("local address must match")
	}
	if AutoStopMatches(s, NetworkObservation{}) {
		t.Fatal("no physical network must not match")
	}
	for _, b := range []string{`{"enabled":true}`, `{"appPackage":"io.test --user 0"}`, `{"rules":["SSID:home"]}`, `{"rules":["1.1.1.1,2.2.2.2;3.3.3.3，4.4.4.4\n5.5.5.5,6.6.6.6"]}`} {
		if _, e := CheckSmartAutoStop([]byte(b)); e == nil {
			t.Fatalf("accepted invalid policy %s", b)
		}
	}
}

func TestAutoStopRulesAndBroadcast(t *testing.T) {
	for _, rule := range []string{"2001:db8::1", "192.0.2.0/24", "GATEWAY:2001:db8::/32"} {
		if _, err := parseAutoRule(rule); err != nil {
			t.Fatalf("%q: %v", rule, err)
		}
	}
	for _, rule := range []string{"host.example", "192.0.2.1/99"} {
		if _, err := parseAutoRule(rule); err == nil {
			t.Fatalf("accepted %q", rule)
		}
	}
	if !AutoStopSuppressed("same", "same") || AutoStopSuppressed("changed", "same") {
		t.Fatal("suppression must clear when fingerprint changes")
	}
	args, err := BuildAutoStopBroadcast("com.lit008834.pixel.wanboxforandroid", "suspended", 10)
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"/system/bin/am", "broadcast", "--user", "10", "-n", "com.lit008834.pixel.wanboxforandroid/io.nekohasekai.sagernet.bg.SmartAutoStopReceiver", "-a", "com.lit008834.pixel.wanboxforandroid.AUTO_STOP_STATE", "--es", "phase", "suspended"}
	if strings.Join(args, "\x00") != strings.Join(want, "\x00") {
		t.Fatalf("argv: %#v", args)
	}
}

func TestDefaultRouteObservation(t *testing.T) {
	for _, tc := range []struct {
		name, v4, v6, iface, gateway string
		match                        bool
	}{
		{"ipv4", "wlan0 00000000 010200C0 0003 0 0 0 00000000", "", "wlan0", "192.0.2.1", true},
		{"ipv6", "", "00000000000000000000000000000000 00 00000000000000000000000000000000 00 fe800000000000000000000000000001 00000001 00000000 00000000 00000003 wlan0", "wlan0", "fe80::1", true},
		{"other interface", "rmnet0 00000000 010200C0 0003 0 0 0 00000000", "", "wlan0", "192.0.2.1", false},
		{"no active interface", "wlan0 00000000 010200C0 0003 0 0 0 00000000", "", "", "192.0.2.1", false},
		{"ignored interface", "tun0 00000000 010200C0 0003 0 0 0 00000000", "", "tun0", "192.0.2.1", false},
		{"non default", "wlan0 000200C0 010200C0 0003 0 0 0 00FFFFFF", "", "wlan0", "192.0.2.1", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			o := NetworkObservation{Interfaces: map[string][]net.IP{}, Gateways: parseDefaultGateways([]byte(tc.v4), []byte(tc.v6))}
			if tc.iface != "" {
				o.Interfaces[tc.iface] = []net.IP{net.ParseIP("192.0.2.9")}
			}
			if got := AutoStopMatches(SmartAutoStop{Enabled: true, Rules: []string{"gateway:" + tc.gateway}}, o); got != tc.match {
				t.Fatalf("match=%v want %v", got, tc.match)
			}
		})
	}
}

func TestObservationFingerprintStableAndAssociated(t *testing.T) {
	a := NetworkObservation{Interfaces: map[string][]net.IP{"wlan0": {net.ParseIP("192.0.2.9"), net.ParseIP("2001:db8::2")}}, Gateways: map[string][]net.IP{"wlan0": {net.ParseIP("192.0.2.1"), net.ParseIP("fe80::1")}}}
	b := NetworkObservation{Interfaces: map[string][]net.IP{"wlan0": {net.ParseIP("2001:db8::2"), net.ParseIP("192.0.2.9"), net.ParseIP("192.0.2.9")}}, Gateways: map[string][]net.IP{"wlan0": {net.ParseIP("fe80::1"), net.ParseIP("192.0.2.1")}, "stale0": {net.ParseIP("198.51.100.1")}}}
	if NetworkFingerprint(a) != NetworkFingerprint(b) {
		t.Fatal("order, duplicates and inactive gateways must not change fingerprint")
	}
	b.Gateways = map[string][]net.IP{"other0": a.Gateways["wlan0"]}
	if NetworkFingerprint(a) == NetworkFingerprint(b) {
		t.Fatal("active gateway association must affect fingerprint")
	}
	fp := NetworkFingerprint(a)
	if len(fp) != 16 || strings.Contains(fp, "192.0.2") {
		t.Fatalf("fingerprint must be bounded one-way digest: %q", fp)
	}
}

func TestBroadcastRejectsUntrustedArguments(t *testing.T) {
	for _, phase := range []string{"suspended", "connected", "stopped", "failed", "disabled"} {
		args, err := BuildAutoStopBroadcast("io.test.app", phase, 10)
		if err != nil || len(args) != 11 || args[10] != phase {
			t.Fatalf("phase %q: %v %v", phase, args, err)
		}
	}
	for _, tc := range [][2]string{{"io.test.app;id", "suspended"}, {"io.test.app", "cleanup_required"}, {"io.test.app", "suspended --user 1"}, {"", "connected"}} {
		if _, err := BuildAutoStopBroadcast(tc[0], tc[1], 10); err == nil {
			t.Fatalf("accepted %v", tc)
		}
	}
}

func TestManualResumeMonitorSuppressionAndRearm(t *testing.T) {
	policy := SmartAutoStop{Enabled: true, Rules: []string{"192.0.2.0/24"}}
	observation := func(ip string) NetworkObservation {
		return NetworkObservation{Interfaces: map[string][]net.IP{"wlan0": {net.ParseIP(ip)}}}
	}
	same := observation("192.0.2.9")
	suppressed := NetworkFingerprint(same)
	for i := 0; i < 3; i++ {
		if got := autoStopMonitorFingerprint(policy, same, &suppressed); got.Suspend {
			t.Fatal("same network re-suspended after resume")
		}
	}
	autoStopMonitorFingerprint(policy, observation("198.51.100.9"), &suppressed)
	if suppressed != "" {
		t.Fatal("network change must clear suppression")
	}
	if got := autoStopMonitorFingerprint(policy, same, &suppressed); !got.Suspend || got.Fingerprint != NetworkFingerprint(same) {
		t.Fatal("return to matching network must re-arm")
	}
}

func TestGenerationCleanupFailureOverridesAutoStop(t *testing.T) {
	wait := make(chan error)
	err := finishGeneration(errAutoStop, []ProcessIdentity{{PID: 123}}, []chan error{wait}, func(ProcessIdentity) error { return nil }, func(ProcessIdentity) error { return nil }, time.Millisecond)
	if errors.Is(err, errAutoStop) || err == nil || err.Error() != "cleanup_timeout_no_restart" {
		t.Fatalf("unconfirmed helper cleanup must prevent suspension/resume: %v", err)
	}
	exited := make(chan error, 1)
	exited <- errors.New("signal: terminated")
	if got := finishGeneration(errAutoStop, []ProcessIdentity{{PID: 123}}, []chan error{exited}, func(ProcessIdentity) error { return nil }, func(ProcessIdentity) error { return nil }, time.Millisecond); !errors.Is(got, errAutoStop) {
		t.Fatalf("confirmed exit must preserve suspension: %v", got)
	}
}

func TestPolicyTableDefaultGateways(t *testing.T) {
	routes := `default via 192.0.2.1 dev wlan0 table 1024 proto static metric 10
 default via fe80::1 dev wlan0 table 1024 proto ra metric 1024 pref medium
 default via 198.51.100.1 dev rmnet_data0 table 1009
 default via 203.0.113.1 dev tun0 table 200
 192.0.2.0/24 via 192.0.2.254 dev wlan0 table 1024
 unreachable default metric 4278198272
 default dev wlan0 scope link
 default via invalid dev wlan0
 default via 0.0.0.0 dev wlan0
 default via :: dev wlan0
 default via 192.0.2.2
 default via 192.0.2.3 dev bad/name
 default via 192.0.2.4 dev wlan0 via 192.0.2.5
 `
	got := parseIPDefaultGateways([]byte(routes))
	if len(got) != 3 || len(got["wlan0"]) != 2 || !got["wlan0"][0].Equal(net.ParseIP("192.0.2.1")) || !got["wlan0"][1].Equal(net.ParseIP("fe80::1")) {
		t.Fatalf("unexpected policy gateways: %v", got)
	}
	o := NetworkObservation{Interfaces: map[string][]net.IP{"wlan0": {net.ParseIP("192.0.2.9")}, "tun0": {net.ParseIP("203.0.113.2")}}, Gateways: got}
	for _, tc := range []struct {
		rule string
		want bool
	}{
		{"gateway:192.0.2.1", true}, {"gateway:fe80::1", true},
		{"gateway:198.51.100.1", false}, {"gateway:203.0.113.1", false}, {"gateway:192.0.2.254", false},
	} {
		if match := AutoStopMatches(SmartAutoStop{Enabled: true, Rules: []string{tc.rule}}, o); match != tc.want {
			t.Errorf("%s matched=%v", tc.rule, match)
		}
	}
}

func TestPersistentSuppressionExpiresWithSharedDecision(t *testing.T) {
	r, _ := fixture(t)
	if err := r.init(); err != nil {
		t.Fatal(err)
	}
	policy := SmartAutoStop{Enabled: true, Rules: []string{"192.0.2.0/24"}}
	observation := func(ip string) NetworkObservation {
		return NetworkObservation{Interfaces: map[string][]net.IP{"wlan0": {net.ParseIP(ip)}}}
	}
	same := observation("192.0.2.9")
	fp := NetworkFingerprint(same)
	if err := AtomicWrite(r.path("runtime", suppressionFile), []byte(fp), 0600); err != nil {
		t.Fatal(err)
	}
	suppressed := r.readSuppression()
	if r.autoStopDecision(policy, same, &suppressed).Suspend {
		t.Fatal("restored suppression ignored")
	}
	r.autoStopDecision(policy, observation("198.51.100.9"), &suppressed)
	if r.readSuppression() != "" {
		t.Fatal("expired marker persisted")
	}
	if !r.autoStopDecision(policy, same, &suppressed).Suspend {
		t.Fatal("return to matching network must rearm")
	}
}

func TestEnabledAutoStopRequiresAndroidUser(t *testing.T) {
	for _, user := range []string{"", `,"userId":null`, `,"userId":-1`, `,"userId":21475`, `,"userId":"10;id"`, `,"userId":1.5`} {
		if _, err := CheckSmartAutoStop([]byte(`{"enabled":true,"appPackage":"io.test.app"` + user + `}`)); err == nil {
			t.Errorf("accepted invalid or missing Android user: %s", user)
		}
	}
	for _, policy := range []string{`{}`, `{"enabled":false,"appPackage":"io.test.app"}`, `{"enabled":true,"appPackage":"io.test.app","userId":0}`, `{"enabled":true,"appPackage":"io.test.app","userId":10}`} {
		if _, err := CheckSmartAutoStop([]byte(policy)); err != nil {
			t.Errorf("compatible snapshot: %s: %v", policy, err)
		}
	}
}

func TestPhysicalLinkLocalObservationMatches(t *testing.T) {
	for _, tc := range []struct{ address, exact, cidr, gateway string }{
		{"169.254.12.34/16", "169.254.12.34", "169.254.0.0/16", "169.254.12.1"},
		{"fe80::1234/64", "fe80::1234", "fe80::/64", "fe80::1"},
	} {
		o := NetworkObservation{Interfaces: map[string][]net.IP{"wlan0": physicalUnicastAddresses([]string{tc.address, "127.0.0.1/8", "::/128", "ff02::1/16", "224.0.0.1/4"})}, Gateways: map[string][]net.IP{"wlan0": {net.ParseIP(tc.gateway)}}}
		if len(o.Interfaces["wlan0"]) != 1 {
			t.Fatalf("expected one usable address: %v", o.Interfaces)
		}
		for _, rule := range []string{tc.exact, tc.cidr, "gateway:" + tc.gateway} {
			if !AutoStopMatches(SmartAutoStop{Enabled: true, Rules: []string{rule}}, o) {
				t.Fatalf("link-local observation must match %s", rule)
			}
		}
		o.Interfaces["tun0"] = o.Interfaces["wlan0"]
		delete(o.Interfaces, "wlan0")
		if AutoStopMatches(SmartAutoStop{Enabled: true, Rules: []string{tc.exact}}, o) {
			t.Fatal("ignored interface matched")
		}
	}
}
