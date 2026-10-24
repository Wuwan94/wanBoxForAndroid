// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"bytes"
	"encoding/json"
	"os"
	"testing"
)

type remoteRuleSetDNSEffectFixture struct {
	GeneratedConfig  map[string]any `json:"generatedConfig"`
	ProjectedConfig  map[string]any `json:"projectedConfig"`
	ExpectedConfig   map[string]any `json:"expectedConfig"`
	UnmigratedConfig map[string]any `json:"unmigratedConfig"`
}

func readRemoteRuleSetDNSEffectFixture(t *testing.T) remoteRuleSetDNSEffectFixture {
	t.Helper()
	data, err := os.ReadFile("testdata/root_dns_ruleset_effect_v56.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture remoteRuleSetDNSEffectFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestRootRemoteRuleSetDNSEffectsGenerateActionsInOriginalOrder(t *testing.T) {
	f := readRemoteRuleSetDNSEffectFixture(t)
	snapshot := Snapshot{RootInbound: &RootInboundSpec{Backend: "tun"}}
	if err := validateRootRemoteRuleSetDNSEffects(f.ProjectedConfig, snapshot); err != nil {
		t.Fatal(err)
	}
	route := f.ProjectedConfig["route"].(map[string]any)
	if err := materializeRootRemoteRuleSets(route); err != nil {
		t.Fatal(err)
	}
	if err := materializeRootRemoteRuleSetDNSEffects(f.ProjectedConfig); err != nil {
		t.Fatal(err)
	}
	got, _ := json.Marshal(f.ProjectedConfig)
	want, _ := json.Marshal(f.ExpectedConfig)
	if !bytes.Equal(got, want) {
		gotPretty, _ := json.MarshalIndent(f.ProjectedConfig, "", "  ")
		wantPretty, _ := json.MarshalIndent(f.ExpectedConfig, "", "  ")
		t.Fatalf("generated DNS rules/order differ\ngot: %s\nwant: %s", gotPretty, wantPretty)
	}
}

func TestRootRemoteRuleSetDNSEffectsRejectUnsupportedReferencesAndLegacyFallback(t *testing.T) {
	f := readRemoteRuleSetDNSEffectFixture(t)
	if hasRootRemoteRuleSetDNSEffectMarker(f.UnmigratedConfig) {
		t.Fatal("unmigrated rule set was projected")
	}
	bad := cloneJSONMap(t, f.ProjectedConfig)
	rules := bad["dns"].(map[string]any)["rules"].([]any)
	marker := rules[1].(map[string]any)[rootRemoteRuleSetDNSEffectMarker].(map[string]any)
	marker["tag"] = "not-migrated"
	if err := validateRootRemoteRuleSetDNSEffects(bad, Snapshot{RootInbound: &RootInboundSpec{Backend: "tun"}}); err == nil {
		t.Fatal("effect referencing an unprojected ruleset was accepted")
	}
	if err := validateRootRemoteRuleSetDNSEffects(f.ProjectedConfig, Snapshot{RootInbound: &RootInboundSpec{Backend: "ebpf"}}); err == nil {
		t.Fatal("fake DNS-capable effects accepted without TUN snapshot")
	}
}
