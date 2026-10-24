// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type rootRemoteRuleSetIPRejectFixture struct {
	ProjectedConfig map[string]any `json:"projectedConfig"`
	ExpectedRules   []any          `json:"expectedRouteRules"`
}

func readRootRemoteRuleSetIPRejectFixture(t *testing.T) rootRemoteRuleSetIPRejectFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_remote_ruleset_ip_reject_v73.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f rootRemoteRuleSetIPRejectFixture
	if err := json.Unmarshal(data, &f); err != nil {
		t.Fatal(err)
	}
	return f
}

func appendRootRemoteRuleSetIPRejectFixture(t *testing.T, version int) (map[string]any, Snapshot, rootRemoteRuleSetIPRejectFixture) {
	t.Helper()
	f := readRootRemoteRuleSetIPRejectFixture(t)
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
	marker := projectedRules[0].(map[string]any)[rootRemoteRuleSetIPRejectRouteMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, projectedRules...)
	route["rules"] = rules
	sets := append([]any{}, route["rule_set"].([]any)...)
	sets = append(sets, projectedRoute["rule_set"].([]any)...)
	route["rule_set"] = sets
	snapshot.Config, _ = json.Marshal(config)
	return config, snapshot, f
}

func TestRootRemoteRuleSetIPRejectV73PreservesConjunctionOrderAndInvert(t *testing.T) {
	config, snapshot, f := appendRootRemoteRuleSetIPRejectFixture(t, SnapshotVersionRemoteRuleSetIPRejectRoutes)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v73 snapshot rejected: %v", err)
	}
	if err := materializeRootRemoteRuleSets(config["route"].(map[string]any)); err != nil {
		t.Fatal(err)
	}
	if err := materializeRootRemoteRuleSetIPRejectRoutes(config); err != nil {
		t.Fatal(err)
	}
	got := config["route"].(map[string]any)["rules"].([]any)
	if !jsonEqual(got[len(got)-len(f.ExpectedRules):], f.ExpectedRules) {
		t.Fatalf("route output/order differs: got %#v want %#v", got, f.ExpectedRules)
	}
}

func TestRootRemoteRuleSetIPRejectV73RejectsOldSchemaInvalidCIDRAndUnmigratedTags(t *testing.T) {
	config, snapshot, _ := appendRootRemoteRuleSetIPRejectFixture(t, SnapshotVersionRemoteRuleSetIPRejectRoutes-1)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("old schema accepted v73 marker: %v", err)
	}
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetIPRejectRoutes
	rules := config["route"].(map[string]any)["rules"].([]any)
	var marker map[string]any
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if value, exists := rule[rootRemoteRuleSetIPRejectRouteMarker]; exists {
				marker = value.(map[string]any)
				break
			}
		}
	}
	if marker == nil {
		t.Fatal("IP reject marker missing")
	}
	marker["cidrs"] = []any{"not-a-cidr"}
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_remote_ruleset_ip_reject_invalid" {
		t.Fatalf("invalid CIDR accepted: %v", err)
	}
	marker["cidrs"] = []any{"203.0.113.0/24", "2001:db8::/32"}
	marker["tags"] = []any{"unmigrated"}
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_remote_ruleset_ip_reject_invalid" {
		t.Fatalf("unmigrated tag accepted: %v", err)
	}
}
