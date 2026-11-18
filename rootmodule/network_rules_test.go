package module

import (
	"errors"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func obsWith(ssid, egress string, ssidKnown bool, gateways map[string][]net.IP, addrs map[string][]net.IP) NetworkObservation {
	return NetworkObservation{
		Interfaces: addrs,
		Gateways:   gateways,
		SSID:       ssid,
		SSIDKnown:  ssidKnown,
		Egress:     egress,
	}
}

func TestNetworkPolicyValidation(t *testing.T) {
	user := 0
	valid := `{"enabled":true,"rules":[{"action":"suspend","ssid":"Office Wi-Fi, 5G"}],"appPackage":"io.nekohasekai.sagernet","userId":0}`
	if _, err := CheckNetworkPolicy([]byte(valid)); err != nil {
		t.Fatal(err)
	}
	_ = user
	for _, b := range []string{
		`{"enabled":true,"rules":[{"action":"pause","ssid":"x"}],"appPackage":"io.nekohasekai.sagernet","userId":0}`,
		`{"enabled":true,"rules":[{"action":"suspend"}],"appPackage":"io.nekohasekai.sagernet","userId":0}`,
		`{"enabled":true,"rules":[{"action":"direct","egress":"cellular"}],"appPackage":"io.nekohasekai.sagernet","userId":0}`,
		`{"enabled":true,"rules":[{"action":"direct","cidr":"host.example"}],"appPackage":"io.nekohasekai.sagernet","userId":0}`,
		`{"enabled":true,"rules":[{"action":"direct","gateway":"192.0.2.1/99"}],"appPackage":"io.nekohasekai.sagernet","userId":0}`,
		`{"enabled":true,"rules":[{"action":"keep","ssid":"a"},{"action":"keep","ssid":"b"},{"action":"keep","ssid":"c"},{"action":"keep","ssid":"d"},{"action":"keep","ssid":"e"},{"action":"keep","ssid":"f"}],"appPackage":"io.nekohasekai.sagernet","userId":0}`,
		`{"enabled":true,"rules":[],"appPackage":"io.nekohasekai.sagernet"}`,
		`{"enabled":true,"rules":[],"userId":0}`,
		`{"enabled":true,"rules":[{"action":"keep","ssid":"a` + "\x01" + `b"}],"appPackage":"io.nekohasekai.sagernet","userId":0}`,
		`{"enabled":true,"rules":[],"appPackage":"io.nekohasekai.sagernet","userId":0,"unknown":1}`,
	} {
		if _, err := CheckNetworkPolicy([]byte(b)); err == nil {
			t.Fatalf("accepted invalid policy %q", b)
		}
	}
	if p, err := CheckNetworkPolicy(nil); err != nil || p.Rules == nil {
		t.Fatalf("empty policy must parse to empty rules: %v", err)
	}
}

func TestNetworkRuleMatching(t *testing.T) {
	policy := NetworkPolicy{Enabled: true, Rules: []NetworkRule{
		{Action: NetworkActionDirect, SSID: "Office Wi-Fi, 5G"},
		{Action: NetworkActionSuspend, SSID: "Office Wi-Fi, 5G"},
		{Action: NetworkActionKeep, Egress: "not_wifi"},
		{Action: NetworkActionSuspend, Gateway: "192.0.2.1"},
		{Action: NetworkActionSuspend, CIDR: "198.51.100.0/24"},
	}}
	wifi := obsWith("Office Wi-Fi, 5G", "wifi", true,
		map[string][]net.IP{"wlan0": {net.ParseIP("192.0.2.1")}},
		map[string][]net.IP{"wlan0": {net.ParseIP("192.0.2.9")}})
	d := EvaluateNetworkPolicy(policy, wifi)
	if d.Action != NetworkActionDirect || d.MatchedIndex != 0 {
		t.Fatalf("first matching rule must win (direct): %+v", d)
	}
	// SSID 精确：大小写/空格/逗号任一不同都不匹配，首条失配后次条也不应命中。
	d = EvaluateNetworkPolicy(policy, obsWith("office wi-fi, 5g", "wifi", true, nil,
		map[string][]net.IP{"wlan0": {net.ParseIP("192.0.2.9")}}))
	if d.Action != "" || d.MatchedIndex != -1 {
		t.Fatalf("SSID must match exactly: %+v", d)
	}
	// 空 SSID 不匹配任何 SSID 规则。
	d = EvaluateNetworkPolicy(policy, obsWith("", "wifi", false, nil,
		map[string][]net.IP{"wlan0": {net.ParseIP("192.0.2.9")}}))
	if d.Action != "" {
		t.Fatalf("empty SSID must not match: %+v", d)
	}
	// 非 Wi-Fi 出口命中 keep（第三条）。
	d = EvaluateNetworkPolicy(policy, obsWith("", "not_wifi", false,
		map[string][]net.IP{"rmnet0": {net.ParseIP("10.0.0.2")}},
		map[string][]net.IP{"rmnet0": {net.ParseIP("10.0.0.2")}}))
	if d.Action != NetworkActionKeep || d.MatchedIndex != 2 {
		t.Fatalf("not_wifi must match keep rule: %+v", d)
	}
	// 网关精确命中（第四条）；SSID/egress 均不相关（出口未知时 not_wifi 规则不抢跑）。
	d = EvaluateNetworkPolicy(policy, obsWith("", "", false,
		map[string][]net.IP{"eth0": {net.ParseIP("192.0.2.1")}},
		map[string][]net.IP{"eth0": {net.ParseIP("203.0.113.5")}}))
	if d.Action != NetworkActionSuspend || d.MatchedIndex != 3 {
		t.Fatalf("gateway must match: %+v", d)
	}
	// CIDR 命中本地地址（第五条）。
	d = EvaluateNetworkPolicy(policy, obsWith("", "", false, nil,
		map[string][]net.IP{"eth0": {net.ParseIP("198.51.100.9")}}))
	if d.Action != NetworkActionSuspend || d.MatchedIndex != 4 {
		t.Fatalf("CIDR must match local address: %+v", d)
	}
	// 无匹配保持默认行为。
	d = EvaluateNetworkPolicy(policy, obsWith("Home", "wifi", true, nil,
		map[string][]net.IP{"wlan0": {net.ParseIP("192.168.0.9")}}))
	if d.Action != "" || d.MatchedIndex != -1 {
		t.Fatalf("no match must keep default behaviour: %+v", d)
	}
	// 功能关闭时不评估。
	off := policy
	off.Enabled = false
	if d := EvaluateNetworkPolicy(off, wifi); d.Action != "" {
		t.Fatalf("disabled policy must not act: %+v", d)
	}
}

func TestNetworkRuleUnknownNeverGuesses(t *testing.T) {
	policy := NetworkPolicy{Enabled: true, Rules: []NetworkRule{
		{Action: NetworkActionDirect, Egress: "not_wifi"},
		{Action: NetworkActionSuspend, SSID: "Office"},
	}}
	// 指纹读取失败：保持当前状态。
	bad := obsWith("", "", false, nil, map[string][]net.IP{"wlan0": {net.ParseIP("192.0.2.9")}})
	bad.ReadErr = errors.New("network_ssid_unreadable")
	if d := EvaluateNetworkPolicy(policy, bad); d.Known || d.Action != "" {
		t.Fatalf("read failure must keep state: %+v", d)
	}
	// 未知出口：不得当作非 Wi-Fi，not_wifi 规则不命中。
	unknown := obsWith("", "", false, nil, map[string][]net.IP{"wlan0": {net.ParseIP("192.0.2.9")}})
	if d := EvaluateNetworkPolicy(policy, unknown); d.Action != "" {
		t.Fatalf("unknown network must not be treated as non-Wi-Fi: %+v", d)
	}
}

func TestNetworkRuleSuspendReusesSuppression(t *testing.T) {
	policy := NetworkPolicy{Enabled: true, Rules: []NetworkRule{
		{Action: NetworkActionSuspend, SSID: "Office"},
	}}
	o := obsWith("Office", "wifi", true, nil, map[string][]net.IP{"wlan0": {net.ParseIP("192.0.2.9")}})
	var suppressed string
	if d := networkPolicyDecision(policy, o, &suppressed); !d.Suspend || d.Action != "suspend" {
		t.Fatalf("suspend rule must suspend: %+v", d)
	}
	// 同 fingerprint 手动恢复后抑制重复挂起。
	suppressed = NetworkFingerprint(o)
	if d := networkPolicyDecision(policy, o, &suppressed); d.Suspend {
		t.Fatalf("same-fingerprint manual resume must suppress: %+v", d)
	}
	// 离开网络（fingerprint 变化）后抑制清除。
	other := obsWith("Home", "wifi", true, nil, map[string][]net.IP{"wlan0": {net.ParseIP("192.168.0.9")}})
	if d := networkPolicyDecision(policy, other, &suppressed); d.Suspend || suppressed != "" {
		t.Fatalf("network change must clear suppression: %+v suppressed=%q", d, suppressed)
	}
	// 组合匹配器：同一条内 AND，SSID 对但网关不对不命中。
	combo := NetworkPolicy{Enabled: true, Rules: []NetworkRule{
		{Action: NetworkActionDirect, SSID: "Office", Gateway: "192.0.2.1"},
	}}
	hit := obsWith("Office", "wifi", true,
		map[string][]net.IP{"wlan0": {net.ParseIP("192.0.2.1")}},
		map[string][]net.IP{"wlan0": {net.ParseIP("192.0.2.9")}})
	if d := EvaluateNetworkPolicy(combo, hit); d.Action != NetworkActionDirect {
		t.Fatalf("AND matchers must both match: %+v", d)
	}
	miss := obsWith("Office", "wifi", true,
		map[string][]net.IP{"wlan0": {net.ParseIP("192.0.2.254")}},
		map[string][]net.IP{"wlan0": {net.ParseIP("192.0.2.9")}})
	if d := EvaluateNetworkPolicy(combo, miss); d.Action != "" {
		t.Fatalf("AND matchers must not match on partial hit: %+v", d)
	}
}

func TestMigrateSmartAutoStopToNetworkPolicy(t *testing.T) {
	user := 10
	legacy, err := CheckSmartAutoStop([]byte(`{"enabled":true,"rules":["192.0.2.9; GATEWAY:2001:db8::/32"],"appPackage":"io.nekohasekai.sagernet","userId":10}`))
	if err != nil {
		t.Fatal(err)
	}
	p, err := MigrateSmartAutoStopToNetworkPolicy(legacy)
	if err != nil {
		t.Fatal(err)
	}
	if !p.Enabled || len(p.Rules) != 2 || *p.UserID != user {
		t.Fatalf("migration must preserve order and identity: %+v", p)
	}
	for _, r := range p.Rules {
		if r.Action != NetworkActionSuspend {
			t.Fatalf("migrated rules must be suspend: %+v", r)
		}
	}
	if p.Rules[0].CIDR != "192.0.2.9" || !strings.HasPrefix(p.Rules[1].Gateway, "2001:db8") {
		t.Fatalf("migrated matchers wrong: %+v", p.Rules)
	}
	// 迁移失败保留原数据：非法规则迁移返回错误，原对象不动。
	bad, _ := CheckSmartAutoStop([]byte(`{"enabled":true,"rules":["192.0.2.9"],"appPackage":"io.nekohasekai.sagernet","userId":10}`))
	bad.Rules = []string{"host.example"}
	if _, err := MigrateSmartAutoStopToNetworkPolicy(bad); err == nil {
		t.Fatal("invalid legacy rule must fail migration")
	}
	if len(bad.Rules) != 1 || bad.Rules[0] != "host.example" {
		t.Fatal("failed migration must not mutate the legacy policy")
	}
}

func TestReadUnifiedPolicyFallback(t *testing.T) {
	dir := t.TempDir()
	// 无文件：空策略。
	p, err := readUnifiedPolicy(dir)
	if err != nil || p.Enabled || len(p.Rules) != 0 {
		t.Fatalf("missing files must yield empty policy: %+v %v", p, err)
	}
	// 只有旧文件：回退迁移。
	if err := os.WriteFile(filepath.Join(dir, autoStopFile),
		[]byte(`{"enabled":true,"rules":["192.0.2.9"],"appPackage":"io.nekohasekai.sagernet","userId":0}`), 0600); err != nil {
		t.Fatal(err)
	}
	p, err = readUnifiedPolicy(dir)
	if err != nil || !p.Enabled || len(p.Rules) != 1 || p.Rules[0].Action != NetworkActionSuspend {
		t.Fatalf("legacy fallback must migrate: %+v %v", p, err)
	}
	// 新文件优先于旧文件。
	if err := os.WriteFile(filepath.Join(dir, networkRulesFile),
		[]byte(`{"enabled":true,"rules":[{"action":"direct","egress":"not_wifi"}],"appPackage":"io.nekohasekai.sagernet","userId":0}`), 0600); err != nil {
		t.Fatal(err)
	}
	p, err = readUnifiedPolicy(dir)
	if err != nil || len(p.Rules) != 1 || p.Rules[0].Action != NetworkActionDirect {
		t.Fatalf("new file must win: %+v %v", p, err)
	}
	// 新文件损坏：报错，不静默回退旧文件。
	if err := os.WriteFile(filepath.Join(dir, networkRulesFile), []byte(`{bad`), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := readUnifiedPolicy(dir); err == nil {
		t.Fatal("corrupt new file must fail, not silently fall back")
	}
}

func TestDefaultEgressAndSSIDReading(t *testing.T) {
	o := NetworkObservation{
		Interfaces: map[string][]net.IP{"wlan0": {net.ParseIP("192.0.2.9")}},
		Gateways:   map[string][]net.IP{"wlan0": {net.ParseIP("192.0.2.1")}},
	}
	if got := DefaultEgressInterface(o); got != "wlan0" {
		t.Fatalf("default egress: %q", got)
	}
	if got := DefaultEgressInterface(NetworkObservation{}); got != "" {
		t.Fatalf("no gateway must yield no egress: %q", got)
	}
	// 桩 SSID 读取：无线出口 + SSID 可读 → wifi；SSID 不可读 → 未知且 ReadErr。
	oldWireless, oldSSID := isWirelessInterface, readStationSSID
	defer func() { isWirelessInterface, readStationSSID = oldWireless, oldSSID }()
	isWirelessInterface = func(iface string) bool { return iface == "wlan0" }
	readStationSSID = func(iface string) (string, error) { return "Office Wi-Fi, 5G", nil }
	got := ReadNetworkFingerprint(o)
	if got.Egress != "wifi" || !got.SSIDKnown || got.SSID != "Office Wi-Fi, 5G" {
		t.Fatalf("wifi fingerprint: %+v", got)
	}
	readStationSSID = func(iface string) (string, error) { return "", errors.New("network_ssid_unreadable") }
	got = ReadNetworkFingerprint(o)
	if got.Egress != "" || got.SSIDKnown || got.ReadErr == nil {
		t.Fatalf("unreadable SSID must yield unknown: %+v", got)
	}
	// 非无线出口 → not_wifi，不读 SSID。
	isWirelessInterface = func(iface string) bool { return false }
	readStationSSID = func(iface string) (string, error) { return "", errors.New("must not be called") }
	got = ReadNetworkFingerprint(o)
	if got.Egress != "not_wifi" || got.SSIDKnown {
		t.Fatalf("wired egress must be not_wifi: %+v", got)
	}
}
