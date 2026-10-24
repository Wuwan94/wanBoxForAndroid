// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"bytes"
	"encoding/json"
	"os"
	"testing"
)

type appIdentityDomainDNSFixture struct {
	Backend          string         `json:"backend"`
	ProjectedConfig  map[string]any `json:"projectedConfig"`
	ExpectedConfig   map[string]any `json:"expectedConfig"`
	UnmigratedConfig map[string]any `json:"unmigratedConfig"`
}

func readAppIdentityDomainDNSFixture(t *testing.T) appIdentityDomainDNSFixture {
	t.Helper()
	data, err := os.ReadFile("testdata/root_dns_app_identity_domain_v80.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture appIdentityDomainDNSFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestRootAppIdentityDomainDNSEffectsMaterializeInPlaceAndPreserveFallback(t *testing.T) {
	fixture := readAppIdentityDomainDNSFixture(t)
	snapshot := Snapshot{RootInbound: &RootInboundSpec{Backend: fixture.Backend}}
	if err := validateRootAppIdentityDomainDNSRules(fixture.ProjectedConfig, snapshot); err != nil {
		t.Fatal(err)
	}
	if err := materializeRootAppIdentityDomainDNSRules(fixture.ProjectedConfig, snapshot); err != nil {
		t.Fatal(err)
	}
	got, _ := json.Marshal(fixture.ProjectedConfig)
	want, _ := json.Marshal(fixture.ExpectedConfig)
	if !bytes.Equal(got, want) {
		gotPretty, _ := json.MarshalIndent(fixture.ProjectedConfig, "", "  ")
		wantPretty, _ := json.MarshalIndent(fixture.ExpectedConfig, "", "  ")
		t.Fatalf("materialized package/domain DNS rules or order differ\ngot: %s\nwant: %s", gotPretty, wantPretty)
	}
	if hasRootAppIdentityDomainDNSRuleMarker(fixture.UnmigratedConfig) {
		t.Fatal("regex fallback unexpectedly marked")
	}
}

func TestRootAppIdentityDomainDNSMarkerRequiresSchemaV80(t *testing.T) {
	fixture := readAppIdentityDomainDNSFixture(t)
	config, err := json.Marshal(fixture.ProjectedConfig)
	if err != nil {
		t.Fatal(err)
	}
	snapshot := Snapshot{SchemaVersion: SnapshotVersionAppIdentityDNSRules, ProfileID: 1, ProfileName: "test", Config: config}
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("v79 marker accepted: %v", err)
	}
}

func TestRootAppIdentityDomainDNSSchema80RunsThroughSnapshotPipeline(t *testing.T) {
	fixture := readAppIdentityDomainDNSFixture(t)
	rootConfig := cloneJSONMap(t, fixture.ProjectedConfig)
	rootConfig["outbounds"] = []any{map[string]any{"type": "direct", "tag": "node"}}
	rootConfig["route"] = map[string]any{"rules": []any{}}
	dns := rootConfig["dns"].(map[string]any)
	servers := dns["servers"].([]any)
	filtered := make([]any, 0, len(servers))
	for _, raw := range servers {
		if raw.(map[string]any)["tag"] != "dns-direct" {
			filtered = append(filtered, raw)
		}
	}
	dns["servers"] = filtered
	config, err := json.Marshal(rootConfig)
	if err != nil {
		t.Fatal(err)
	}
	snapshot := Snapshot{
		SchemaVersion: SnapshotVersionAppIdentityDomainDNSRules,
		ProfileID:     1, ProfileName: "test", Config: config,
		RootInbound:            &RootInboundSpec{Backend: "tun", MTU: 1500},
		RootProxyGroup:         &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"node"}, Default: "node"},
		RootDNSPolicy:          &RootDNSPolicySpec{Final: "dns-remote"},
		RootDNSLocalServer:     &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"},
		RootDNSDirectServer:    &RootDNSDirectServerSpec{Tag: "dns-direct"},
		RootRouteFinal:         &RootRouteFinalSpec{Final: "proxy"},
		RootRouteProbeRule:     &RootRouteProbeRuleSpec{Index: 0, Host: "probe.example", Outbound: "proxy"},
		RootRouteResolveRule:   &RootRouteResolveRuleSpec{Strategy: "prefer_ipv4"},
		RootRouteIPVersionRule: &RootRouteIPVersionRuleSpec{Index: 0, IPVersion: 6},
	}
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("schema v80 snapshot rejected: %v", err)
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
