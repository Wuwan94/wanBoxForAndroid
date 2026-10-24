// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type rootRemoteRuleSetFixture struct {
	GeneratedConfig           map[string]any `json:"generatedConfig"`
	ProjectedConfig           map[string]any `json:"projectedConfig"`
	ExpectedMaterializedRules []any          `json:"expectedMaterializedRuleSets"`
}

func readRootRemoteRuleSetFixture(t *testing.T) rootRemoteRuleSetFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_remote_ruleset_v46.json"))
	if err != nil {
		t.Fatal(err)
	}
	var sample rootRemoteRuleSetFixture
	if err := json.Unmarshal(data, &sample); err != nil {
		t.Fatal(err)
	}
	return sample
}

func rootRemoteRuleSetV46Snapshot(t *testing.T, shared rootRemoteRuleSetFixture) (Snapshot, []any) {
	t.Helper()
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionRouteRemoteRuleSets
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	generatedRoute := shared.GeneratedConfig["route"].(map[string]any)
	projectedRoute := shared.ProjectedConfig["route"].(map[string]any)
	route := config["route"].(map[string]any)
	baseRules := route["rules"].([]any)
	addedRules := generatedRoute["rules"].([]any)
	route["rules"] = append(baseRules, addedRules...)
	route["rule_set"] = projectedRoute["rule_set"]
	config["route"] = route
	snapshot.Config, _ = json.Marshal(config)
	wantRules := append([]any(nil), integration.ExpectedRouteRules...)
	wantRules = append(wantRules, addedRules...)
	return snapshot, wantRules
}

func TestRootRemoteRuleSetV46RestoresGeneratedShapeAndOrder(t *testing.T) {
	shared := readRootRemoteRuleSetFixture(t)
	snapshot, wantRules := rootRemoteRuleSetV46Snapshot(t, shared)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("valid v46 remote-ruleset snapshot rejected: %v", err)
	}
	materialized, err := materializeRootInbound(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(materialized, &got); err != nil {
		t.Fatal(err)
	}
	route := got["route"].(map[string]any)
	if !jsonEqual(route["rule_set"], shared.ExpectedMaterializedRules) {
		t.Fatalf("remote ruleset shape/order changed:\n got: %#v\nwant: %#v", route["rule_set"], shared.ExpectedMaterializedRules)
	}
	if !jsonEqual(route["rules"], wantRules) {
		t.Fatalf("rules referencing sets, including inverted composite legacy rules, changed order or content:\n got: %#v\nwant: %#v", route["rules"], wantRules)
	}
}

func TestRootRemoteRuleSetV46RejectsInvalidMarkerAndVersionGate(t *testing.T) {
	shared := readRootRemoteRuleSetFixture(t)
	base, _ := rootRemoteRuleSetV46Snapshot(t, shared)
	badIndex := base
	badConfig := map[string]any{}
	if err := json.Unmarshal(badIndex.Config, &badConfig); err != nil {
		t.Fatal(err)
	}
	sets := badConfig["route"].(map[string]any)["rule_set"].([]any)
	sets[0].(map[string]any)[rootRemoteRuleSetMarker].(map[string]any)["index"] = float64(2)
	badIndex.Config, _ = json.Marshal(badConfig)
	if err := CheckSnapshot(badIndex); err == nil || err.Error() != "root_remote_rule_set_invalid" {
		t.Fatalf("wrong remote ruleset index accepted: %v", err)
	}
	old := Snapshot{
		SchemaVersion: SnapshotVersionRouteAppUIDRules,
		Config:        []byte(`{"route":{"rule_set":[{"_wanbox_root_remote_rule_set_v46":{"index":0,"tag":"ruleset-demo","url":"https://example.com/rules.srs","updateInterval":"1d"}}]}}`),
		RootInbound:   &RootInboundSpec{Backend: "ebpf"},
		ProfileID:     1,
	}
	if err := CheckSnapshot(old); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("v46 remote ruleset marker accepted in earlier schema: %v", err)
	}
}
