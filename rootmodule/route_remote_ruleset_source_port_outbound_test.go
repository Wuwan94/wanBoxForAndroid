// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type rootRemoteRuleSetSourcePortOutboundFixture struct {
	ProjectedConfig    map[string]any `json:"projectedConfig"`
	ExpectedRouteRules []any          `json:"expectedRouteRules"`
	ExpectedRuleSets   []any          `json:"expectedRuleSets"`
}

func readRootRemoteRuleSetSourcePortOutboundFixture(t *testing.T) rootRemoteRuleSetSourcePortOutboundFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_remote_ruleset_source_port_outbound_v66.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture rootRemoteRuleSetSourcePortOutboundFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func rootRemoteRuleSetSourcePortOutboundSnapshot(t *testing.T, shared rootRemoteRuleSetSourcePortOutboundFixture) (Snapshot, map[string]any) {
	t.Helper()
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetSourcePortOutboundRoutes
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	projectedRoute := shared.ProjectedConfig["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	projectedRules := append([]any{}, projectedRoute["rules"].([]any)...)
	marker := projectedRules[0].(map[string]any)[rootRemoteRuleSetSourcePortOutboundRouteMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, projectedRules...)
	route["rules"] = rules
	sets := append([]any{}, route["rule_set"].([]any)...)
	sets = append(sets, projectedRoute["rule_set"].([]any)...)
	route["rule_set"] = sets
	snapshot.Config, _ = json.Marshal(config)
	return snapshot, config
}

func TestRootGeneratedRemoteRuleSetSourcePortOutboundV66PreservesConjunctionAndPosition(t *testing.T) {
	shared := readRootRemoteRuleSetSourcePortOutboundFixture(t)
	snapshot, config := rootRemoteRuleSetSourcePortOutboundSnapshot(t, shared)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v66 remote-ruleset source_port projection rejected: %v", err)
	}
	if err := materializeRootRemoteRuleSetSourcePortOutboundRoutes(config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := route["rules"].([]any)
	if len(rules) == 0 || !jsonEqual(rules[len(rules)-1:], shared.ExpectedRouteRules) {
		t.Fatalf("generated rule_set AND source_port predicate/order changed: got %#v want suffix %#v", rules, shared.ExpectedRouteRules)
	}
	if err := materializeRootRemoteRuleSets(route); err != nil {
		t.Fatal(err)
	}
	sets := route["rule_set"].([]any)
	if len(sets) == 0 || !jsonEqual(sets[len(sets)-1:], shared.ExpectedRuleSets) {
		t.Fatalf("remote set definitions/order changed: got %#v want suffix %#v", sets, shared.ExpectedRuleSets)
	}
}

func TestRootRemoteRuleSetSourcePortOutboundV66RejectsDowngradeAndInvalidPort(t *testing.T) {
	shared := readRootRemoteRuleSetSourcePortOutboundFixture(t)
	snapshot, _ := rootRemoteRuleSetSourcePortOutboundSnapshot(t, shared)
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetSourcePortOutboundRoutes - 1
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("v66 marker accepted with old schema: %v", err)
	}
	snapshot, _ = rootRemoteRuleSetSourcePortOutboundSnapshot(t, shared)
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	rules := config["route"].(map[string]any)["rules"].([]any)
	marker := rules[len(rules)-1].(map[string]any)[rootRemoteRuleSetSourcePortOutboundRouteMarker].(map[string]any)
	marker["ports"] = []any{float64(70000)}
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_remote_ruleset_source_port_outbound_invalid" {
		t.Fatalf("unsupported source_port accepted: %v", err)
	}
}
