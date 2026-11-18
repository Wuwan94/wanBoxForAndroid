package module

import (
	"errors"
	"net"
	"strings"
	"testing"
)

func TestCheckSharedConfig(t *testing.T) {
	// 默认：关闭，dns_mode=hijack，data_plane=socket_assign。
	cfg, err := CheckSharedConfig(nil)
	if err != nil || cfg.Enabled || cfg.DNSMode != "hijack" || cfg.DataPlane != "socket_assign" || cfg.DefaultInterface || cfg.UDPTimeout != "5m" {
		t.Fatalf("defaults wrong: %+v %v", cfg, err)
	}
	// 开启但无下游接口：拒绝。
	if _, err := CheckSharedConfig([]byte(`{"enabled":true,"interface":[]}`)); err == nil {
		t.Fatal("enabled without downstream interface must be rejected")
	}
	// loopback 不得入选。
	if _, err := CheckSharedConfig([]byte(`{"enabled":true,"interface":["lo"]}`)); err == nil {
		t.Fatal("loopback must be rejected")
	}
	// 合法开启配置。
	cfg, err = CheckSharedConfig([]byte(`{"enabled":true,"interface":["wlan1","rndis0"],"data_plane":"packet_rewrite","dns_mode":"hijack","default_interface":false,"udp_timeout":"30s","include_source_cidr":["192.168.43.0/24"],"exclude_mac_address":["02:11:22:33:44:55"]}`))
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Interface) != 2 || cfg.DataPlane != "packet_rewrite" {
		t.Fatalf("valid config parsed wrong: %+v", cfg)
	}
	for _, b := range []string{
		`{"enabled":true,"interface":["wlan1"],"data_plane":"tc"}`,
		`{"enabled":true,"interface":["wlan1"],"dns_mode":"forward"}`,
		`{"enabled":true,"interface":["wlan1"],"udp_timeout":"1s"}`,
		`{"enabled":true,"interface":["wlan1"],"udp_timeout":"soon"}`,
		`{"enabled":true,"interface":["wlan1"],"include_source_cidr":["not-a-cidr"]}`,
		`{"enabled":true,"interface":["wlan1"],"exclude_mac_address":["zz:zz"]}`,
		`{"enabled":true,"interface":["wlan1","wlan1"]}`,
		`{"enabled":true,"interface":["wlan1;rm"]}`,
		`{"enabled":true,"interface":["wlan1"],"unknown_field":1}`,
	} {
		if _, err := CheckSharedConfig([]byte(b)); err == nil {
			t.Fatalf("accepted invalid shared config %s", b)
		}
	}
	// udp_timeout 下限 5s：恰好 5s 通过。
	if _, err := CheckSharedConfig([]byte(`{"enabled":true,"interface":["wlan1"],"udp_timeout":"5s"}`)); err != nil {
		t.Fatalf("5s must pass: %v", err)
	}
}

func TestEbpfPreflight(t *testing.T) {
	env := &EbpfProbeEnv{Stat: func(path string) (bool, bool) {
		switch path {
		case "/sys/fs/bpf":
			return true, true
		case "/sys/kernel/btf/vmlinux":
			return true, false
		}
		return false, false
	}}
	if r := EbpfPreflight("socket_assign", env); !r.OK {
		t.Fatalf("complete env must pass: %+v", r)
	}
	missingBTF := &EbpfProbeEnv{Stat: func(path string) (bool, bool) {
		return path == "/sys/fs/bpf", path == "/sys/fs/bpf"
	}}
	r := EbpfPreflight("packet_rewrite", missingBTF)
	if r.OK || len(r.Missing) != 1 || r.Missing[0] != "btf" {
		t.Fatalf("missing BTF must fail with btf: %+v", r)
	}
	if r := EbpfPreflight("tc", env); r.OK {
		t.Fatalf("unknown data plane must fail: %+v", r)
	}
	// 预检失败不得自动切换数据平面：门禁只报错。
	cfg, _ := CheckSharedConfig([]byte(`{"enabled":true,"interface":["wlan1"],"data_plane":"socket_assign"}`))
	if err := CheckSharedPreflight(cfg, missingBTF); err == nil || !strings.Contains(err.Error(), "shared_preflight_failed") {
		t.Fatalf("preflight failure must block with a clear error: %v", err)
	}
	// 未启用不预检。
	off, _ := CheckSharedConfig(nil)
	if err := CheckSharedPreflight(off, missingBTF); err != nil {
		t.Fatalf("disabled shared path must not preflight: %v", err)
	}
}

func TestListDownstreamInterfaces(t *testing.T) {
	old := listSystemInterfaces
	defer func() { listSystemInterfaces = old }()
	listSystemInterfaces = func() ([]net.Interface, error) {
		return []net.Interface{
			{Name: "lo", Flags: net.FlagUp | net.FlagLoopback},
			{Name: "wlan0", Flags: net.FlagUp},
			{Name: "wlan1", Flags: net.FlagUp},
			{Name: "rmnet0", Flags: 0}, // down: not a candidate
			{Name: "tun0", Flags: net.FlagUp},
		}, nil
	}
	// 接口地址由真实系统读取，CI 环境下结果不可断言具体名字；
	// 本测试只断言 loopback 永不入选且函数不报错。
	names, err := ListDownstreamInterfaces()
	if err != nil {
		t.Fatal(err)
	}
	for _, n := range names {
		if n == "lo" {
			t.Fatal("loopback must never be listed")
		}
	}
}

func TestSharedAttachmentDecision(t *testing.T) {
	cfg, _ := CheckSharedConfig([]byte(`{"enabled":true,"interface":["wlan1","rndis0"],"default_interface":false}`))
	d := SharedAttachmentDecision(cfg, "wlan1")
	if d["wlan1"] != "paused" || d["rndis0"] != "attached" {
		t.Fatalf("default upstream must pause, downstream must attach: %v", d)
	}
	// 接口恢复为下游后重新挂载。
	if d := SharedAttachmentDecision(cfg, "rmnet0"); d["wlan1"] != "attached" {
		t.Fatalf("recovered downstream must re-attach: %v", d)
	}
	// default_interface=true 时即使是默认上游也保持挂载。
	cfg2, _ := CheckSharedConfig([]byte(`{"enabled":true,"interface":["wlan1"],"default_interface":true}`))
	if d := SharedAttachmentDecision(cfg2, "wlan1"); d["wlan1"] != "attached" {
		t.Fatalf("default_interface=true must keep attached: %v", d)
	}
	// 未启用无裁决。
	off, _ := CheckSharedConfig(nil)
	if d := SharedAttachmentDecision(off, "wlan1"); len(d) != 0 {
		t.Fatalf("disabled must yield no decision: %v", d)
	}
}

func TestSharedSourceAllowed(t *testing.T) {
	cfg, _ := CheckSharedConfig([]byte(`{"enabled":true,"interface":["wlan1"],"include_source_cidr":["192.168.43.0/24"],"exclude_source_cidr":["192.168.43.128/25"],"exclude_mac_address":["02:11:22:33:44:55"]}`))
	if !SharedSourceAllowed(cfg, net.ParseIP("192.168.43.10"), "") {
		t.Fatal("included CIDR must be allowed")
	}
	// 排除优先于包含。
	if SharedSourceAllowed(cfg, net.ParseIP("192.168.43.200"), "") {
		t.Fatal("excluded CIDR must win over include")
	}
	if SharedSourceAllowed(cfg, net.ParseIP("192.168.43.10"), "02:11:22:33:44:55") {
		t.Fatal("excluded MAC must win over include")
	}
	if SharedSourceAllowed(cfg, net.ParseIP("10.0.0.1"), "") {
		t.Fatal("outside include CIDR must be denied")
	}
	// 无包含名单时默认允许（仅排除生效）。
	open, _ := CheckSharedConfig([]byte(`{"enabled":true,"interface":["wlan1"],"exclude_mac_address":["02:11:22:33:44:55"]}`))
	if !SharedSourceAllowed(open, net.ParseIP("10.0.0.1"), "") || SharedSourceAllowed(open, net.ParseIP("10.0.0.1"), "02:11:22:33:44:55") {
		t.Fatal("open filter must allow all except excluded MAC")
	}
}

func TestParsePerUserAppRef(t *testing.T) {
	u, pkg, err := ParsePerUserAppRef("10:com.example.app")
	if err != nil || u != 10 || pkg != "com.example.app" {
		t.Fatalf("valid ref: %d %s %v", u, pkg, err)
	}
	for _, bad := range []string{"com.example.app", "0:", ":com.example.app", "99999:com.example.app", "-1:com.example.app", "0:Com.Example", "0:com.example.app:extra", "0:1com.example"} {
		if _, _, err := ParsePerUserAppRef(bad); err == nil {
			t.Fatalf("accepted invalid ref %q", bad)
		}
	}
}

func TestResolvePerUserAppUIDs(t *testing.T) {
	query := func(userID int, pkg string) (int, error) {
		if pkg == "com.example.app" {
			return 100000*userID + 10123, nil
		}
		return 0, errors.New("per_user_app_not_found")
	}
	uids, missing, err := ResolvePerUserAppUIDs([]string{"0:com.example.app", "10:com.example.app", "10:com.missing.app"}, query)
	if err != nil {
		t.Fatal(err)
	}
	if len(uids) != 2 || uids[0] != 10123 || uids[1] != 1010123 {
		t.Fatalf("same package under two users must resolve to two UIDs: %v", uids)
	}
	if len(missing) != 1 || missing[0] != "10:com.missing.app" {
		t.Fatalf("missing must be reported, not dropped silently: %v", missing)
	}
	// UID 0 永不自动加入。
	zero := func(userID int, pkg string) (int, error) { return 0, nil }
	uids, _, err = ResolvePerUserAppUIDs([]string{"0:com.example.app"}, zero)
	if err != nil || len(uids) != 0 {
		t.Fatalf("UID 0 must never be auto-added: %v %v", uids, err)
	}
	// 非法引用报错。
	if _, _, err := ResolvePerUserAppUIDs([]string{"com.example.app"}, query); err == nil {
		t.Fatal("bare package must be rejected")
	}
}

func TestEmptyWhitelistGuard(t *testing.T) {
	// 白名单开启但解析后与原生 include 均为空 → 护栏生效。
	if !EmptyWhitelistGuard(true, false, nil, nil, nil) {
		t.Fatal("empty whitelist must trigger the guard")
	}
	if EmptyWhitelistGuard(true, false, []int{10123}, nil, nil) {
		t.Fatal("non-empty whitelist must not trigger the guard")
	}
	if EmptyWhitelistGuard(true, false, nil, []int{10123}, nil) {
		t.Fatal("native include UID must not trigger the guard")
	}
	if EmptyWhitelistGuard(true, false, nil, nil, []string{"10000:20000"}) {
		t.Fatal("native include range must not trigger the guard")
	}
	if EmptyWhitelistGuard(true, true, nil, nil, nil) {
		t.Fatal("blacklist mode must not trigger the guard")
	}
	if EmptyWhitelistGuard(false, false, nil, nil, nil) {
		t.Fatal("per-app disabled must not trigger the guard")
	}
	if ExcludeAllUIDRange != "0:4294967294" {
		t.Fatalf("guard range changed: %s", ExcludeAllUIDRange)
	}
}

func TestMigrateLegacyIndividual(t *testing.T) {
	reverse := func(uid int) (string, bool) {
		if uid == 10123 {
			return "com.example.app", true
		}
		return "", false
	}
	migrated, retained := MigrateLegacyIndividual([]string{
		"com.example.app", "10123", "10999", "10:com.clone.app", "com.example.app", "", "bad uid!!",
	}, reverse)
	// com.example.app → 0:com.example.app；10123 反查同一包（去重）；
	// 10999 无法反查保留；10:com.clone.app 原样保留；非法行保留待确认。
	if len(migrated) != 2 || migrated[0] != "0:com.example.app" || migrated[1] != "10:com.clone.app" {
		t.Fatalf("migrated wrong: %v", migrated)
	}
	if len(retained) != 2 || retained[0] != "10999" || retained[1] != "bad uid!!" {
		t.Fatalf("unresolvable must be retained: %v", retained)
	}
	// 无反查函数时数字 UID 一律保留。
	_, retained = MigrateLegacyIndividual([]string{"10123"}, nil)
	if len(retained) != 1 || retained[0] != "10123" {
		t.Fatalf("without reverse lookup UID must be retained: %v", retained)
	}
}
