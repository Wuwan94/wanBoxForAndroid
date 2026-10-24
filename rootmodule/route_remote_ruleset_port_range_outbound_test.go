// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type rootRemoteRuleSetPortRangeOutboundFixture struct {
	ProjectedConfig    map[string]any `json:"projectedConfig"`
	ExpectedRouteRules []any          `json:"expectedRouteRules"`
}

func readRootRemoteRuleSetPortRangeOutboundFixture(t *testing.T) rootRemoteRuleSetPortRangeOutboundFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_remote_ruleset_port_range_outbound_v76.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture rootRemoteRuleSetPortRangeOutboundFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func rootRemoteRuleSetPortRangeOutboundSnapshot(t *testing.T, fixture rootRemoteRuleSetPortRangeOutboundFixture) (Snapshot, map[string]any) {
	t.Helper()
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetPortRangeOutboundRoutes
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	projectedRoute := fixture.ProjectedConfig["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	projectedRules := append([]any{}, projectedRoute["rules"].([]any)...)
	marker := projectedRules[0].(map[string]any)[rootRemoteRuleSetPortRangeOutboundRouteMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, projectedRules...)
	route["rules"] = rules
	sets := append([]any{}, route["rule_set"].([]any)...)
	sets = append(sets, projectedRoute["rule_set"].([]any)...)
	route["rule_set"] = sets
	snapshot.Config, _ = json.Marshal(config)
	return snapshot, config
}

func TestRootRemoteRuleSetPortRangeOutboundV76PreservesRangeConjunctionAndOrder(t *testing.T) {
	fixture := readRootRemoteRuleSetPortRangeOutboundFixture(t)
	snapshot, config := rootRemoteRuleSetPortRangeOutboundSnapshot(t, fixture)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v76 port-range projection rejected: %v", err)
	}
	if err := materializeRootRemoteRuleSetPortRangeOutboundRoutes(config); err != nil {
		t.Fatal(err)
	}
	rules := config["route"].(map[string]any)["rules"].([]any)
	if len(rules) == 0 || !jsonEqual(rules[len(rules)-1:], fixture.ExpectedRouteRules) {
		t.Fatalf("generated matcher conjunction/order changed: got %#v want suffix %#v", rules, fixture.ExpectedRouteRules)
	}
}

func TestRootRemoteRuleSetPortRangeOutboundV76RejectsDowngradeAndInvalidRanges(t *testing.T) {
	fixture := readRootRemoteRuleSetPortRangeOutboundFixture(t)
	snapshot, _ := rootRemoteRuleSetPortRangeOutboundSnapshot(t, fixture)
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetPortRangeOutboundRoutes - 1
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("v76 marker accepted by older schema: %v", err)
	}
	snapshot, _ = rootRemoteRuleSetPortRangeOutboundSnapshot(t, fixture)
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	rules := config["route"].(map[string]any)["rules"].([]any)
	marker := rules[len(rules)-1].(map[string]any)[rootRemoteRuleSetPortRangeOutboundRouteMarker].(map[string]any)
	marker["portRanges"] = []any{"90:80"}
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_remote_ruleset_port_range_outbound_invalid" {
		t.Fatalf("invalid port range accepted: %v", err)
	}
}

func TestRootRemoteRuleSetPortRangeOutboundV76FullSnapshotMaterialization(t *testing.T) {
	fixture := readRootRemoteRuleSetPortRangeOutboundFixture(t)
	snapshot, _ := rootRemoteRuleSetPortRangeOutboundSnapshot(t, fixture)
	gotConfig, err := materializeRootInbound(snapshot)
	if err != nil {
		t.Fatalf("materialize v76 snapshot: %v", err)
	}
	var got map[string]any
	if err := json.Unmarshal(gotConfig, &got); err != nil {
		t.Fatal(err)
	}
	gotRules := got["route"].(map[string]any)["rules"].([]any)
	if len(gotRules) < len(fixture.ExpectedRouteRules) || !jsonEqual(gotRules[len(gotRules)-len(fixture.ExpectedRouteRules):], fixture.ExpectedRouteRules) {
		t.Fatalf("full snapshot route output/order differs: got %#v want suffix %#v", gotRules, fixture.ExpectedRouteRules)
	}
}
