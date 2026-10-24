package module

import (
	"encoding/json"
	"net"
	"net/url"
	"strconv"
	"strings"
)

// RootDNSResolversSpec carries raw resolver lists, user domain-strategy preferences,
// IPv6 mode, and the selected proxy tag. The module independently builds the
// direct/remote DNS servers and ordered route guards. Legacy JSON remains a safe
// fallback unless every generated object matches.
type RootDNSResolversSpec struct {
	Direct               []string `json:"direct"`
	Remote               []string `json:"remote"`
	IPv6Mode             int      `json:"ipv6Mode"`
	DirectDomainStrategy string   `json:"directDomainStrategy,omitempty"`
	RemoteDomainStrategy string   `json:"remoteDomainStrategy,omitempty"`
	ProxyTag             string   `json:"proxyTag"`
}

type dnsAddress struct {
	kind, host, path string
	port             int
}

func parseDNSAddress(raw string, remote bool) (dnsAddress, bool) {
	a := strings.TrimSpace(raw)
	if a == "" {
		return dnsAddress{}, false
	}
	if a == "hosts" || a == "local" {
		if remote {
			a = "https://8.8.8.8/dns-query"
		} else {
			return dnsAddress{kind: "local"}, true
		}
	}
	if remote && (a == "localhost" || a == "fakeip") {
		a = "https://8.8.8.8/dns-query"
	}
	for _, scheme := range []string{"https", "http", "h3", "tls", "quic", "tcp"} {
		prefix := scheme + "://"
		if !strings.HasPrefix(strings.ToLower(a), prefix) {
			continue
		}
		u, err := url.Parse(a)
		if err != nil || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || !validDNSHost(u.Hostname()) {
			return dnsAddress{}, false
		}
		port := 0
		if u.Port() != "" {
			port, err = strconv.Atoi(u.Port())
			if err != nil || port < 1 || port > 65535 {
				return dnsAddress{}, false
			}
		}
		path := u.EscapedPath()
		if scheme == "https" || scheme == "http" || scheme == "h3" {
			if path == "" {
				path = "/dns-query"
			}
		} else if path != "" && path != "/" {
			return dnsAddress{}, false
		}
		if (scheme == "https" || scheme == "http" || scheme == "h3") && port == 0 {
			port = 443
		}
		if (scheme == "tls" || scheme == "quic") && port == 0 {
			port = 853
		}
		if scheme == "tcp" && port == 0 {
			port = 53
		}
		// ConfigBuilder intentionally serializes both HTTP and HTTPS resolver
		// URLs as sing-box's "https" type (and defaults either to port 443).
		kind := scheme
		if scheme == "http" {
			kind = "https"
		}
		return dnsAddress{kind: kind, host: u.Hostname(), port: port, path: path}, true
	}
	if strings.Contains(a, "://") && !strings.HasPrefix(a, "udp://") {
		return dnsAddress{}, false
	}
	rawHost := a
	if strings.HasPrefix(a, "udp://") {
		rawHost = strings.TrimPrefix(a, "udp://")
	}
	host, port := rawHost, 53
	if strings.HasPrefix(rawHost, "[") {
		end := strings.IndexByte(rawHost, ']')
		if end < 0 {
			return dnsAddress{}, false
		}
		host = rawHost[1:end]
		after := rawHost[end+1:]
		if after != "" {
			if !strings.HasPrefix(after, ":") || len(after) == 1 {
				return dnsAddress{}, false
			}
			n, err := strconv.Atoi(after[1:])
			if err != nil || n < 1 || n > 65535 {
				return dnsAddress{}, false
			}
			port = n
		}
	} else if strings.Count(rawHost, ":") == 1 {
		parts := strings.SplitN(rawHost, ":", 2)
		host = parts[0]
		n, err := strconv.Atoi(parts[1])
		if err != nil || n < 1 || n > 65535 {
			return dnsAddress{}, false
		}
		port = n
	} else if strings.Contains(rawHost, ":") && net.ParseIP(rawHost) == nil {
		return dnsAddress{}, false
	}
	if !validDNSHost(host) {
		return dnsAddress{}, false
	}
	return dnsAddress{kind: "udp", host: host, port: port}, true
}

func validDNSHost(host string) bool {
	if net.ParseIP(strings.Trim(host, "[]")) != nil {
		return true
	}
	if host == "" || len(host) > 253 || strings.ContainsAny(host, " /\\\t\r\n") {
		return false
	}
	for _, label := range strings.Split(host, ".") {
		if label == "" || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, r := range label {
			if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-' || r == '_') {
				return false
			}
		}
	}
	return true
}

func firstDNSAddress(list []string, fallback string) string {
	for _, v := range list {
		v = strings.TrimSpace(v)
		if v != "" && !strings.HasPrefix(v, "#") {
			return v
		}
	}
	return fallback
}

func buildRootDNSServer(raw, tag, detour, resolver, strategy string, remote bool) (map[string]any, bool) {
	addr, ok := parseDNSAddress(raw, remote)
	if !ok {
		return nil, false
	}
	server := map[string]any{"type": addr.kind, "tag": tag, "detour": detour}
	if addr.kind == "local" {
		return server, true
	}
	server["server"] = addr.host
	server["server_port"] = addr.port
	if addr.kind == "https" || addr.kind == "http" || addr.kind == "h3" {
		server["path"] = addr.path
	}
	if net.ParseIP(strings.Trim(addr.host, "[]")) == nil {
		server["domain_resolver"] = resolver
		if strategy != "" {
			server["domain_strategy"] = strategy
		}
	}
	return server, true
}

type orderedDNSSet struct {
	values []string
	seen   map[string]bool
}

func (s *orderedDNSSet) add(v string) {
	if !s.seen[v] {
		s.seen[v] = true
		s.values = append(s.values, v)
	}
}

var directDNSDomains = []string{"dns.alidns.com", "doh.pub", "dot.pub", "doh.360.cn", "dot.360.cn"}
var directDNSIPs = []string{"223.5.5.5", "223.6.6.6", "119.29.29.29", "1.12.12.12", "120.53.53.53", "114.114.114.114", "114.114.115.115", "180.76.76.76", "1.2.4.8", "210.2.4.8", "2400:3200::1", "2400:3200:baba::1"}
var remoteDNSDomains = []string{"dns.google", "cloudflare-dns.com", "one.one.one.one", "dns.quad9.net", "dns.opendns.com"}
var remoteDNSIPs = []string{"8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112", "208.67.222.222", "208.67.220.220", "2001:4860:4860::8888", "2001:4860:4860::8844", "2606:4700:4700::1111", "2606:4700:4700::1001", "2620:fe::fe", "2620:fe::9", "2620:119:35::35", "2620:119:53::53"}

func dnsTargetHost(raw string) (string, bool) {
	v := strings.TrimSpace(raw)
	if v == "" || strings.HasPrefix(v, "#") || v == "local" || v == "hosts" || v == "fakeip" {
		return "", true
	}
	var host string
	if strings.Contains(v, "://") {
		u, err := url.Parse(v)
		if err != nil || u.Hostname() == "" {
			return "", false
		}
		host = u.Hostname()
	} else {
		rawNoPath := strings.SplitN(v, "/", 2)[0]
		if strings.HasPrefix(rawNoPath, "[") {
			end := strings.IndexByte(rawNoPath, ']')
			if end < 0 {
				return "", false
			}
			host = rawNoPath[1:end]
		} else if strings.Count(rawNoPath, ":") == 1 {
			host = strings.SplitN(rawNoPath, ":", 2)[0]
		} else {
			host = rawNoPath
		}
	}
	host = strings.ToLower(strings.Trim(host, "[]"))
	if !validDNSHost(host) {
		return "", false
	}
	return host, true
}

func dnsTargets(list []string, remote bool, mode int) ([]string, []string, bool) {
	domains := orderedDNSSet{seen: map[string]bool{}}
	ips := orderedDNSSet{seen: map[string]bool{}}
	builtinDomains, builtinIPs := directDNSDomains, directDNSIPs
	if remote {
		builtinDomains, builtinIPs = remoteDNSDomains, remoteDNSIPs
	}
	for _, v := range builtinDomains {
		domains.add(v)
	}
	for _, v := range builtinIPs {
		ips.add(v)
	}
	for _, raw := range list {
		trimmed := strings.TrimSpace(raw)
		if trimmed == "" || strings.HasPrefix(trimmed, "#") {
			continue
		}
		if _, ok := parseDNSAddress(trimmed, remote); !ok {
			return nil, nil, false
		}
		host, ok := dnsTargetHost(trimmed)
		if !ok {
			return nil, nil, false
		}
		if host == "" {
			continue
		}
		if net.ParseIP(host) != nil {
			ips.add(host)
		} else {
			domains.add(host)
		}
	}
	cidrs := make([]string, 0, len(ips.values))
	for _, ip := range ips.values {
		parsed := net.ParseIP(ip)
		if parsed == nil {
			return nil, nil, false
		}
		isV6 := strings.Contains(ip, ":")
		if mode == 0 && isV6 || mode == 3 && !isV6 {
			continue
		}
		if isV6 {
			cidrs = append(cidrs, ip+"/128")
		} else {
			cidrs = append(cidrs, ip+"/32")
		}
	}
	return domains.values, cidrs, true
}

func buildRootDNSRouteRules(spec *RootDNSResolversSpec) ([]map[string]any, bool) {
	directDomains, directIPs, ok := dnsTargets(spec.Direct, false, spec.IPv6Mode)
	if !ok {
		return nil, false
	}
	remoteDomains, remoteIPs, ok := dnsTargets(spec.Remote, true, spec.IPv6Mode)
	if !ok {
		return nil, false
	}
	remoteDomainSet := map[string]bool{}
	for _, v := range remoteDomains {
		remoteDomainSet[v] = true
	}
	filtered := make([]string, 0, len(directDomains))
	for _, v := range directDomains {
		if !remoteDomainSet[v] {
			filtered = append(filtered, v)
		}
	}
	rules := make([]map[string]any, 0, 4)
	add := func(kind string, targets []string, outbound string) {
		if len(targets) > 0 {
			rules = append(rules, map[string]any{kind: targets, "outbound": outbound})
		}
	}
	add("domain", filtered, "direct")
	add("ip_cidr", directIPs, "direct")
	add("domain", remoteDomains, spec.ProxyTag)
	add("ip_cidr", remoteIPs, spec.ProxyTag)
	return rules, true
}

func sameJSON(a, b any) bool {
	x, e1 := json.Marshal(a)
	y, e2 := json.Marshal(b)
	return e1 == nil && e2 == nil && string(x) == string(y)
}

// materializeRootDNSResolvers is deliberately all-or-none. Existing legacy
// objects are a fallback; the module replaces them only when its independently
// generated full server+route structures exactly match the App's source config.
func materializeRootDNSResolvers(config map[string]any, spec *RootDNSResolversSpec, snapshot Snapshot) {
	if spec == nil || spec.IPv6Mode < 0 || spec.IPv6Mode > 3 || spec.ProxyTag == "" || len(spec.Direct) > 256 || len(spec.Remote) > 256 {
		return
	}
	if !hasRootOutboundTag(config, snapshot, spec.ProxyTag) {
		return
	}
	directStrategy, ok := rootDNSDomainStrategy(spec.DirectDomainStrategy, spec.IPv6Mode, false)
	if !ok {
		return
	}
	remoteStrategy, ok := rootDNSDomainStrategy(spec.RemoteDomainStrategy, spec.IPv6Mode, true)
	if !ok {
		return
	}
	directRaw := firstDNSAddress(spec.Direct, "https://223.5.5.5/dns-query")
	remoteRaw := firstDNSAddress(spec.Remote, "https://dns.google/dns-query")
	local := map[string]any{"type": "local", "tag": "dns-local", "detour": "direct"}
	direct, ok := buildRootDNSServer(directRaw, "dns-direct", "direct", "dns-local", directStrategy, false)
	if !ok {
		return
	}
	remote, ok := buildRootDNSServer(remoteRaw, "dns-remote", spec.ProxyTag, "dns-direct", remoteStrategy, true)
	if !ok {
		return
	}
	dns, ok := config["dns"].(map[string]any)
	if !ok {
		return
	}
	servers, ok := dns["servers"].([]any)
	if !ok {
		return
	}
	wanted := map[string]map[string]any{"dns-local": local, "dns-direct": direct, "dns-remote": remote}
	positions := map[string]int{}
	for i, raw := range servers {
		m, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		tag, ok := m["tag"].(string)
		if ok {
			if _, yes := wanted[tag]; yes {
				if _, dup := positions[tag]; dup {
					return
				}
				positions[tag] = i
			}
		}
	}
	if len(positions) != 3 {
		return
	}
	for tag, want := range wanted {
		if !sameJSON(servers[positions[tag]], want) {
			return
		}
	}
	route, ok := config["route"].(map[string]any)
	if !ok {
		return
	}
	rules, ok := route["rules"].([]any)
	if !ok {
		return
	}
	generated, ok := buildRootDNSRouteRules(spec)
	if !ok {
		return
	}
	fallbackIndexes := make([]int, 0, 1)
	for i := 0; i+1 < len(rules); i++ {
		if sameJSON(rules[i], map[string]any{"port": []any{float64(53)}, "outbound": spec.ProxyTag}) && sameJSON(rules[i+1], map[string]any{"protocol": []any{"dns"}, "outbound": spec.ProxyTag}) {
			fallbackIndexes = append(fallbackIndexes, i)
		}
	}
	if len(fallbackIndexes) != 1 {
		return
	}
	fi := fallbackIndexes[0]
	if fi < len(generated) {
		return
	}
	start := fi - len(generated)
	for i, want := range generated {
		if !sameJSON(rules[start+i], want) {
			return
		}
		matches := 0
		for _, candidate := range rules {
			if sameJSON(candidate, want) {
				matches++
			}
		}
		if matches != 1 {
			return
		}
	}
	// Everything matched, so replace the three generated server values and route block
	// with module-built objects. The byte-for-byte JSON comparison above guarantees
	// unknown or user-edited legacy entries remain untouched.
	for tag, want := range wanted {
		servers[positions[tag]] = want
	}
	for i, want := range generated {
		rules[start+i] = want
	}
}

// rootDNSDomainStrategy mirrors ConfigBuilder's policy using the raw saved
// preference rather than serializing its precomputed JSON result.
func rootDNSDomainStrategy(preference string, mode int, proxied bool) (string, bool) {
	if mode < 0 || mode > 3 || len(preference) > 32 {
		return "", false
	}
	preference = strings.ReplaceAll(preference, "auto", "")
	switch preference {
	case "", "ipv4_only", "ipv6_only", "prefer_ipv4", "prefer_ipv6":
	default:
		return "", false
	}
	if mode == 0 {
		return "ipv4_only", true
	}
	if mode == 3 {
		return "ipv6_only", true
	}
	if preference != "" {
		return preference, true
	}
	switch mode {
	case 1:
		return "prefer_ipv4", true
	case 2:
		if proxied {
			return "prefer_ipv4", true
		}
		return "prefer_ipv6", true
	default:
		return "", false
	}
}
