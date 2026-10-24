// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type rootRemoteRuleSetOutboundFixture struct {
	ProjectedConfig    map[string]any `json:"projectedConfig"`
	ExpectedRouteRules []any          `json:"expectedRouteRules"`
	ExpectedRuleSets   []any          `json:"expectedRuleSets"`
}

func readRootRemoteRuleSetOutboundFixture(t *testing.T) rootRemoteRuleSetOutboundFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_remote_ruleset_outbound_v54.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture rootRemoteRuleSetOutboundFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestRootGeneratedRemoteRuleSetOutboundRouteV54PreservesMatcherInvertAndPosition(t *testing.T) {
	shared := readRootRemoteRuleSetOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetOutboundRoutes
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	projectedRoute := shared.ProjectedConfig["route"].(map[string]any)
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	rules = append(rules, projectedRoute["rules"].([]any)...)
	route["rules"] = rules
	sets := append([]any{}, route["rule_set"].([]any)...)
	sets = append(sets, projectedRoute["rule_set"].([]any)...)
	route["rule_set"] = sets
	config["route"] = route
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v54 remote-ruleset route projection rejected: %v", err)
	}
	if err := materializeRootRemoteRuleSetOutboundRoutes(config); err != nil {
		t.Fatal(err)
	}
	route = config["route"].(map[string]any)
	gotRules := route["rules"].([]any)
	if len(gotRules) == 0 || !jsonEqual(gotRules[len(gotRules)-1:], shared.ExpectedRouteRules) {
		t.Fatalf("generated route matcher/order changed: got %#v want suffix %#v", gotRules, shared.ExpectedRouteRules)
	}
	if err := materializeRootRemoteRuleSets(route); err != nil {
		t.Fatal(err)
	}
	gotSets := route["rule_set"].([]any)
	if len(gotSets) == 0 || !jsonEqual(gotSets[len(gotSets)-1:], shared.ExpectedRuleSets) {
		t.Fatalf("remote set definitions/order changed: got %#v want suffix %#v", gotSets, shared.ExpectedRuleSets)
	}
}

func TestRootRemoteRuleSetOutboundRouteV54RejectsUnmigratedTagAndOldSchema(t *testing.T) {
	shared := readRootRemoteRuleSetOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetOutboundRoutes - 1
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	projectedRoute := shared.ProjectedConfig["route"].(map[string]any)
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	rules = append(rules, projectedRoute["rules"].([]any)...)
	route["rules"] = rules
	sets := append([]any{}, route["rule_set"].([]any)...)
	sets = append(sets, projectedRoute["rule_set"].([]any)...)
	route["rule_set"] = sets
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("v54 marker accepted with v53 schema: %v", err)
	}

	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetOutboundRoutes
	bad := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &bad); err != nil {
		t.Fatal(err)
	}
	badRoute := bad["route"].(map[string]any)
	badRules := badRoute["rules"].([]any)
	marker := badRules[len(badRules)-1].(map[string]any)[rootRemoteRuleSetOutboundRouteMarker].(map[string]any)
	marker["tags"] = []any{"not-migrated"}
	snapshot.Config, _ = json.Marshal(bad)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_remote_ruleset_outbound_invalid" {
		t.Fatalf("unmigrated set reference accepted: %v", err)
	}
}
