// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type rootRemoteRuleSetIPVersionOutboundFixture struct {
	ProjectedConfig    map[string]any `json:"projectedConfig"`
	ExpectedRouteRules []any          `json:"expectedRouteRules"`
	ExpectedRuleSets   []any          `json:"expectedRuleSets"`
}

func readRootRemoteRuleSetIPVersionOutboundFixture(t *testing.T) rootRemoteRuleSetIPVersionOutboundFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_remote_ruleset_ip_version_outbound_v65.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture rootRemoteRuleSetIPVersionOutboundFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func rootRemoteRuleSetIPVersionOutboundSnapshot(t *testing.T, shared rootRemoteRuleSetIPVersionOutboundFixture) (Snapshot, map[string]any) {
	t.Helper()
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetIPVersionOutboundRoutes
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	projectedRoute := shared.ProjectedConfig["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	projectedRules := append([]any{}, projectedRoute["rules"].([]any)...)
	marker := projectedRules[0].(map[string]any)[rootRemoteRuleSetIPVersionOutboundRouteMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, projectedRules...)
	route["rules"] = rules
	sets := append([]any{}, route["rule_set"].([]any)...)
	sets = append(sets, projectedRoute["rule_set"].([]any)...)
	route["rule_set"] = sets
	snapshot.Config, _ = json.Marshal(config)
	return snapshot, config
}

func TestRootGeneratedRemoteRuleSetIPVersionOutboundV65PreservesConjunctionAndPosition(t *testing.T) {
	shared := readRootRemoteRuleSetIPVersionOutboundFixture(t)
	snapshot, config := rootRemoteRuleSetIPVersionOutboundSnapshot(t, shared)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v65 remote-ruleset ip_version projection rejected: %v", err)
	}
	if err := materializeRootRemoteRuleSetIPVersionOutboundRoutes(config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := route["rules"].([]any)
	if len(rules) == 0 || !jsonEqual(rules[len(rules)-1:], shared.ExpectedRouteRules) {
		t.Fatalf("generated rule_set AND ip_version predicate/order changed: got %#v want suffix %#v", rules, shared.ExpectedRouteRules)
	}
	if err := materializeRootRemoteRuleSets(route); err != nil {
		t.Fatal(err)
	}
	sets := route["rule_set"].([]any)
	if len(sets) == 0 || !jsonEqual(sets[len(sets)-1:], shared.ExpectedRuleSets) {
		t.Fatalf("remote set definitions/order changed: got %#v want suffix %#v", sets, shared.ExpectedRuleSets)
	}
}

func TestRootRemoteRuleSetIPVersionOutboundV65RejectsDowngradeAndInvalidVersion(t *testing.T) {
	shared := readRootRemoteRuleSetIPVersionOutboundFixture(t)
	snapshot, _ := rootRemoteRuleSetIPVersionOutboundSnapshot(t, shared)
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetIPVersionOutboundRoutes - 1
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("v65 marker accepted with old schema: %v", err)
	}
	snapshot, _ = rootRemoteRuleSetIPVersionOutboundSnapshot(t, shared)
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	rules := config["route"].(map[string]any)["rules"].([]any)
	marker := rules[len(rules)-1].(map[string]any)[rootRemoteRuleSetIPVersionOutboundRouteMarker].(map[string]any)
	marker["ipVersion"] = float64(5)
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_remote_ruleset_ip_version_outbound_invalid" {
		t.Fatalf("unsupported ip_version accepted: %v", err)
	}
}
