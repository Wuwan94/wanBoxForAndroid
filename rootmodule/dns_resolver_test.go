package module

import (
	"encoding/json"
	"net"
	"os"
	"strings"
	"testing"
)

type dnsResolverFixture struct {
	Spec                RootDNSResolversSpec `json:"spec"`
	ExpectedServers     []map[string]any     `json:"expectedServers"`
	ExpectedTargetRules []map[string]any     `json:"expectedTargetRules"`
}

func loadDNSResolverFixture(t *testing.T) dnsResolverFixture {
	t.Helper()
	b, err := os.ReadFile("testdata/root_dns_resolvers.json")
	if err != nil {
		t.Fatal(err)
	}
	var f dnsResolverFixture
	if err := json.Unmarshal(b, &f); err != nil {
		t.Fatal(err)
	}
	return f
}

func TestRootDNSResolverGenerationMatchesSharedFixtureAndPreservesOrder(t *testing.T) {
	f := loadDNSResolverFixture(t)
	local := map[string]any{"type": "local", "tag": "dns-local", "detour": "direct"}
	directStrategy, ok := rootDNSDomainStrategy(f.Spec.DirectDomainStrategy, f.Spec.IPv6Mode, false)
	if !ok {
		t.Fatal("direct DNS domain strategy not generated")
	}
	remoteStrategy, ok := rootDNSDomainStrategy(f.Spec.RemoteDomainStrategy, f.Spec.IPv6Mode, true)
	if !ok {
		t.Fatal("remote DNS domain strategy not generated")
	}
	direct, ok := buildRootDNSServer(firstDNSAddress(f.Spec.Direct, "https://223.5.5.5/dns-query"), "dns-direct", "direct", "dns-local", directStrategy, false)
	if !ok {
		t.Fatal("direct DNS server not generated")
	}
	remote, ok := buildRootDNSServer(firstDNSAddress(f.Spec.Remote, "https://dns.google/dns-query"), "dns-remote", f.Spec.ProxyTag, "dns-direct", remoteStrategy, true)
	if !ok {
		t.Fatal("remote DNS server not generated")
	}
	gotServers := []map[string]any{local, direct, remote}
	if !sameJSON(gotServers, f.ExpectedServers) {
		t.Fatalf("DNS servers differ from shared fixture\n got: %#v\nwant: %#v", gotServers, f.ExpectedServers)
	}
	gotRules, ok := buildRootDNSRouteRules(&f.Spec)
	if !ok {
		t.Fatal("DNS target rules not generated")
	}
	if !sameJSON(gotRules, f.ExpectedTargetRules) {
		t.Fatalf("target rules differ from shared fixture\n got: %#v\nwant: %#v", gotRules, f.ExpectedTargetRules)
	}

	prefix := []any{
		map[string]any{"inbound": []any{"mixed"}, "domain": []any{"probe.example"}, "outbound": "proxy"},
		map[string]any{"action": "sniff"},
		map[string]any{"action": "resolve", "strategy": "prefer_ipv4"},
		map[string]any{"port": []any{float64(53)}, "action": "hijack-dns"},
		map[string]any{"protocol": []any{"dns"}, "action": "hijack-dns"},
		map[string]any{"ip_version": float64(6), "action": "reject"},
	}
	rules := append([]any{}, prefix...)
	for _, rule := range f.ExpectedTargetRules {
		rules = append(rules, rule)
	}
	rules = append(rules,
		map[string]any{"port": []any{float64(53)}, "outbound": "proxy"},
		map[string]any{"protocol": []any{"dns"}, "outbound": "proxy"},
		map[string]any{"domain": []any{"user.example"}, "outbound": "direct"},
	)
	config := map[string]any{
		"dns":       map[string]any{"servers": []any{local, direct, remote}},
		"outbounds": []any{map[string]any{"type": "selector", "tag": "proxy"}},
		"route":     map[string]any{"rules": rules},
	}
	original, _ := json.Marshal(config)
	materializeRootDNSResolvers(config, &f.Spec, Snapshot{RootProxyGroup: &RootProxyGroupSpec{Tag: "proxy"}})
	encoded, _ := json.Marshal(config)
	if !sameJSON(config["dns"].(map[string]any)["servers"].([]any)[:3], f.ExpectedServers) {
		t.Fatal("materialized server block differs from the shared fixture")
	}
	if len(encoded) == 0 || len(original) == 0 {
		t.Fatal("could not encode generated config")
	}
	wantRules := append([]any{}, prefix...)
	for _, rule := range f.ExpectedTargetRules {
		wantRules = append(wantRules, rule)
	}
	wantRules = append(wantRules,
		map[string]any{"port": []any{float64(53)}, "outbound": "proxy"},
		map[string]any{"protocol": []any{"dns"}, "outbound": "proxy"},
		map[string]any{"domain": []any{"user.example"}, "outbound": "direct"},
	)
	if !sameJSON(config["route"].(map[string]any)["rules"], wantRules) {
		t.Fatal("target rules moved relative to earlier projections, fallback guards, or user routes")
	}
}

func TestRootDNSResolverDefaultsAndCommentedLists(t *testing.T) {
	if got := firstDNSAddress([]string{"", "  # ignored", "  ", " https://first.example/dns "}, "fallback"); got != "https://first.example/dns" {
		t.Fatalf("first usable entry = %q", got)
	}
	if got := firstDNSAddress([]string{"", "# only comment"}, "https://fallback.example/dns"); got != "https://fallback.example/dns" {
		t.Fatalf("fallback = %q", got)
	}
	spec := &RootDNSResolversSpec{Direct: []string{"", "# direct"}, Remote: []string{"# remote"}, IPv6Mode: 0, ProxyTag: "proxy"}
	direct, ok := buildRootDNSServer(firstDNSAddress(spec.Direct, "https://223.5.5.5/dns-query"), "dns-direct", "direct", "dns-local", "", false)
	if !ok || direct["server"] != "223.5.5.5" {
		t.Fatalf("direct default = %#v, %v", direct, ok)
	}
	remote, ok := buildRootDNSServer(firstDNSAddress(spec.Remote, "https://dns.google/dns-query"), "dns-remote", "proxy", "dns-direct", "", true)
	if !ok || remote["server"] != "dns.google" {
		t.Fatalf("remote default = %#v, %v", remote, ok)
	}
	rules, ok := buildRootDNSRouteRules(spec)
	if !ok || len(rules) != 4 {
		t.Fatalf("built-in route guards = %#v, %v", rules, ok)
	}

}

func TestRootDNSAddressSchemesAndMalformedFallback(t *testing.T) {
	cases := []struct {
		raw, kind string
		port      int
	}{
		{"https://dns.example/path", "https", 443}, {"http://dns.example:8080/dns", "https", 8080},
		{"h3://dns.example:8443/dns", "h3", 8443}, {"tls://dns.example", "tls", 853},
		{"quic://dns.example:8853", "quic", 8853}, {"tcp://dns.example", "tcp", 53},
		{"udp://dns.example:5353", "udp", 5353}, {"[2001:db8::1]:5353", "udp", 5353},
		{"hosts", "local", 0}, {"local", "local", 0},
	}
	for _, tc := range cases {
		got, ok := parseDNSAddress(tc.raw, false)
		if !ok || got.kind != tc.kind || got.port != tc.port {
			t.Errorf("parseDNSAddress(%q) = %#v, %v", tc.raw, got, ok)
		}
	}
	for _, raw := range []string{"ftp://dns.example", "https://user@dns.example/query", "https://dns.example/?q=x", "[2001:db8::1", "dns.example:0", "https://bad host/dns"} {
		if _, ok := parseDNSAddress(raw, false); ok {
			t.Errorf("accepted malformed/unsupported endpoint %q", raw)
		}
	}
	for _, raw := range []string{"hosts", "local", "localhost", "fakeip"} {
		got, ok := parseDNSAddress(raw, true)
		if !ok || got.kind != "https" || got.host != "8.8.8.8" {
			t.Errorf("remote normalization %q = %#v, %v", raw, got, ok)
		}
	}
}

func TestRootDNSResolverTargetDeduplicatesWithoutChangingOrder(t *testing.T) {
	domains, ips, ok := dnsTargets([]string{
		"first.example", "192.0.2.9", "FIRST.example", "192.0.2.9", "second.example",
	}, false, 1)
	if !ok {
		t.Fatal("valid duplicate resolver list rejected")
	}
	if !sameJSON(domains[len(domains)-2:], []string{"first.example", "second.example"}) {
		t.Fatalf("domain dedup/order = %#v", domains)
	}
	if got := ips[len(ips)-1]; got != "192.0.2.9/32" {
		t.Fatalf("IP dedup/order = %#v", ips)
	}
}

func TestRootDNSResolverIPv6FilteringAndOverlap(t *testing.T) {
	for _, tc := range []struct {
		mode         int
		hasV4, hasV6 bool
	}{{0, true, false}, {1, true, true}, {3, false, true}} {
		domains, direct, ok := dnsTargets([]string{"192.0.2.8", "2001:db8::8"}, false, tc.mode)
		if !ok {
			t.Fatalf("targets mode %d", tc.mode)
		}
		joined := strings.Join(direct, " ")
		_ = domains
		if strings.Contains(joined, "192.0.2.8") != tc.hasV4 || strings.Contains(joined, "2001:db8::8") != tc.hasV6 {
			t.Errorf("mode %d IPv4/IPv6 filtering wrong: %q", tc.mode, joined)
		}
	}
	spec := &RootDNSResolversSpec{Direct: []string{"https://shared.example/dns", "direct.example"}, Remote: []string{"https://shared.example/dns", "remote.example"}, IPv6Mode: 1, ProxyTag: "chosen-proxy"}
	rules, ok := buildRootDNSRouteRules(spec)
	if !ok {
		t.Fatal("target generation failed")
	}
	if strings.Contains(strings.Join(rules[0]["domain"].([]string), " "), "shared.example") {
		t.Fatal("overlapping domain was not excluded from direct targets")
	}
	if rules[2]["outbound"] != "chosen-proxy" {
		t.Fatalf("remote targets did not use selected proxy: %#v", rules[2])
	}
}

func TestRootDNSDomainStrategyUsesRawPreferenceAndIPv6Mode(t *testing.T) {
	cases := []struct {
		name, preference string
		mode             int
		proxied          bool
		want             string
	}{
		{"auto ipv4", "auto", 1, false, "prefer_ipv4"},
		{"auto prefer ipv6 direct", "auto", 2, false, "prefer_ipv6"},
		{"auto prefer ipv6 proxy", "auto", 2, true, "prefer_ipv4"},
		{"explicit overrides mode", "prefer_ipv6", 1, false, "prefer_ipv6"},
		{"disabled overrides explicit", "prefer_ipv6", 0, true, "ipv4_only"},
		{"only overrides explicit", "prefer_ipv4", 3, false, "ipv6_only"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, ok := rootDNSDomainStrategy(tc.preference, tc.mode, tc.proxied)
			if !ok || got != tc.want {
				t.Fatalf("rootDNSDomainStrategy(%q, %d, %v) = %q, %v; want %q", tc.preference, tc.mode, tc.proxied, got, ok, tc.want)
			}
		})
	}
	if _, ok := rootDNSDomainStrategy("surprise", 1, false); ok {
		t.Fatal("unsupported saved strategy accepted")
	}
}

func TestRootDNSResolverAllOrNoneOnMalformedDuplicatesAndAmbiguousOrder(t *testing.T) {
	f := loadDNSResolverFixture(t)
	local, direct, remote := f.ExpectedServers[0], f.ExpectedServers[1], f.ExpectedServers[2]
	targets := make([]any, 0, len(f.ExpectedTargetRules))
	for _, r := range f.ExpectedTargetRules {
		targets = append(targets, r)
	}
	fallback := []any{map[string]any{"port": []any{float64(53)}, "outbound": "proxy"}, map[string]any{"protocol": []any{"dns"}, "outbound": "proxy"}}
	newConfig := func() map[string]any {
		rules := []any{map[string]any{"action": "sniff"}}
		rules = append(rules, targets...)
		rules = append(rules, fallback...)
		return map[string]any{"dns": map[string]any{"servers": []any{local, direct, remote}}, "outbounds": []any{map[string]any{"tag": "proxy"}}, "route": map[string]any{"rules": rules}}
	}
	cases := map[string]func(map[string]any, *RootDNSResolversSpec){
		"unsupported endpoint": func(_ map[string]any, s *RootDNSResolversSpec) { s.Direct = []string{"ftp://bad.example"} },
		"malformed endpoint":   func(_ map[string]any, s *RootDNSResolversSpec) { s.Direct = []string{"https://bad host/dns"} },
		"malformed preference": func(_ map[string]any, s *RootDNSResolversSpec) { s.RemoteDomainStrategy = "automatic" },
		"duplicate DNS server": func(c map[string]any, _ *RootDNSResolversSpec) {
			d := c["dns"].(map[string]any)
			d["servers"] = append(d["servers"].([]any), direct)
		},
		"duplicate target rule": func(c map[string]any, _ *RootDNSResolversSpec) {
			r := c["route"].(map[string]any)
			a := r["rules"].([]any)
			r["rules"] = append(a, targets[0])
		},
		"nonadjacent fallback": func(c map[string]any, _ *RootDNSResolversSpec) {
			r := c["route"].(map[string]any)
			a := r["rules"].([]any)
			r["rules"] = append(a[:len(a)-1], append([]any{map[string]any{"action": "reject"}}, a[len(a)-1:]...)...)
		},
		"wrong selected proxy": func(c map[string]any, s *RootDNSResolversSpec) { s.ProxyTag = "absent" },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			cfg := newConfig()
			spec := f.Spec
			mutate(cfg, &spec)
			before, _ := json.Marshal(cfg)
			materializeRootDNSResolvers(cfg, &spec, Snapshot{RootProxyGroup: &RootProxyGroupSpec{Tag: "proxy"}})
			after, _ := json.Marshal(cfg)
			if string(before) != string(after) {
				t.Fatal("invalid/ambiguous input was partially rewritten")
			}
		})
	}
}

func TestDNSResolverTargetParsingDoesNotInventAddresses(t *testing.T) {
	for _, raw := range []string{"https://dns.example/path", "tls://dns.example:853", "udp://192.0.2.53:5353", "[2001:db8::53]:5353", "local", "hosts", "fakeip", "# comment", ""} {
		if _, ok := dnsTargetHost(raw); !ok {
			t.Errorf("valid resolver target %q rejected", raw)
		}
	}
	for _, raw := range []string{"https://bad host/path", "https://user@dns.example", "not a host"} {
		if _, ok := parseDNSAddress(raw, false); ok {
			t.Errorf("malformed endpoint %q accepted", raw)
		}
	}
	if net.ParseIP("2001:db8::53") == nil {
		t.Fatal("test's IPv6 literal is invalid")
	}
}
