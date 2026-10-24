// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type rootRemoteRuleSetNetworkRejectFixture struct {
	ProjectedConfig map[string]any `json:"projectedConfig"`
	ExpectedRules   []any          `json:"expectedRouteRules"`
}

func readRootRemoteRuleSetNetworkRejectFixture(t *testing.T) rootRemoteRuleSetNetworkRejectFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_remote_ruleset_network_reject_v72.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f rootRemoteRuleSetNetworkRejectFixture
	if err := json.Unmarshal(data, &f); err != nil {
		t.Fatal(err)
	}
	return f
}

func appendRootRemoteRuleSetNetworkRejectFixture(t *testing.T, version int) (map[string]any, Snapshot, rootRemoteRuleSetNetworkRejectFixture) {
	t.Helper()
	f := readRootRemoteRuleSetNetworkRejectFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = version
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	projectedRoute := f.ProjectedConfig["route"].(map[string]any)
	projectedRules := projectedRoute["rules"].([]any)
	marker := projectedRules[0].(map[string]any)[rootRemoteRuleSetNetworkRejectRouteMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, projectedRules...)
	route["rules"] = rules
	sets := append([]any{}, route["rule_set"].([]any)...)
	sets = append(sets, projectedRoute["rule_set"].([]any)...)
	route["rule_set"] = sets
	snapshot.Config, _ = json.Marshal(config)
	return config, snapshot, f
}

func TestRootRemoteRuleSetNetworkRejectV72PreservesConjunctionOrderAndInvert(t *testing.T) {
	config, snapshot, f := appendRootRemoteRuleSetNetworkRejectFixture(t, SnapshotVersionRemoteRuleSetNetworkRejectRoutes)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v72 snapshot rejected: %v", err)
	}
	if err := materializeRootRemoteRuleSets(config["route"].(map[string]any)); err != nil {
		t.Fatal(err)
	}
	if err := materializeRootRemoteRuleSetNetworkRejectRoutes(config); err != nil {
		t.Fatal(err)
	}
	got := config["route"].(map[string]any)["rules"].([]any)
	if !jsonEqual(got[len(got)-len(f.ExpectedRules):], f.ExpectedRules) {
		t.Fatalf("route output/order differs: got %#v want %#v", got, f.ExpectedRules)
	}
}

func TestRootRemoteRuleSetNetworkRejectV72RejectsOldSchemaAndUnmigratedTags(t *testing.T) {
	config, snapshot, _ := appendRootRemoteRuleSetNetworkRejectFixture(t, SnapshotVersionRemoteRuleSetNetworkRejectRoutes-1)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("old schema accepted v72 marker: %v", err)
	}
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetNetworkRejectRoutes
	rules := config["route"].(map[string]any)["rules"].([]any)
	var marker map[string]any
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if value, exists := rule[rootRemoteRuleSetNetworkRejectRouteMarker]; exists {
				marker = value.(map[string]any)
				break
			}
		}
	}
	if marker == nil {
		t.Fatal("network reject marker missing")
	}
	marker["tags"] = []any{"unmigrated"}
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_remote_ruleset_network_reject_route_invalid" {
		t.Fatalf("unmigrated tag accepted: %v", err)
	}
}
