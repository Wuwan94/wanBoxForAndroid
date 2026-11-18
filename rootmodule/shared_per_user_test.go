package module

import (
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
