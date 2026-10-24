// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type rootRemoteRuleSetProtocolOutboundFixture struct {
	ProjectedConfig    map[string]any `json:"projectedConfig"`
	ExpectedRouteRules []any          `json:"expectedRouteRules"`
	ExpectedRuleSets   []any          `json:"expectedRuleSets"`
}

func readRootRemoteRuleSetProtocolOutboundFixture(t *testing.T) rootRemoteRuleSetProtocolOutboundFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_remote_ruleset_protocol_outbound_v77.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture rootRemoteRuleSetProtocolOutboundFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func appendRootRemoteRuleSetProtocolOutboundFixture(t *testing.T, snapshot *Snapshot, fixture rootRemoteRuleSetProtocolOutboundFixture) map[string]any {
	t.Helper()
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	projectedRoute := fixture.ProjectedConfig["route"].(map[string]any)
	projectedRules := append([]any{}, projectedRoute["rules"].([]any)...)
	marker := projectedRules[0].(map[string]any)[rootRemoteRuleSetProtocolOutboundRouteMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, projectedRules...)
	route["rules"] = rules
	sets, _ := route["rule_set"].([]any)
	sets = append(append([]any{}, sets...), projectedRoute["rule_set"].([]any)...)
	route["rule_set"] = sets
	snapshot.Config, _ = json.Marshal(config)
	return config
}

func TestRootGeneratedRemoteRuleSetProtocolOutboundV77PreservesConjunctionAndPosition(t *testing.T) {
	fixture := readRootRemoteRuleSetProtocolOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetProtocolOutboundRoutes
	config := appendRootRemoteRuleSetProtocolOutboundFixture(t, &snapshot, fixture)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v77 remote-ruleset protocol route rejected: %v", err)
	}
	if err := materializeRootRemoteRuleSetProtocolOutboundRoutes(config); err != nil {
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

func TestRootRemoteRuleSetProtocolOutboundV77RejectsUnmigratedTagAndOldSchema(t *testing.T) {
	fixture := readRootRemoteRuleSetProtocolOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetProtocolOutboundRoutes - 1
	config := appendRootRemoteRuleSetProtocolOutboundFixture(t, &snapshot, fixture)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("v77 marker accepted with old schema: %v", err)
	}
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetProtocolOutboundRoutes
	config["route"].(map[string]any)["rules"].([]any)[len(config["route"].(map[string]any)["rules"].([]any))-1].(map[string]any)[rootRemoteRuleSetProtocolOutboundRouteMarker].(map[string]any)["tags"] = []any{"not-migrated"}
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_remote_ruleset_protocol_outbound_invalid" {
		t.Fatalf("unmigrated ruleset reference accepted: %v", err)
	}
	config["route"].(map[string]any)["rules"].([]any)[len(config["route"].(map[string]any)["rules"].([]any))-1].(map[string]any)[rootRemoteRuleSetProtocolOutboundRouteMarker].(map[string]any)["protocols"] = []any{"bogus"}
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_remote_ruleset_protocol_outbound_invalid" {
		t.Fatalf("unsupported protocol accepted: %v", err)
	}
}
func TestRootRemoteRuleSetProtocolOutboundV77FullSnapshotMaterialization(t *testing.T) {
	fixture := readRootRemoteRuleSetProtocolOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetProtocolOutboundRoutes
	appendRootRemoteRuleSetProtocolOutboundFixture(t, &snapshot, fixture)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("validate v77 snapshot: %v", err)
	}
	gotConfig, err := materializeRootInbound(snapshot)
	if err != nil {
		t.Fatalf("materialize v77 snapshot: %v", err)
	}
	var got map[string]any
	if err := json.Unmarshal(gotConfig, &got); err != nil {
		t.Fatal(err)
	}
	route := got["route"].(map[string]any)
	gotRules := route["rules"].([]any)
	if len(gotRules) < len(fixture.ExpectedRouteRules) || !jsonEqual(gotRules[len(gotRules)-len(fixture.ExpectedRouteRules):], fixture.ExpectedRouteRules) {
		t.Fatalf("full snapshot route output/order differs: got %#v want suffix %#v", gotRules, fixture.ExpectedRouteRules)
	}
	gotSets := route["rule_set"].([]any)
	if len(gotSets) < len(fixture.ExpectedRuleSets) || !jsonEqual(gotSets[len(gotSets)-len(fixture.ExpectedRuleSets):], fixture.ExpectedRuleSets) {
		t.Fatalf("full snapshot ruleset output differs: got %#v want suffix %#v", gotSets, fixture.ExpectedRuleSets)
	}
}
