// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

const Version = 1
const SnapshotVersion = 2
const SnapshotVersionProxyGroup = 3
const SnapshotVersionOutbounds = 4
const SnapshotVersionTLSOutbounds = 5
const SnapshotVersionRoutePolicy = 6
const SnapshotVersionDNSHosts = 7
const SnapshotVersionDNSPolicy = 8
const SnapshotVersionDNSCachePolicy = 9
const SnapshotVersionDNSLocalServer = 10
const SnapshotVersionDNSDefaultDirectServer = 11
const SnapshotVersionRouteFinal = 12
const SnapshotVersionRouteLoopback = 13
const SnapshotVersionRouteMulticast = 14
const SnapshotVersionDNSOutboundAnyRule = 15
const SnapshotVersionDNSIPv6QueryRule = 16
const SnapshotVersionRouteDNSPort53Rule = 17
const SnapshotVersionRouteDNSProtocolRule = 18
const SnapshotVersionRouteBypassLAN = 19
const SnapshotVersionRouteIPVersionRule = 20
const SnapshotVersionRoutePrivateBypass = 21
const SnapshotVersionRouteDNSHijackPort53Rule = 22
const SnapshotVersionRouteDNSProtocolHijackRule = 23
const SnapshotVersionRouteSniffRule = 24
const SnapshotVersionRouteProbeRule = 25
const SnapshotVersionRouteResolveRule = 26
const SnapshotVersionRouteModeRules = 27
const SnapshotVersionRouteDNSTargetRules = 28
const SnapshotVersionDNSResolvers = 29
const SnapshotVersionRoutePortRejectRules = 30
const SnapshotVersionRouteNetworkRejectRules = 31
const SnapshotVersionRouteNetworkPortRejectRules = 32
const SnapshotVersionRouteSourcePortRejectRules = 33
const SnapshotVersionRouteProtocolRejectRules = 34
const SnapshotVersionRouteSourceIPRejectRules = 35
const SnapshotVersionRouteIPRejectRules = 36
const SnapshotVersionRouteSourceDestinationIPRejectRules = 37
const SnapshotVersionRouteSourceDestinationPortRejectRules = 38
const SnapshotVersionRouteProtocolPortRejectRules = 39
const SnapshotVersionRouteDomainRejectRules = 40
const SnapshotVersionRouteDomainSuffixRejectRules = 41
const SnapshotVersionRouteDomainKeywordRejectRules = 42
const SnapshotVersionRouteDomainRegexRejectRules = 43
const SnapshotVersionRouteNetworkOutboundRules = 44
const SnapshotVersionRouteAppUIDRules = 45
const SnapshotVersionRouteRemoteRuleSets = 46
const SnapshotVersionRouteRemoteRuleSetReferences = 47
const SnapshotVersionRouteRawRules = 48
const SnapshotVersionRouteInvertedGroups = 49
const SnapshotVersionRouteExactDomainOutbound = 50
const SnapshotVersionRouteCompositeDomainIPOutbound = 51
const SnapshotVersionRouteIPOutbound = 52
const SnapshotVersionExactDomainDNSRules = 53
const SnapshotVersionRemoteRuleSetOutboundRoutes = 54
const SnapshotVersionDomainOutboundRoutes = 55
const SnapshotVersionRemoteRuleSetDNSEffects = 56
const SnapshotVersionNetworkPortOutboundRoutes = 57
const SnapshotVersionDomainNetworkOutboundRoutes = 58
const SnapshotVersionDomainPortOutboundRoutes = 59
const SnapshotVersionSourceDestinationIPOutboundRoutes = 60
const SnapshotVersionSourceIPOutboundRoutes = 61
const SnapshotVersionIPVersionOutboundRoutes = 62
const SnapshotVersionSourcePortOutboundRoutes = 63
const SnapshotVersionRemoteRuleSetPortOutboundRoutes = 64
const SnapshotVersionRemoteRuleSetIPVersionOutboundRoutes = 65
const SnapshotVersionRemoteRuleSetSourcePortOutboundRoutes = 66
const SnapshotVersionDomainIPVersionOutboundRoutes = 67
const SnapshotVersionRemoteRuleSetDomainOutboundRoutes = 68
const SnapshotVersionRemoteRuleSetIPOutboundRoutes = 69
const SnapshotVersionRemoteRuleSetNetworkOutboundRoutes = 70
const SnapshotVersionRemoteRuleSetRejectRoutes = 71
const SnapshotVersionRemoteRuleSetNetworkRejectRoutes = 72
const SnapshotVersionRemoteRuleSetIPRejectRoutes = 73
const SnapshotVersionRemoteRuleSetDomainRejectRoutes = 74
const SnapshotVersionRemoteRuleSetIPVersionRejectRoutes = 75
const SnapshotVersionRemoteRuleSetPortRangeOutboundRoutes = 76
const SnapshotVersionRemoteRuleSetProtocolOutboundRoutes = 77
const SnapshotVersionProtocolOutboundRoutes = 78
const SnapshotVersionAppIdentityDNSRules = 79
const SnapshotVersionAppIdentityDomainDNSRules = 80
const SnapshotVersionAppUIDDomainOutboundRoutes = 81
const SnapshotVersionPortOutboundRoutes = 82
const SnapshotVersionAppUIDRuleSetOutboundRoutes = 83
const SnapshotVersionAppUIDDomainRuleSetOutboundRoutes = 84
const SnapshotVersionPortRangeOutboundRoutes = 85
const MaxSnapshotBytes = 192 << 20
const MaxConfigBytes = 8 << 20
const MaxFiles = 512

var safeName = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9._/-]{0,180}$`)
var revisionName = regexp.MustCompile(`^[a-f0-9]{64}$`)

// Snapshot is the portable App -> module contract. Files are JSON base64 bytes,
// never App paths. Only fixed plugin kinds can become executable helpers.
type Snapshot struct {
	SchemaVersion                             int                                             `json:"schemaVersion"`
	ExpectedRevision                          string                                          `json:"expectedRevision"`
	Config                                    json.RawMessage                                 `json:"config"`
	RootInbound                               *RootInboundSpec                                `json:"rootInbound,omitempty"`
	RootProxyGroup                            *RootProxyGroupSpec                             `json:"rootProxyGroup,omitempty"`
	RootOutbounds                             []RootOutboundSpec                              `json:"rootOutbounds,omitempty"`
	RootRoutePolicy                           *RootRoutePolicy                                `json:"rootRoutePolicy,omitempty"`
	RootDNSHosts                              *RootDNSHostsSpec                               `json:"rootDnsHosts,omitempty"`
	RootDNSPolicy                             *RootDNSPolicySpec                              `json:"rootDnsPolicy,omitempty"`
	RootDNSLocalServer                        *RootDNSLocalServerSpec                         `json:"rootDnsLocalServer,omitempty"`
	RootDNSDirectServer                       *RootDNSDirectServerSpec                        `json:"rootDnsDirectServer,omitempty"`
	RootRouteFinal                            *RootRouteFinalSpec                             `json:"rootRouteFinal,omitempty"`
	RootRouteLoopback                         *RootRouteLoopbackSpec                          `json:"rootRouteLoopback,omitempty"`
	RootRouteMulticast                        *RootRouteMulticastSpec                         `json:"rootRouteMulticast,omitempty"`
	RootDNSOutboundAnyRule                    *RootDNSOutboundAnyRuleSpec                     `json:"rootDnsOutboundAnyRule,omitempty"`
	RootDNSIPv6QueryRule                      *RootDNSIPv6QueryRuleSpec                       `json:"rootDnsIPv6QueryRule,omitempty"`
	RootRouteDNSPort53Rule                    *RootRouteDNSPort53RuleSpec                     `json:"rootRouteDnsPort53Rule,omitempty"`
	RootRouteDNSProtocolRule                  *RootRouteDNSProtocolRuleSpec                   `json:"rootRouteDnsProtocolRule,omitempty"`
	RootRouteBypassLAN                        *RootRouteBypassLANSpec                         `json:"rootRouteBypassLan,omitempty"`
	RootRouteIPVersionRule                    *RootRouteIPVersionRuleSpec                     `json:"rootRouteIpVersionRule,omitempty"`
	RootRoutePrivateBypass                    *RootRoutePrivateBypassSpec                     `json:"rootRoutePrivateBypass,omitempty"`
	RootRouteDNSHijackPort53Rule              *RootRouteDNSHijackPort53RuleSpec               `json:"rootRouteDnsHijackPort53Rule,omitempty"`
	RootRouteDNSProtocolHijackRule            *RootRouteDNSProtocolHijackRuleSpec             `json:"rootRouteDnsProtocolHijackRule,omitempty"`
	RootRouteSniffRule                        *RootRouteSniffRuleSpec                         `json:"rootRouteSniffRule,omitempty"`
	RootRouteProbeRule                        *RootRouteProbeRuleSpec                         `json:"rootRouteProbeRule,omitempty"`
	RootRouteResolveRule                      *RootRouteResolveRuleSpec                       `json:"rootRouteResolveRule,omitempty"`
	RootRouteModeRules                        *RootRouteModeRulesSpec                         `json:"rootRouteModeRules,omitempty"`
	RootRouteDNSTargetRules                   *RootRouteDNSTargetRulesSpec                    `json:"rootRouteDnsTargetRules,omitempty"`
	RootDNSResolvers                          *RootDNSResolversSpec                           `json:"rootDnsResolvers,omitempty"`
	RootRoutePortRejectRules                  *RootRoutePortRejectRulesSpec                   `json:"rootRoutePortRejectRules,omitempty"`
	RootRouteNetworkRejectRules               *RootRouteNetworkRejectRulesSpec                `json:"rootRouteNetworkRejectRules,omitempty"`
	RootRouteNetworkPortRejectRules           *RootRouteNetworkPortRejectRulesSpec            `json:"rootRouteNetworkPortRejectRules,omitempty"`
	RootRouteSourcePortRejectRules            *RootRouteSourcePortRejectRulesSpec             `json:"rootRouteSourcePortRejectRules,omitempty"`
	RootRouteProtocolRejectRules              *RootRouteProtocolRejectRulesSpec               `json:"rootRouteProtocolRejectRules,omitempty"`
	RootRouteSourceIPRejectRules              *RootRouteSourceIPRejectRulesSpec               `json:"rootRouteSourceIPRejectRules,omitempty"`
	RootRouteIPRejectRules                    *RootRouteIPRejectRulesSpec                     `json:"rootRouteIPRejectRules,omitempty"`
	RootRouteSourceDestinationIPRejectRules   *RootRouteSourceDestinationIPRejectRulesSpec    `json:"rootRouteSourceDestinationIPRejectRules,omitempty"`
	RootRouteSourceDestinationPortRejectRules *RootRouteSourceDestinationPortRejectRulesSpec  `json:"rootRouteSourceDestinationPortRejectRules,omitempty"`
	RootRouteProtocolPortRejectRules          *RootRouteProtocolPortRejectRulesSpec           `json:"rootRouteProtocolPortRejectRules,omitempty"`
	RootRouteDomainRejectRules                *RootRouteDomainRejectRulesSpec                 `json:"rootRouteDomainRejectRules,omitempty"`
	RootRouteDomainSuffixRejectRules          *RootRouteDomainSuffixRejectRulesSpec           `json:"rootRouteDomainSuffixRejectRules,omitempty"`
	RootRouteDomainKeywordRejectRules         *RootRouteDomainKeywordRejectRulesSpec          `json:"rootRouteDomainKeywordRejectRules,omitempty"`
	RootRouteDomainRegexRejectRules           *RootRouteDomainRegexRejectRulesSpec            `json:"rootRouteDomainRegexRejectRules,omitempty"`
	RootRouteNetworkOutboundRules             *RootRouteNetworkOutboundRulesSpec              `json:"rootRouteNetworkOutboundRules,omitempty"`
	RootRouteAppUIDRules                      *RootRouteAppUIDRulesSpec                       `json:"rootRouteAppUidRules,omitempty"`
	RootAppUIDDomainOutboundRoutes            *RootRouteAppUIDDomainOutboundRoutesSpec        `json:"rootAppUidDomainOutboundRoutes,omitempty"`
	RootAppUIDRuleSetOutboundRoutes           *RootRouteAppUIDRuleSetOutboundRoutesSpec       `json:"rootAppUidRuleSetOutboundRoutes,omitempty"`
	RootAppUIDDomainRuleSetOutboundRoutes     *RootRouteAppUIDDomainRuleSetOutboundRoutesSpec `json:"rootAppUidDomainRuleSetOutboundRoutes,omitempty"`
	Files                                     map[string][]byte                               `json:"files"`
	Plugins                                   []Plugin                                        `json:"plugins"`
	PerformancePriority                       bool                                            `json:"performancePriority"`
	AutoStart                                 bool                                            `json:"autoStart"`
	ProfileID                                 int64                                           `json:"profileId"`
	ProfileName                               string                                          `json:"profileName"`
}

// RootInboundSpec is the versioned declarative input for the module-owned Root ingress.
// App supplies policy values; the module builds the actual sing-box inbound.
type RootInboundSpec struct {
	Backend              string `json:"backend"`
	IPv6Mode             int    `json:"ipv6Mode"`
	MTU                  int    `json:"mtu,omitempty"`
	StrictRoute          bool   `json:"strictRoute,omitempty"`
	BypassPrivateAddress bool   `json:"bypassPrivateAddress,omitempty"`
	IncludeUIDs          []int  `json:"includeUids,omitempty"`
	ExcludeUIDs          []int  `json:"excludeUids,omitempty"`
}

// RootProxyGroupSpec lets the module materialize the top-level user-selectable
// proxy group. The app sends node references and preferences, not sing-box JSON.
type RootProxyGroupSpec struct {
	Type              string   `json:"type"`
	Tag               string   `json:"tag"`
	Members           []string `json:"members"`
	Default           string   `json:"default,omitempty"`
	Strategy          string   `json:"strategy,omitempty"`
	URL               string   `json:"url,omitempty"`
	Interval          string   `json:"interval,omitempty"`
	Tolerance         int      `json:"tolerance,omitempty"`
	IdleTimeout       string   `json:"idleTimeout,omitempty"`
	InterruptExisting bool     `json:"interruptExisting,omitempty"`
}

// RootOutboundSpec is a deliberately small, typed declaration for basic proxy nodes.
// Schema 5 adds direct HTTP/Trojan with basic TLS; transports, uTLS, ECH, chains and custom
// options remain in the legacy config until separately migrated.
// RootRoutePolicy carries the small set of root-wide routing controls that the
// App previously emitted as sing-box JSON. Match rules and DNS policy remain in
// the transitional config until their typed protocols have parity coverage.
type RootRouteFinalSpec struct {
	Final string `json:"final"`
}

// RootRouteLoopbackSpec relocates ConfigBuilder's exact loopback/link-local safety rule.
type RootRouteLoopbackSpec struct {
	Index int `json:"index"`
}

// RootRouteMulticastSpec relocates ConfigBuilder's exact multicast safety rule.
type RootRouteMulticastSpec struct {
	Index int `json:"index"`
}

// RootDNSOutboundAnyRuleSpec relocates ConfigBuilder's unconditional DNS resolver guard.
type RootDNSOutboundAnyRuleSpec struct {
	Index int `json:"index"`
}

// RootDNSIPv6QueryRuleSpec relocates ConfigBuilder's fixed address-family rejection rule.
type RootDNSIPv6QueryRuleSpec struct {
	Index     int    `json:"index"`
	QueryType string `json:"queryType"`
}

// RootRouteDNSPort53RuleSpec moves ConfigBuilder's fixed DNS leak-protection route rule.
type RootRouteDNSPort53RuleSpec struct {
	Index    int    `json:"index"`
	Outbound string `json:"outbound"`
}

// RootRouteDNSProtocolRuleSpec moves ConfigBuilder's fixed DNS-protocol route guard.
type RootRouteDNSProtocolRuleSpec struct {
	Index    int    `json:"index"`
	Outbound string `json:"outbound"`
}

// RootRouteBypassLANSpec moves ConfigBuilder's exact optional direct LAN bypass rule.
type RootRouteBypassLANSpec struct {
	Index int `json:"index"`
}

// RootRouteIPVersionRuleSpec moves ConfigBuilder's exact single-stack route rejection rule.
type RootRouteIPVersionRuleSpec struct {
	Index     int `json:"index"`
	IPVersion int `json:"ipVersion"`
}

// RootRoutePrivateBypassSpec moves ConfigBuilder's exact optional private-address bypass rule.
type RootRoutePrivateBypassSpec struct {
	Index int `json:"index"`
}

// RootRouteDNSHijackPort53RuleSpec moves ConfigBuilder's exact fixed DNS interception rule.
type RootRouteDNSHijackPort53RuleSpec struct {
	Index int `json:"index"`
}

// RootRouteDNSProtocolHijackRuleSpec moves ConfigBuilder's protocol-DNS interception rule.
type RootRouteDNSProtocolHijackRuleSpec struct {
	Index int `json:"index"`
}

// RootRouteSniffRuleSpec moves ConfigBuilder's exact optional sniff action.
type RootRouteSniffRuleSpec struct {
	Index int `json:"index"`
}

// RootRouteProbeRuleSpec relocates ConfigBuilder's exact first-position Root probe route.
type RootRouteProbeRuleSpec struct {
	Index    int    `json:"index"`
	Host     string `json:"host"`
	Outbound string `json:"outbound"`
}

// RootRouteResolveRuleSpec moves ConfigBuilder's optional initial resolve action.
type RootRouteResolveRuleSpec struct {
	Strategy string `json:"strategy"`
}

// RootRouteModeRulesSpec moves the exact adjacent global/direct mode pair.
type RootRouteModeRulesSpec struct {
	Index int `json:"index"`
}

// RootRouteDNSTargetRulesSpec transfers ConfigBuilder's normalized ordered DNS target routes.
type RootRouteDNSTargetRulesSpec struct {
	Index int                          `json:"index"`
	Rules []RootRouteDNSTargetRuleSpec `json:"rules"`
}

type RootRouteDNSTargetRuleSpec struct {
	Kind     string   `json:"kind"`
	Targets  []string `json:"targets"`
	Outbound string   `json:"outbound"`
}

type RootRoutePolicy struct {
	AutoDetectInterface    bool   `json:"autoDetectInterface"`
	OverrideAndroidVPN     bool   `json:"overrideAndroidVpn"`
	FindProcess            bool   `json:"findProcess"`
	DefaultDomainResolver  string `json:"defaultDomainResolver,omitempty"`
	DefaultNetworkStrategy string `json:"defaultNetworkStrategy,omitempty"`
}

// RootDNSHostsSpec moves only user-supplied static host mappings into the module
// contract. The module parses and materializes them as a sing-box hosts DNS server.
type RootDNSHostsSpec struct {
	Raw string `json:"raw"`
}

// RootDNSPolicySpec moves DNS final-server and address-family selection into module materialization.
type RootDNSPolicySpec struct {
	Final            string `json:"final"`
	Strategy         string `json:"strategy,omitempty"`
	IndependentCache *bool  `json:"independentCache,omitempty"`
}

// RootDNSLocalServerSpec describes the fixed local resolver generated by ConfigBuilder.
type RootDNSLocalServerSpec struct {
	Tag    string `json:"tag"`
	Detour string `json:"detour"`
}

// RootDNSDirectServerSpec selects ConfigBuilder's exact built-in default direct resolver.
type RootDNSDirectServerSpec struct {
	Tag string `json:"tag"`
}

type RootOutboundSpec struct {
	Protocol       string   `json:"protocol"`
	Tag            string   `json:"tag"`
	Server         string   `json:"server"`
	ServerPort     int      `json:"serverPort"`
	Method         string   `json:"method,omitempty"`
	Version        string   `json:"version,omitempty"`
	Username       string   `json:"username,omitempty"`
	Password       string   `json:"password,omitempty"`
	Plugin         string   `json:"plugin,omitempty"`
	PluginOptions  string   `json:"pluginOptions,omitempty"`
	DomainStrategy string   `json:"domainStrategy,omitempty"`
	UDPOverTCP     bool     `json:"udpOverTcp,omitempty"`
	TLS            bool     `json:"tls,omitempty"`
	TLSServerName  string   `json:"tlsServerName,omitempty"`
	TLSInsecure    bool     `json:"tlsInsecure,omitempty"`
	TLSALPN        []string `json:"tlsAlpn,omitempty"`
}
type Plugin struct {
	Kind        string `json:"kind"`
	Binary      string `json:"binary"`
	Config      string `json:"config"`
	Certificate string `json:"certificate,omitempty"`
}
type ProcessIdentity struct {
	PID        int    `json:"pid"`
	Start      string `json:"start"`
	Executable string `json:"executable"`
}
type State struct {
	SchemaVersion   int                   `json:"schemaVersion"`
	Phase           string                `json:"phase"`
	Revision        string                `json:"revision"`
	RunningRevision string                `json:"runningRevision"`
	ProfileID       int64                 `json:"profileId"`
	ProfileName     string                `json:"profileName"`
	Supervisor      ProcessIdentity       `json:"supervisor"`
	Core            ProcessIdentity       `json:"core"`
	Error           string                `json:"error,omitempty"`
	Stats           json.RawMessage       `json:"stats,omitempty"`
	Telemetry       *Telemetry            `json:"telemetry,omitempty"`
	Events          []Event               `json:"events,omitempty"`
	InstallData     *InstallDataSelection `json:"installData,omitempty"`
}
type Event struct {
	Time int64  `json:"time"`
	Code string `json:"code"`
}

// @author 雾晚：模块端遥测字段，可选。
// 由 Status() 在 Core 进程身份校验通过后填充。
// 未知时为 nil/0，Kotlin 端显示"—"，不伪造数据。
type Telemetry struct {
	// VmRSSBytes: 核心进程 VmRSS（字节），从 /proc/<pid>/status 读取
	VmRSSBytes int64 `json:"vmRssBytes,omitempty"`
	// UptimeSeconds: 核心进程运行时长（秒），从 ready marker 计算
	UptimeSeconds int64 `json:"uptimeSeconds,omitempty"`
	// TxBytes/RxBytes: 累计上传/下载字节，nil 表示未知（不用 0 冒充）
	TxBytes *int64 `json:"txBytes,omitempty"`
	RxBytes *int64 `json:"rxBytes,omitempty"`
	// ConnectionCount: 活动连接数，nil 表示未知
	ConnectionCount *int `json:"connectionCount,omitempty"`
}

func (r *Runtime) Events() []Event {
	var events []Event
	_ = readJSON(r.path("events.json"), &events, 64<<10)
	return events
}
func (r *Runtime) recordEvent(code string) {
	if !regexp.MustCompile(`^[a-z_]{1,80}$`).MatchString(code) {
		return
	}
	events := r.Events()
	events = append(events, Event{time.Now().Unix(), code})
	if len(events) > 40 {
		events = events[len(events)-40:]
	}
	_ = jsonWrite(r.path("events.json"), events)
}

type Runtime struct {
	Root      string
	ModuleDir string
	// Validate must perform the pinned core's real check, without starting TUN.
	Validate func(context.Context, string, string) error
	// Unexported test seams; CLI input can never replace these lifecycle operations.
	installValidate   func(context.Context, string, string) error
	installStart      func(context.Context) error
	installRunning    func() bool
	independentCgroup func() error
}

func New(root, moduleDir string) *Runtime {
	r := &Runtime{Root: root, ModuleDir: moduleDir, independentCgroup: IndependentCgroup}
	r.Validate = func(ctx context.Context, config, assets string) error {
		ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
		defer cancel()
		// Core errors may contain credentials: never forward raw stderr to the App/log.
		cmd := exec.CommandContext(ctx, filepath.Join(moduleDir, "bin", "rootbox"), "--check", config, assets)
		cmd.Stdout = io.Discard
		cmd.Stderr = io.Discard
		if err := cmd.Run(); err != nil {
			return errors.New("core_config_invalid")
		}
		return nil
	}
	return r
}
func (r *Runtime) path(parts ...string) string {
	return filepath.Join(append([]string{r.Root}, parts...)...)
}
func AtomicWrite(path string, data []byte, mode os.FileMode) error {
	f, err := os.CreateTemp(filepath.Dir(path), ".commit-")
	if err != nil {
		return err
	}
	tmp := f.Name()
	defer os.Remove(tmp)
	if err = f.Chmod(mode); err == nil {
		_, err = f.Write(data)
	}
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	if err = os.Rename(tmp, path); err != nil {
		return err
	}
	return syncDir(filepath.Dir(path))
}
func jsonWrite(path string, v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return AtomicWrite(path, b, 0600)
}
func readJSON(path string, v any, limit int64) error {
	f, e := os.Open(path)
	if e != nil {
		return e
	}
	defer f.Close()
	b, e := io.ReadAll(io.LimitReader(f, limit+1))
	if e != nil {
		return e
	}
	if int64(len(b)) > limit {
		return errors.New("input_too_large")
	}
	return json.Unmarshal(b, v)
}
func DecodeSnapshot(in io.Reader) (Snapshot, error) {
	var s Snapshot
	b, e := io.ReadAll(io.LimitReader(in, MaxSnapshotBytes+1))
	if e != nil {
		return s, e
	}
	if len(b) > MaxSnapshotBytes {
		return s, errors.New("snapshot_too_large")
	}
	d := json.NewDecoder(bytes.NewReader(b))
	d.DisallowUnknownFields()
	if e = d.Decode(&s); e != nil {
		return s, errors.New("snapshot_invalid")
	}
	var extra any
	if d.Decode(&extra) != io.EOF {
		return s, errors.New("trailing_input")
	}
	if e = CheckSnapshot(s); e != nil {
		return s, e
	}
	return s, nil
}
func CheckSnapshot(s Snapshot) error {
	if (s.SchemaVersion < Version || s.SchemaVersion > SnapshotVersionPortRangeOutboundRoutes) || !json.Valid(s.Config) || len(s.Config) > MaxConfigBytes || s.ProfileID <= 0 || len(s.ProfileName) > 512 {
		return errors.New("snapshot_invalid")
	}
	if s.ExpectedRevision != "" && !revisionName.MatchString(s.ExpectedRevision) {
		return errors.New("revision_invalid")
	}
	if len(s.Files) > MaxFiles || len(s.Plugins) > 32 {
		return errors.New("too_many_files")
	}
	var size int
	for name, data := range s.Files {
		if !safeRelative(name) || !(strings.HasPrefix(name, "assets/") || strings.HasPrefix(name, "files/") || strings.HasPrefix(name, "plugins/")) {
			return errors.New("unsafe_path")
		}
		if len(data) > 64<<20 {
			return errors.New("file_too_large")
		}
		size += len(data)
		if size > 128<<20 {
			return errors.New("files_too_large")
		}
	}
	for _, p := range s.Plugins {
		switch p.Kind {
		case "trojan-go", "mieru", "naive", "hysteria":
		default:
			return errors.New("plugin_unsupported")
		}
		bin, ok := s.Files[p.Binary]
		if !ok || !strings.HasPrefix(p.Binary, "plugins/") || len(bin) < 64 || !bytes.Equal(bin[:6], []byte{127, 'E', 'L', 'F', 2, 1}) || binary.LittleEndian.Uint16(bin[18:20]) != 183 {
			return errors.New("plugin_invalid")
		}
		if _, ok = s.Files[p.Config]; !ok || !strings.HasPrefix(p.Config, "files/") {
			return errors.New("plugin_config_missing")
		}
		if p.Certificate != "" {
			if _, ok = s.Files[p.Certificate]; !ok || !strings.HasPrefix(p.Certificate, "files/") {
				return errors.New("plugin_cert_missing")
			}
		}
	}
	// No App directory may remain in a runtime snapshot, including custom JSON.
	var config any
	if json.Unmarshal(s.Config, &config) != nil {
		return errors.New("config_invalid")
	}
	root, ok := config.(map[string]any)
	if !ok {
		return errors.New("config_invalid")
	}
	// Reject a newer remote-ruleset marker before validating unrelated schema fields,
	// so a downgrade cannot mask the actual protocol-version violation.
	if s.SchemaVersion < SnapshotVersionRouteRemoteRuleSets && hasRootRemoteRuleSetMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionDomainOutboundRoutes && hasRootDomainOutboundRouteMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionNetworkPortOutboundRoutes && hasRootNetworkPortOutboundRouteMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionDomainNetworkOutboundRoutes && hasRootDomainNetworkOutboundRouteMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionDomainPortOutboundRoutes && hasRootDomainPortOutboundRouteMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionSourceDestinationIPOutboundRoutes && hasRootSourceDestinationIPOutboundMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionSourceIPOutboundRoutes && hasRootSourceIPOutboundMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionIPVersionOutboundRoutes && hasRootIPVersionOutboundMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionSourcePortOutboundRoutes && hasRootSourcePortOutboundMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionPortOutboundRoutes && hasRootPortOutboundMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionAppUIDRuleSetOutboundRoutes && hasRootAppUIDRuleSetOutboundMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionAppUIDDomainRuleSetOutboundRoutes && hasRootAppUIDDomainRuleSetOutboundMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionPortRangeOutboundRoutes && (hasRootPortRangeOutboundMarker(root, rootPortRangeOutboundMarker) || hasRootPortRangeOutboundMarker(root, rootSourcePortRangeOutboundMarker)) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionRemoteRuleSetPortOutboundRoutes && hasRootRemoteRuleSetPortOutboundRouteMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionRemoteRuleSetIPVersionOutboundRoutes && hasRootRemoteRuleSetIPVersionOutboundRouteMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionRemoteRuleSetSourcePortOutboundRoutes && hasRootRemoteRuleSetSourcePortOutboundRouteMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionDomainIPVersionOutboundRoutes && hasRootDomainIPVersionOutboundRouteMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionRemoteRuleSetDomainOutboundRoutes && hasRootRemoteRuleSetDomainOutboundRouteMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionRemoteRuleSetIPOutboundRoutes && hasRootRemoteRuleSetIPOutboundRouteMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionRemoteRuleSetNetworkOutboundRoutes && hasRootRemoteRuleSetNetworkOutboundRouteMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionRemoteRuleSetRejectRoutes && hasRootRemoteRuleSetRejectRouteMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionRemoteRuleSetNetworkRejectRoutes && hasRootRemoteRuleSetNetworkRejectRouteMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionRemoteRuleSetIPRejectRoutes && hasRootRemoteRuleSetIPRejectRouteMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionRemoteRuleSetDomainRejectRoutes && hasRootRemoteRuleSetDomainRejectRouteMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionRemoteRuleSetIPVersionRejectRoutes && hasRootRemoteRuleSetIPVersionRejectRouteMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionRemoteRuleSetProtocolOutboundRoutes && hasRootRemoteRuleSetProtocolOutboundRouteMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionProtocolOutboundRoutes && hasRootProtocolOutboundRouteMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionRouteRemoteRuleSetReferences && hasRootRemoteRuleSetReferenceMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionRouteRawRules && hasRootRawRuleMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionRouteInvertedGroups && hasRootInvertedGroupMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionRouteExactDomainOutbound && hasRootExactDomainOutboundMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionRouteCompositeDomainIPOutbound && hasRootCompositeDomainIPOutboundMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionRouteIPOutbound && hasRootIPOutboundRouteMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionExactDomainDNSRules && hasRootExactDomainDNSRuleMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionAppIdentityDNSRules && hasRootAppIdentityDNSRuleMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion < SnapshotVersionAppIdentityDomainDNSRules && hasRootAppIdentityDomainDNSRuleMarker(root) {
		return errors.New("snapshot_invalid")
	}
	if s.SchemaVersion >= SnapshotVersion && s.SchemaVersion <= SnapshotVersionPortRangeOutboundRoutes {
		if err := validateRootInbound(s.RootInbound); err != nil {
			return err
		}
		if hasRootInbound(root) {
			return errors.New("root_inbound_duplicate")
		}
		if s.SchemaVersion >= SnapshotVersionProxyGroup {
			if err := validateRootProxyGroup(s.RootProxyGroup); err != nil {
				return err
			}
			if hasRootProxyGroup(root, s.RootProxyGroup.Tag) {
				return errors.New("root_proxy_group_duplicate")
			}
		} else if s.RootProxyGroup != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionOutbounds {
			if err := validateRootOutbounds(s.RootOutbounds, s.SchemaVersion); err != nil {
				return err
			}
			for _, outbound := range s.RootOutbounds {
				if hasRootProxyGroup(root, outbound.Tag) {
					return errors.New("root_outbound_duplicate")
				}
			}
		} else if len(s.RootOutbounds) != 0 {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRoutePolicy {
			if s.RootRoutePolicy != nil {
				if err := validateRootRoutePolicy(s.RootRoutePolicy); err != nil {
					return err
				}
				if hasRootRoutePolicy(root) {
					return errors.New("root_route_policy_duplicate")
				}
			} else if s.SchemaVersion == SnapshotVersionRoutePolicy {
				return errors.New("root_route_policy_invalid")
			}
		} else if s.RootRoutePolicy != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionDNSHosts {
			if s.RootDNSHosts != nil {
				if err := validateRootDNSHosts(s.RootDNSHosts); err != nil {
					return err
				}
				if hasRootDNSHosts(root) {
					return errors.New("root_dns_hosts_duplicate")
				}
			} else if s.SchemaVersion == SnapshotVersionDNSHosts {
				return errors.New("root_dns_hosts_invalid")
			}
		} else if s.RootDNSHosts != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionDNSPolicy {
			if err := validateRootDNSPolicy(s.RootDNSPolicy, root); err != nil {
				return err
			}
			if s.SchemaVersion < SnapshotVersionDNSCachePolicy && s.RootDNSPolicy.IndependentCache != nil {
				return errors.New("snapshot_invalid")
			}
			if hasRootDNSPolicy(root, s.SchemaVersion >= SnapshotVersionDNSCachePolicy) {
				return errors.New("root_dns_policy_duplicate")
			}
		} else if s.RootDNSPolicy != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionDNSLocalServer {
			if err := validateRootDNSLocalServer(s.RootDNSLocalServer); err != nil {
				return err
			}
			if hasRootDNSLocalServer(root) {
				return errors.New("root_dns_local_server_duplicate")
			}
		} else if s.RootDNSLocalServer != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionDNSDefaultDirectServer {
			if err := validateRootDNSDirectServer(s.RootDNSDirectServer); err != nil {
				return err
			}
			if hasRootDNSDirectServer(root) {
				return errors.New("root_dns_direct_server_duplicate")
			}
		} else if s.RootDNSDirectServer != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteFinal {
			if err := validateRootRouteFinal(s.RootRouteFinal, root, s); err != nil {
				return err
			}
		} else if s.RootRouteFinal != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteLoopback {
			if s.RootRouteLoopback != nil {
				if err := validateRootRouteLoopback(s.RootRouteLoopback, root, s); err != nil {
					return err
				}
			} else if s.SchemaVersion == SnapshotVersionRouteLoopback {
				return errors.New("root_route_loopback_invalid")
			}
		} else if s.RootRouteLoopback != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteMulticast {
			if s.RootRouteMulticast != nil {
				if err := validateRootRouteMulticast(s.RootRouteMulticast, root, s); err != nil {
					return err
				}
			} else if s.SchemaVersion == SnapshotVersionRouteMulticast {
				return errors.New("root_route_multicast_invalid")
			}
		} else if s.RootRouteMulticast != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionDNSOutboundAnyRule {
			if s.RootDNSOutboundAnyRule != nil {
				if err := validateRootDNSOutboundAnyRule(s.RootDNSOutboundAnyRule, root); err != nil {
					return err
				}
			} else if s.SchemaVersion == SnapshotVersionDNSOutboundAnyRule {
				return errors.New("root_dns_outbound_any_invalid")
			}
		} else if s.RootDNSOutboundAnyRule != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionDNSIPv6QueryRule {
			if s.RootDNSIPv6QueryRule != nil {
				if err := validateRootDNSIPv6QueryRule(s.RootDNSIPv6QueryRule, s.RootDNSPolicy, root); err != nil {
					return err
				}
			} else if s.SchemaVersion == SnapshotVersionDNSIPv6QueryRule {
				return errors.New("root_dns_ipv6_query_invalid")
			}
		} else if s.RootDNSIPv6QueryRule != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteDNSPort53Rule {
			if s.RootRouteDNSPort53Rule != nil {
				if err := validateRootRouteDNSPort53Rule(s.RootRouteDNSPort53Rule, root, s); err != nil {
					return err
				}
			} else if s.SchemaVersion == SnapshotVersionRouteDNSPort53Rule {
				return errors.New("root_route_dns_port53_invalid")
			}
		} else if s.RootRouteDNSPort53Rule != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteDNSProtocolRule {
			if s.RootRouteDNSProtocolRule != nil {
				if err := validateRootRouteDNSProtocolRule(s.RootRouteDNSProtocolRule, root, s); err != nil {
					return err
				}
			} else if s.SchemaVersion == SnapshotVersionRouteDNSProtocolRule {
				return errors.New("root_route_dns_protocol_invalid")
			}
		} else if s.RootRouteDNSProtocolRule != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteBypassLAN {
			if s.RootRouteBypassLAN != nil {
				if err := validateRootRouteBypassLAN(s.RootRouteBypassLAN, root, s); err != nil {
					return err
				}
			} else if s.SchemaVersion == SnapshotVersionRouteBypassLAN {
				// The optional ConfigBuilder setting may legitimately be absent.
			}
		} else if s.RootRouteBypassLAN != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteIPVersionRule {
			if err := validateRootRouteIPVersionRule(s.RootRouteIPVersionRule, root, s); err != nil {
				return err
			}
		} else if s.RootRouteIPVersionRule != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRoutePrivateBypass {
			if s.RootRoutePrivateBypass != nil {
				if err := validateRootRoutePrivateBypass(s.RootRoutePrivateBypass, root, s); err != nil {
					return err
				}
			} else if s.SchemaVersion == SnapshotVersionRoutePrivateBypass {
				// The optional ConfigBuilder setting may legitimately be absent.
			}
		} else if s.RootRoutePrivateBypass != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteDNSHijackPort53Rule {
			if s.RootRouteDNSHijackPort53Rule != nil {
				if err := validateRootRouteDNSHijackPort53Rule(s.RootRouteDNSHijackPort53Rule, root, s); err != nil {
					return err
				}
			} else if s.SchemaVersion == SnapshotVersionRouteDNSHijackPort53Rule {
				// The fixed interception rule is optional in imported/custom configurations.
			}
		} else if s.RootRouteDNSHijackPort53Rule != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteDNSProtocolHijackRule {
			if s.RootRouteDNSProtocolHijackRule != nil {
				if err := validateRootRouteDNSProtocolHijackRule(s.RootRouteDNSProtocolHijackRule, root, s); err != nil {
					return err
				}
			} else if s.SchemaVersion == SnapshotVersionRouteDNSProtocolHijackRule {
				// The fixed protocol-DNS interception rule is optional in imported/custom configurations.
			}
		} else if s.RootRouteDNSProtocolHijackRule != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteSniffRule {
			if s.RootRouteSniffRule != nil {
				if err := validateRootRouteSniffRule(s.RootRouteSniffRule, root, s); err != nil {
					return err
				}
			} else if s.SchemaVersion == SnapshotVersionRouteSniffRule {
				// ConfigBuilder may omit sniff when traffic sniffing is disabled.
			}
		} else if s.RootRouteSniffRule != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteProbeRule {
			if err := validateRootRouteProbeRule(s.RootRouteProbeRule, root, s); err != nil {
				return err
			}
		} else if s.RootRouteProbeRule != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteResolveRule {
			if s.RootRouteResolveRule != nil {
				if err := validateRootRouteResolveRule(s.RootRouteResolveRule, root); err != nil {
					return err
				}
			} else if s.SchemaVersion == SnapshotVersionRouteResolveRule {
				return errors.New("root_route_resolve_invalid")
			}
		} else if s.RootRouteResolveRule != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteModeRules {
			if s.RootRouteModeRules != nil {
				if err := validateRootRouteModeRules(s.RootRouteModeRules, root, s); err != nil {
					return err
				}
			} else if s.SchemaVersion == SnapshotVersionRouteModeRules {
				return errors.New("root_route_mode_rules_invalid")
			}
		} else if s.RootRouteModeRules != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteDNSTargetRules {
			if s.RootRouteDNSTargetRules != nil {
				if err := validateRootRouteDNSTargetRules(s.RootRouteDNSTargetRules, root, s); err != nil {
					return err
				}
			} else if s.SchemaVersion == SnapshotVersionRouteDNSTargetRules {
				// DNS endpoint target sets can legitimately be empty.
			}
		} else if s.RootRouteDNSTargetRules != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionDNSResolvers {
			if s.RootDNSResolvers == nil && s.SchemaVersion == SnapshotVersionDNSResolvers {
				return errors.New("root_dns_resolvers_invalid")
			}
		} else if s.RootDNSResolvers != nil {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRoutePortRejectRules {
			if s.RootRoutePortRejectRules != nil {
				if err := validateRootRoutePortRejectRules(s.RootRoutePortRejectRules, root); err != nil {
					return err
				}
			} else if s.SchemaVersion == SnapshotVersionRoutePortRejectRules || hasRootRoutePortRejectMarker(root) {
				return errors.New("root_route_port_reject_invalid")
			}
		} else if s.RootRoutePortRejectRules != nil || hasRootRoutePortRejectMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteNetworkRejectRules {
			if s.RootRouteNetworkRejectRules != nil || hasRootRouteNetworkRejectMarker(root) || s.SchemaVersion == SnapshotVersionRouteNetworkRejectRules {
				if err := validateRootRouteNetworkRejectRules(s.RootRouteNetworkRejectRules, root); err != nil {
					return err
				}
			}
		} else if s.RootRouteNetworkRejectRules != nil || hasRootRouteNetworkRejectMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteNetworkPortRejectRules {
			if s.RootRouteNetworkPortRejectRules != nil || hasRootRouteNetworkPortRejectMarker(root) || s.SchemaVersion == SnapshotVersionRouteNetworkPortRejectRules {
				if err := validateRootRouteNetworkPortRejectRules(s.RootRouteNetworkPortRejectRules, root); err != nil {
					return err
				}
			}
		} else if s.RootRouteNetworkPortRejectRules != nil || hasRootRouteNetworkPortRejectMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteSourcePortRejectRules {
			if s.RootRouteSourcePortRejectRules != nil || hasRootRouteSourcePortRejectMarker(root) || s.SchemaVersion == SnapshotVersionRouteSourcePortRejectRules {
				if err := validateRootRouteSourcePortRejectRules(s.RootRouteSourcePortRejectRules, root); err != nil {
					return err
				}
			}
		} else if s.RootRouteSourcePortRejectRules != nil || hasRootRouteSourcePortRejectMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteProtocolRejectRules {
			if s.RootRouteProtocolRejectRules != nil || hasRootRouteProtocolRejectMarker(root) || s.SchemaVersion == SnapshotVersionRouteProtocolRejectRules {
				if err := validateRootRouteProtocolRejectRules(s.RootRouteProtocolRejectRules, root); err != nil {
					return err
				}
			}
		} else if s.RootRouteProtocolRejectRules != nil || hasRootRouteProtocolRejectMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteSourceIPRejectRules {
			if s.RootRouteSourceIPRejectRules != nil || hasRootRouteSourceIPRejectMarker(root) || s.SchemaVersion == SnapshotVersionRouteSourceIPRejectRules {
				if err := validateRootRouteSourceIPRejectRules(s.RootRouteSourceIPRejectRules, root); err != nil {
					return err
				}
			}
		} else if s.RootRouteSourceIPRejectRules != nil || hasRootRouteSourceIPRejectMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteIPRejectRules {
			if s.RootRouteIPRejectRules != nil || hasRootRouteIPRejectMarker(root) || s.SchemaVersion == SnapshotVersionRouteIPRejectRules {
				if err := validateRootRouteIPRejectRules(s.RootRouteIPRejectRules, root); err != nil {
					return err
				}
			}
		} else if s.RootRouteIPRejectRules != nil || hasRootRouteIPRejectMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteSourceDestinationIPRejectRules {
			if s.RootRouteSourceDestinationIPRejectRules != nil || hasRootRouteSourceDestinationIPRejectMarker(root) || s.SchemaVersion == SnapshotVersionRouteSourceDestinationIPRejectRules {
				if err := validateRootRouteSourceDestinationIPRejectRules(s.RootRouteSourceDestinationIPRejectRules, root); err != nil {
					return err
				}
			}
		} else if s.RootRouteSourceDestinationIPRejectRules != nil || hasRootRouteSourceDestinationIPRejectMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteSourceDestinationPortRejectRules {
			if s.RootRouteSourceDestinationPortRejectRules != nil || hasRootRouteSourceDestinationPortRejectMarker(root) || s.SchemaVersion == SnapshotVersionRouteSourceDestinationPortRejectRules {
				if err := validateRootRouteSourceDestinationPortRejectRules(s.RootRouteSourceDestinationPortRejectRules, root); err != nil {
					return err
				}
			}
		} else if s.RootRouteSourceDestinationPortRejectRules != nil || hasRootRouteSourceDestinationPortRejectMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteProtocolPortRejectRules {
			if s.RootRouteProtocolPortRejectRules != nil || hasRootRouteProtocolPortRejectMarker(root) || s.SchemaVersion == SnapshotVersionRouteProtocolPortRejectRules {
				if err := validateRootRouteProtocolPortRejectRules(s.RootRouteProtocolPortRejectRules, root); err != nil {
					return err
				}
			}
		} else if s.RootRouteProtocolPortRejectRules != nil || hasRootRouteProtocolPortRejectMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteDomainRejectRules {
			if s.RootRouteDomainRejectRules != nil || hasRootRouteDomainRejectMarker(root) || s.SchemaVersion == SnapshotVersionRouteDomainRejectRules {
				if err := validateRootRouteDomainRejectRules(s.RootRouteDomainRejectRules, root); err != nil {
					return err
				}
			}
		} else if s.RootRouteDomainRejectRules != nil || hasRootRouteDomainRejectMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteDomainSuffixRejectRules {
			if s.RootRouteDomainSuffixRejectRules != nil || hasRootRouteDomainSuffixRejectMarker(root) || s.SchemaVersion == SnapshotVersionRouteDomainSuffixRejectRules {
				if err := validateRootRouteDomainSuffixRejectRules(s.RootRouteDomainSuffixRejectRules, root); err != nil {
					return err
				}
			}
		} else if s.RootRouteDomainSuffixRejectRules != nil || hasRootRouteDomainSuffixRejectMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteDomainKeywordRejectRules {
			if s.RootRouteDomainKeywordRejectRules != nil || hasRootRouteDomainKeywordRejectMarker(root) || s.SchemaVersion == SnapshotVersionRouteDomainKeywordRejectRules {
				if err := validateRootRouteDomainKeywordRejectRules(s.RootRouteDomainKeywordRejectRules, root); err != nil {
					return err
				}
			}
		} else if s.RootRouteDomainKeywordRejectRules != nil || hasRootRouteDomainKeywordRejectMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteDomainRegexRejectRules {
			if s.RootRouteDomainRegexRejectRules != nil || hasRootRouteDomainRegexRejectMarker(root) || s.SchemaVersion == SnapshotVersionRouteDomainRegexRejectRules {
				if err := validateRootRouteDomainRegexRejectRules(s.RootRouteDomainRegexRejectRules, root); err != nil {
					return err
				}
			}
		} else if s.RootRouteDomainRegexRejectRules != nil || hasRootRouteDomainRegexRejectMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteNetworkOutboundRules {
			if s.RootRouteNetworkOutboundRules != nil || hasRootRouteNetworkOutboundMarker(root) || s.SchemaVersion == SnapshotVersionRouteNetworkOutboundRules {
				if s.RootRouteDomainRegexRejectRules != nil || hasRootRouteDomainRegexRejectMarker(root) {
					return errors.New("root_route_network_outbound_regex_conflict")
				}
				if err := validateRootRouteNetworkOutboundRules(s.RootRouteNetworkOutboundRules, root, s); err != nil {
					return err
				}
			}
		} else if s.RootRouteNetworkOutboundRules != nil || hasRootRouteNetworkOutboundMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteAppUIDRules {
			if s.RootRouteAppUIDRules != nil || hasRootRouteAppUIDMarker(root) || s.SchemaVersion == SnapshotVersionRouteAppUIDRules {
				if err := validateRootRouteAppUIDRules(s.RootRouteAppUIDRules, root, s); err != nil {
					return err
				}
			}
		} else if s.RootRouteAppUIDRules != nil || hasRootRouteAppUIDMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteRemoteRuleSets {
			if err := validateRootRemoteRuleSets(root); err != nil {
				return err
			}
		} else if hasRootRemoteRuleSetMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteRemoteRuleSetReferences {
			if err := validateRootRemoteRuleSetReferences(root); err != nil {
				return err
			}
		} else if hasRootRemoteRuleSetReferenceMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteRawRules {
			if err := validateRootRawRules(root); err != nil {
				return err
			}
		} else if hasRootRawRuleMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteInvertedGroups {
			if err := validateRootInvertedGroups(root); err != nil {
				return err
			}
		} else if hasRootInvertedGroupMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteExactDomainOutbound {
			if err := validateRootExactDomainOutbound(root); err != nil {
				return err
			}
		} else if hasRootExactDomainOutboundMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteCompositeDomainIPOutbound {
			if err := validateRootCompositeDomainIPOutbound(root); err != nil {
				return err
			}
		} else if hasRootCompositeDomainIPOutboundMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRouteIPOutbound {
			if err := validateRootIPOutboundRoute(root, s); err != nil {
				return err
			}
		} else if hasRootIPOutboundRouteMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionExactDomainDNSRules {
			if err := validateRootExactDomainDNSRules(root, s); err != nil {
				return err
			}
		} else if hasRootExactDomainDNSRuleMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionAppIdentityDNSRules {
			if err := validateRootAppIdentityDNSRules(root, s); err != nil {
				return err
			}
		} else if hasRootAppIdentityDNSRuleMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionAppIdentityDomainDNSRules {
			if err := validateRootAppIdentityDomainDNSRules(root, s); err != nil {
				return err
			}
		} else if hasRootAppIdentityDomainDNSRuleMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionAppUIDDomainOutboundRoutes {
			if s.RootAppUIDDomainOutboundRoutes != nil || hasRootAppUIDDomainOutboundRouteMarker(root) || s.SchemaVersion == SnapshotVersionAppUIDDomainOutboundRoutes {
				if err := validateRootAppUIDDomainOutboundRoutes(s.RootAppUIDDomainOutboundRoutes, root, s); err != nil {
					return err
				}
			}
		} else if s.RootAppUIDDomainOutboundRoutes != nil || hasRootAppUIDDomainOutboundRouteMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionAppUIDRuleSetOutboundRoutes {
			if s.RootAppUIDRuleSetOutboundRoutes != nil || hasRootAppUIDRuleSetOutboundMarker(root) || s.SchemaVersion == SnapshotVersionAppUIDRuleSetOutboundRoutes {
				if err := validateRootAppUIDRuleSetOutboundRoutes(s.RootAppUIDRuleSetOutboundRoutes, root, s); err != nil {
					return err
				}
			}
		} else if s.RootAppUIDRuleSetOutboundRoutes != nil || hasRootAppUIDRuleSetOutboundMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionAppUIDDomainRuleSetOutboundRoutes {
			if err := validateRootAppUIDDomainRuleSetOutboundRoutes(s.RootAppUIDDomainRuleSetOutboundRoutes, root, s); err != nil {
				return err
			}
		} else if s.RootAppUIDDomainRuleSetOutboundRoutes != nil || hasRootAppUIDDomainRuleSetOutboundMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionPortRangeOutboundRoutes {
			if err := validateRootPortRangeOutbound(root, s, rootPortRangeOutboundMarker); err != nil {
				return err
			}
			if err := validateRootPortRangeOutbound(root, s, rootSourcePortRangeOutboundMarker); err != nil {
				return err
			}
		} else if hasRootPortRangeOutboundMarker(root, rootPortRangeOutboundMarker) || hasRootPortRangeOutboundMarker(root, rootSourcePortRangeOutboundMarker) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRemoteRuleSetOutboundRoutes {
			if err := validateRootRemoteRuleSetOutboundRoutes(root, s); err != nil {
				return err
			}
		} else if hasRootRemoteRuleSetOutboundRouteMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionDomainOutboundRoutes {
			if err := validateRootDomainOutboundRoutes(root, s); err != nil {
				return err
			}
		} else if hasRootDomainOutboundRouteMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRemoteRuleSetDNSEffects {
			if err := validateRootRemoteRuleSetDNSEffects(root, s); err != nil {
				return err
			}
		} else if hasRootRemoteRuleSetDNSEffectMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionNetworkPortOutboundRoutes {
			if err := validateRootNetworkPortOutboundRoutes(root, s); err != nil {
				return err
			}
		} else if hasRootNetworkPortOutboundRouteMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionDomainNetworkOutboundRoutes {
			if err := validateRootDomainNetworkOutboundRoutes(root, s); err != nil {
				return err
			}
		} else if hasRootDomainNetworkOutboundRouteMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionDomainPortOutboundRoutes {
			if err := validateRootDomainPortOutboundRoutes(root, s); err != nil {
				return err
			}
		} else if hasRootDomainPortOutboundRouteMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionSourceDestinationIPOutboundRoutes {
			if err := validateRootSourceDestinationIPOutbound(root); err != nil {
				return err
			}
		} else if hasRootSourceDestinationIPOutboundMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionSourceIPOutboundRoutes {
			if err := validateRootSourceIPOutbound(root, s); err != nil {
				return err
			}
		} else if hasRootSourceIPOutboundMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionIPVersionOutboundRoutes {
			if err := validateRootIPVersionOutbound(root, s); err != nil {
				return err
			}
		} else if hasRootIPVersionOutboundMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionSourcePortOutboundRoutes {
			if err := validateRootSourcePortOutbound(root, s); err != nil {
				return err
			}
		} else if hasRootSourcePortOutboundMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionPortOutboundRoutes {
			if err := validateRootPortOutbound(root, s); err != nil {
				return err
			}
		} else if hasRootPortOutboundMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRemoteRuleSetPortOutboundRoutes {
			if err := validateRootRemoteRuleSetPortOutboundRoutes(root, s); err != nil {
				return err
			}
		} else if hasRootRemoteRuleSetPortOutboundRouteMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRemoteRuleSetIPVersionOutboundRoutes {
			if err := validateRootRemoteRuleSetIPVersionOutboundRoutes(root, s); err != nil {
				return err
			}
		} else if hasRootRemoteRuleSetIPVersionOutboundRouteMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRemoteRuleSetSourcePortOutboundRoutes {
			if err := validateRootRemoteRuleSetSourcePortOutboundRoutes(root, s); err != nil {
				return err
			}
		} else if hasRootRemoteRuleSetSourcePortOutboundRouteMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionDomainIPVersionOutboundRoutes {
			if err := validateRootDomainIPVersionOutboundRoutes(root, s); err != nil {
				return err
			}
		} else if hasRootDomainIPVersionOutboundRouteMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRemoteRuleSetDomainOutboundRoutes {
			if err := validateRootRemoteRuleSetDomainOutboundRoutes(root, s); err != nil {
				return err
			}
		} else if hasRootRemoteRuleSetDomainOutboundRouteMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRemoteRuleSetIPOutboundRoutes {
			if err := validateRootRemoteRuleSetIPOutboundRoutes(root, s); err != nil {
				return err
			}
		} else if hasRootRemoteRuleSetIPOutboundRouteMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRemoteRuleSetNetworkOutboundRoutes {
			if err := validateRootRemoteRuleSetNetworkOutboundRoutes(root, s); err != nil {
				return err
			}
		} else if hasRootRemoteRuleSetNetworkOutboundRouteMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRemoteRuleSetRejectRoutes {
			if err := validateRootRemoteRuleSetRejectRoutes(root, s); err != nil {
				return err
			}
		} else if hasRootRemoteRuleSetRejectRouteMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRemoteRuleSetNetworkRejectRoutes {
			if err := validateRootRemoteRuleSetNetworkRejectRoutes(root, s); err != nil {
				return err
			}
		} else if hasRootRemoteRuleSetNetworkRejectRouteMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRemoteRuleSetIPRejectRoutes {
			if err := validateRootRemoteRuleSetIPRejectRoutes(root, s); err != nil {
				return err
			}
		} else if hasRootRemoteRuleSetIPRejectRouteMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRemoteRuleSetDomainRejectRoutes {
			if err := validateRootRemoteRuleSetDomainRejectRoutes(root, s); err != nil {
				return err
			}
		} else if hasRootRemoteRuleSetDomainRejectRouteMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRemoteRuleSetIPVersionRejectRoutes {
			if err := validateRootRemoteRuleSetIPVersionRejectRoutes(root, s); err != nil {
				return err
			}
		} else if hasRootRemoteRuleSetIPVersionRejectRouteMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRemoteRuleSetPortRangeOutboundRoutes {
			if err := validateRootRemoteRuleSetPortRangeOutboundRoutes(root, s); err != nil {
				return err
			}
		} else if hasRootRemoteRuleSetPortRangeOutboundRouteMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionRemoteRuleSetProtocolOutboundRoutes {
			if err := validateRootRemoteRuleSetProtocolOutboundRoutes(root, s); err != nil {
				return err
			}
		} else if hasRootRemoteRuleSetProtocolOutboundRouteMarker(root) {
			return errors.New("snapshot_invalid")
		}
		if s.SchemaVersion >= SnapshotVersionProtocolOutboundRoutes {
			if err := validateRootProtocolOutboundRoutes(root, s); err != nil {
				return err
			}
		} else if hasRootProtocolOutboundRouteMarker(root) {
			return errors.New("snapshot_invalid")
		}

	} else if s.RootInbound != nil || s.RootProxyGroup != nil || len(s.RootOutbounds) != 0 || s.RootRoutePolicy != nil || s.RootDNSHosts != nil || s.RootDNSPolicy != nil || s.RootDNSLocalServer != nil || s.RootDNSDirectServer != nil || s.RootRouteFinal != nil || s.RootRouteLoopback != nil || s.RootRouteMulticast != nil || s.RootDNSOutboundAnyRule != nil || s.RootDNSIPv6QueryRule != nil || s.RootRouteDNSPort53Rule != nil || s.RootRouteDNSProtocolRule != nil || s.RootRouteBypassLAN != nil || s.RootRouteIPVersionRule != nil || s.RootRoutePrivateBypass != nil || s.RootRouteDNSHijackPort53Rule != nil || s.RootRouteDNSProtocolHijackRule != nil || s.RootRouteSniffRule != nil || s.RootRouteProbeRule != nil || s.RootRouteResolveRule != nil || s.RootRouteModeRules != nil || s.RootRouteDNSTargetRules != nil || s.RootDNSResolvers != nil || s.RootRoutePortRejectRules != nil || s.RootRouteNetworkRejectRules != nil || s.RootRouteNetworkPortRejectRules != nil || s.RootRouteSourcePortRejectRules != nil || s.RootRouteProtocolRejectRules != nil || s.RootRouteSourceIPRejectRules != nil || s.RootRouteIPRejectRules != nil || s.RootRouteSourceDestinationIPRejectRules != nil || s.RootRouteSourceDestinationPortRejectRules != nil || s.RootRouteProtocolPortRejectRules != nil || s.RootRouteDomainRejectRules != nil || s.RootRouteDomainSuffixRejectRules != nil || s.RootRouteDomainKeywordRejectRules != nil || s.RootRouteDomainRegexRejectRules != nil || s.RootRouteNetworkOutboundRules != nil || s.RootRouteAppUIDRules != nil || s.RootAppUIDDomainOutboundRoutes != nil || s.RootAppUIDRuleSetOutboundRoutes != nil {
		return errors.New("snapshot_invalid")
	}
	if err := checkRuntimePaths(config, s.Files); err != nil {
		return err
	}
	return nil
}
func validateRootInbound(spec *RootInboundSpec) error {
	if spec == nil || (spec.Backend != "ebpf" && spec.Backend != "tun") {
		return errors.New("root_inbound_invalid")
	}
	if spec.Backend == "tun" && (spec.MTU < 576 || spec.MTU > 9000) {
		return errors.New("root_inbound_invalid")
	}
	if len(spec.IncludeUIDs) > 4096 || len(spec.ExcludeUIDs) > 4096 || (len(spec.IncludeUIDs) > 0 && len(spec.ExcludeUIDs) > 0) {
		return errors.New("root_inbound_invalid")
	}
	seen := map[int]bool{}
	for _, uid := range append(append([]int(nil), spec.IncludeUIDs...), spec.ExcludeUIDs...) {
		if uid < 0 || uid > 2147483647 || seen[uid] {
			return errors.New("root_inbound_invalid")
		}
		seen[uid] = true
	}
	return nil
}
func hasRootInbound(config map[string]any) bool {
	inbounds, _ := config["inbounds"].([]any)
	for _, raw := range inbounds {
		if inbound, ok := raw.(map[string]any); ok && inbound["tag"] == "tun-in" {
			return true
		}
	}
	return false
}
func validateRootRoutePolicy(spec *RootRoutePolicy) error {
	if spec == nil {
		return errors.New("root_route_policy_invalid")
	}
	if len(spec.DefaultDomainResolver) > 128 || strings.ContainsAny(spec.DefaultDomainResolver, "\\/\x00\r\n") {
		return errors.New("root_route_policy_invalid")
	}
	switch spec.DefaultNetworkStrategy {
	case "", "hybrid", "fallback":
	default:
		return errors.New("root_route_policy_invalid")
	}
	return nil
}
func hasRootRoutePolicy(config map[string]any) bool {
	route, _ := config["route"].(map[string]any)
	if route == nil {
		return false
	}
	for _, key := range []string{"auto_detect_interface", "override_android_vpn", "find_process", "default_domain_resolver", "default_network_strategy"} {
		if _, ok := route[key]; ok {
			return true
		}
	}
	return false
}

func validateRootRouteFinal(spec *RootRouteFinalSpec, config map[string]any, snapshot Snapshot) error {
	if spec == nil || spec.Final == "" || len(spec.Final) > 256 || strings.ContainsAny(spec.Final, "\\\x00\r\n") {
		return errors.New("root_route_final_invalid")
	}
	if !hasRootProxyGroup(config, spec.Final) && (snapshot.RootProxyGroup == nil || snapshot.RootProxyGroup.Tag != spec.Final) {
		found := false
		for _, outbound := range snapshot.RootOutbounds {
			if outbound.Tag == spec.Final {
				found = true
				break
			}
		}
		if !found {
			return errors.New("root_route_final_invalid")
		}
	}
	route, _ := config["route"].(map[string]any)
	if route != nil {
		if _, exists := route["final"]; exists {
			return errors.New("root_route_final_duplicate")
		}
	}
	return nil
}

func validateRootRouteLoopback(spec *RootRouteLoopbackSpec, config map[string]any, snapshot Snapshot) error {
	if spec == nil || spec.Index < 0 {
		return errors.New("root_route_loopback_invalid")
	}
	route, _ := config["route"].(map[string]any)
	var rules []any
	if route != nil {
		if raw, exists := route["rules"]; exists {
			var ok bool
			rules, ok = raw.([]any)
			if !ok {
				return errors.New("root_route_loopback_invalid")
			}
		}
	}
	maxIndex := len(rules)
	if snapshot.RootRouteMulticast != nil {
		maxIndex++
	}
	if snapshot.RootRouteDNSHijackPort53Rule != nil {
		maxIndex++
	}
	if snapshot.RootRouteDNSPort53Rule != nil {
		maxIndex++
	}
	if snapshot.RootRouteDNSProtocolRule != nil {
		maxIndex++
	}
	if spec.Index > maxIndex {
		return errors.New("root_route_loopback_invalid")
	}
	for _, rule := range rules {
		if isRootRouteLoopbackRule(rule) {
			return errors.New("root_route_loopback_duplicate")
		}
	}
	return nil
}

func isRootRouteLoopbackRule(value any) bool {
	rule, ok := value.(map[string]any)
	if !ok || len(rule) != 2 || rule["outbound"] != "direct" {
		return false
	}
	cidrs, ok := rule["ip_cidr"].([]any)
	if !ok || len(cidrs) != 5 {
		return false
	}
	want := []string{"127.0.0.0/8", "::1/128", "224.0.0.0/4", "169.254.0.0/16", "fe80::/10"}
	for i, cidr := range want {
		if cidrs[i] != cidr {
			return false
		}
	}
	return true
}

func validateRootRouteMulticast(spec *RootRouteMulticastSpec, config map[string]any, snapshot Snapshot) error {
	if spec == nil || spec.Index < 0 {
		return errors.New("root_route_multicast_invalid")
	}
	route, _ := config["route"].(map[string]any)
	var rules []any
	if route != nil {
		if raw, exists := route["rules"]; exists {
			var ok bool
			rules, ok = raw.([]any)
			if !ok {
				return errors.New("root_route_multicast_invalid")
			}
		}
	}
	maxIndex := len(rules)
	if snapshot.RootRouteDNSPort53Rule != nil {
		maxIndex++
	}
	if snapshot.RootRouteDNSProtocolRule != nil {
		maxIndex++
	}
	if snapshot.RootRouteDNSHijackPort53Rule != nil {
		maxIndex++
	}
	if snapshot.RootRouteBypassLAN != nil {
		maxIndex++
	}
	if snapshot.RootRouteIPVersionRule != nil {
		maxIndex++
	}
	if snapshot.RootRoutePrivateBypass != nil && snapshot.RootRoutePrivateBypass.Index <= spec.Index {
		maxIndex++
	}
	if spec.Index > maxIndex {
		return errors.New("root_route_multicast_invalid")
	}
	for _, rule := range rules {
		if isRootRouteMulticastRule(rule) {
			return errors.New("root_route_multicast_duplicate")
		}
	}
	return nil
}

func isRootRouteMulticastRule(value any) bool {
	rule, ok := value.(map[string]any)
	if !ok || len(rule) != 3 || rule["action"] != "reject" {
		return false
	}
	want := []string{"224.0.0.0/3", "ff00::/8"}
	for _, key := range []string{"ip_cidr", "source_ip_cidr"} {
		values, ok := rule[key].([]any)
		if !ok || len(values) != len(want) {
			return false
		}
		for i, item := range want {
			if values[i] != item {
				return false
			}
		}
	}
	return true
}

func validateRootDNSOutboundAnyRule(spec *RootDNSOutboundAnyRuleSpec, config map[string]any) error {
	if spec == nil || spec.Index < 0 {
		return errors.New("root_dns_outbound_any_invalid")
	}
	dns, _ := config["dns"].(map[string]any)
	var rules []any
	if dns != nil {
		if raw, exists := dns["rules"]; exists {
			var ok bool
			rules, ok = raw.([]any)
			if !ok {
				return errors.New("root_dns_outbound_any_invalid")
			}
		}
	}
	if spec.Index > len(rules) {
		return errors.New("root_dns_outbound_any_invalid")
	}
	for _, rule := range rules {
		if isRootDNSOutboundAnyRule(rule) {
			return errors.New("root_dns_outbound_any_duplicate")
		}
	}
	return nil
}

func isRootDNSOutboundAnyRule(value any) bool {
	rule, ok := value.(map[string]any)
	if !ok || len(rule) != 2 || rule["server"] != "dns-direct" {
		return false
	}
	outbound, ok := rule["outbound"].([]any)
	return ok && len(outbound) == 1 && outbound[0] == "any"
}

func isRootRouteIPVersionRule(value any, ipVersion int) bool {
	rule, ok := value.(map[string]any)
	if !ok || len(rule) != 2 || rule["action"] != "reject" {
		return false
	}
	version, ok := rule["ip_version"].(float64)
	return ok && version == float64(ipVersion)
}

func validateRootRouteIPVersionRule(spec *RootRouteIPVersionRuleSpec, config map[string]any, snapshot Snapshot) error {
	if spec == nil || spec.Index < 0 || (spec.IPVersion != 4 && spec.IPVersion != 6) || snapshot.RootInbound == nil ||
		(snapshot.RootInbound.IPv6Mode == 0 && spec.IPVersion != 6) || (snapshot.RootInbound.IPv6Mode == 3 && spec.IPVersion != 4) ||
		(snapshot.RootInbound.IPv6Mode != 0 && snapshot.RootInbound.IPv6Mode != 3) {
		return errors.New("root_route_ip_version_invalid")
	}
	route, _ := config["route"].(map[string]any)
	var rules []any
	if route != nil {
		if raw, exists := route["rules"]; exists {
			var ok bool
			rules, ok = raw.([]any)
			if !ok {
				return errors.New("root_route_ip_version_invalid")
			}
		}
	}
	maxIndex := len(rules)
	if snapshot.RootRoutePrivateBypass != nil && snapshot.RootRoutePrivateBypass.Index <= spec.Index {
		maxIndex++
	}
	if snapshot.RootRouteDNSHijackPort53Rule != nil && snapshot.RootRouteDNSHijackPort53Rule.Index <= spec.Index {
		maxIndex++
	}
	if snapshot.RootRouteDNSProtocolHijackRule != nil && snapshot.RootRouteDNSProtocolHijackRule.Index <= spec.Index {
		maxIndex++
	}
	if snapshot.RootRouteSniffRule != nil && snapshot.RootRouteSniffRule.Index <= spec.Index {
		maxIndex++
	}
	if spec.Index > maxIndex {
		return errors.New("root_route_ip_version_invalid")
	}
	for _, rule := range rules {
		if isRootRouteIPVersionRule(rule, spec.IPVersion) {
			return errors.New("root_route_ip_version_duplicate")
		}
	}
	return nil
}

func isRootRoutePrivateBypassRule(value any) bool {
	rule, ok := value.(map[string]any)
	return ok && len(rule) == 2 && rule["ip_is_private"] == true && rule["outbound"] == "bypass"
}

func validateRootRoutePrivateBypass(spec *RootRoutePrivateBypassSpec, config map[string]any, snapshot Snapshot) error {
	if spec == nil || spec.Index < 0 {
		return errors.New("root_route_private_bypass_invalid")
	}
	route, _ := config["route"].(map[string]any)
	var rules []any
	if route != nil {
		if raw, exists := route["rules"]; exists {
			var ok bool
			rules, ok = raw.([]any)
			if !ok {
				return errors.New("root_route_private_bypass_invalid")
			}
		}
	}
	maxIndex := len(rules)
	// This index is captured before the other route rules are projected away.
	for _, present := range []bool{snapshot.RootRouteLoopback != nil, snapshot.RootRouteMulticast != nil, snapshot.RootRouteDNSPort53Rule != nil, snapshot.RootRouteDNSProtocolRule != nil, snapshot.RootRouteBypassLAN != nil, snapshot.RootRouteIPVersionRule != nil, snapshot.RootRouteDNSHijackPort53Rule != nil, snapshot.RootRouteDNSProtocolHijackRule != nil, snapshot.RootRouteSniffRule != nil} {
		if present {
			maxIndex++
		}
	}
	if spec.Index > maxIndex {
		return errors.New("root_route_private_bypass_invalid")
	}
	for _, rule := range rules {
		if isRootRoutePrivateBypassRule(rule) {
			return errors.New("root_route_private_bypass_duplicate")
		}
	}
	return nil
}

func isRootRouteDNSHijackPort53Rule(value any) bool {
	rule, ok := value.(map[string]any)
	if !ok || len(rule) != 2 || rule["action"] != "hijack-dns" {
		return false
	}
	ports, ok := rule["port"].([]any)
	return ok && len(ports) == 1 && ports[0] == float64(53)
}

func validateRootRouteDNSHijackPort53Rule(spec *RootRouteDNSHijackPort53RuleSpec, config map[string]any, snapshot Snapshot) error {
	if spec == nil || spec.Index < 0 {
		return errors.New("root_route_dns_hijack_port53_invalid")
	}
	route, _ := config["route"].(map[string]any)
	var rules []any
	if route != nil {
		if raw, exists := route["rules"]; exists {
			var ok bool
			rules, ok = raw.([]any)
			if !ok {
				return errors.New("root_route_dns_hijack_port53_invalid")
			}
		}
	}
	maxIndex := len(rules)
	for _, present := range []bool{snapshot.RootRouteLoopback != nil, snapshot.RootRouteMulticast != nil, snapshot.RootRouteDNSPort53Rule != nil, snapshot.RootRouteDNSProtocolRule != nil, snapshot.RootRouteBypassLAN != nil, snapshot.RootRouteIPVersionRule != nil, snapshot.RootRoutePrivateBypass != nil} {
		if present {
			maxIndex++
		}
	}
	if spec.Index > maxIndex {
		return errors.New("root_route_dns_hijack_port53_invalid")
	}
	for _, rule := range rules {
		if isRootRouteDNSHijackPort53Rule(rule) {
			return errors.New("root_route_dns_hijack_port53_duplicate")
		}
	}
	return nil
}

func isRootRouteDNSProtocolHijackRule(value any) bool {
	rule, ok := value.(map[string]any)
	if !ok || len(rule) != 2 || rule["action"] != "hijack-dns" {
		return false
	}
	protocols, ok := rule["protocol"].([]any)
	return ok && len(protocols) == 1 && protocols[0] == "dns"
}

func validateRootRouteDNSProtocolHijackRule(spec *RootRouteDNSProtocolHijackRuleSpec, config map[string]any, snapshot Snapshot) error {
	if spec == nil || spec.Index < 0 {
		return errors.New("root_route_dns_protocol_hijack_invalid")
	}
	route, _ := config["route"].(map[string]any)
	var rules []any
	if route != nil {
		if raw, exists := route["rules"]; exists {
			var ok bool
			rules, ok = raw.([]any)
			if !ok {
				return errors.New("root_route_dns_protocol_hijack_invalid")
			}
		}
	}
	maxIndex := len(rules)
	for _, present := range []bool{snapshot.RootRouteLoopback != nil, snapshot.RootRouteMulticast != nil, snapshot.RootRouteDNSPort53Rule != nil, snapshot.RootRouteDNSProtocolRule != nil, snapshot.RootRouteBypassLAN != nil, snapshot.RootRouteIPVersionRule != nil, snapshot.RootRoutePrivateBypass != nil, snapshot.RootRouteDNSHijackPort53Rule != nil} {
		if present {
			maxIndex++
		}
	}
	if spec.Index > maxIndex {
		return errors.New("root_route_dns_protocol_hijack_invalid")
	}
	for _, rule := range rules {
		if isRootRouteDNSProtocolHijackRule(rule) {
			return errors.New("root_route_dns_protocol_hijack_duplicate")
		}
	}
	return nil
}

func isRootRouteSniffRule(value any) bool {
	rule, ok := value.(map[string]any)
	return ok && len(rule) == 1 && rule["action"] == "sniff"
}

func validateRootRouteProbeRule(spec *RootRouteProbeRuleSpec, config map[string]any, snapshot Snapshot) error {
	if spec == nil || spec.Index != 0 || spec.Host == "" || len(spec.Host) > 253 || strings.TrimSpace(spec.Host) != spec.Host || strings.ContainsAny(spec.Host, "\\/\x00\r\n\t ") || spec.Outbound == "" || len(spec.Outbound) > 256 || strings.ContainsAny(spec.Outbound, "\\/\x00\r\n") {
		return errors.New("root_route_probe_invalid")
	}
	foundOutbound := hasRootProxyGroup(config, spec.Outbound) || (snapshot.RootProxyGroup != nil && snapshot.RootProxyGroup.Tag == spec.Outbound)
	if !foundOutbound {
		for _, outbound := range snapshot.RootOutbounds {
			if outbound.Tag == spec.Outbound {
				foundOutbound = true
				break
			}
		}
	}
	if !foundOutbound {
		return errors.New("root_route_probe_invalid")
	}
	route, _ := config["route"].(map[string]any)
	if route != nil {
		rules, _ := route["rules"].([]any)
		for _, raw := range rules {
			if rule, ok := raw.(map[string]any); ok && len(rule) == 3 && rule["inbound"] != nil && rule["domain"] != nil && rule["outbound"] == spec.Outbound {
				inbound, _ := rule["inbound"].([]any)
				domain, _ := rule["domain"].([]any)
				if len(inbound) == 1 && inbound[0] == "mixed" && len(domain) == 1 && domain[0] == spec.Host {
					return errors.New("root_route_probe_duplicate")
				}
			}
		}
	}
	return nil
}

func isRootRouteResolveRule(value any) bool {
	rule, ok := value.(map[string]any)
	if !ok || len(rule) != 2 || rule["action"] != "resolve" {
		return false
	}
	switch rule["strategy"] {
	case "ipv4_only", "ipv6_only", "prefer_ipv4", "prefer_ipv6":
		return true
	default:
		return false
	}
}

func isRootRouteDNSTargetRule(value any) bool {
	rule, ok := value.(map[string]any)
	if !ok || len(rule) != 2 || rule["outbound"] == "" {
		return false
	}
	_, domain := rule["domain"]
	_, cidr := rule["ip_cidr"]
	if domain == cidr {
		return false
	}
	key := "domain"
	if cidr {
		key = "ip_cidr"
	}
	values, ok := rule[key].([]any)
	if !ok || len(values) == 0 {
		return false
	}
	for _, raw := range values {
		value, ok := raw.(string)
		if !ok || strings.TrimSpace(value) != value || value == "" {
			return false
		}
	}
	return true
}

func hasRootOutboundTag(config map[string]any, snapshot Snapshot, tag string) bool {
	if tag == "" {
		return false
	}
	if hasRootProxyGroup(config, tag) || (snapshot.RootProxyGroup != nil && snapshot.RootProxyGroup.Tag == tag) {
		return true
	}
	outbounds, _ := config["outbounds"].([]any)
	for _, raw := range outbounds {
		if outbound, ok := raw.(map[string]any); ok && outbound["tag"] == tag {
			return true
		}
	}
	for _, outbound := range snapshot.RootOutbounds {
		if outbound.Tag == tag {
			return true
		}
	}
	return false
}

func validateRootRouteDNSTargetRules(spec *RootRouteDNSTargetRulesSpec, config map[string]any, snapshot Snapshot) error {
	if spec == nil || spec.Index < 0 || len(spec.Rules) == 0 || len(spec.Rules) > 4 ||
		snapshot.RootRouteDNSPort53Rule == nil || snapshot.RootRouteDNSProtocolRule == nil ||
		snapshot.RootRouteDNSPort53Rule.Outbound == "" || snapshot.RootRouteDNSProtocolRule.Outbound != snapshot.RootRouteDNSPort53Rule.Outbound {
		return errors.New("root_route_dns_targets_invalid")
	}
	route, _ := config["route"].(map[string]any)
	var rules []any
	if route != nil {
		if raw, exists := route["rules"]; exists {
			var ok bool
			rules, ok = raw.([]any)
			if !ok {
				return errors.New("root_route_dns_targets_invalid")
			}
		}
	}
	maxIndex := len(rules)
	for _, present := range []bool{
		snapshot.RootRouteLoopback != nil, snapshot.RootRouteMulticast != nil,
		snapshot.RootRouteDNSPort53Rule != nil, snapshot.RootRouteDNSProtocolRule != nil,
		snapshot.RootRouteBypassLAN != nil, snapshot.RootRouteIPVersionRule != nil,
		snapshot.RootRoutePrivateBypass != nil, snapshot.RootRouteDNSHijackPort53Rule != nil,
		snapshot.RootRouteDNSProtocolHijackRule != nil, snapshot.RootRouteSniffRule != nil,
		snapshot.RootRouteProbeRule != nil, snapshot.RootRouteResolveRule != nil,
		snapshot.RootRouteModeRules != nil,
	} {
		if present {
			maxIndex++
		}
	}
	if spec.Index > maxIndex {
		return errors.New("root_route_dns_targets_invalid")
	}
	lastCategory := -1
	seenCategories := map[int]bool{}
	for _, target := range spec.Rules {
		if (target.Kind != "domain" && target.Kind != "ip_cidr") || len(target.Targets) == 0 || len(target.Targets) > 4096 || strings.TrimSpace(target.Outbound) != target.Outbound || len(target.Outbound) > 256 || strings.ContainsAny(target.Outbound, "\\/\x00") || !hasRootOutboundTag(config, snapshot, target.Outbound) ||
			(target.Outbound != "direct" && target.Outbound != snapshot.RootRouteDNSPort53Rule.Outbound) {
			return errors.New("root_route_dns_targets_invalid")
		}
		category := 0
		if target.Kind == "ip_cidr" {
			category = 1
		}
		if target.Outbound != "direct" {
			category += 2
		} else if category > 1 {
			return errors.New("root_route_dns_targets_invalid")
		}
		if seenCategories[category] || category <= lastCategory {
			return errors.New("root_route_dns_targets_invalid")
		}
		seenCategories[category] = true
		lastCategory = category
		seen := map[string]bool{}
		for _, value := range target.Targets {
			if value == "" || strings.TrimSpace(value) != value || strings.ContainsAny(value, "\x00\r\n\t ") || seen[value] {
				return errors.New("root_route_dns_targets_invalid")
			}
			seen[value] = true
			if target.Kind == "ip_cidr" {
				ip, _, err := net.ParseCIDR(value)
				if err != nil || ip == nil {
					return errors.New("root_route_dns_targets_invalid")
				}
				if snapshot.RootInbound != nil && ((snapshot.RootInbound.IPv6Mode == 0 && ip.To4() == nil) || (snapshot.RootInbound.IPv6Mode == 3 && ip.To4() != nil)) {
					return errors.New("root_route_dns_targets_invalid")
				}
			} else if len(value) > 253 {
				return errors.New("root_route_dns_targets_invalid")
			}
		}
	}
	for _, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok || len(rule) != 2 {
			continue
		}
		for _, target := range spec.Rules {
			if rule["outbound"] != target.Outbound {
				continue
			}
			values, ok := rule[target.Kind].([]any)
			if !ok || len(values) != len(target.Targets) {
				continue
			}
			match := true
			for i, value := range values {
				if value != target.Targets[i] {
					match = false
					break
				}
			}
			if match {
				return errors.New("root_route_dns_targets_duplicate")
			}
		}
	}
	return nil
}

func validateRootRouteModeRules(spec *RootRouteModeRulesSpec, config map[string]any, snapshot Snapshot) error {
	if spec == nil || spec.Index < 0 {
		return errors.New("root_route_mode_rules_invalid")
	}
	if !hasRootProxyGroup(config, "proxy") && (snapshot.RootProxyGroup == nil || snapshot.RootProxyGroup.Tag != "proxy") {
		found := false
		for _, outbound := range snapshot.RootOutbounds {
			if outbound.Tag == "proxy" {
				found = true
				break
			}
		}
		if !found {
			return errors.New("root_route_mode_rules_invalid")
		}
	}
	route, _ := config["route"].(map[string]any)
	var rules []any
	if route != nil {
		if raw, exists := route["rules"]; exists {
			var ok bool
			rules, ok = raw.([]any)
			if !ok {
				return errors.New("root_route_mode_rules_invalid")
			}
		}
	}
	maxIndex := len(rules)
	for _, present := range []bool{
		snapshot.RootRouteLoopback != nil, snapshot.RootRouteMulticast != nil,
		snapshot.RootRouteDNSPort53Rule != nil, snapshot.RootRouteDNSProtocolRule != nil,
		snapshot.RootRouteBypassLAN != nil, snapshot.RootRouteIPVersionRule != nil,
		snapshot.RootRoutePrivateBypass != nil, snapshot.RootRouteDNSHijackPort53Rule != nil,
		snapshot.RootRouteDNSProtocolHijackRule != nil, snapshot.RootRouteSniffRule != nil,
		snapshot.RootRouteProbeRule != nil, snapshot.RootRouteResolveRule != nil,
	} {
		if present {
			maxIndex++
		}
	}
	if spec.Index > maxIndex {
		return errors.New("root_route_mode_rules_invalid")
	}
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule["clash_mode"]; exists {
				return errors.New("root_route_mode_rules_duplicate")
			}
		}
	}
	return nil
}

func validateRootRouteResolveRule(spec *RootRouteResolveRuleSpec, config map[string]any) error {
	if spec == nil {
		return errors.New("root_route_resolve_invalid")
	}
	switch spec.Strategy {
	case "ipv4_only", "ipv6_only", "prefer_ipv4", "prefer_ipv6":
	default:
		return errors.New("root_route_resolve_invalid")
	}
	route, _ := config["route"].(map[string]any)
	if route != nil {
		if raw, exists := route["rules"]; exists {
			rules, ok := raw.([]any)
			if !ok {
				return errors.New("root_route_resolve_invalid")
			}
			for _, rule := range rules {
				if isRootRouteResolveRule(rule) {
					return errors.New("root_route_resolve_duplicate")
				}
			}
		}
	}
	return nil
}

func validateRootRouteSniffRule(spec *RootRouteSniffRuleSpec, config map[string]any, snapshot Snapshot) error {
	if spec == nil || spec.Index < 0 {
		return errors.New("root_route_sniff_invalid")
	}
	route, _ := config["route"].(map[string]any)
	var rules []any
	if route != nil {
		if raw, exists := route["rules"]; exists {
			var ok bool
			rules, ok = raw.([]any)
			if !ok {
				return errors.New("root_route_sniff_invalid")
			}
		}
	}
	maxIndex := len(rules)
	for _, present := range []bool{snapshot.RootRouteLoopback != nil, snapshot.RootRouteMulticast != nil, snapshot.RootRouteDNSPort53Rule != nil, snapshot.RootRouteDNSProtocolRule != nil, snapshot.RootRouteBypassLAN != nil, snapshot.RootRouteIPVersionRule != nil, snapshot.RootRoutePrivateBypass != nil, snapshot.RootRouteDNSHijackPort53Rule != nil, snapshot.RootRouteDNSProtocolHijackRule != nil, snapshot.RootRouteSniffRule != nil} {
		if present {
			maxIndex++
		}
	}
	if spec.Index > maxIndex {
		return errors.New("root_route_sniff_invalid")
	}
	for _, rule := range rules {
		if isRootRouteSniffRule(rule) {
			return errors.New("root_route_sniff_duplicate")
		}
	}
	return nil
}

func isRootRouteBypassLANRule(value any) bool {
	rule, ok := value.(map[string]any)
	if !ok || len(rule) != 2 || rule["outbound"] != "direct" {
		return false
	}
	cidrs, ok := rule["ip_cidr"].([]any)
	if !ok || len(cidrs) != 4 {
		return false
	}
	want := []string{"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"}
	for i, value := range want {
		if cidrs[i] != value {
			return false
		}
	}
	return true
}

func validateRootRouteBypassLAN(spec *RootRouteBypassLANSpec, config map[string]any, snapshot Snapshot) error {
	if spec == nil || spec.Index < 0 {
		return errors.New("root_route_bypass_lan_invalid")
	}
	route, _ := config["route"].(map[string]any)
	var rules []any
	if route != nil {
		if raw, exists := route["rules"]; exists {
			var ok bool
			rules, ok = raw.([]any)
			if !ok {
				return errors.New("root_route_bypass_lan_invalid")
			}
		}
	}
	maxIndex := len(rules)
	// The ConfigBuilder DNS safety rules precede this optional LAN rule. Their
	// positions have already been removed from the legacy JSON by the App.
	if snapshot.RootRouteDNSPort53Rule != nil && snapshot.RootRouteDNSPort53Rule.Index <= spec.Index {
		maxIndex++
	}
	if snapshot.RootRouteDNSProtocolRule != nil && snapshot.RootRouteDNSProtocolRule.Index <= spec.Index {
		maxIndex++
	}
	if snapshot.RootRouteDNSHijackPort53Rule != nil && snapshot.RootRouteDNSHijackPort53Rule.Index <= spec.Index {
		maxIndex++
	}
	if snapshot.RootRouteDNSProtocolHijackRule != nil && snapshot.RootRouteDNSProtocolHijackRule.Index <= spec.Index {
		maxIndex++
	}
	if spec.Index > maxIndex {
		return errors.New("root_route_bypass_lan_invalid")
	}
	for _, rule := range rules {
		if isRootRouteBypassLANRule(rule) {
			return errors.New("root_route_bypass_lan_duplicate")
		}
	}
	return nil
}

func validateRootRouteDNSPort53Rule(spec *RootRouteDNSPort53RuleSpec, config map[string]any, snapshot Snapshot) error {
	if spec == nil || spec.Index < 0 || spec.Outbound == "" || len(spec.Outbound) > 256 || strings.ContainsAny(spec.Outbound, "\\/\x00") || strings.TrimSpace(spec.Outbound) != spec.Outbound {
		return errors.New("root_route_dns_port53_invalid")
	}
	known := hasRootProxyGroup(config, spec.Outbound)
	if snapshot.RootProxyGroup != nil && snapshot.RootProxyGroup.Tag == spec.Outbound {
		known = true
	}
	for _, outbound := range snapshot.RootOutbounds {
		if outbound.Tag == spec.Outbound {
			known = true
			break
		}
	}
	if !known {
		return errors.New("root_route_dns_port53_invalid")
	}
	route, _ := config["route"].(map[string]any)
	var rules []any
	if route != nil {
		if raw, exists := route["rules"]; exists {
			var ok bool
			rules, ok = raw.([]any)
			if !ok {
				return errors.New("root_route_dns_port53_invalid")
			}
		}
	}
	maxIndex := len(rules)
	if snapshot.RootRouteDNSProtocolRule != nil {
		maxIndex++
	}
	if snapshot.RootRouteDNSHijackPort53Rule != nil && snapshot.RootRouteDNSHijackPort53Rule.Index <= spec.Index {
		maxIndex++
	}
	if snapshot.RootRouteDNSProtocolHijackRule != nil && snapshot.RootRouteDNSProtocolHijackRule.Index <= spec.Index {
		maxIndex++
	}
	if spec.Index > maxIndex {
		return errors.New("root_route_dns_port53_invalid")
	}
	for _, rule := range rules {
		if isRootRouteDNSPort53Rule(rule) {
			return errors.New("root_route_dns_port53_duplicate")
		}
	}
	return nil
}

func isRootRouteDNSPort53Rule(value any) bool {
	rule, ok := value.(map[string]any)
	if !ok || len(rule) != 2 {
		return false
	}
	ports, ok := rule["port"].([]any)
	if !ok || len(ports) != 1 || ports[0] != float64(53) {
		return false
	}
	outbound, ok := rule["outbound"].(string)
	return ok && outbound != ""
}

func isRootRouteDNSProtocolRule(value any) bool {
	rule, ok := value.(map[string]any)
	if !ok || len(rule) != 2 || rule["outbound"] == "" {
		return false
	}
	protocols, ok := rule["protocol"].([]any)
	return ok && len(protocols) == 1 && protocols[0] == "dns"
}

func validateRootRouteDNSProtocolRule(spec *RootRouteDNSProtocolRuleSpec, config map[string]any, snapshot Snapshot) error {
	if spec == nil || spec.Index < 0 || spec.Outbound == "" || len(spec.Outbound) > 256 || strings.ContainsAny(spec.Outbound, "\\/\x00") || strings.TrimSpace(spec.Outbound) != spec.Outbound {
		return errors.New("root_route_dns_protocol_invalid")
	}
	known := hasRootProxyGroup(config, spec.Outbound)
	if snapshot.RootProxyGroup != nil && snapshot.RootProxyGroup.Tag == spec.Outbound {
		known = true
	}
	for _, outbound := range snapshot.RootOutbounds {
		if outbound.Tag == spec.Outbound {
			known = true
			break
		}
	}
	if !known {
		return errors.New("root_route_dns_protocol_invalid")
	}
	route, _ := config["route"].(map[string]any)
	var rules []any
	if route != nil {
		if raw, exists := route["rules"]; exists {
			var ok bool
			rules, ok = raw.([]any)
			if !ok {
				return errors.New("root_route_dns_protocol_invalid")
			}
		}
	}
	maxIndex := len(rules)
	if snapshot.RootRouteDNSHijackPort53Rule != nil && snapshot.RootRouteDNSHijackPort53Rule.Index <= spec.Index {
		maxIndex++
	}
	if snapshot.RootRouteDNSProtocolHijackRule != nil && snapshot.RootRouteDNSProtocolHijackRule.Index <= spec.Index {
		maxIndex++
	}
	if spec.Index > maxIndex {
		return errors.New("root_route_dns_protocol_invalid")
	}
	for _, rule := range rules {
		if isRootRouteDNSProtocolRule(rule) {
			return errors.New("root_route_dns_protocol_duplicate")
		}
	}
	return nil
}

func validateRootDNSIPv6QueryRule(spec *RootDNSIPv6QueryRuleSpec, policy *RootDNSPolicySpec, config map[string]any) error {
	if spec == nil || spec.Index < 0 || (spec.QueryType != "A" && spec.QueryType != "AAAA") || policy == nil {
		return errors.New("root_dns_ipv6_query_invalid")
	}
	if (policy.Strategy == "ipv4_only" && spec.QueryType != "AAAA") || (policy.Strategy == "ipv6_only" && spec.QueryType != "A") || (policy.Strategy != "ipv4_only" && policy.Strategy != "ipv6_only") {
		return errors.New("root_dns_ipv6_query_invalid")
	}
	dns, _ := config["dns"].(map[string]any)
	var rules []any
	if dns != nil {
		if raw, exists := dns["rules"]; exists {
			var ok bool
			rules, ok = raw.([]any)
			if !ok {
				return errors.New("root_dns_ipv6_query_invalid")
			}
		}
	}
	if spec.Index > len(rules) {
		return errors.New("root_dns_ipv6_query_invalid")
	}
	for _, rule := range rules {
		if isRootDNSIPv6QueryRule(rule, spec.QueryType) {
			return errors.New("root_dns_ipv6_query_duplicate")
		}
	}
	return nil
}

func isRootDNSIPv6QueryRule(value any, queryType string) bool {
	rule, ok := value.(map[string]any)
	if !ok || len(rule) != 2 || rule["action"] != "reject" {
		return false
	}
	query, ok := rule["query_type"].([]any)
	return ok && len(query) == 1 && query[0] == queryType
}

func validateRootDNSHosts(spec *RootDNSHostsSpec) error {
	if spec == nil || len(spec.Raw) > 64<<10 {
		return errors.New("root_dns_hosts_invalid")
	}
	_, err := parseRootDNSHosts(spec.Raw)
	return err
}

func validateRootDNSPolicy(spec *RootDNSPolicySpec, config map[string]any) error {
	if spec == nil || spec.Final == "" || len(spec.Final) > 128 || strings.ContainsAny(spec.Final, "\\/\x00\r\n") {
		return errors.New("root_dns_policy_invalid")
	}
	switch spec.Strategy {
	case "", "ipv4_only", "ipv6_only", "prefer_ipv4", "prefer_ipv6":
	default:
		return errors.New("root_dns_policy_invalid")
	}
	dns, _ := config["dns"].(map[string]any)
	servers, _ := dns["servers"].([]any)
	found := false
	for _, value := range servers {
		server, _ := value.(map[string]any)
		if server["tag"] == spec.Final {
			found = true
			break
		}
	}
	if !found {
		return errors.New("root_dns_policy_invalid")
	}
	return nil
}

func hasRootDNSPolicy(config map[string]any, includeIndependentCache bool) bool {
	dns, _ := config["dns"].(map[string]any)
	if dns == nil {
		return false
	}
	_, final := dns["final"]
	_, strategy := dns["strategy"]
	if !includeIndependentCache {
		return final || strategy
	}
	_, independentCache := dns["independent_cache"]
	return final || strategy || independentCache
}

func validateRootDNSLocalServer(spec *RootDNSLocalServerSpec) error {
	if spec == nil || spec.Tag != "dns-local" || spec.Detour != "direct" {
		return errors.New("root_dns_local_server_invalid")
	}
	return nil
}

func hasRootDNSLocalServer(config map[string]any) bool {
	dns, _ := config["dns"].(map[string]any)
	servers, _ := dns["servers"].([]any)
	for _, raw := range servers {
		server, ok := raw.(map[string]any)
		if ok && server["tag"] == "dns-local" {
			return true
		}
	}
	return false
}

func validateRootDNSDirectServer(spec *RootDNSDirectServerSpec) error {
	if spec == nil || spec.Tag != "dns-direct" {
		return errors.New("root_dns_direct_server_invalid")
	}
	return nil
}

func hasRootDNSDirectServer(config map[string]any) bool {
	dns, _ := config["dns"].(map[string]any)
	servers, _ := dns["servers"].([]any)
	for _, raw := range servers {
		server, ok := raw.(map[string]any)
		if ok && server["tag"] == "dns-direct" {
			return true
		}
	}
	return false
}

func parseRootDNSHosts(raw string) (map[string][]string, error) {
	hosts := make(map[string][]string)
	seen := make(map[string]map[string]bool)
	lines := strings.Split(raw, "\n")
	if len(lines) > 4096 {
		return nil, errors.New("root_dns_hosts_invalid")
	}
	for _, line := range lines {
		fields := strings.Fields(strings.TrimSpace(line))
		if len(fields) == 0 || strings.HasPrefix(fields[0], "#") {
			continue
		}
		if len(fields[0]) > 253 || strings.ContainsAny(fields[0], "\x00\r") {
			return nil, errors.New("root_dns_hosts_invalid")
		}
		for _, address := range fields[1:] {
			if net.ParseIP(address) == nil {
				continue
			}
			if seen[fields[0]] == nil {
				seen[fields[0]] = make(map[string]bool)
			}
			if !seen[fields[0]][address] {
				hosts[fields[0]] = append(hosts[fields[0]], address)
				seen[fields[0]][address] = true
			}
		}
	}
	return hosts, nil
}

func hasRootDNSHosts(config map[string]any) bool {
	dns, _ := config["dns"].(map[string]any)
	servers, _ := dns["servers"].([]any)
	for _, raw := range servers {
		server, ok := raw.(map[string]any)
		if ok && server["tag"] == "dns-hosts" {
			return true
		}
	}
	return false
}

func isRootRouteDNSPort53RuleForMaterialization(value any) bool {
	rule, ok := value.(map[string]any)
	outbound, hasOutbound := rule["outbound"].(string)
	if !ok || len(rule) != 2 || !hasOutbound || outbound == "" {
		return false
	}
	switch ports := rule["port"].(type) {
	case []int:
		return len(ports) == 1 && ports[0] == 53
	case []any:
		return len(ports) == 1 && ports[0] == float64(53)
	default:
		return false
	}
}

func isRootRouteDNSProtocolRuleForMaterialization(value any) bool {
	rule, ok := value.(map[string]any)
	outbound, hasOutbound := rule["outbound"].(string)
	if !ok || len(rule) != 2 || !hasOutbound || outbound == "" {
		return false
	}
	switch protocols := rule["protocol"].(type) {
	case []string:
		return len(protocols) == 1 && protocols[0] == "dns"
	case []any:
		return len(protocols) == 1 && protocols[0] == "dns"
	default:
		return false
	}
}

func materializeRootRouteDNSTargetRules(route map[string]any, spec *RootRouteDNSTargetRulesSpec, config map[string]any, snapshot Snapshot) error {
	if err := validateRootRouteDNSTargetRules(spec, config, snapshot); err != nil {
		return err
	}
	rules, _ := route["rules"].([]any)
	portMatches := make([]int, 0, 1)
	protocolMatches := make([]int, 0, 1)
	for i, rule := range rules {
		if isRootRouteDNSPort53RuleForMaterialization(rule) {
			portMatches = append(portMatches, i)
		}
		if isRootRouteDNSProtocolRuleForMaterialization(rule) {
			protocolMatches = append(protocolMatches, i)
		}
	}
	if len(portMatches) != 1 || len(protocolMatches) != 1 || protocolMatches[0] != portMatches[0]+1 {
		return errors.New("root_route_dns_targets_fallback_invalid")
	}
	portOutbound := rules[portMatches[0]].(map[string]any)["outbound"]
	protocolOutbound := rules[protocolMatches[0]].(map[string]any)["outbound"]
	if portOutbound != protocolOutbound || portOutbound != snapshot.RootRouteDNSPort53Rule.Outbound {
		return errors.New("root_route_dns_targets_fallback_invalid")
	}
	inserted := make([]any, 0, len(spec.Rules))
	for _, target := range spec.Rules {
		inserted = append(inserted, map[string]any{target.Kind: append([]string(nil), target.Targets...), "outbound": target.Outbound})
	}
	index := portMatches[0]
	rules = append(rules, make([]any, len(inserted))...)
	copy(rules[index+len(inserted):], rules[index:len(rules)-len(inserted)])
	copy(rules[index:index+len(inserted)], inserted)
	route["rules"] = rules
	return nil
}

func materializeRootInbound(s Snapshot) ([]byte, error) {
	if s.SchemaVersion < SnapshotVersion || s.SchemaVersion > SnapshotVersionPortRangeOutboundRoutes {
		return s.Config, nil
	}
	if err := validateRootInbound(s.RootInbound); err != nil {
		return nil, err
	}
	var config map[string]any
	if err := json.Unmarshal(s.Config, &config); err != nil {
		return nil, errors.New("config_invalid")
	}
	if s.SchemaVersion >= SnapshotVersionRouteDomainRegexRejectRules && (s.RootRouteDomainRegexRejectRules != nil || hasRootRouteDomainRegexRejectMarker(config) || s.SchemaVersion == SnapshotVersionRouteDomainRegexRejectRules) {
		if err := validateRootRouteDomainRegexRejectRules(s.RootRouteDomainRegexRejectRules, config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteExactDomainOutbound && hasRootExactDomainOutboundMarker(config) {
		if err := materializeRootExactDomainOutbound(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteCompositeDomainIPOutbound && hasRootCompositeDomainIPOutboundMarker(config) {
		if err := materializeRootCompositeDomainIPOutbound(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteIPOutbound && hasRootIPOutboundRouteMarker(config) {
		if err := materializeRootIPOutboundRoute(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteNetworkOutboundRules && (s.RootRouteNetworkOutboundRules != nil || hasRootRouteNetworkOutboundMarker(config)) {
		if s.RootRouteDomainRegexRejectRules != nil || hasRootRouteDomainRegexRejectMarker(config) {
			return nil, errors.New("root_route_network_outbound_regex_conflict")
		}
		if err := validateRootRouteNetworkOutboundRules(s.RootRouteNetworkOutboundRules, config, s); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteAppUIDRules && (s.RootRouteAppUIDRules != nil || hasRootRouteAppUIDMarker(config)) {
		if err := validateRootRouteAppUIDRules(s.RootRouteAppUIDRules, config, s); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteRemoteRuleSets || hasRootRemoteRuleSetMarker(config) {
		if s.SchemaVersion < SnapshotVersionRouteRemoteRuleSets {
			return nil, errors.New("snapshot_invalid")
		}
		if err := validateRootRemoteRuleSets(config); err != nil {
			return nil, err
		}
	}
	inbounds, ok := config["inbounds"].([]any)
	if !ok {
		inbounds = []any{}
	}
	spec := s.RootInbound
	var inbound map[string]any
	if spec.Backend == "ebpf" {
		local := map[string]any{"enabled": true, "data_plane": "cgroup", "dns_mode": "respect_policy", "ipv6": spec.IPv6Mode != 0, "bypass_private_address": spec.BypassPrivateAddress}
		if len(spec.IncludeUIDs) > 0 {
			local["include_uid"] = spec.IncludeUIDs
		}
		if len(spec.ExcludeUIDs) > 0 {
			local["exclude_uid"] = spec.ExcludeUIDs
		}
		inbound = map[string]any{"type": "ebpf", "tag": "tun-in", "network": []string{"tcp", "udp"}, "udp_timeout": "5m", "local": local, "shared": map[string]any{"enabled": false}}
	} else {
		addresses := []string{"172.19.0.1/30"}
		if spec.IPv6Mode == 3 {
			addresses = []string{"fdfe:dcba:9876::1/126"}
		} else if spec.IPv6Mode != 0 {
			addresses = append(addresses, "fdfe:dcba:9876::1/126")
		}
		inbound = map[string]any{"type": "tun", "tag": "tun-in", "interface_name": "tun0", "mtu": spec.MTU, "auto_route": true, "auto_redirect": true, "strict_route": spec.StrictRoute, "address": addresses}
		if len(spec.IncludeUIDs) > 0 {
			inbound["include_uid"] = spec.IncludeUIDs
		}
		if len(spec.ExcludeUIDs) > 0 {
			inbound["exclude_uid"] = spec.ExcludeUIDs
		}
	}
	config["inbounds"] = append(inbounds, inbound)
	if s.SchemaVersion >= SnapshotVersionOutbounds {
		for _, spec := range s.RootOutbounds {
			if hasRootProxyGroup(config, spec.Tag) {
				return nil, errors.New("root_outbound_duplicate")
			}
			item, err := buildRootOutbound(spec)
			if err != nil {
				return nil, err
			}
			outbounds, _ := config["outbounds"].([]any)
			config["outbounds"] = append(outbounds, item)
		}
	}
	if s.SchemaVersion >= SnapshotVersionProxyGroup {
		group, err := buildRootProxyGroup(s.RootProxyGroup)
		if err != nil {
			return nil, err
		}
		outbounds, _ := config["outbounds"].([]any)
		config["outbounds"] = append(outbounds, group)
	}
	if s.SchemaVersion >= SnapshotVersionRoutePolicy && s.RootRoutePolicy != nil {
		if err := validateRootRoutePolicy(s.RootRoutePolicy); err != nil {
			return nil, err
		}
		route, _ := config["route"].(map[string]any)
		if route == nil {
			route = map[string]any{}
		}
		policy := s.RootRoutePolicy
		route["auto_detect_interface"] = policy.AutoDetectInterface
		route["override_android_vpn"] = policy.OverrideAndroidVPN
		route["find_process"] = policy.FindProcess
		if policy.DefaultDomainResolver != "" {
			route["default_domain_resolver"] = policy.DefaultDomainResolver
		}
		if policy.DefaultNetworkStrategy != "" {
			route["default_network_strategy"] = policy.DefaultNetworkStrategy
		}
		config["route"] = route
	}
	if s.SchemaVersion == SnapshotVersionRoutePolicy && s.RootRoutePolicy == nil {
		return nil, errors.New("root_route_policy_invalid")
	}
	if s.SchemaVersion >= SnapshotVersionDNSHosts && s.RootDNSHosts != nil {
		if err := validateRootDNSHosts(s.RootDNSHosts); err != nil {
			return nil, err
		}
		hosts, err := parseRootDNSHosts(s.RootDNSHosts.Raw)
		if err != nil {
			return nil, err
		}
		if len(hosts) > 0 {
			dns, _ := config["dns"].(map[string]any)
			if dns == nil {
				dns = map[string]any{}
			}
			servers, _ := dns["servers"].([]any)
			servers = append(servers, map[string]any{"type": "hosts", "tag": "dns-hosts", "predefined": hosts})
			dns["servers"] = servers
			config["dns"] = dns
		}
	}
	if s.SchemaVersion >= SnapshotVersionDNSPolicy {
		if err := validateRootDNSPolicy(s.RootDNSPolicy, config); err != nil {
			return nil, err
		}
		dns, _ := config["dns"].(map[string]any)
		if dns == nil {
			dns = map[string]any{}
		}
		dns["final"] = s.RootDNSPolicy.Final
		if s.RootDNSPolicy.Strategy != "" {
			dns["strategy"] = s.RootDNSPolicy.Strategy
		}
		if s.RootDNSPolicy.IndependentCache != nil {
			dns["independent_cache"] = *s.RootDNSPolicy.IndependentCache
		}
		config["dns"] = dns
	}
	if s.SchemaVersion >= SnapshotVersionDNSDefaultDirectServer {
		if err := validateRootDNSDirectServer(s.RootDNSDirectServer); err != nil {
			return nil, err
		}
		dns, _ := config["dns"].(map[string]any)
		if dns == nil {
			dns = map[string]any{}
		}
		servers, _ := dns["servers"].([]any)
		direct := map[string]any{"type": "https", "tag": s.RootDNSDirectServer.Tag, "server": "223.5.5.5", "server_port": 443, "path": "/dns-query", "detour": "direct"}
		dns["servers"] = append([]any{direct}, servers...)
		config["dns"] = dns
	}
	if s.SchemaVersion >= SnapshotVersionDNSLocalServer {
		if err := validateRootDNSLocalServer(s.RootDNSLocalServer); err != nil {
			return nil, err
		}
		dns, _ := config["dns"].(map[string]any)
		if dns == nil {
			dns = map[string]any{}
		}
		servers, _ := dns["servers"].([]any)
		local := map[string]any{"type": "local", "tag": s.RootDNSLocalServer.Tag, "detour": s.RootDNSLocalServer.Detour}
		dns["servers"] = append([]any{local}, servers...)
		config["dns"] = dns
	}
	if s.SchemaVersion >= SnapshotVersionRouteFinal {
		if err := validateRootRouteFinal(s.RootRouteFinal, config, s); err != nil {
			return nil, err
		}
		route, _ := config["route"].(map[string]any)
		if route == nil {
			route = map[string]any{}
		}
		route["final"] = s.RootRouteFinal.Final
		config["route"] = route
	}
	if s.SchemaVersion >= SnapshotVersionRouteIPVersionRule {
		if err := validateRootRouteIPVersionRule(s.RootRouteIPVersionRule, config, s); err != nil {
			return nil, err
		}
		route, _ := config["route"].(map[string]any)
		if route == nil {
			route = map[string]any{}
		}
		rules, _ := route["rules"].([]any)
		rule := map[string]any{"ip_version": s.RootRouteIPVersionRule.IPVersion, "action": "reject"}
		rules = append(rules, nil)
		copy(rules[s.RootRouteIPVersionRule.Index+1:], rules[s.RootRouteIPVersionRule.Index:])
		rules[s.RootRouteIPVersionRule.Index] = rule
		route["rules"] = rules
		config["route"] = route
	}
	if s.SchemaVersion >= SnapshotVersionRouteDNSProtocolRule && s.RootRouteDNSProtocolRule != nil {
		if err := validateRootRouteDNSProtocolRule(s.RootRouteDNSProtocolRule, config, s); err != nil {
			return nil, err
		}
		route, _ := config["route"].(map[string]any)
		if route == nil {
			route = map[string]any{}
		}
		rules, _ := route["rules"].([]any)
		rule := map[string]any{"protocol": []string{"dns"}, "outbound": s.RootRouteDNSProtocolRule.Outbound}
		rules = append(rules, nil)
		copy(rules[s.RootRouteDNSProtocolRule.Index+1:], rules[s.RootRouteDNSProtocolRule.Index:])
		rules[s.RootRouteDNSProtocolRule.Index] = rule
		route["rules"] = rules
		config["route"] = route
	}
	if s.SchemaVersion >= SnapshotVersionRouteDNSPort53Rule && s.RootRouteDNSPort53Rule != nil {
		if err := validateRootRouteDNSPort53Rule(s.RootRouteDNSPort53Rule, config, s); err != nil {
			return nil, err
		}
		route, _ := config["route"].(map[string]any)
		if route == nil {
			route = map[string]any{}
		}
		rules, _ := route["rules"].([]any)
		rule := map[string]any{"port": []int{53}, "outbound": s.RootRouteDNSPort53Rule.Outbound}
		rules = append(rules, nil)
		copy(rules[s.RootRouteDNSPort53Rule.Index+1:], rules[s.RootRouteDNSPort53Rule.Index:])
		rules[s.RootRouteDNSPort53Rule.Index] = rule
		route["rules"] = rules
		config["route"] = route
	}
	if s.SchemaVersion >= SnapshotVersionRouteBypassLAN && s.RootRouteBypassLAN != nil {
		if err := validateRootRouteBypassLAN(s.RootRouteBypassLAN, config, s); err != nil {
			return nil, err
		}
		route, _ := config["route"].(map[string]any)
		if route == nil {
			route = map[string]any{}
		}
		rules, _ := route["rules"].([]any)
		guard := map[string]any{"ip_cidr": []string{"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"}, "outbound": "direct"}
		rules = append(rules, nil)
		copy(rules[s.RootRouteBypassLAN.Index+1:], rules[s.RootRouteBypassLAN.Index:])
		rules[s.RootRouteBypassLAN.Index] = guard
		route["rules"] = rules
		config["route"] = route
	}
	if s.SchemaVersion >= SnapshotVersionRouteMulticast && s.RootRouteMulticast != nil {
		if err := validateRootRouteMulticast(s.RootRouteMulticast, config, s); err != nil {
			return nil, err
		}
		route, _ := config["route"].(map[string]any)
		if route == nil {
			route = map[string]any{}
		}
		rules, _ := route["rules"].([]any)
		guard := map[string]any{"ip_cidr": []string{"224.0.0.0/3", "ff00::/8"}, "source_ip_cidr": []string{"224.0.0.0/3", "ff00::/8"}, "action": "reject"}
		rules = append(rules, nil)
		copy(rules[s.RootRouteMulticast.Index+1:], rules[s.RootRouteMulticast.Index:])
		rules[s.RootRouteMulticast.Index] = guard
		route["rules"] = rules
		config["route"] = route
	}
	if s.SchemaVersion >= SnapshotVersionRouteLoopback && s.RootRouteLoopback != nil {
		if err := validateRootRouteLoopback(s.RootRouteLoopback, config, s); err != nil {
			return nil, err
		}
		route, _ := config["route"].(map[string]any)
		if route == nil {
			route = map[string]any{}
		}
		rules, _ := route["rules"].([]any)
		guard := map[string]any{"ip_cidr": []string{"127.0.0.0/8", "::1/128", "224.0.0.0/4", "169.254.0.0/16", "fe80::/10"}, "outbound": "direct"}
		rules = append(rules, nil)
		copy(rules[s.RootRouteLoopback.Index+1:], rules[s.RootRouteLoopback.Index:])
		rules[s.RootRouteLoopback.Index] = guard
		route["rules"] = rules
		config["route"] = route
	}
	if s.SchemaVersion >= SnapshotVersionRoutePrivateBypass && s.RootRoutePrivateBypass != nil {
		if err := validateRootRoutePrivateBypass(s.RootRoutePrivateBypass, config, s); err != nil {
			return nil, err
		}
		route, _ := config["route"].(map[string]any)
		if route == nil {
			route = map[string]any{}
		}
		rules, _ := route["rules"].([]any)
		rule := map[string]any{"ip_is_private": true, "outbound": "bypass"}
		if s.RootRoutePrivateBypass.Index > len(rules) {
			return nil, errors.New("root_route_private_bypass_invalid")
		}
		rules = append(rules, nil)
		copy(rules[s.RootRoutePrivateBypass.Index+1:], rules[s.RootRoutePrivateBypass.Index:])
		rules[s.RootRoutePrivateBypass.Index] = rule
		route["rules"] = rules
		config["route"] = route
	}
	if s.SchemaVersion >= SnapshotVersionRouteDNSHijackPort53Rule && s.RootRouteDNSHijackPort53Rule != nil {
		if err := validateRootRouteDNSHijackPort53Rule(s.RootRouteDNSHijackPort53Rule, config, s); err != nil {
			return nil, err
		}
		route, _ := config["route"].(map[string]any)
		if route == nil {
			route = map[string]any{}
		}
		rules, _ := route["rules"].([]any)
		rule := map[string]any{"port": []int{53}, "action": "hijack-dns"}
		if s.RootRouteDNSHijackPort53Rule.Index > len(rules) {
			return nil, errors.New("root_route_dns_hijack_port53_invalid")
		}
		rules = append(rules, nil)
		copy(rules[s.RootRouteDNSHijackPort53Rule.Index+1:], rules[s.RootRouteDNSHijackPort53Rule.Index:])
		rules[s.RootRouteDNSHijackPort53Rule.Index] = rule
		route["rules"] = rules
		config["route"] = route
	}
	if s.SchemaVersion >= SnapshotVersionRouteDNSProtocolHijackRule && s.RootRouteDNSProtocolHijackRule != nil {
		if err := validateRootRouteDNSProtocolHijackRule(s.RootRouteDNSProtocolHijackRule, config, s); err != nil {
			return nil, err
		}
		route, _ := config["route"].(map[string]any)
		if route == nil {
			route = map[string]any{}
		}
		rules, _ := route["rules"].([]any)
		rule := map[string]any{"protocol": []string{"dns"}, "action": "hijack-dns"}
		if s.RootRouteDNSProtocolHijackRule.Index > len(rules) {
			return nil, errors.New("root_route_dns_protocol_hijack_invalid")
		}
		rules = append(rules, nil)
		copy(rules[s.RootRouteDNSProtocolHijackRule.Index+1:], rules[s.RootRouteDNSProtocolHijackRule.Index:])
		rules[s.RootRouteDNSProtocolHijackRule.Index] = rule
		route["rules"] = rules
		config["route"] = route
	}
	if s.SchemaVersion >= SnapshotVersionRouteDNSTargetRules && s.RootRouteDNSTargetRules != nil {
		route, _ := config["route"].(map[string]any)
		if route == nil {
			route = map[string]any{}
		}
		if err := materializeRootRouteDNSTargetRules(route, s.RootRouteDNSTargetRules, config, s); err != nil {
			return nil, err
		}
		config["route"] = route
	}
	if s.SchemaVersion >= SnapshotVersionRouteSniffRule && s.RootRouteSniffRule != nil {
		if err := validateRootRouteSniffRule(s.RootRouteSniffRule, config, s); err != nil {
			return nil, err
		}
		route, _ := config["route"].(map[string]any)
		if route == nil {
			route = map[string]any{}
		}
		rules, _ := route["rules"].([]any)
		rule := map[string]any{"action": "sniff"}
		if s.RootRouteSniffRule.Index > len(rules) {
			return nil, errors.New("root_route_sniff_invalid")
		}
		rules = append(rules, nil)
		copy(rules[s.RootRouteSniffRule.Index+1:], rules[s.RootRouteSniffRule.Index:])
		rules[s.RootRouteSniffRule.Index] = rule
		route["rules"] = rules
		config["route"] = route
	}
	if s.SchemaVersion >= SnapshotVersionRouteProbeRule && s.RootRouteProbeRule != nil {
		if err := validateRootRouteProbeRule(s.RootRouteProbeRule, config, s); err != nil {
			return nil, err
		}
		route, _ := config["route"].(map[string]any)
		if route == nil {
			route = map[string]any{}
		}
		rules, _ := route["rules"].([]any)
		rule := map[string]any{"inbound": []string{"mixed"}, "domain": []string{s.RootRouteProbeRule.Host}, "outbound": s.RootRouteProbeRule.Outbound}
		rules = append(rules, nil)
		copy(rules[1:], rules[:len(rules)-1])
		rules[0] = rule
		route["rules"] = rules
		config["route"] = route
	}
	if s.SchemaVersion >= SnapshotVersionRouteResolveRule && s.RootRouteResolveRule != nil {
		if err := validateRootRouteResolveRule(s.RootRouteResolveRule, config); err != nil {
			return nil, err
		}
		route, _ := config["route"].(map[string]any)
		if route == nil {
			route = map[string]any{}
		}
		rules, _ := route["rules"].([]any)
		// Exact ConfigBuilder resolve matches can only follow the optional Root probe/sniff rules.
		index := 0
		if s.RootRouteProbeRule != nil {
			index++
		}
		if s.RootRouteSniffRule != nil {
			index++
		}
		if index > len(rules) {
			return nil, errors.New("root_route_resolve_invalid")
		}
		rule := map[string]any{"action": "resolve", "strategy": s.RootRouteResolveRule.Strategy}
		rules = append(rules, nil)
		copy(rules[index+1:], rules[index:])
		rules[index] = rule
		route["rules"] = rules
		config["route"] = route
	}
	if s.SchemaVersion >= SnapshotVersionRouteModeRules && s.RootRouteModeRules != nil {
		if err := validateRootRouteModeRules(s.RootRouteModeRules, config, s); err != nil {
			return nil, err
		}
		route, _ := config["route"].(map[string]any)
		if route == nil {
			route = map[string]any{}
		}
		rules, _ := route["rules"].([]any)
		index := s.RootRouteModeRules.Index
		if index > len(rules) {
			return nil, errors.New("root_route_mode_rules_invalid")
		}
		rules = append(rules, nil, nil)
		copy(rules[index+2:], rules[index:len(rules)-2])
		rules[index] = map[string]any{"clash_mode": "global", "outbound": "proxy"}
		rules[index+1] = map[string]any{"clash_mode": "direct", "outbound": "direct"}
		route["rules"] = rules
		config["route"] = route
	}
	if s.SchemaVersion >= SnapshotVersionDNSIPv6QueryRule && s.RootDNSIPv6QueryRule != nil {
		if err := validateRootDNSIPv6QueryRule(s.RootDNSIPv6QueryRule, s.RootDNSPolicy, config); err != nil {
			return nil, err
		}
		dns, _ := config["dns"].(map[string]any)
		if dns == nil {
			dns = map[string]any{}
		}
		rules, _ := dns["rules"].([]any)
		rule := map[string]any{"query_type": []string{s.RootDNSIPv6QueryRule.QueryType}, "action": "reject"}
		rules = append(rules, nil)
		copy(rules[s.RootDNSIPv6QueryRule.Index+1:], rules[s.RootDNSIPv6QueryRule.Index:])
		rules[s.RootDNSIPv6QueryRule.Index] = rule
		dns["rules"] = rules
		config["dns"] = dns
	}
	if s.SchemaVersion >= SnapshotVersionDNSOutboundAnyRule && s.RootDNSOutboundAnyRule != nil {
		if err := validateRootDNSOutboundAnyRule(s.RootDNSOutboundAnyRule, config); err != nil {
			return nil, err
		}
		dns, _ := config["dns"].(map[string]any)
		if dns == nil {
			dns = map[string]any{}
		}
		rules, _ := dns["rules"].([]any)
		rule := map[string]any{"outbound": []string{"any"}, "server": "dns-direct"}
		rules = append(rules, nil)
		copy(rules[s.RootDNSOutboundAnyRule.Index+1:], rules[s.RootDNSOutboundAnyRule.Index:])
		rules[s.RootDNSOutboundAnyRule.Index] = rule
		dns["rules"] = rules
		config["dns"] = dns
	}
	if s.SchemaVersion >= SnapshotVersionDNSResolvers && s.RootDNSResolvers != nil {
		materializeRootDNSResolvers(config, s.RootDNSResolvers, s)
	}
	if s.SchemaVersion >= SnapshotVersionExactDomainDNSRules && hasRootExactDomainDNSRuleMarker(config) {
		if err := materializeRootExactDomainDNSRules(config, s); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionAppIdentityDNSRules && hasRootAppIdentityDNSRuleMarker(config) {
		if err := materializeRootAppIdentityDNSRules(config, s); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionAppIdentityDomainDNSRules && hasRootAppIdentityDomainDNSRuleMarker(config) {
		if err := materializeRootAppIdentityDomainDNSRules(config, s); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRoutePortRejectRules && (s.RootRoutePortRejectRules != nil || hasRootRoutePortRejectMarker(config)) {
		route, _ := config["route"].(map[string]any)
		if route == nil {
			return nil, errors.New("root_route_port_reject_invalid")
		}
		if err := materializeRootRoutePortRejectRules(route, s.RootRoutePortRejectRules, config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteNetworkRejectRules && (s.RootRouteNetworkRejectRules != nil || hasRootRouteNetworkRejectMarker(config)) {
		route, _ := config["route"].(map[string]any)
		if route == nil {
			return nil, errors.New("root_route_network_reject_invalid")
		}
		if err := materializeRootRouteNetworkRejectRules(route, s.RootRouteNetworkRejectRules, config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteNetworkPortRejectRules &&
		(s.RootRouteNetworkPortRejectRules != nil || hasRootRouteNetworkPortRejectMarker(config) || s.SchemaVersion == SnapshotVersionRouteNetworkPortRejectRules) {
		route, _ := config["route"].(map[string]any)
		if route == nil {
			return nil, errors.New("root_route_network_port_reject_invalid")
		}
		if err := materializeRootRouteNetworkPortRejectRules(route, s.RootRouteNetworkPortRejectRules); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteSourcePortRejectRules &&
		(s.RootRouteSourcePortRejectRules != nil || hasRootRouteSourcePortRejectMarker(config) || s.SchemaVersion == SnapshotVersionRouteSourcePortRejectRules) {
		route, _ := config["route"].(map[string]any)
		if route == nil {
			return nil, errors.New("root_route_source_port_reject_invalid")
		}
		if err := materializeRootRouteSourcePortRejectRules(route, s.RootRouteSourcePortRejectRules); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteProtocolRejectRules && (s.RootRouteProtocolRejectRules != nil || hasRootRouteProtocolRejectMarker(config)) {
		route, _ := config["route"].(map[string]any)
		if route == nil {
			return nil, errors.New("root_route_protocol_reject_invalid")
		}
		if err := materializeRootRouteProtocolRejectRules(route, s.RootRouteProtocolRejectRules); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteSourceIPRejectRules && (s.RootRouteSourceIPRejectRules != nil || hasRootRouteSourceIPRejectMarker(config)) {
		route, _ := config["route"].(map[string]any)
		if route == nil {
			return nil, errors.New("root_route_source_ip_reject_invalid")
		}
		if err := materializeRootRouteSourceIPRejectRules(route, s.RootRouteSourceIPRejectRules); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteIPRejectRules && (s.RootRouteIPRejectRules != nil || hasRootRouteIPRejectMarker(config)) {
		route, _ := config["route"].(map[string]any)
		if route == nil {
			return nil, errors.New("root_route_ip_reject_invalid")
		}
		if err := materializeRootRouteIPRejectRules(route, s.RootRouteIPRejectRules); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteSourceDestinationIPRejectRules && (s.RootRouteSourceDestinationIPRejectRules != nil || hasRootRouteSourceDestinationIPRejectMarker(config)) {
		route, _ := config["route"].(map[string]any)
		if route == nil {
			return nil, errors.New("root_route_source_destination_ip_reject_invalid")
		}
		if err := materializeRootRouteSourceDestinationIPRejectRules(route, s.RootRouteSourceDestinationIPRejectRules); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteSourceDestinationPortRejectRules && (s.RootRouteSourceDestinationPortRejectRules != nil || hasRootRouteSourceDestinationPortRejectMarker(config)) {
		route, _ := config["route"].(map[string]any)
		if route == nil {
			return nil, errors.New("root_route_source_destination_port_reject_invalid")
		}
		if err := materializeRootRouteSourceDestinationPortRejectRules(route, s.RootRouteSourceDestinationPortRejectRules); err != nil {
			return nil, err
		}
	}

	if s.SchemaVersion >= SnapshotVersionRouteProtocolPortRejectRules && (s.RootRouteProtocolPortRejectRules != nil || hasRootRouteProtocolPortRejectMarker(config)) {
		route, _ := config["route"].(map[string]any)
		if route == nil {
			return nil, errors.New("root_route_protocol_port_reject_invalid")
		}
		if err := materializeRootRouteProtocolPortRejectRules(route, s.RootRouteProtocolPortRejectRules); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteDomainRejectRules && (s.RootRouteDomainRejectRules != nil || hasRootRouteDomainRejectMarker(config)) {
		route, _ := config["route"].(map[string]any)
		if route == nil {
			return nil, errors.New("root_route_domain_reject_invalid")
		}
		if err := materializeRootRouteDomainRejectRules(route, s.RootRouteDomainRejectRules); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteDomainSuffixRejectRules && (s.RootRouteDomainSuffixRejectRules != nil || hasRootRouteDomainSuffixRejectMarker(config)) {
		route, _ := config["route"].(map[string]any)
		if route == nil {
			return nil, errors.New("root_route_domain_suffix_reject_invalid")
		}
		if err := materializeRootRouteDomainSuffixRejectRules(route, s.RootRouteDomainSuffixRejectRules); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteDomainKeywordRejectRules && (s.RootRouteDomainKeywordRejectRules != nil || hasRootRouteDomainKeywordRejectMarker(config)) {
		route, _ := config["route"].(map[string]any)
		if route == nil {
			return nil, errors.New("root_route_domain_keyword_reject_invalid")
		}
		if err := materializeRootRouteDomainKeywordRejectRules(route, s.RootRouteDomainKeywordRejectRules); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteDomainRegexRejectRules && (s.RootRouteDomainRegexRejectRules != nil || hasRootRouteDomainRegexRejectMarker(config)) {
		route, _ := config["route"].(map[string]any)
		if route == nil {
			return nil, errors.New("root_route_domain_regex_reject_invalid")
		}
		if err := materializeRootRouteDomainRegexRejectRules(route, s.RootRouteDomainRegexRejectRules); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteNetworkOutboundRules && (s.RootRouteNetworkOutboundRules != nil || hasRootRouteNetworkOutboundMarker(config)) {
		route, _ := config["route"].(map[string]any)
		if route == nil {
			return nil, errors.New("root_route_network_outbound_invalid")
		}
		if err := materializeRootRouteNetworkOutboundRules(route, s.RootRouteNetworkOutboundRules); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteAppUIDRules && (s.RootRouteAppUIDRules != nil || hasRootRouteAppUIDMarker(config)) {
		route, _ := config["route"].(map[string]any)
		if route == nil {
			return nil, errors.New("root_route_app_uid_invalid")
		}
		if err := materializeRootRouteAppUIDRules(route, s.RootRouteAppUIDRules); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionAppUIDDomainOutboundRoutes && hasRootAppUIDDomainOutboundRouteMarker(config) {
		route, _ := config["route"].(map[string]any)
		if route == nil {
			return nil, errors.New("root_app_uid_domain_outbound_invalid")
		}
		if err := materializeRootAppUIDDomainOutboundRoutes(route, s.RootAppUIDDomainOutboundRoutes); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionAppUIDRuleSetOutboundRoutes && hasRootAppUIDRuleSetOutboundMarker(config) {
		route, _ := config["route"].(map[string]any)
		if route == nil {
			return nil, errors.New("root_app_uid_ruleset_outbound_invalid")
		}
		if err := materializeRootAppUIDRuleSetOutboundRoutes(route, s.RootAppUIDRuleSetOutboundRoutes); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionAppUIDDomainRuleSetOutboundRoutes && hasRootAppUIDDomainRuleSetOutboundMarker(config) {
		route, _ := config["route"].(map[string]any)
		if route == nil {
			return nil, errors.New("root_app_uid_domain_ruleset_outbound_invalid")
		}
		if err := materializeRootAppUIDDomainRuleSetOutboundRoutes(route, s.RootAppUIDDomainRuleSetOutboundRoutes); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteRemoteRuleSets && hasRootRemoteRuleSetMarker(config) {
		route, _ := config["route"].(map[string]any)
		if route == nil {
			return nil, errors.New("root_remote_rule_set_invalid")
		}
		if err := materializeRootRemoteRuleSets(route); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRemoteRuleSetDNSEffects && hasRootRemoteRuleSetDNSEffectMarker(config) {
		if err := materializeRootRemoteRuleSetDNSEffects(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRemoteRuleSetOutboundRoutes && hasRootRemoteRuleSetOutboundRouteMarker(config) {
		if err := materializeRootRemoteRuleSetOutboundRoutes(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRemoteRuleSetPortOutboundRoutes && hasRootRemoteRuleSetPortOutboundRouteMarker(config) {
		if err := materializeRootRemoteRuleSetPortOutboundRoutes(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRemoteRuleSetIPVersionOutboundRoutes && hasRootRemoteRuleSetIPVersionOutboundRouteMarker(config) {
		if err := materializeRootRemoteRuleSetIPVersionOutboundRoutes(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRemoteRuleSetSourcePortOutboundRoutes && hasRootRemoteRuleSetSourcePortOutboundRouteMarker(config) {
		if err := materializeRootRemoteRuleSetSourcePortOutboundRoutes(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionDomainIPVersionOutboundRoutes && hasRootDomainIPVersionOutboundRouteMarker(config) {
		if err := materializeRootDomainIPVersionOutboundRoutes(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRemoteRuleSetDomainOutboundRoutes && hasRootRemoteRuleSetDomainOutboundRouteMarker(config) {
		if err := materializeRootRemoteRuleSetDomainOutboundRoutes(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRemoteRuleSetIPOutboundRoutes && hasRootRemoteRuleSetIPOutboundRouteMarker(config) {
		if err := materializeRootRemoteRuleSetIPOutboundRoutes(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRemoteRuleSetNetworkOutboundRoutes && hasRootRemoteRuleSetNetworkOutboundRouteMarker(config) {
		if err := materializeRootRemoteRuleSetNetworkOutboundRoutes(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRemoteRuleSetRejectRoutes && hasRootRemoteRuleSetRejectRouteMarker(config) {
		if err := materializeRootRemoteRuleSetRejectRoutes(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRemoteRuleSetNetworkRejectRoutes && hasRootRemoteRuleSetNetworkRejectRouteMarker(config) {
		if err := materializeRootRemoteRuleSetNetworkRejectRoutes(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRemoteRuleSetIPRejectRoutes && hasRootRemoteRuleSetIPRejectRouteMarker(config) {
		if err := materializeRootRemoteRuleSetIPRejectRoutes(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRemoteRuleSetDomainRejectRoutes && hasRootRemoteRuleSetDomainRejectRouteMarker(config) {
		if err := materializeRootRemoteRuleSetDomainRejectRoutes(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRemoteRuleSetIPVersionRejectRoutes && hasRootRemoteRuleSetIPVersionRejectRouteMarker(config) {
		if err := materializeRootRemoteRuleSetIPVersionRejectRoutes(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRemoteRuleSetPortRangeOutboundRoutes && hasRootRemoteRuleSetPortRangeOutboundRouteMarker(config) {
		if err := materializeRootRemoteRuleSetPortRangeOutboundRoutes(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRemoteRuleSetProtocolOutboundRoutes && hasRootRemoteRuleSetProtocolOutboundRouteMarker(config) {
		if err := materializeRootRemoteRuleSetProtocolOutboundRoutes(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionProtocolOutboundRoutes && hasRootProtocolOutboundRouteMarker(config) {
		if err := materializeRootProtocolOutboundRoutes(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionDomainOutboundRoutes && hasRootDomainOutboundRouteMarker(config) {
		if err := materializeRootDomainOutboundRoutes(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionNetworkPortOutboundRoutes && hasRootNetworkPortOutboundRouteMarker(config) {
		if err := materializeRootNetworkPortOutboundRoutes(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionDomainNetworkOutboundRoutes && hasRootDomainNetworkOutboundRouteMarker(config) {
		if err := materializeRootDomainNetworkOutboundRoutes(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionDomainPortOutboundRoutes && hasRootDomainPortOutboundRouteMarker(config) {
		if err := materializeRootDomainPortOutboundRoutes(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionSourceDestinationIPOutboundRoutes && hasRootSourceDestinationIPOutboundMarker(config) {
		if err := materializeRootSourceDestinationIPOutbound(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionSourceIPOutboundRoutes && hasRootSourceIPOutboundMarker(config) {
		if err := materializeRootSourceIPOutbound(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionIPVersionOutboundRoutes && hasRootIPVersionOutboundMarker(config) {
		if err := materializeRootIPVersionOutbound(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionSourcePortOutboundRoutes && hasRootSourcePortOutboundMarker(config) {
		if err := materializeRootSourcePortOutbound(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionPortOutboundRoutes && hasRootPortOutboundMarker(config) {
		if err := materializeRootPortOutbound(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionPortRangeOutboundRoutes {
		if hasRootPortRangeOutboundMarker(config, rootPortRangeOutboundMarker) {
			if err := materializeRootPortRangeOutbound(config, rootPortRangeOutboundMarker, "port_range"); err != nil {
				return nil, err
			}
		}
		if hasRootPortRangeOutboundMarker(config, rootSourcePortRangeOutboundMarker) {
			if err := materializeRootPortRangeOutbound(config, rootSourcePortRangeOutboundMarker, "source_port_range"); err != nil {
				return nil, err
			}
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteRemoteRuleSetReferences && hasRootRemoteRuleSetReferenceMarker(config) {
		if err := materializeRootRemoteRuleSetReferences(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteRawRules && hasRootRawRuleMarker(config) {
		if err := materializeRootRawRules(config); err != nil {
			return nil, err
		}
	}
	if s.SchemaVersion >= SnapshotVersionRouteInvertedGroups && hasRootInvertedGroupMarker(config) {
		if err := materializeRootInvertedGroups(config); err != nil {
			return nil, err
		}
	}

	encoded, err := json.Marshal(config)
	if err != nil {
		return nil, errors.New("config_invalid")
	}
	return encoded, nil
}
func validateRootOutbounds(specs []RootOutboundSpec, schemaVersion int) error {
	if len(specs) > 4096 {
		return errors.New("root_outbound_invalid")
	}
	seen := map[string]bool{}
	for _, spec := range specs {
		if spec.Tag == "" || len(spec.Tag) > 256 || strings.ContainsAny(spec.Tag, "\\/\x00\r\n") || seen[spec.Tag] || spec.Server == "" || len(spec.Server) > 253 || spec.ServerPort < 1 || spec.ServerPort > 65535 || len(spec.Password) > 4096 {
			return errors.New("root_outbound_invalid")
		}
		seen[spec.Tag] = true
		switch spec.Protocol {
		case "shadowsocks":
			if spec.Method == "" || len(spec.Method) > 128 || len(spec.Plugin) > 128 || len(spec.PluginOptions) > 2048 || spec.TLS || spec.TLSServerName != "" || spec.TLSInsecure || len(spec.TLSALPN) > 0 {
				return errors.New("root_outbound_invalid")
			}
		case "socks":
			if spec.Version != "4" && spec.Version != "4a" && spec.Version != "5" {
				return errors.New("root_outbound_invalid")
			}
			if len(spec.Username) > 1024 || spec.TLS || spec.TLSServerName != "" || spec.TLSInsecure || len(spec.TLSALPN) > 0 {
				return errors.New("root_outbound_invalid")
			}
		case "trojan":
			if schemaVersion < SnapshotVersionTLSOutbounds || spec.Password == "" || !spec.TLS || len(spec.TLSServerName) > 253 || len(spec.TLSALPN) > 16 || spec.DomainStrategy != "" || spec.UDPOverTCP || spec.Method != "" || spec.Version != "" || spec.Username != "" || spec.Plugin != "" || spec.PluginOptions != "" {
				return errors.New("root_outbound_invalid")
			}
			for _, alpn := range spec.TLSALPN {
				if alpn == "" || len(alpn) > 64 {
					return errors.New("root_outbound_invalid")
				}
			}
		case "http":
			if schemaVersion < SnapshotVersionTLSOutbounds || len(spec.Username) > 1024 || (spec.TLS && (len(spec.TLSServerName) > 253 || len(spec.TLSALPN) > 16)) || (!spec.TLS && (spec.TLSServerName != "" || spec.TLSInsecure || len(spec.TLSALPN) > 0)) || spec.DomainStrategy != "" || spec.UDPOverTCP || spec.Method != "" || spec.Version != "" || spec.Plugin != "" || spec.PluginOptions != "" {
				return errors.New("root_outbound_invalid")
			}
			for _, alpn := range spec.TLSALPN {
				if alpn == "" || len(alpn) > 64 {
					return errors.New("root_outbound_invalid")
				}
			}
		default:
			return errors.New("root_outbound_invalid")
		}
	}
	return nil
}
func buildRootOutbound(spec RootOutboundSpec) (map[string]any, error) {
	if err := validateRootOutbounds([]RootOutboundSpec{spec}, SnapshotVersionTLSOutbounds); err != nil {
		return nil, err
	}
	out := map[string]any{"type": spec.Protocol, "tag": spec.Tag, "server": spec.Server, "server_port": spec.ServerPort}
	if spec.DomainStrategy != "" {
		out["domain_strategy"] = spec.DomainStrategy
	}
	if spec.UDPOverTCP {
		out["udp_over_tcp"] = true
	}
	switch spec.Protocol {
	case "shadowsocks":
		out["method"], out["password"] = spec.Method, spec.Password
		if spec.Plugin != "" {
			out["plugin"] = spec.Plugin
		}
		if spec.PluginOptions != "" {
			out["plugin_opts"] = spec.PluginOptions
		}
	case "socks":
		out["version"] = spec.Version
		if spec.Username != "" {
			out["username"] = spec.Username
		}
		if spec.Password != "" {
			out["password"] = spec.Password
		}
	case "http":
		if spec.Username != "" {
			out["username"] = spec.Username
		}
		if spec.Password != "" {
			out["password"] = spec.Password
		}
	case "trojan":
		out["password"] = spec.Password
	}
	if spec.TLS {
		serverName := spec.TLSServerName
		if serverName == "" {
			serverName = spec.Server
		}
		tls := map[string]any{"enabled": true, "insecure": spec.TLSInsecure, "server_name": serverName}
		if len(spec.TLSALPN) > 0 {
			tls["alpn"] = spec.TLSALPN
		}
		out["tls"] = tls
	}
	return out, nil
}
func validateRootProxyGroup(spec *RootProxyGroupSpec) error {
	if spec == nil || spec.Tag != "proxy" || (spec.Type != "selector" && spec.Type != "urltest" && spec.Type != "loadbalance") || len(spec.Members) == 0 || len(spec.Members) > 4096 {
		return errors.New("root_proxy_group_invalid")
	}
	seen := map[string]bool{}
	for _, member := range spec.Members {
		if member == "" || len(member) > 256 || strings.ContainsAny(member, "\\/\x00\r\n") || seen[member] {
			return errors.New("root_proxy_group_invalid")
		}
		seen[member] = true
	}
	if spec.Type == "selector" {
		if spec.Default == "" || !seen[spec.Default] {
			return errors.New("root_proxy_group_invalid")
		}
	} else {
		if spec.URL == "" || len(spec.URL) > 2048 || spec.Interval == "" || len(spec.Interval) > 32 || spec.Tolerance < 0 || spec.Tolerance > 600000 || len(spec.IdleTimeout) > 32 {
			return errors.New("root_proxy_group_invalid")
		}
		if spec.Type == "loadbalance" && spec.Strategy == "" {
			return errors.New("root_proxy_group_invalid")
		}
	}
	return nil
}
func hasRootProxyGroup(config map[string]any, tag string) bool {
	outbounds, _ := config["outbounds"].([]any)
	for _, raw := range outbounds {
		if item, ok := raw.(map[string]any); ok && item["tag"] == tag {
			return true
		}
	}
	return false
}
func buildRootProxyGroup(spec *RootProxyGroupSpec) (map[string]any, error) {
	if err := validateRootProxyGroup(spec); err != nil {
		return nil, err
	}
	group := map[string]any{"type": spec.Type, "tag": spec.Tag, "outbounds": spec.Members}
	switch spec.Type {
	case "selector":
		group["default"] = spec.Default
	case "urltest":
		group["url"] = spec.URL
		group["interval"] = spec.Interval
		group["tolerance"] = spec.Tolerance
		if spec.IdleTimeout != "" {
			group["idle_timeout"] = spec.IdleTimeout
		}
		group["interrupt_exist_connections"] = spec.InterruptExisting
	case "loadbalance":
		group["strategy"] = spec.Strategy
		group["url"] = spec.URL
		group["interval"] = spec.Interval
		group["tolerance"] = spec.Tolerance
		if spec.IdleTimeout != "" {
			group["idle_timeout"] = spec.IdleTimeout
		}
		group["interrupt_exist_connections"] = spec.InterruptExisting
	}
	return group, nil
}

func safeRelative(s string) bool {
	return safeName.MatchString(s) && !strings.Contains(s, "\\") && !filepath.IsAbs(s) && filepath.ToSlash(filepath.Clean(s)) == s && !strings.Contains(s, "../")
}
func checkRuntimePaths(v any, files map[string][]byte) error { return checkPaths(v, files, "") }
func checkPaths(v any, files map[string][]byte, parent string) error {
	switch x := v.(type) {
	case map[string]any:
		for k, v := range x {
			if text, ok := v.(string); ok {
				if strings.Contains(text, "/data/user/") || strings.Contains(text, "/data/data/") || strings.Contains(text, "/storage/emulated/") {
					return errors.New("app_path_not_portable")
				}
				filePath := strings.HasSuffix(k, "_path") || k == "external_ui" ||
					(k == "path" && (x["type"] == "local" || parent == "cache_file")) || (k == "output" && parent == "log")
				if filePath {
					// Existing wanBox geo aliases are interpreted by libcore, not
					// filesystem traversal. Require their portable backing asset.
					if k == "path" && x["type"] == "local" {
						if geo := regexp.MustCompile(`^(geoip|geosite)[:-]([a-zA-Z0-9_@!-]+)(?:\.srs)?$`).FindStringSubmatch(text); geo != nil {
							_, db := files["assets/"+geo[1]+".db"]
							_, binary := files["assets/"+geo[1]+"-"+strings.TrimSuffix(geo[2], ".srs")+".srs"]
							if !db && !binary {
								return errors.New("config_resource_missing")
							}
							continue
						}
					}
					// Paths are relative to generation/run; writable cache has one fixed location.
					if text == "../files/yacd" {
						if _, ok := files["files/yacd/index.html"]; !ok {
							return errors.New("config_resource_missing")
						}
					}
					if text != "" && text != "cache.db" && text != "../files/yacd" && !(parent == "log" && (text == "stdout" || text == "stderr")) {
						name := strings.TrimPrefix(text, "../")
						if !strings.HasPrefix(text, "../") || !safeRelative(name) {
							return errors.New("unsafe_config_path")
						}
						if _, exists := files[name]; !exists {
							return errors.New("config_resource_missing")
						}
					}
				}
			}
			if e := checkPaths(v, files, k); e != nil {
				return e
			}
		}
	case []any:
		for _, v := range x {
			if e := checkPaths(v, files, parent); e != nil {
				return e
			}
		}
	}
	return nil
}
func (r *Runtime) init() error {
	if e := privateDirectory(r.Root); e != nil {
		return e
	}
	for _, d := range []string{"generations", "runtime"} {
		if e := privateDirectory(r.path(d)); e != nil {
			return e
		}
	}
	return nil
}
func privateDirectory(p string) error {
	if info, e := os.Lstat(p); e == nil && (!info.IsDir() || info.Mode()&os.ModeSymlink != 0) {
		return errors.New("unsafe_directory")
	}
	if e := os.MkdirAll(p, 0700); e != nil {
		return e
	}
	return os.Chmod(p, 0700)
}
func (r *Runtime) Current() (string, Snapshot, error) {
	var s Snapshot
	rev, e := r.CurrentRevision()
	if e != nil || rev == "" {
		return rev, s, e
	}
	e = readJSON(r.path("generations", rev, "snapshot.json"), &s, MaxSnapshotBytes)
	if e == nil {
		e = CheckSnapshot(s)
	}
	return rev, s, e
}
func (r *Runtime) CurrentRevision() (string, error) {
	f, e := os.Open(r.path("current"))
	if os.IsNotExist(e) {
		return "", nil
	}
	if e != nil {
		return "", e
	}
	defer f.Close()
	b, e := io.ReadAll(io.LimitReader(f, 256))
	if e != nil {
		return "", e
	}
	rev := strings.TrimSpace(string(b))
	if !revisionName.MatchString(rev) {
		return "", errors.New("current_invalid")
	}
	return rev, nil
}
func (r *Runtime) Stage(ctx context.Context, s Snapshot) (string, error) {
	if e := CheckSnapshot(s); e != nil {
		return "", e
	}
	if e := r.init(); e != nil {
		return "", e
	}
	effectiveConfig, e := materializeRootInbound(s)
	if e != nil {
		return "", e
	}
	encoded, e := json.Marshal(s)
	if e != nil {
		return "", e
	}
	hash := sha256.Sum256(encoded)
	rev := hex.EncodeToString(hash[:])
	dir, e := os.MkdirTemp(r.path("generations"), ".stage-")
	if e != nil {
		return "", e
	}
	defer os.RemoveAll(dir)
	for _, d := range []string{"run", "assets", "files", "plugins"} {
		if e = privateDirectory(filepath.Join(dir, d)); e != nil {
			return "", e
		}
	}
	for name, b := range s.Files {
		p := filepath.Join(dir, filepath.FromSlash(name))
		if e = privateDirectory(filepath.Dir(p)); e != nil {
			return "", e
		}
		mode := os.FileMode(0600)
		if strings.HasPrefix(name, "plugins/") {
			mode = 0700
		}
		if e = AtomicWrite(p, b, mode); e != nil {
			return "", e
		}
	}
	if e = AtomicWrite(filepath.Join(dir, "run", "config.json"), effectiveConfig, 0600); e != nil {
		return "", e
	}
	if e = r.Validate(ctx, filepath.Join(dir, "run", "config.json"), filepath.Join(dir, "assets")); e != nil {
		return "", e
	}
	if e = jsonWrite(filepath.Join(dir, "snapshot.json"), s); e != nil {
		return "", e
	}
	dst := r.path("generations", rev)
	if _, e = os.Stat(dst); e == nil {
		return rev, nil
	}
	if e = os.Rename(dir, dst); e != nil {
		return "", e
	}
	if e = syncDir(r.path("generations")); e != nil {
		return "", e
	}
	return rev, nil
}

// @author 雾晚：从 /proc/<pid>/status 安全读取 VmRSS（字节）。
// PID 身份已由 SameProcess 校验，读取前后核对代次。
// 使用可注入的解析器，便于测试溢出与非法值。
var vmRSSParser = parseVmRSSFromText

func readVmRSS(pid int) int64 {
	data, err := os.ReadFile(fmt.Sprintf("/proc/%d/status", pid))
	if err != nil {
		return 0
	}
	return vmRSSParser(string(data))
}

// @author 雾晚：从 status 文本解析 VmRSS，处理溢出与非法值。
func parseVmRSSFromText(text string) int64 {
	for _, line := range strings.Split(text, "\n") {
		if strings.HasPrefix(line, "VmRSS:") {
			fields := strings.Fields(line)
			if len(fields) >= 2 {
				var kb int64
				if _, err := fmt.Sscanf(fields[1], "%d", &kb); err != nil {
					return 0
				}
				if kb < 0 {
					return 0
				}
				// 溢出检查：kb * 1024 不得超过 MaxInt64
				if kb > (1<<63-1)/1024 {
					return 0
				}
				return kb * 1024
			}
		}
	}
	return 0
}

// @author 雾晚：从 ready marker 文件时间计算核心就绪时长（秒）。
// Start 是 /proc/<pid>/stat 的启动 ticks（非 RFC3339），不硬猜 HZ。
// 使用 runtime/ready 文件的修改时间作为可说明的就绪起点，
// 读取前后验证 SameProcess 与 revision 一致，防止 PID 复用。
func (r *Runtime) coreReadyDuration(core ProcessIdentity, revision string) int64 {
	if !SameProcess(core) {
		return 0
	}
	fi, err := os.Stat(r.path("runtime", "ready"))
	if err != nil {
		return 0
	}
	// 读取后再次验证，防止竞态
	if !SameProcess(core) {
		return 0
	}
	if rev, _ := r.CurrentRevision(); rev != revision {
		return 0
	}
	d := time.Since(fi.ModTime())
	if d < 0 {
		return 0
	}
	return int64(d.Seconds())
}

// @author 雾晚：从运行中配置读取 Clash API secret，仅本地鉴权。
// 真实路径：generations/<runningRevision>/run/config.json
// 绑定 revision，有上限流式读取，禁止先全量再检查。
func (r *Runtime) clashAPISecret(revision string) string {
	if revision == "" || !revisionName.MatchString(revision) {
		return ""
	}
	path := r.path("generations", revision, "run", "config.json")
	f, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer f.Close()
	// 有上限读取：MaxConfigBytes+1，超限明确拒绝
	limited := io.LimitReader(f, MaxConfigBytes+1)
	data, err := io.ReadAll(limited)
	if err != nil || len(data) > MaxConfigBytes {
		return ""
	}
	var config struct {
		Experimental struct {
			ClashAPI struct {
				Secret string `json:"secret"`
			} `json:"clash_api"`
		} `json:"experimental"`
	}
	if err := json.Unmarshal(data, &config); err != nil {
		return ""
	}
	return config.Experimental.ClashAPI.Secret
}

// @author 雾晚：共享受限 loopback 客户端，避免每轮新建遗留 idle socket。
// 禁用环境代理，禁重定向。调用方负责 ctx 超时。
var loopbackClient = &http.Client{
	Transport: &http.Transport{
		Proxy:              nil, // 禁用环境代理，仅 loopback
		MaxIdleConns:       2,
		IdleConnTimeout:    10 * time.Second,
		DisableCompression: true,
	},
	CheckRedirect: func(req *http.Request, via []*http.Request) error {
		return errors.New("redirect_forbidden")
	},
}

// @author 雾晚：通过受限 loopback API 获取连接数与累计流量。
// 仅访问 127.0.0.1:9090，复用受限客户端，总 ctx 超时 5s，
// 响应读 max+1 明确拒绝超限。请求后由调用方验证代次。
func (r *Runtime) queryClashStats(revision string, core ProcessIdentity) (tx, rx *int64, connCount *int) {
	secret := r.clashAPISecret(revision)
	if secret == "" {
		return nil, nil, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, "GET", "http://127.0.0.1:9090/connections", nil)
	if err != nil {
		return nil, nil, nil
	}
	req.Header.Set("Authorization", "Bearer "+secret)
	// @author 雾晚：复用受限客户端，避免每轮遗留 idle socket
	resp, err := loopbackClient.Do(req)
	if err != nil {
		return nil, nil, nil
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, nil, nil
	}
	// 读 max+1 (256KB+1)，超限明确拒绝
	const maxResp = 262144
	limited := io.LimitReader(resp.Body, maxResp+1)
	data, err := io.ReadAll(limited)
	if err != nil || len(data) > maxResp {
		return nil, nil, nil
	}
	var result struct {
		UploadTotal   *int64            `json:"uploadTotal"`
		DownloadTotal *int64            `json:"downloadTotal"`
		Connections   []json.RawMessage `json:"connections"`
	}
	if err := json.Unmarshal(data, &result); err != nil {
		return nil, nil, nil
	}
	if result.UploadTotal != nil && *result.UploadTotal < 0 {
		result.UploadTotal = nil
	}
	if result.DownloadTotal != nil && *result.DownloadTotal < 0 {
		result.DownloadTotal = nil
	}
	var count *int
	if result.Connections != nil {
		c := len(result.Connections)
		count = &c
	}
	return result.UploadTotal, result.DownloadTotal, count
}

// @author 雾晚：填充本地遥测数据（无 HTTP）。
// Runtime.Status() 必须保持快速本地检查，不访问网络。
// Tx/Rx/connectionCount 由专用 dashboard 命令在 CLI 层显式获取，
// 不经过 Status()，避免守护循环/持锁逻辑被网络等待阻塞。
func (r *Runtime) fillTelemetry(s *State) {
	if !SameProcess(s.Core) {
		s.Telemetry = nil
		return
	}
	revision := s.RunningRevision
	t := &Telemetry{}
	// VmRSS：读取后再次验证身份
	t.VmRSSBytes = readVmRSS(s.Core.PID)
	if !SameProcess(s.Core) {
		s.Telemetry = nil
		return
	}
	// 运行时长：ready marker 时间，需验证 revision 一致
	t.UptimeSeconds = r.coreReadyDuration(s.Core, revision)
	// Tx/Rx/connectionCount: nil（未知），由 dashboard 命令单独采集
	// Status() 永不发起 HTTP，保持快速
	if t.VmRSSBytes > 0 || t.UptimeSeconds > 0 {
		s.Telemetry = t
	} else {
		s.Telemetry = nil
	}
}

// @author 雾晚：可注入的 Dashboard 状态采集，用于测试。
// readStatus: 读取状态；collect: 采集遥测；sameProc: 进程身份校验。
// 生产代码传入真实实现，测试传入模拟。
func dashboardStateInternal(
	readStatus func() State,
	collect func(snap State) *Telemetry,
	sameProc func(ProcessIdentity) bool,
) State {
	before := readStatus()
	if before.Phase != "connected" || !sameProc(before.Core) {
		return before
	}
	// 以 before 采集
	t := collect(before)

	// 无条件回读，严格验证
	after := readStatus()
	if after.Phase != before.Phase {
		after.Telemetry = nil
		after.Stats = nil
		return after
	}
	if after.Core.PID != before.Core.PID || after.Core.Start != before.Core.Start || after.Core.Executable != before.Core.Executable {
		after.Telemetry = nil
		after.Stats = nil
		return after
	}
	if after.RunningRevision != before.RunningRevision {
		after.Telemetry = nil
		after.Stats = nil
		return after
	}
	// ProfileID：热切换节点同 PID/revision 也必须识别
	if after.ProfileID != before.ProfileID {
		after.Telemetry = nil
		after.Stats = nil
		return after
	}
	if !sameProc(after.Core) {
		after.Telemetry = nil
		after.Stats = nil
		return after
	}
	// 一致：把采集赋给 after
	if t != nil {
		after.Telemetry = t
	}
	return after
}

// @author 雾晚：返回绑定同一快照的完整 Dashboard 状态。
// 生产代码调用可注入版本，传入真实实现。
func (r *Runtime) DashboardState() State {
	return dashboardStateInternal(
		r.Status,
		func(snap State) *Telemetry {
			t := &Telemetry{}
			t.VmRSSBytes = readVmRSS(snap.Core.PID)
			t.UptimeSeconds = r.coreReadyDuration(snap.Core, snap.RunningRevision)
			tx, rx, connCount := r.queryClashStats(snap.RunningRevision, snap.Core)
			t.TxBytes = tx
			t.RxBytes = rx
			t.ConnectionCount = connCount
			if t.VmRSSBytes <= 0 && t.UptimeSeconds <= 0 && tx == nil && rx == nil && connCount == nil {
				return nil
			}
			return t
		},
		SameProcess,
	)
}

// @author 雾晚：DashboardTelemetry 仅为兼容保留，调用完整 DashboardState。
// 不另读自己的快照，避免代次混合。
func (r *Runtime) DashboardTelemetry() *Telemetry {
	s := r.DashboardState()
	return s.Telemetry
}

func (r *Runtime) Status() State {
	s := State{SchemaVersion: Version, Phase: "stopped"}
	_ = readJSON(r.path("state.json"), &s, 1<<20)
	rev, err := r.CurrentRevision()
	if err != nil {
		s.Error = "current_invalid"
	}
	s.Revision = rev
	if !r.Enabled() {
		s.Phase = "disabled"
	}
	if !SameProcess(s.Supervisor) {
		// Do not show stale connected/failed status as this process's success.
		if SameProcess(s.Core) {
			s.Phase = "cleanup_required"
			s.Error = "supervisor_missing"
		} else if s.Phase != "disabled" && s.Phase != "failed" {
			s.Phase = "stopped"
		}
	} else if s.Phase == "connected" {
		if !SameProcess(s.Core) {
			s.Phase = "failed"
			s.Error = "core_exited"
		} else if _, e := os.Stat(r.path("runtime", "ready")); e != nil {
			s.Phase = "starting"
		}
	}
	if s.Phase != "connected" {
		s.Stats = nil
		s.Telemetry = nil
	} else {
		// @author 雾晚：已连接时填充遥测，进程身份已校验
		r.fillTelemetry(&s)
	}
	selection, selectionError := r.installSelection()
	s.InstallData = selection
	if selectionError != nil {
		s.Error = "install_data_selection_invalid"
	} else if selection != nil {
		s.Error = "install_data_update_pending"
	}
	return s
}
func (r *Runtime) Enabled() bool {
	if _, e := os.Stat(filepath.Join(r.ModuleDir, "module.prop")); e != nil {
		return false
	}
	for _, flag := range []string{"disable", "remove"} {
		if _, e := os.Stat(filepath.Join(r.ModuleDir, flag)); e == nil {
			return false
		}
	}
	return true
}
func (r *Runtime) withLock(fn func() error) error {
	return r.withDataLock(func() error {
		if _, e := os.Lstat(r.path("install-data.json")); e == nil {
			return errors.New("install_data_update_pending")
		} else if !os.IsNotExist(e) {
			return e
		}
		if _, e := os.Lstat(r.path("data-reset.json")); e == nil {
			return errors.New("data_update_pending")
		} else if !os.IsNotExist(e) {
			return e
		}
		return fn()
	})
}
func (r *Runtime) withDataLock(fn func() error) error {
	if e := r.init(); e != nil {
		return e
	}
	unlock, e := fileLock(r.path("control.lock"))
	if e != nil {
		return e
	}
	defer unlock()
	return fn()
}
func (r *Runtime) Apply(ctx context.Context, s Snapshot) (State, error) {
	var out State
	e := r.withLock(func() error {
		old, current, e := r.Current()
		if e != nil {
			return e
		}
		if s.ExpectedRevision != old {
			return errors.New("revision_conflict")
		}
		if e = ctx.Err(); e != nil {
			return e
		}
		if e = CheckSnapshot(s); e != nil {
			return e
		}
		// ExpectedRevision guards concurrency; it is not configuration content.
		// Compare bytes directly rather than marshal two potentially large snapshots.
		if old != "" && identicalSnapshotContent(current, s) {
			if e = ctx.Err(); e != nil {
				return e
			}
			out = r.Status()
			return nil
		}
		rev, e := r.Stage(ctx, s)
		if e != nil {
			return e
		}
		if e = ctx.Err(); e != nil {
			return e
		}
		if old != "" {
			if e = AtomicWrite(r.path("previous"), []byte(old), 0600); e != nil {
				return e
			}
		}
		active := SameProcess(r.Status().Supervisor)
		if active {
			if e = r.stop(ctx); e != nil {
				return e
			}
		}
		if e = AtomicWrite(r.path("current"), []byte(rev), 0600); e != nil {
			if active {
				_ = r.start(context.Background())
			}
			return e
		}
		if active {
			if e = r.start(ctx); e != nil {
				// Fully tear down a rejected generation before restoring the last working one.
				if clean := r.stop(context.Background()); clean != nil {
					return errors.New("rollback_cleanup_failed")
				}
				if old != "" {
					if err := AtomicWrite(r.path("current"), []byte(old), 0600); err != nil {
						return err
					}
					if err := r.start(context.Background()); err != nil {
						return errors.New("rollback_start_failed")
					}
				}
				return errors.New("new_core_failed_rolled_back")
			}
		}
		out = r.Status()
		r.recordEvent("config_applied")
		r.pruneGenerations()
		return nil
	})
	return out, e
}
func (r *Runtime) Start(ctx context.Context) error {
	return r.withLock(func() error { return r.start(ctx) })
}
func (r *Runtime) Stop(ctx context.Context) error {
	return r.withLock(func() error { return r.stop(ctx) })
}
func (r *Runtime) start(ctx context.Context) error {
	if _, e := os.Lstat(r.path("install-data.json")); e == nil {
		return errors.New("install_data_update_pending")
	} else if !os.IsNotExist(e) {
		return e
	}
	if e := ctx.Err(); e != nil {
		return e
	}
	if !r.Enabled() {
		return errors.New("module_disabled_or_missing")
	}
	s := r.Status()
	if SameProcess(s.Supervisor) {
		if s.Phase == "connected" {
			return nil
		}
		return errors.New("module_busy")
	}
	if SameProcess(s.Core) {
		return errors.New("cleanup_required")
	}
	rev, _, e := r.Current()
	if e != nil {
		return e
	}
	if rev == "" {
		return errors.New("config_missing")
	}
	if e = r.Validate(ctx, r.path("generations", rev, "run", "config.json"), r.path("generations", rev, "assets")); e != nil {
		return e
	}
	_ = os.Remove(r.path("runtime", "stop"))
	_ = os.Remove(r.path("runtime", "ready"))
	if e = jsonWrite(r.path("state.json"), State{SchemaVersion: Version, Phase: "starting", RunningRevision: rev}); e != nil {
		return e
	}
	cmd := exec.Command(filepath.Join(r.ModuleDir, "bin", "wanboxctl"), "__internal", "supervise")
	detach(cmd)
	cmd.Stdin = nil
	cmd.Stdout = io.Discard
	cmd.Stderr = io.Discard
	if e = cmd.Start(); e != nil {
		return errors.New("supervisor_start_failed")
	}
	// Reap without tying the new session's lifetime to this short CLI invocation.
	go cmd.Wait()
	until := time.NewTimer(270 * time.Second)
	defer until.Stop()
	ticker := time.NewTicker(200 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-until.C:
			return errors.New("startup_timeout")
		case <-ticker.C:
			state := r.Status()
			if state.RunningRevision == rev && state.Phase == "connected" {
				r.recordEvent("core_started")
				return nil
			}
			if state.RunningRevision == rev && state.Phase == "failed" && !SameProcess(state.Supervisor) {
				// Preserve the supervisor's safe diagnostic instead of making a
				// cgroup/Root-profile restriction look like a protocol failure.
				if regexp.MustCompile(`^[a-z_]{1,80}$`).MatchString(state.Error) && state.Error != "" {
					return errors.New(state.Error)
				}
				return errors.New("core_start_failed")
			}
		}
	}
}
func (r *Runtime) stop(ctx context.Context) error {
	s := r.Status()
	if !SameProcess(s.Supervisor) && !SameProcess(s.Core) {
		return nil
	}
	if e := AtomicWrite(r.path("runtime", "stop"), []byte("stop"), 0600); e != nil {
		return e
	}
	// The identity is rechecked at signal delivery: never kill a reused PID.
	if SameProcess(s.Supervisor) {
		if e := terminate(s.Supervisor); e != nil {
			return e
		}
	}
	if !SameProcess(s.Supervisor) && SameProcess(s.Core) {
		if e := terminate(s.Core); e != nil {
			return e
		}
	}
	timer := time.NewTimer(25 * time.Second)
	defer timer.Stop()
	tick := time.NewTicker(100 * time.Millisecond)
	defer tick.Stop()
	for {
		if !SameProcess(s.Supervisor) && !SameProcess(s.Core) {
			_ = os.Remove(r.path("runtime", "ready"))
			r.recordEvent("core_stopped")
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-timer.C:
			return errors.New("cleanup_timeout_no_restart")
		case <-tick.C:
		}
	}
}
func (r *Runtime) Restart(ctx context.Context) error {
	return r.withLock(func() error {
		if e := r.stop(ctx); e != nil {
			return e
		}
		return r.start(ctx)
	})
}

// Rollback restores the separately retained validated generation, including
// recovery from a corrupt current pointer; it never deletes App/database data.
// @author 雾晚
func (r *Runtime) Rollback(ctx context.Context) error {
	return r.withLock(func() error {
		raw, e := os.ReadFile(r.path("previous"))
		if e != nil {
			return errors.New("rollback_missing")
		}
		previous := strings.TrimSpace(string(raw))
		if !revisionName.MatchString(previous) {
			return errors.New("rollback_invalid")
		}
		var snapshot Snapshot
		if e = readJSON(r.path("generations", previous, "snapshot.json"), &snapshot, MaxSnapshotBytes); e != nil {
			return errors.New("rollback_invalid")
		}
		if e = CheckSnapshot(snapshot); e != nil {
			return e
		}
		if e = r.Validate(ctx, r.path("generations", previous, "run", "config.json"), r.path("generations", previous, "assets")); e != nil {
			return e
		}
		active := SameProcess(r.Status().Supervisor) || SameProcess(r.Status().Core)
		if active {
			if e = r.stop(ctx); e != nil {
				return e
			}
		}
		if e = AtomicWrite(r.path("current"), []byte(previous), 0600); e != nil {
			return e
		}
		if active {
			if e = r.start(ctx); e != nil {
				return e
			}
		}
		r.recordEvent("config_rolled_back")
		return nil
	})
}
func (r *Runtime) SetAutoStart(enabled bool) error {
	return r.withLock(func() error {
		return jsonWrite(r.path("boot.json"), struct {
			AutoStart bool `json:"autoStart"`
		}{enabled})
	})
}
func (r *Runtime) Boot(ctx context.Context, bootReady func() bool) error {
	timer := time.NewTimer(3 * time.Minute)
	defer timer.Stop()
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for !bootReady() {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-timer.C:
			return errors.New("boot_timeout")
		case <-ticker.C:
		}
	}
	if e := r.consumeBootInstallChoice(); e != nil {
		return e
	}
	return r.withLock(func() error {
		if !r.Enabled() {
			return nil
		}
		_, snapshot, e := r.Current()
		if e != nil {
			return e
		}
		if snapshot.SchemaVersion == 0 {
			return nil
		}
		settings := struct {
			AutoStart bool `json:"autoStart"`
		}{snapshot.AutoStart}
		if e = readJSON(r.path("boot.json"), &settings, 4096); e != nil && !os.IsNotExist(e) {
			return errors.New("boot_settings_invalid")
		}
		if !settings.AutoStart {
			return nil
		}
		return r.start(ctx)
	})
}

// Errors are stable codes; never serialize raw core/plugin stderr or configurations.
func Response(w io.Writer, state State, err error) {
	payload := struct {
		SchemaVersion int    `json:"schemaVersion"`
		OK            bool   `json:"ok"`
		State         State  `json:"state"`
		Error         string `json:"error,omitempty"`
	}{Version, err == nil, state, ""}
	if err != nil {
		payload.Error = err.Error()
		if !regexp.MustCompile(`^[a-z_]{1,80}$`).MatchString(payload.Error) {
			payload.Error = "module_operation_failed"
		}
	}
	_ = json.NewEncoder(w).Encode(payload)
}
func (r *Runtime) ValidateSnapshot(ctx context.Context, s Snapshot) error {
	return r.withLock(func() error {
		_, e := r.Stage(ctx, s)
		if e == nil {
			r.pruneGenerations()
		}
		return e
	})
}

// @author 雾晚: retain current/previous/running snapshots; never delete active resources.
func (r *Runtime) pruneGenerations() {
	current, e := r.CurrentRevision()
	if e != nil {
		return
	}
	state := r.Status()
	keep := map[string]bool{current: true, state.RunningRevision: true}
	previous, e := os.ReadFile(r.path("previous"))
	if e == nil {
		keep[strings.TrimSpace(string(previous))] = true
	}
	entries, e := os.ReadDir(r.path("generations"))
	if e != nil {
		return
	}
	for _, entry := range entries {
		if entry.IsDir() && revisionName.MatchString(entry.Name()) && !keep[entry.Name()] {
			_ = os.RemoveAll(r.path("generations", entry.Name()))
		}
	}
}
func (r *Runtime) String() string { return fmt.Sprintf("wanbox module protocol %d", Version) }
