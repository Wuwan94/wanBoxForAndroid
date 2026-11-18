// B6（N3）：统一「按网络」规则。F1 智能启停与按网络选策略合并为一套规则表，
// 单指纹源（SSID + 实际出口接口 + 默认网关 + 本地地址集合），首条匹配胜出，
// suspend/direct/keep 三动作互斥。F1 的挂起状态机、通知、抑制与 Fail-Closed
// 屏障全部复用（见 auto_stop.go），本文件只新增统一规则模型、SSID/出口读取
// 与裁决，不重写 F1。
package module

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"time"
)

// networkRulesFile 是 App 快照中统一规则表的文件名；旧 smart_auto_stop.json
// 仍被读取为回退（见 readNetworkPolicy），不写新文件。
const networkRulesFile = "network_rules.json"

// NetworkRuleAction 是单条规则的动作，三选一且互斥。
type NetworkRuleAction string

const (
	NetworkActionSuspend NetworkRuleAction = "suspend"
	NetworkActionDirect  NetworkRuleAction = "direct"
	NetworkActionKeep    NetworkRuleAction = "keep"
)

// NetworkRule 是一条统一按网络规则：匹配器内 AND，规则间 OR，按顺序首条胜出。
// SSID 精确保留空格/大小写/逗号；空 SSID 永不匹配。Gateway 与 CIDR 沿用 F1
// 的 parseAutoRule 语义（精确 IP 或 CIDR）；Egress 为实际默认出口类型。
type NetworkRule struct {
	Action  NetworkRuleAction `json:"action"`
	SSID    string            `json:"ssid,omitempty"`
	Gateway string            `json:"gateway,omitempty"`
	CIDR    string            `json:"cidr,omitempty"`
	Egress  string            `json:"egress,omitempty"`
}

// NetworkPolicy 是统一按网络规则表。AppPackage/UserID 沿用 F1 的广播身份，
// 挂起通知仍只用固定 phase 值广播，不含任何网络标识。
type NetworkPolicy struct {
	Enabled    bool          `json:"enabled"`
	Rules      []NetworkRule `json:"rules"`
	AppPackage string        `json:"appPackage"`
	UserID     *int          `json:"userId"`
}

// broadcastIdentity 把统一规则表的广播身份（appPackage + userId）转为
// 旧广播函数接受的 SmartAutoStop；广播只用固定 phase 值，不含规则与网络标识。
func (p NetworkPolicy) broadcastIdentity() SmartAutoStop {
	return SmartAutoStop{AppPackage: p.AppPackage, UserID: p.UserID}
}

// CheckNetworkPolicy 校验统一规则表 JSON。规则上限仍为 5 条（F1 既有上限，
// 不放宽）；每条规则至少一个匹配器；动作只接受 suspend/direct/keep。
func CheckNetworkPolicy(b []byte) (NetworkPolicy, error) {
	var p NetworkPolicy
	if len(b) == 0 {
		return NetworkPolicy{Rules: []NetworkRule{}}, nil
	}
	dec := json.NewDecoder(bytes.NewReader(b))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&p); err != nil {
		return p, errors.New("network_rules_invalid")
	}
	if p.UserID != nil && (*p.UserID < 0 || *p.UserID > 21474) {
		return p, errors.New("network_rules_user_invalid")
	}
	if p.Enabled && p.UserID == nil {
		return p, errors.New("network_rules_user_required")
	}
	if p.Rules == nil {
		p.Rules = []NetworkRule{}
	}
	if len(p.Rules) > 5 {
		return p, errors.New("network_rules_too_many")
	}
	if p.AppPackage != "" && !androidPackage.MatchString(p.AppPackage) {
		return p, errors.New("network_rules_package_invalid")
	}
	if p.Enabled && p.AppPackage == "" {
		return p, errors.New("network_rules_package_required")
	}
	for i, r := range p.Rules {
		switch r.Action {
		case NetworkActionSuspend, NetworkActionDirect, NetworkActionKeep:
		default:
			return p, errors.New("network_rules_action_invalid")
		}
		if r.SSID == "" && r.Gateway == "" && r.CIDR == "" && r.Egress == "" {
			return p, errors.New("network_rules_matcher_required")
		}
		if r.Egress != "" && r.Egress != "wifi" && r.Egress != "not_wifi" {
			return p, errors.New("network_rules_egress_invalid")
		}
		for _, raw := range []string{r.Gateway, r.CIDR} {
			if raw == "" {
				continue
			}
			if _, err := parseAutoRule(raw); err != nil {
				return p, err
			}
		}
		// SSID 不做字符白名单：精确保留空格/大小写/逗号，只拒绝控制字符。
		for _, c := range r.SSID {
			if c < 0x20 || c == 0x7f {
				return p, errors.New("network_rules_ssid_invalid")
			}
		}
		p.Rules[i] = r
	}
	return p, nil
}

// MigrateSmartAutoStopToNetworkPolicy 把 F1 已保存规则迁移为 suspend 动作规则，
// 原顺序保留；F1 开关关闭时整表停用，规则本身不清空。迁移失败时调用方 MUST
// 保留原 F1 数据并报错（本函数只做纯转换，不写文件）。
func MigrateSmartAutoStopToNetworkPolicy(s SmartAutoStop) (NetworkPolicy, error) {
	p := NetworkPolicy{Enabled: s.Enabled, AppPackage: s.AppPackage, UserID: s.UserID}
	for _, raw := range s.Rules {
		r, err := parseAutoRule(raw)
		if err != nil {
			return NetworkPolicy{}, err
		}
		rule := NetworkRule{Action: NetworkActionSuspend}
		if r.Gateway {
			if r.Network != nil {
				rule.Gateway = r.Network.String()
			} else {
				rule.Gateway = r.IP.String()
			}
		} else {
			if r.Network != nil {
				rule.CIDR = r.Network.String()
			} else {
				rule.CIDR = r.IP.String()
			}
		}
		p.Rules = append(p.Rules, rule)
	}
	if len(p.Rules) > 5 {
		return NetworkPolicy{}, errors.New("network_rules_too_many")
	}
	return p, nil
}

func readNetworkPolicy(path string) (NetworkPolicy, error) {
	b, e := os.ReadFile(path)
	if os.IsNotExist(e) {
		return NetworkPolicy{}, nil
	}
	if e != nil {
		return NetworkPolicy{}, e
	}
	return CheckNetworkPolicy(b)
}

// readUnifiedPolicy 读统一规则表；新文件不存在时回退旧 F1 文件并按原顺序
// 迁移为 suspend 规则（旧快照仍可用，迁移失败保留原数据并报错）。
func readUnifiedPolicy(dir string) (NetworkPolicy, error) {
	p, err := readNetworkPolicy(dir + "/" + networkRulesFile)
	if err != nil {
		return NetworkPolicy{}, err
	}
	if p.Rules != nil || p.Enabled || p.AppPackage != "" {
		return p, nil
	}
	legacy, err := readAutoStop(dir + "/" + autoStopFile)
	if err != nil {
		return NetworkPolicy{}, err
	}
	if len(legacy.Rules) == 0 && !legacy.Enabled && legacy.AppPackage == "" {
		return NetworkPolicy{}, nil
	}
	return MigrateSmartAutoStopToNetworkPolicy(legacy)
}

// NetworkRuleDecision 是一次统一规则评估的结果。Known=false 表示指纹不可读
// （读取失败/SSID 不可读/网关解析失败）：调用方 MUST 保持当前状态并记录，
// 不得猜测切换；此时 Action 一律为空。
type NetworkRuleDecision struct {
	Action       NetworkRuleAction
	MatchedIndex int // -1 表示无规则匹配
	Known        bool
	Fingerprint  string
}

// networkRuleMatches 判断单条规则是否命中。SSID 为空字符串永不匹配任何 SSID
// 规则；未知出口（Egress==""）下含 ssid/egress 匹配器的规则不命中。
func networkRuleMatches(r NetworkRule, o NetworkObservation) bool {
	if r.SSID != "" {
		if !o.SSIDKnown || o.SSID == "" || o.SSID != r.SSID {
			return false
		}
	}
	if r.Egress != "" {
		if o.Egress == "" || o.Egress != r.Egress {
			return false
		}
	}
	if r.Gateway != "" {
		rule, err := parseAutoRule(r.Gateway)
		if err != nil {
			return false
		}
		if !autoRuleMatchesGateway(rule, o) {
			return false
		}
	}
	if r.CIDR != "" {
		rule, err := parseAutoRule(r.CIDR)
		if err != nil {
			return false
		}
		if !autoRuleMatchesAddresses(rule, o) {
			return false
		}
	}
	return true
}

func autoRuleMatchesGateway(r AutoStopRule, o NetworkObservation) bool {
	for name, addresses := range o.Interfaces {
		if ignoredInterface(name) || len(addresses) == 0 {
			continue
		}
		for _, ip := range o.Gateways[name] {
			if r.IP != nil && r.IP.Equal(ip) || (r.Network != nil && r.Network.Contains(ip)) {
				return true
			}
		}
	}
	return false
}

func autoRuleMatchesAddresses(r AutoStopRule, o NetworkObservation) bool {
	for name, addresses := range o.Interfaces {
		if ignoredInterface(name) || len(addresses) == 0 {
			continue
		}
		for _, ip := range addresses {
			if r.IP != nil && r.IP.Equal(ip) || (r.Network != nil && r.Network.Contains(ip)) {
				return true
			}
		}
	}
	return false
}

// EvaluateNetworkPolicy 按用户顺序评估统一规则表，首条匹配胜出；无匹配时
// Action 为空（保持当前默认行为）。新规则默认动作取 keep 的约定由 App 侧
// 落地（新建规则默认 keep），此处只裁决已保存的规则。
func EvaluateNetworkPolicy(p NetworkPolicy, o NetworkObservation) NetworkRuleDecision {
	d := NetworkRuleDecision{MatchedIndex: -1, Known: true, Fingerprint: NetworkFingerprint(o)}
	if o.ReadErr != nil {
		return NetworkRuleDecision{MatchedIndex: -1, Known: false, Fingerprint: d.Fingerprint}
	}
	if !p.Enabled {
		return d
	}
	for i, r := range p.Rules {
		if networkRuleMatches(r, o) {
			d.Action = r.Action
			d.MatchedIndex = i
			return d
		}
	}
	return d
}

// networkPolicyDecision 把统一裁决转为 monitor 用的 networkDecision：
// suspend 复用 F1 的 suppression 语义（同 fingerprint 手动恢复抑制重复挂起，
// 网络变化后清除抑制），但命中判定以统一规则为准——旧 F1 匹配器不懂
// SSID/egress，不得用它二次确认，否则 SSID 规则永不挂起。
func networkPolicyDecision(p NetworkPolicy, o NetworkObservation, suppressed *string) networkDecision {
	rule := EvaluateNetworkPolicy(p, o)
	if !rule.Known {
		return networkDecision{Fingerprint: rule.Fingerprint, Known: false}
	}
	d := networkDecision{Fingerprint: rule.Fingerprint, Action: string(rule.Action), Known: true}
	// 手动恢复抑制在首次网络变化时永久清除（与 F1 的 autoStopMonitorFingerprint
	// 一致），无论新网络是否命中规则。
	if *suppressed != "" && *suppressed != rule.Fingerprint {
		*suppressed = ""
	}
	if rule.Action != NetworkActionSuspend {
		return d
	}
	if !AutoStopSuppressed(rule.Fingerprint, *suppressed) {
		d.Suspend = true
	}
	return d
}

// policyNetworkDecision 是 supervisor/monitor 的统一规则入口（替代旧 F1 入口）。
// 抑制清除时与 F1 一致删除 suppression 文件。
func (r *Runtime) policyNetworkDecision(p NetworkPolicy, suppressed *string) networkDecision {
	if !p.Enabled {
		return networkDecision{Known: true}
	}
	before := *suppressed
	decision := networkPolicyDecision(p, r.physicalObservation(), suppressed)
	if before != "" && *suppressed == "" {
		_ = os.Remove(r.path("runtime", suppressionFile))
	}
	return decision
}

// monitorUnifiedNetworkDecision 供 health monitor 使用；Fingerprint 仍覆盖为
// health.go 的 networkFingerprint()（与既有变化检测一致）。
func (r *Runtime) monitorUnifiedNetworkDecision(p NetworkPolicy, suppressed *string) networkDecision {
	decision := r.policyNetworkDecision(p, suppressed)
	decision.Fingerprint = networkFingerprint()
	return decision
}

// --- SSID 与实际出口读取 -------------------------------------------------
// SSID 由 Root 侧按实际出口 station 读取，不经过 Android 定位 API。
// 读取路径：默认出口接口 → /sys/class/net/<iface>/phy80211 判定无线接口 →
// `iw dev <iface> link` 读 station SSID。任一步不可读时 SSID 未知（Fail-Closed：
// 未知网络不得当作非 Wi-Fi，含 ssid/egress 匹配器的规则不命中）。

var isWirelessInterface = func(iface string) bool {
	_, err := os.Stat("/sys/class/net/" + iface + "/phy80211")
	return err == nil
}

var readStationSSID = func(iface string) (string, error) {
	if !ifaceName.MatchString(iface) {
		return "", errors.New("network_ssid_iface_invalid")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "iw", "dev", iface, "link")
	cmd.WaitDelay = 100 * time.Millisecond
	out, err := cmd.Output()
	if err != nil {
		return "", errors.New("network_ssid_unreadable")
	}
	for _, line := range strings.Split(string(out), "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "SSID: ") {
			ssid := strings.TrimPrefix(trimmed, "SSID: ")
			if ssid == "" {
				return "", errors.New("network_ssid_empty")
			}
			return ssid, nil
		}
		if trimmed == "Not connected." {
			return "", errors.New("network_ssid_not_connected")
		}
	}
	return "", errors.New("network_ssid_unreadable")
}

var ifaceName = regexp.MustCompile(`^[a-zA-Z0-9_.-]+$`)

// DefaultEgressInterface 返回拥有默认网关的出口接口（无默认网关时为空）。
// 有多个默认网关时取字典序最小的接口，保证确定性。
func DefaultEgressInterface(o NetworkObservation) string {
	best := ""
	for name := range o.Gateways {
		if len(o.Gateways[name]) == 0 || ignoredInterface(name) {
			continue
		}
		if best == "" || name < best {
			best = name
		}
	}
	return best
}

// ReadNetworkFingerprint 在物理观测上叠加 SSID 与实际出口类型。
// Egress: "wifi"（station SSID 可读）、"not_wifi"（默认出口存在且非无线接口）、
// ""（未知：无默认出口，或无线接口但 SSID 不可读——不得猜测为非 Wi-Fi）。
func ReadNetworkFingerprint(o NetworkObservation) NetworkObservation {
	egress := DefaultEgressInterface(o)
	if egress == "" {
		o.Egress = ""
		o.SSIDKnown = false
		return o
	}
	if !isWirelessInterface(egress) {
		o.Egress = "not_wifi"
		o.SSIDKnown = false
		return o
	}
	ssid, err := readStationSSID(egress)
	if err != nil {
		o.Egress = ""
		o.SSIDKnown = false
		o.ReadErr = err
		return o
	}
	o.Egress = "wifi"
	o.SSID = ssid
	o.SSIDKnown = true
	return o
}
