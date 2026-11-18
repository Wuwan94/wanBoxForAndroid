// B6（N4）：eBPF 共享路径（热点 / USB 共享流量接管）。只接 eBPF 共享路径，
// 默认关闭；开启前 MUST 过 eBPF 能力预检，预检失败明确报错、不得自动切换
// 数据平面。模块不创建热点/DHCP/NAT/IPv6 RA/IP 转发。TUN 下共享接管明确
// 不支持（TUN 不建 local/shared 映射）。接口出现/消失/成为默认上游时的
// attachment 生命周期由 sing-box 管理；本文件只做配置校验、预检与
// default_interface=false 时的挂载裁决（供 supervisor 记录与诊断）。
package module

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"regexp"
	"sort"
	"strings"
	"time"
)

// sharedInterceptionFile 是 App 快照中共享接管配置的文件名。
const sharedInterceptionFile = "shared_interception.json"

// SharedConfig 是 eBPF 共享路径配置。字段名属 sing-box 原生入站 schema。
type SharedConfig struct {
	Enabled           bool     `json:"enabled"`
	Interface         []string `json:"interface"`
	DataPlane         string   `json:"data_plane"`
	DNSMode           string   `json:"dns_mode"`
	DefaultInterface  bool     `json:"default_interface"`
	UDPTimeout        string   `json:"udp_timeout"`
	IncludeSourceCIDR []string `json:"include_source_cidr,omitempty"`
	ExcludeSourceCIDR []string `json:"exclude_source_cidr,omitempty"`
	IncludeMACAddress []string `json:"include_mac_address,omitempty"`
	ExcludeMACAddress []string `json:"exclude_mac_address,omitempty"`
}

// SharedConfigDefaults 返回关闭态默认配置（dns_mode=hijack，
// data_plane=socket_assign，default_interface=false，udp_timeout=5m）。
func SharedConfigDefaults() SharedConfig {
	return SharedConfig{
		Enabled:          false,
		DataPlane:        "socket_assign",
		DNSMode:          "hijack",
		DefaultInterface: false,
		UDPTimeout:       "5m",
	}
}

var sharedInterfaceName = regexp.MustCompile(`^[a-zA-Z0-9_.-]{1,15}$`)

// CheckSharedConfig 校验共享接管配置 JSON。启用时 interface MUST 含至少一个
// 真实下游接口（不能填 loopback）；data_plane 只接受 socket_assign 或
// packet_rewrite；udp_timeout 下限 5s；CIDR/MAC 筛选格式 MUST 合法。
// 未启用时仍校验已填字段的合法性（Fail-Closed：垃圾配置不得静默入库）。
func CheckSharedConfig(b []byte) (SharedConfig, error) {
	cfg := SharedConfigDefaults()
	if len(b) == 0 {
		return cfg, nil
	}
	dec := json.NewDecoder(bytes.NewReader(b))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&cfg); err != nil {
		return cfg, errors.New("shared_config_invalid")
	}
	// 空值回填默认（与 sing-box 原生 schema 的默认值一致）。
	if cfg.DataPlane == "" {
		cfg.DataPlane = "socket_assign"
	}
	if cfg.DNSMode == "" {
		cfg.DNSMode = "hijack"
	}
	if cfg.UDPTimeout == "" {
		cfg.UDPTimeout = "5m"
	}
	if cfg.DataPlane != "socket_assign" && cfg.DataPlane != "packet_rewrite" {
		return cfg, errors.New("shared_data_plane_invalid")
	}
	if cfg.DNSMode != "hijack" && cfg.DNSMode != "respect_policy" {
		return cfg, errors.New("shared_dns_mode_invalid")
	}
	d, err := time.ParseDuration(cfg.UDPTimeout)
	if err != nil || d < 5*time.Second {
		return cfg, errors.New("shared_udp_timeout_invalid")
	}
	for _, cidr := range append(append([]string{}, cfg.IncludeSourceCIDR...), cfg.ExcludeSourceCIDR...) {
		if _, _, err := net.ParseCIDR(cidr); err != nil {
			if net.ParseIP(cidr) == nil {
				return cfg, errors.New("shared_cidr_invalid")
			}
		}
	}
	for _, mac := range append(append([]string{}, cfg.IncludeMACAddress...), cfg.ExcludeMACAddress...) {
		if _, err := net.ParseMAC(mac); err != nil {
			return cfg, errors.New("shared_mac_invalid")
		}
	}
	seen := map[string]bool{}
	for _, iface := range cfg.Interface {
		if !sharedInterfaceName.MatchString(iface) || iface == "lo" {
			return cfg, errors.New("shared_interface_invalid")
		}
		if seen[iface] {
			return cfg, errors.New("shared_interface_duplicate")
		}
		seen[iface] = true
	}
	if cfg.Enabled && len(cfg.Interface) == 0 {
		return cfg, errors.New("shared_interface_required")
	}
	return cfg, nil
}

func readSharedConfig(path string) (SharedConfig, error) {
	b, e := os.ReadFile(path)
	if os.IsNotExist(e) {
		return SharedConfigDefaults(), nil
	}
	if e != nil {
		return SharedConfig{}, e
	}
	return CheckSharedConfig(b)
}

// --- eBPF 能力预检 -------------------------------------------------------
// 预检只回答“本机是否具备所选数据平面的 eBPF 能力”，通过不得表述为实际
// 接管成功。预检失败 MUST 明确报错，不得自动切换数据平面。

// EbpfProbeEnv 是预检的注入接缝：默认读真实 /sys 与 /proc；测试注入假文件集。
type EbpfProbeEnv struct {
	// Stat 报告 path 是否存在且为目录（BPF fs 挂载点）或普通文件（BTF）。
	Stat func(path string) (exists bool, isDir bool)
}

func defaultEbpfProbeEnv() *EbpfProbeEnv {
	return &EbpfProbeEnv{Stat: func(path string) (bool, bool) {
		fi, err := os.Stat(path)
		if err != nil {
			return false, false
		}
		return true, fi.IsDir()
	}}
}

// EbpfPreflightReport 是预检的稳定报告结构。
type EbpfPreflightReport struct {
	OK      bool     `json:"ok"`
	Missing []string `json:"missing,omitempty"`
	Checked []string `json:"checked"`
}

// EbpfPreflight 检查所选数据平面的 eBPF 能力：BPF fs 已挂载、内核 BTF 可用。
// 不以 Android 版本或内核版本号代替能力判断（design.md §8）。
func EbpfPreflight(dataPlane string, env *EbpfProbeEnv) EbpfPreflightReport {
	report := EbpfPreflightReport{Checked: []string{"bpf_fs", "btf"}}
	if dataPlane != "socket_assign" && dataPlane != "packet_rewrite" {
		report.Missing = []string{"data_plane_invalid"}
		return report
	}
	if env == nil {
		env = defaultEbpfProbeEnv()
	}
	if ok, isDir := env.Stat("/sys/fs/bpf"); !ok || !isDir {
		report.Missing = append(report.Missing, "bpf_fs")
	}
	if ok, _ := env.Stat("/sys/kernel/btf/vmlinux"); !ok {
		report.Missing = append(report.Missing, "btf")
	}
	report.OK = len(report.Missing) == 0
	return report
}

// CheckSharedPreflight 是共享开启门禁：未启用时直接通过；启用时预检 MUST
// 通过，否则返回稳定错误 token，调用方 MUST 拒绝应用且不得切换数据平面。
func CheckSharedPreflight(cfg SharedConfig, env *EbpfProbeEnv) error {
	if !cfg.Enabled {
		return nil
	}
	report := EbpfPreflight(cfg.DataPlane, env)
	if !report.OK {
		return fmt.Errorf("shared_preflight_failed: %s", strings.Join(report.Missing, ","))
	}
	return nil
}

// --- 下游接口枚举 ---------------------------------------------------------

var listSystemInterfaces = func() ([]net.Interface, error) { return net.Interfaces() }

// ListDownstreamInterfaces 枚举真实下游接口候选：up、非 loopback、
// 含单播地址、接口名合法。返回排序后的接口名；loopback 永不入选。
// 候选是否可作共享接口仍由 CheckSharedConfig + 预检在应用时校验。
func ListDownstreamInterfaces() ([]string, error) {
	ifaces, err := listSystemInterfaces()
	if err != nil {
		return nil, errors.New("shared_interfaces_unreadable")
	}
	var out []string
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		if ignoredInterface(iface.Name) || !sharedInterfaceName.MatchString(iface.Name) {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		hasUnicast := false
		for _, a := range addrs {
			ip, _, _ := net.ParseCIDR(a.String())
			if ip != nil && !ip.IsLoopback() && !ip.IsUnspecified() && !ip.IsMulticast() {
				hasUnicast = true
				break
			}
		}
		if hasUnicast {
			out = append(out, iface.Name)
		}
	}
	sort.Strings(out)
	return out, nil
}

// --- 挂载裁决 -------------------------------------------------------------
// SharedAttachmentDecision 决定每个已配置共享接口的挂载状态。
// default_interface=false 时，已配置的共享接口成为默认上游后暂时停止共享
// 挂载（"paused"），恢复为下游后重新挂载（"attached"）；default_interface=true
// 时接口即使是默认上游也保持挂载。attachment 的实际建立/拆除由 sing-box
// 管理，本函数只做可测试的裁决，供 supervisor 记录与诊断。

func SharedAttachmentDecision(cfg SharedConfig, defaultEgress string) map[string]string {
	out := map[string]string{}
	if !cfg.Enabled {
		return out
	}
	for _, iface := range cfg.Interface {
		if !cfg.DefaultInterface && iface == defaultEgress && defaultEgress != "" {
			out[iface] = "paused"
		} else {
			out[iface] = "attached"
		}
	}
	return out
}

// --- 源筛选（排除优先） ----------------------------------------------------
// SharedSourceAllowed 按共享路径的源筛选判定一个下游源是否被接管：
// 排除（CIDR 或 MAC）优先于包含；包含名单非空时源 MUST 命中至少一项包含。

func SharedSourceAllowed(cfg SharedConfig, srcIP net.IP, srcMAC string) bool {
	for _, cidr := range cfg.ExcludeSourceCIDR {
		if cidrContains(cidr, srcIP) {
			return false
		}
	}
	for _, mac := range cfg.ExcludeMACAddress {
		if macEqual(mac, srcMAC) {
			return false
		}
	}
	if len(cfg.IncludeSourceCIDR) == 0 && len(cfg.IncludeMACAddress) == 0 {
		return true
	}
	for _, cidr := range cfg.IncludeSourceCIDR {
		if cidrContains(cidr, srcIP) {
			return true
		}
	}
	for _, mac := range cfg.IncludeMACAddress {
		if macEqual(mac, srcMAC) {
			return true
		}
	}
	return false
}

func cidrContains(cidr string, ip net.IP) bool {
	if ip == nil {
		return false
	}
	if _, network, err := net.ParseCIDR(cidr); err == nil {
		return network.Contains(ip)
	}
	return net.ParseIP(cidr) != nil && net.ParseIP(cidr).Equal(ip)
}

func macEqual(want, got string) bool {
	if want == "" || got == "" {
		return false
	}
	a, errA := net.ParseMAC(want)
	b, errB := net.ParseMAC(got)
	return errA == nil && errB == nil && a.String() == b.String()
}
