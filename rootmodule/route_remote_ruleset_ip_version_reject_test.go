// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type rootRemoteRuleSetIPVersionRejectFixture struct {
	ProjectedConfig map[string]any `json:"projectedConfig"`
	ExpectedRules   []any          `json:"expectedRouteRules"`
}

func readRootRemoteRuleSetIPVersionRejectFixture(t *testing.T) rootRemoteRuleSetIPVersionRejectFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_remote_ruleset_ip_version_reject_v75.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f rootRemoteRuleSetIPVersionRejectFixture
	if err := json.Unmarshal(data, &f); err != nil {
		t.Fatal(err)
	}
	return f
}

func appendRootRemoteRuleSetIPVersionRejectFixture(t *testing.T, version int) (map[string]any, Snapshot, rootRemoteRuleSetIPVersionRejectFixture) {
	t.Helper()
	f := readRootRemoteRuleSetIPVersionRejectFixture(t)
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
	marker := projectedRules[0].(map[string]any)[rootRemoteRuleSetIPVersionRejectRouteMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, projectedRules...)
	route["rules"] = rules
	sets := append([]any{}, route["rule_set"].([]any)...)
	sets = append(sets, projectedRoute["rule_set"].([]any)...)
	route["rule_set"] = sets
	snapshot.Config, _ = json.Marshal(config)
	return config, snapshot, f
}

func TestRootRemoteRuleSetIPVersionRejectV75PreservesConjunctionOrderAndInvert(t *testing.T) {
	config, snapshot, f := appendRootRemoteRuleSetIPVersionRejectFixture(t, SnapshotVersionRemoteRuleSetIPVersionRejectRoutes)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v75 snapshot rejected: %v", err)
	}
	if err := materializeRootRemoteRuleSets(config["route"].(map[string]any)); err != nil {
		t.Fatal(err)
	}
	if err := materializeRootRemoteRuleSetIPVersionRejectRoutes(config); err != nil {
		t.Fatal(err)
	}
	got := config["route"].(map[string]any)["rules"].([]any)
	if !jsonEqual(got[len(got)-len(f.ExpectedRules):], f.ExpectedRules) {
		t.Fatalf("route output/order differs: got %#v want %#v", got, f.ExpectedRules)
	}
}

func TestRootRemoteRuleSetIPVersionRejectV75RejectsOldSchemaInvalidVersionAndUnmigratedTags(t *testing.T) {
	config, snapshot, _ := appendRootRemoteRuleSetIPVersionRejectFixture(t, SnapshotVersionRemoteRuleSetIPVersionRejectRoutes-1)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("old schema accepted v75 marker: %v", err)
	}
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetIPVersionRejectRoutes
	rules := config["route"].(map[string]any)["rules"].([]any)
	var marker map[string]any
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if value, exists := rule[rootRemoteRuleSetIPVersionRejectRouteMarker]; exists {
				marker = value.(map[string]any)
				break
			}
		}
	}
	if marker == nil {
		t.Fatal("IP-version reject marker missing")
	}
	marker["ipVersion"] = float64(5)
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_remote_ruleset_ip_version_reject_invalid" {
		t.Fatalf("invalid IP version accepted: %v", err)
	}
	marker["ipVersion"] = float64(6)
	marker["tags"] = []any{"unmigrated"}
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_remote_ruleset_ip_version_reject_invalid" {
		t.Fatalf("unmigrated tag accepted: %v", err)
	}
}

func TestRootRemoteRuleSetIPVersionRejectV75FullSnapshotMaterialization(t *testing.T) {
	_, snapshot, f := appendRootRemoteRuleSetIPVersionRejectFixture(t, SnapshotVersionRemoteRuleSetIPVersionRejectRoutes)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v75 snapshot rejected: %v", err)
	}
	gotConfig, err := materializeRootInbound(snapshot)
	if err != nil {
		t.Fatalf("materialize v75 snapshot: %v", err)
	}
	var got map[string]any
	if err := json.Unmarshal(gotConfig, &got); err != nil {
		t.Fatal(err)
	}
	gotRules := got["route"].(map[string]any)["rules"].([]any)
	if len(gotRules) < len(f.ExpectedRules) || !jsonEqual(gotRules[len(gotRules)-len(f.ExpectedRules):], f.ExpectedRules) {
		t.Fatalf("full snapshot route output/order differs: got %#v want suffix %#v", gotRules, f.ExpectedRules)
	}
}
