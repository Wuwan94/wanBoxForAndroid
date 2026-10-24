// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"reflect"
	"testing"
	"time"
)

func TestUnchangedApplyDoesNotStageOrReplaceRollback(t *testing.T) {
	r, snapshot := fixture(t)
	validations := 0
	r.Validate = func(context.Context, string, string) error { validations++; return nil }
	initial, err := r.Apply(context.Background(), snapshot)
	if err != nil {
		t.Fatal(err)
	}
	events := len(r.Events())
	snapshot.ExpectedRevision = initial.Revision
	result, err := r.Apply(context.Background(), snapshot)
	if err != nil {
		t.Fatal(err)
	}
	if result.Revision != initial.Revision || validations != 1 {
		t.Fatalf("identical configuration was staged: revision changed=%v validations=%d", result.Revision != initial.Revision, validations)
	}
	if _, err = os.Stat(r.path("previous")); !os.IsNotExist(err) {
		t.Fatal("no-op changed rollback pointer", err)
	}
	if len(r.Events()) != events {
		t.Fatal("no-op emitted a configuration event")
	}
	entries, err := os.ReadDir(r.path("generations"))
	if err != nil || len(entries) != 1 {
		t.Fatal("no-op added a generation", err)
	}
	stale := snapshot
	stale.ExpectedRevision = ""
	if _, err = r.Apply(context.Background(), stale); err == nil || err.Error() != "revision_conflict" {
		t.Fatal("no-op bypassed revision conflict", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err = r.Apply(ctx, snapshot); err != context.Canceled {
		t.Fatal("cancellation ignored", err)
	}
	if validations != 1 {
		t.Fatal("cancelled no-op staged a snapshot")
	}
}

// @author 雾晚: editing while stopped commits the candidate but does not create core processes.
func TestStoppedEditCommitsAndInvalidCandidatePreservesPriorConfig(t *testing.T) {
	r, snapshot := fixture(t)
	initial, err := r.Apply(context.Background(), snapshot)
	if err != nil {
		t.Fatal(err)
	}
	snapshot.ExpectedRevision = initial.Revision
	snapshot.ProfileName = "edited while stopped"
	changed, err := r.Apply(context.Background(), snapshot)
	if err != nil || changed.Revision == initial.Revision || changed.Phase != "stopped" || changed.Core.PID != 0 || changed.Supervisor.PID != 0 {
		t.Fatal("stopped apply started/ignored config", changed, err)
	}
	r.Validate = func(context.Context, string, string) error { return fmt.Errorf("fixture_invalid") }
	snapshot.ExpectedRevision = changed.Revision
	snapshot.ProfileName = "invalid edit"
	if _, err = r.Apply(context.Background(), snapshot); err == nil {
		t.Fatal("invalid candidate accepted")
	}
	if current := r.Status(); current.Revision != changed.Revision || current.Phase != "stopped" || current.Core.PID != 0 || current.Supervisor.PID != 0 {
		t.Fatal("invalid edit changed stopped state", current)
	}
}

func TestSnapshotComparisonCoversEveryContentField(t *testing.T) {
	base := Snapshot{SchemaVersion: Version, ExpectedRevision: "old", Config: json.RawMessage(`{"a":1}`),
		Files: map[string][]byte{"asset": {1}}, Plugins: []Plugin{{"kind", "bin", "config", "certificate"}},
		ProfileID: 7, ProfileName: "fixture", AutoStart: true}
	cases := map[string]func(*Snapshot){
		"SchemaVersion": func(s *Snapshot) { s.SchemaVersion++ },
		"Config":        func(s *Snapshot) { s.Config = json.RawMessage(`{"a":2}`) },
		"RootInbound":   func(s *Snapshot) { s.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 9000} },
		"RootProxyGroup": func(s *Snapshot) {
			s.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"node-a"}, Default: "node-a"}
		},
		"RootOutbounds": func(s *Snapshot) {
			s.RootOutbounds = []RootOutboundSpec{{Protocol: "socks", Tag: "node-a", Server: "127.0.0.1", ServerPort: 1080, Version: "5"}}
		},
		"RootRoutePolicy": func(s *Snapshot) {
			s.RootRoutePolicy = &RootRoutePolicy{AutoDetectInterface: true, FindProcess: true, DefaultNetworkStrategy: "hybrid"}
		},
		"RootDNSHosts":        func(s *Snapshot) { s.RootDNSHosts = &RootDNSHostsSpec{Raw: "example.org 1.1.1.1"} },
		"RootDNSPolicy":       func(s *Snapshot) { s.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote", Strategy: "ipv4_only"} },
		"RootDNSLocalServer":  func(s *Snapshot) { s.RootDNSLocalServer = &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"} },
		"RootDNSDirectServer": func(s *Snapshot) { s.RootDNSDirectServer = &RootDNSDirectServerSpec{Tag: "dns-direct"} },
		"RootDNSResolvers": func(s *Snapshot) {
			s.RootDNSResolvers = &RootDNSResolversSpec{Direct: []string{"https://223.5.5.5/dns-query"}, Remote: []string{"https://dns.google/dns-query"}, ProxyTag: "proxy"}
		},
		"RootRoutePortRejectRules": func(s *Snapshot) {
			s.RootRoutePortRejectRules = &RootRoutePortRejectRulesSpec{Rules: []RootRoutePortRejectRuleSpec{{RuleID: 1, PortText: "443"}}}
		},
		"RootRouteNetworkRejectRules": func(s *Snapshot) {
			s.RootRouteNetworkRejectRules = &RootRouteNetworkRejectRulesSpec{Rules: []RootRouteNetworkRejectRuleSpec{{RuleID: 1, NetworkText: "tcp"}}}
		},
		"RootRouteNetworkPortRejectRules": func(s *Snapshot) {
			s.RootRouteNetworkPortRejectRules = &RootRouteNetworkPortRejectRulesSpec{Rules: []RootRouteNetworkPortRejectRuleSpec{{RuleID: 2, NetworkText: "tcp", PortText: "443"}}}
		},
		"RootRouteSourcePortRejectRules": func(s *Snapshot) {
			s.RootRouteSourcePortRejectRules = &RootRouteSourcePortRejectRulesSpec{Rules: []RootRouteSourcePortRejectRuleSpec{{RuleID: 3, SourcePortText: "53"}}}
		},
		"RootRouteProtocolRejectRules": func(s *Snapshot) {
			s.RootRouteProtocolRejectRules = &RootRouteProtocolRejectRulesSpec{Rules: []RootRouteProtocolRejectRuleSpec{{RuleID: 4, ProtocolText: "tls"}}}
		},
		"RootRouteSourceIPRejectRules": func(s *Snapshot) {
			s.RootRouteSourceIPRejectRules = &RootRouteSourceIPRejectRulesSpec{Rules: []RootRouteSourceIPRejectRuleSpec{{RuleID: 4, SourceText: "192.0.2.0/24"}}}
		},
		"RootRouteIPRejectRules": func(s *Snapshot) {
			s.RootRouteIPRejectRules = &RootRouteIPRejectRulesSpec{Rules: []RootRouteIPRejectRuleSpec{{RuleID: 5, IPText: "198.51.100.0/24"}}}
		},
		"RootRouteSourceDestinationIPRejectRules": func(s *Snapshot) {
			s.RootRouteSourceDestinationIPRejectRules = &RootRouteSourceDestinationIPRejectRulesSpec{Rules: []RootRouteSourceDestinationIPRejectRuleSpec{{RuleID: 6, SourceText: "192.0.2.0/24", IPText: "198.51.100.0/24"}}}
		},
		"RootRouteSourceDestinationPortRejectRules": func(s *Snapshot) {
			s.RootRouteSourceDestinationPortRejectRules = &RootRouteSourceDestinationPortRejectRulesSpec{Rules: []RootRouteSourceDestinationPortRejectRuleSpec{{RuleID: 7, SourcePortText: "53", PortText: "80"}}}
		},
		"RootRouteProtocolPortRejectRules": func(s *Snapshot) {
			s.RootRouteProtocolPortRejectRules = &RootRouteProtocolPortRejectRulesSpec{Rules: []RootRouteProtocolPortRejectRuleSpec{{RuleID: 8, ProtocolText: "tls", PortText: "443"}}}
		},
		"RootRouteDomainRejectRules": func(s *Snapshot) {
			s.RootRouteDomainRejectRules = &RootRouteDomainRejectRulesSpec{Rules: []RootRouteDomainRejectRuleSpec{{RuleID: 9, DomainText: "full:ads.example"}}}
		},
		"RootRouteDomainSuffixRejectRules": func(s *Snapshot) {
			s.RootRouteDomainSuffixRejectRules = &RootRouteDomainSuffixRejectRulesSpec{Rules: []RootRouteDomainSuffixRejectRuleSpec{{RuleID: 10, DomainText: "domain:ads.example"}}}
		},
		"RootRouteDomainKeywordRejectRules": func(s *Snapshot) {
			s.RootRouteDomainKeywordRejectRules = &RootRouteDomainKeywordRejectRulesSpec{Rules: []RootRouteDomainKeywordRejectRuleSpec{{RuleID: 11, DomainText: "keyword:ads"}}}
		},
		"RootRouteDomainRegexRejectRules": func(s *Snapshot) {
			s.RootRouteDomainRegexRejectRules = &RootRouteDomainRegexRejectRulesSpec{Rules: []RootRouteDomainRegexRejectRuleSpec{{Index: 0, Rule: json.RawMessage(`{"domain_regex":["ads"],"action":"reject"}`)}}}
		},
		"RootRouteNetworkOutboundRules": func(s *Snapshot) {
			s.RootRouteNetworkOutboundRules = &RootRouteNetworkOutboundRulesSpec{Rules: []RootRouteNetworkOutboundRuleSpec{{Index: 0, Network: []string{"tcp"}, Outbound: "direct"}}}
		},
		"RootRouteAppUIDRules": func(s *Snapshot) {
			s.RootRouteAppUIDRules = &RootRouteAppUIDRulesSpec{Rules: []RootRouteAppUIDRuleSpec{{Index: 0, Packages: []string{"com.example.app"}, UIDs: []int{10001}, Outbound: "direct"}}}
		},
		"RootAppUIDDomainOutboundRoutes": func(s *Snapshot) {
			s.RootAppUIDDomainOutboundRoutes = &RootRouteAppUIDDomainOutboundRoutesSpec{Rules: []RootRouteAppUIDDomainOutboundRuleSpec{{Index: 0, RuleID: 1, Packages: []string{"com.example.app"}, UIDs: []int{10001}, DomainText: "domain:example.org", Outbound: "direct"}}}
		},
		"RootAppUIDRuleSetOutboundRoutes": func(s *Snapshot) {
			s.RootAppUIDRuleSetOutboundRoutes = &RootRouteAppUIDRuleSetOutboundRoutesSpec{Rules: []RootRouteAppUIDRuleSetOutboundRuleSpec{{Index: 0, RuleID: 1, Packages: []string{"com.example.app"}, UIDs: []int{10001}, RuleSetTags: []string{"ruleset-domain"}, Outbound: "direct"}}}
		},
		"RootAppUIDDomainRuleSetOutboundRoutes": func(s *Snapshot) {
			s.RootAppUIDDomainRuleSetOutboundRoutes = &RootRouteAppUIDDomainRuleSetOutboundRoutesSpec{Rules: []RootRouteAppUIDDomainRuleSetOutboundRuleSpec{{Index: 0, RuleID: 1, Packages: []string{"com.example.app"}, UIDs: []int{10001}, DomainText: "domain:example.org", RuleSetTags: []string{"ruleset-domain"}, RuleSetKinds: []string{"domain"}, Outbound: "direct"}}}
		},
		"RootRouteFinal":         func(s *Snapshot) { s.RootRouteFinal = &RootRouteFinalSpec{Final: "direct"} },
		"RootRouteLoopback":      func(s *Snapshot) { s.RootRouteLoopback = &RootRouteLoopbackSpec{Index: 0} },
		"RootRouteMulticast":     func(s *Snapshot) { s.RootRouteMulticast = &RootRouteMulticastSpec{Index: 0} },
		"RootDNSOutboundAnyRule": func(s *Snapshot) { s.RootDNSOutboundAnyRule = &RootDNSOutboundAnyRuleSpec{Index: 0} },
		"RootDNSIPv6QueryRule":   func(s *Snapshot) { s.RootDNSIPv6QueryRule = &RootDNSIPv6QueryRuleSpec{Index: 0, QueryType: "AAAA"} },
		"RootRouteDNSPort53Rule": func(s *Snapshot) { s.RootRouteDNSPort53Rule = &RootRouteDNSPort53RuleSpec{Index: 0, Outbound: "proxy"} },
		"RootRouteDNSProtocolRule": func(s *Snapshot) {
			s.RootRouteDNSProtocolRule = &RootRouteDNSProtocolRuleSpec{Index: 0, Outbound: "proxy"}
		},
		"RootRouteBypassLAN":             func(s *Snapshot) { s.RootRouteBypassLAN = &RootRouteBypassLANSpec{Index: 0} },
		"RootRouteIPVersionRule":         func(s *Snapshot) { s.RootRouteIPVersionRule = &RootRouteIPVersionRuleSpec{Index: 0, IPVersion: 6} },
		"RootRoutePrivateBypass":         func(s *Snapshot) { s.RootRoutePrivateBypass = &RootRoutePrivateBypassSpec{Index: 0} },
		"RootRouteDNSHijackPort53Rule":   func(s *Snapshot) { s.RootRouteDNSHijackPort53Rule = &RootRouteDNSHijackPort53RuleSpec{Index: 0} },
		"RootRouteDNSProtocolHijackRule": func(s *Snapshot) { s.RootRouteDNSProtocolHijackRule = &RootRouteDNSProtocolHijackRuleSpec{Index: 0} },
		"RootRouteSniffRule":             func(s *Snapshot) { s.RootRouteSniffRule = &RootRouteSniffRuleSpec{Index: 0} },
		"RootRouteProbeRule": func(s *Snapshot) {
			s.RootRouteProbeRule = &RootRouteProbeRuleSpec{Index: 0, Host: "probe.example", Outbound: "proxy"}
		},
		"RootRouteResolveRule": func(s *Snapshot) { s.RootRouteResolveRule = &RootRouteResolveRuleSpec{Strategy: "prefer_ipv4"} },
		"RootRouteModeRules":   func(s *Snapshot) { s.RootRouteModeRules = &RootRouteModeRulesSpec{Index: 0} },
		"RootRouteDNSTargetRules": func(s *Snapshot) {
			s.RootRouteDNSTargetRules = &RootRouteDNSTargetRulesSpec{Index: 0, Rules: []RootRouteDNSTargetRuleSpec{{Kind: "domain", Targets: []string{"dns.example"}, Outbound: "direct"}}}
		},
		"Files":               func(s *Snapshot) { s.Files = map[string][]byte{"asset": {2}} },
		"Plugins":             func(s *Snapshot) { s.Plugins = []Plugin{{"other", "bin", "config", "certificate"}} },
		"PerformancePriority": func(s *Snapshot) { s.PerformancePriority = true },
		"AutoStart":           func(s *Snapshot) { s.AutoStart = false },
		"ProfileID":           func(s *Snapshot) { s.ProfileID++ },
		"ProfileName":         func(s *Snapshot) { s.ProfileName = "other" },
	}
	for i := 0; i < reflect.TypeOf(base).NumField(); i++ {
		field := reflect.TypeOf(base).Field(i).Name
		if field != "ExpectedRevision" && cases[field] == nil {
			t.Fatalf("new snapshot field needs comparison coverage: %s", field)
		}
	}
	for name, change := range cases {
		t.Run(name, func(t *testing.T) {
			other := base
			change(&other)
			if identicalSnapshotContent(base, other) {
				t.Fatal("changed content ignored")
			}
		})
	}
	other := base
	other.ExpectedRevision = "next"
	if !identicalSnapshotContent(base, other) {
		t.Fatal("concurrency token treated as content")
	}
	other.Files = map[string][]byte{"renamed": {1}}
	if identicalSnapshotContent(base, other) {
		t.Fatal("renamed file ignored")
	}
	for i := 0; i < reflect.TypeOf(Plugin{}).NumField(); i++ {
		other = base
		other.Plugins = append([]Plugin(nil), base.Plugins...)
		reflect.ValueOf(&other.Plugins[0]).Elem().Field(i).SetString("changed")
		if identicalSnapshotContent(base, other) {
			t.Fatalf("plugin field %d ignored", i)
		}
	}
}

func TestChangedSnapshotStillValidatesAndCanRollback(t *testing.T) {
	r, snapshot := fixture(t)
	initial, err := r.Apply(context.Background(), snapshot)
	if err != nil {
		t.Fatal(err)
	}
	snapshot.ExpectedRevision = initial.Revision
	snapshot.Files["assets/example.srs"] = []byte{3, 4}
	changed, err := r.Apply(context.Background(), snapshot)
	if err != nil || changed.Revision == initial.Revision {
		t.Fatal("changed resource ignored", err)
	}
	previous, err := os.ReadFile(r.path("previous"))
	if err != nil || string(previous) != initial.Revision {
		t.Fatal("rollback pointer lost", err)
	}
	snapshot.ExpectedRevision = changed.Revision
	snapshot.SchemaVersion++
	if _, err = r.Apply(context.Background(), snapshot); err == nil {
		t.Fatal("invalid schema accepted")
	}
	if r.Status().Revision != changed.Revision {
		t.Fatal("invalid input replaced current")
	}
}

func TestStatsDedupPreservesChangesAndClearing(t *testing.T) {
	s := State{}
	zero := json.RawMessage(`{"tag":"proxy","tx":0,"rx":0,"directTx":0,"directRx":0}`)
	if !replaceStats(&s, zero) {
		t.Fatal("first zero sample lost")
	}
	if replaceStats(&s, append(json.RawMessage(nil), zero...)) {
		t.Fatal("idle sample rewrites state")
	}
	changed := json.RawMessage(`{"tag":"proxy","tx":10,"rx":20,"directTx":0,"directRx":0}`)
	if !replaceStats(&s, changed) {
		t.Fatal("traffic change lost")
	}
	if !replaceStats(&s, nil) || s.Stats != nil {
		t.Fatal("disconnect/error cannot clear sample")
	}
	if replaceStats(&s, nil) {
		t.Fatal("empty sample rewrites state")
	}
}

func TestStartupProbeStopsAllStartupWorkAfterReadyOrCancellation(t *testing.T) {
	for _, name := range []string{"ready", "cancel", "failure"} {
		t.Run(name, func(t *testing.T) {
			p := newStartupProbe(time.Millisecond, time.Hour)
			defer p.stop()
			select {
			case <-p.tick:
			case <-time.After(time.Second):
				t.Fatal("startup never probes")
			}
			p.stop()
			p.stop() // repeated lifecycle cleanup is safe
			if p.tick != nil || p.deadline != nil {
				t.Fatal("select still schedules startup work")
			}
		})
	}
	p := newStartupProbe(time.Hour, time.Millisecond)
	defer p.stop()
	select {
	case <-p.deadline:
	case <-time.After(time.Second):
		t.Fatal("unready core no longer times out")
	}
}
