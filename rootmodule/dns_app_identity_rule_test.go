// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"bytes"
	"encoding/json"
	"os"
	"testing"
)

type appIdentityDNSFixture struct {
	Backend          string         `json:"backend"`
	ProjectedConfig  map[string]any `json:"projectedConfig"`
	ExpectedConfig   map[string]any `json:"expectedConfig"`
	UnmigratedConfig map[string]any `json:"unmigratedConfig"`
}

func readAppIdentityDNSFixture(t *testing.T) appIdentityDNSFixture {
	t.Helper()
	data, err := os.ReadFile("testdata/root_dns_app_identity_v79.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture appIdentityDNSFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestRootAppIdentityDNSEffectsMaterializeInPlaceAndPreserveFallback(t *testing.T) {
	fixture := readAppIdentityDNSFixture(t)
	snapshot := Snapshot{RootInbound: &RootInboundSpec{Backend: fixture.Backend}}
	if err := validateRootAppIdentityDNSRules(fixture.ProjectedConfig, snapshot); err != nil {
		t.Fatal(err)
	}
	if err := materializeRootAppIdentityDNSRules(fixture.ProjectedConfig, snapshot); err != nil {
		t.Fatal(err)
	}
	got, _ := json.Marshal(fixture.ProjectedConfig)
	want, _ := json.Marshal(fixture.ExpectedConfig)
	if !bytes.Equal(got, want) {
		gotPretty, _ := json.MarshalIndent(fixture.ProjectedConfig, "", "  ")
		wantPretty, _ := json.MarshalIndent(fixture.ExpectedConfig, "", "  ")
		t.Fatalf("materialized App-identity DNS rules/order differ\ngot: %s\nwant: %s", gotPretty, wantPretty)
	}
	if hasRootAppIdentityDNSRuleMarker(fixture.UnmigratedConfig) {
		t.Fatal("legacy App-generated DNS rule unexpectedly marked")
	}
}

func TestRootAppIdentityDNSRejectsMalformedInputsAndRequiresFakeDNSSupport(t *testing.T) {
	fixture := readAppIdentityDNSFixture(t)
	cases := []func(map[string]any){
		func(config map[string]any) { markerRule(config, 1)["kind"] = "unknown" },
		func(config map[string]any) { markerRule(config, 1)["packages"] = []any{"bad package"} },
		func(config map[string]any) { markerRule(config, 1)["uids"] = []any{1.5} },
		func(config map[string]any) { markerRule(config, 1)["fakeDns"] = true },
	}
	for index, mutate := range cases {
		config := cloneJSONMap(t, fixture.ProjectedConfig)
		mutate(config)
		if err := validateRootAppIdentityDNSRules(config, Snapshot{RootInbound: &RootInboundSpec{Backend: "tun"}}); err == nil {
			t.Fatalf("malformed case %d accepted", index)
		}
	}
	if err := validateRootAppIdentityDNSRules(fixture.ProjectedConfig, Snapshot{RootInbound: &RootInboundSpec{Backend: "ebpf"}}); err == nil {
		t.Fatal("FakeDNS App-identity rule accepted outside TUN")
	}
}

func markerRule(config map[string]any, index int) map[string]any {
	rules := config["dns"].(map[string]any)["rules"].([]any)
	return rules[index+1].(map[string]any)[rootAppIdentityDNSRuleMarker].(map[string]any)
}

func TestRootAppIdentityDNSMarkerRequiresSchemaV79(t *testing.T) {
	fixture := readAppIdentityDNSFixture(t)
	config, err := json.Marshal(fixture.ProjectedConfig)
	if err != nil {
		t.Fatal(err)
	}
	snapshot := Snapshot{SchemaVersion: SnapshotVersionProtocolOutboundRoutes, ProfileID: 1, ProfileName: "test", Config: config}
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("v78 marker accepted: %v", err)
	}
}

func TestRootAppIdentityDNSSchema79RunsThroughSnapshotPipeline(t *testing.T) {
	fixture := readAppIdentityDNSFixture(t)
	rootConfig := cloneJSONMap(t, fixture.ProjectedConfig)
	rootConfig["outbounds"] = []any{map[string]any{"type": "direct", "tag": "node"}}
	rootConfig["route"] = map[string]any{"rules": []any{}}
	// The default direct DNS server is now a Root-owned input, not App JSON.
	dns := rootConfig["dns"].(map[string]any)
	servers := dns["servers"].([]any)
	filteredServers := make([]any, 0, len(servers))
	for _, raw := range servers {
		server := raw.(map[string]any)
		if server["tag"] != "dns-direct" {
			filteredServers = append(filteredServers, raw)
		}
	}
	dns["servers"] = filteredServers
	config, err := json.Marshal(rootConfig)
	if err != nil {
		t.Fatal(err)
	}
	snapshot := Snapshot{
		SchemaVersion: SnapshotVersionAppIdentityDNSRules,
		ProfileID:     1,
		ProfileName:   "test",
		Config:        config,
		RootInbound:   &RootInboundSpec{Backend: "tun", MTU: 1500},
		RootProxyGroup: &RootProxyGroupSpec{
			Type: "selector", Tag: "proxy", Members: []string{"node"}, Default: "node",
		},
		RootDNSPolicy:          &RootDNSPolicySpec{Final: "dns-remote"},
		RootDNSLocalServer:     &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"},
		RootDNSDirectServer:    &RootDNSDirectServerSpec{Tag: "dns-direct"},
		RootRouteFinal:         &RootRouteFinalSpec{Final: "proxy"},
		RootRouteProbeRule:     &RootRouteProbeRuleSpec{Index: 0, Host: "probe.example", Outbound: "proxy"},
		RootRouteResolveRule:   &RootRouteResolveRuleSpec{Strategy: "prefer_ipv4"},
		RootRouteIPVersionRule: &RootRouteIPVersionRuleSpec{Index: 0, IPVersion: 6},
	}
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("schema v79 snapshot rejected: %v", err)
	}
	materialized, err := materializeRootInbound(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(materialized, &got); err != nil {
		t.Fatal(err)
	}
	gotDNS, _ := json.Marshal(got["dns"].(map[string]any)["rules"])
	wantDNS, _ := json.Marshal(fixture.ExpectedConfig["dns"].(map[string]any)["rules"])
	if !bytes.Equal(gotDNS, wantDNS) {
		t.Fatalf("snapshot pipeline DNS output differs\ngot: %s\nwant: %s", gotDNS, wantDNS)
	}
}
