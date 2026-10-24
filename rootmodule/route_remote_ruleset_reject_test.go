// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type rootRemoteRuleSetRejectFixture struct {
	ProjectedConfig map[string]any `json:"projectedConfig"`
	ExpectedRules   []any          `json:"expectedRouteRules"`
}

func readRootRemoteRuleSetRejectFixture(t *testing.T) rootRemoteRuleSetRejectFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_remote_ruleset_reject_v71.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f rootRemoteRuleSetRejectFixture
	if err := json.Unmarshal(data, &f); err != nil {
		t.Fatal(err)
	}
	return f
}

// append the complete route block from the fixture, offsetting Root marker indices
// by any preceding rules in the integration snapshot.
func appendRootRemoteRuleSetRejectProjection(t *testing.T, snapshot *Snapshot, f rootRemoteRuleSetRejectFixture) (map[string]any, []any, int) {
	t.Helper()
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	start := len(rules)
	projectedRoute := f.ProjectedConfig["route"].(map[string]any)
	projectedRules := projectedRoute["rules"].([]any)
	for i, value := range projectedRules {
		rule, ok := value.(map[string]any)
		if !ok {
			t.Fatalf("projected rule %d is not an object", i)
		}
		if marker, ok := rule[rootRemoteRuleSetRejectRouteMarker].(map[string]any); ok {
			marker["index"] = float64(start + i)
		}
	}
	rules = append(rules, projectedRules...)
	route["rules"] = rules
	sets := append([]any{}, route["rule_set"].([]any)...)
	sets = append(sets, projectedRoute["rule_set"].([]any)...)
	route["rule_set"] = sets
	return config, projectedRules, start
}

func TestRootRemoteRuleSetRejectV71PreservesOrderAndInversion(t *testing.T) {
	f := readRootRemoteRuleSetRejectFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetRejectRoutes
	config, projectedRules, start := appendRootRemoteRuleSetRejectProjection(t, &snapshot, f)
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v71 snapshot rejected: %v", err)
	}
	if err := materializeRootRemoteRuleSetRejectRoutes(config); err != nil {
		t.Fatal(err)
	}
	got := config["route"].(map[string]any)["rules"].([]any)
	want := append([]any{}, projectedRules...)
	markerFound := false
	for i, value := range want {
		rule, ok := value.(map[string]any)
		if !ok {
			continue
		}
		if _, ok := rule[rootRemoteRuleSetRejectRouteMarker]; ok {
			if len(f.ExpectedRules) != 1 {
				t.Fatalf("expected one materialized reject rule, got %d", len(f.ExpectedRules))
			}
			want[i] = f.ExpectedRules[0]
			markerFound = true
		}
	}
	if !markerFound {
		t.Fatal("fixture has no Root reject marker")
	}
	if !jsonEqual(got[start:start+len(want)], want) {
		t.Fatalf("route output/order differs: got %#v want %#v", got[start:start+len(want)], want)
	}
}

func TestRootRemoteRuleSetRejectV71RequiresMigratedTagsAndCurrentSchema(t *testing.T) {
	f := readRootRemoteRuleSetRejectFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetRejectRoutes - 1
	config, _, _ := appendRootRemoteRuleSetRejectProjection(t, &snapshot, f)
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("old schema accepted marker: %v", err)
	}
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetRejectRoutes
	rules := config["route"].(map[string]any)["rules"].([]any)
	markerFound := false
	for _, value := range rules {
		rule, ok := value.(map[string]any)
		if !ok {
			continue
		}
		if marker, ok := rule[rootRemoteRuleSetRejectRouteMarker].(map[string]any); ok {
			marker["tags"] = []any{"unknown"}
			markerFound = true
		}
	}
	if !markerFound {
		t.Fatal("fixture has no Root reject marker")
	}
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_remote_ruleset_reject_route_invalid" {
		t.Fatalf("unmigrated tag accepted: %v", err)
	}
}
