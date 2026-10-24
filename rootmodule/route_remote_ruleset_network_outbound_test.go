// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type rootRemoteRuleSetNetworkOutboundFixture struct {
	ProjectedConfig    map[string]any `json:"projectedConfig"`
	ExpectedRouteRules []any          `json:"expectedRouteRules"`
	ExpectedRuleSets   []any          `json:"expectedRuleSets"`
}

func readRootRemoteRuleSetNetworkOutboundFixture(t *testing.T) rootRemoteRuleSetNetworkOutboundFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_remote_ruleset_network_outbound_v70.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture rootRemoteRuleSetNetworkOutboundFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func appendRootRemoteRuleSetNetworkOutboundFixture(t *testing.T, snapshot *Snapshot, fixture rootRemoteRuleSetNetworkOutboundFixture) map[string]any {
	t.Helper()
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	projectedRoute := fixture.ProjectedConfig["route"].(map[string]any)
	projectedRules := append([]any{}, projectedRoute["rules"].([]any)...)
	marker := projectedRules[0].(map[string]any)[rootRemoteRuleSetNetworkOutboundRouteMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, projectedRules...)
	route["rules"] = rules
	sets, _ := route["rule_set"].([]any)
	sets = append(append([]any{}, sets...), projectedRoute["rule_set"].([]any)...)
	route["rule_set"] = sets
	snapshot.Config, _ = json.Marshal(config)
	return config
}

func TestRootGeneratedRemoteRuleSetNetworkOutboundV70PreservesConjunctionAndPosition(t *testing.T) {
	fixture := readRootRemoteRuleSetNetworkOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetNetworkOutboundRoutes
	config := appendRootRemoteRuleSetNetworkOutboundFixture(t, &snapshot, fixture)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v70 remote-ruleset network route rejected: %v", err)
	}
	if err := materializeRootRemoteRuleSetNetworkOutboundRoutes(config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	gotRules := route["rules"].([]any)
	if len(gotRules) == 0 || !jsonEqual(gotRules[len(gotRules)-1:], fixture.ExpectedRouteRules) {
		t.Fatalf("generated route matcher/order changed: got %#v want suffix %#v", gotRules, fixture.ExpectedRouteRules)
	}
	if err := materializeRootRemoteRuleSets(route); err != nil {
		t.Fatal(err)
	}
	gotSets := route["rule_set"].([]any)
	if len(gotSets) == 0 || !jsonEqual(gotSets[len(gotSets)-1:], fixture.ExpectedRuleSets) {
		t.Fatalf("remote definitions changed: got %#v want suffix %#v", gotSets, fixture.ExpectedRuleSets)
	}
}

func TestRootRemoteRuleSetNetworkOutboundV70RejectsUnmigratedTagAndOldSchema(t *testing.T) {
	fixture := readRootRemoteRuleSetNetworkOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetNetworkOutboundRoutes - 1
	config := appendRootRemoteRuleSetNetworkOutboundFixture(t, &snapshot, fixture)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("v70 marker accepted with old schema: %v", err)
	}
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetNetworkOutboundRoutes
	config["route"].(map[string]any)["rules"].([]any)[len(config["route"].(map[string]any)["rules"].([]any))-1].(map[string]any)[rootRemoteRuleSetNetworkOutboundRouteMarker].(map[string]any)["tags"] = []any{"not-migrated"}
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_remote_ruleset_network_outbound_invalid" {
		t.Fatalf("unmigrated ruleset reference accepted: %v", err)
	}
	config["route"].(map[string]any)["rules"].([]any)[len(config["route"].(map[string]any)["rules"].([]any))-1].(map[string]any)[rootRemoteRuleSetNetworkOutboundRouteMarker].(map[string]any)["networks"] = []any{"icmp"}
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_remote_ruleset_network_outbound_invalid" {
		t.Fatalf("unsupported network accepted: %v", err)
	}
}
