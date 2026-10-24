// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type rootRemoteRuleSetDomainRejectFixture struct {
	GeneratedConfig map[string]any `json:"generatedConfig"`
	ProjectedConfig map[string]any `json:"projectedConfig"`
	ExpectedRules   []any          `json:"expectedRouteRules"`
}

func readRootRemoteRuleSetDomainRejectFixture(t *testing.T) rootRemoteRuleSetDomainRejectFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_remote_ruleset_domain_reject_v74.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f rootRemoteRuleSetDomainRejectFixture
	if err := json.Unmarshal(data, &f); err != nil {
		t.Fatal(err)
	}
	return f
}

func appendRootRemoteRuleSetDomainRejectFixture(t *testing.T, version int) (map[string]any, Snapshot, rootRemoteRuleSetDomainRejectFixture) {
	t.Helper()
	f := readRootRemoteRuleSetDomainRejectFixture(t)
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
	marker := projectedRules[0].(map[string]any)[rootRemoteRuleSetDomainRejectRouteMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, projectedRules...)
	route["rules"] = rules
	sets := append([]any{}, route["rule_set"].([]any)...)
	sets = append(sets, projectedRoute["rule_set"].([]any)...)
	route["rule_set"] = sets
	snapshot.Config, _ = json.Marshal(config)
	return config, snapshot, f
}

func TestRootRemoteRuleSetDomainRejectV74PreservesConjunctionOrderAndInvert(t *testing.T) {
	config, snapshot, f := appendRootRemoteRuleSetDomainRejectFixture(t, SnapshotVersionRemoteRuleSetDomainRejectRoutes)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v74 snapshot rejected: %v", err)
	}
	dnsBefore, err := json.Marshal(config["dns"])
	if err != nil {
		t.Fatal(err)
	}
	if err := materializeRootRemoteRuleSets(config["route"].(map[string]any)); err != nil {
		t.Fatal(err)
	}
	if err := materializeRootRemoteRuleSetDomainRejectRoutes(config); err != nil {
		t.Fatal(err)
	}
	got := config["route"].(map[string]any)["rules"].([]any)
	if !jsonEqual(got[len(got)-len(f.ExpectedRules):], f.ExpectedRules) {
		t.Fatalf("route output/order differs: got %#v want %#v", got, f.ExpectedRules)
	}
	dnsAfter, err := json.Marshal(config["dns"])
	if err != nil {
		t.Fatal(err)
	}
	if string(dnsBefore) != string(dnsAfter) {
		t.Fatalf("Root route materialization changed App-generated DNS effects: before %s after %s", dnsBefore, dnsAfter)
	}
	if !jsonEqual(f.GeneratedConfig["dns"], f.ProjectedConfig["dns"]) {
		t.Fatal("shared fixture unexpectedly changes App-generated DNS effects during projection")
	}
}

func TestRootRemoteRuleSetDomainRejectV74RejectsOldSchemaAndInvalidDomain(t *testing.T) {
	config, snapshot, _ := appendRootRemoteRuleSetDomainRejectFixture(t, SnapshotVersionRemoteRuleSetDomainRejectRoutes-1)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("old schema accepted v74 marker: %v", err)
	}
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetDomainRejectRoutes
	rules := config["route"].(map[string]any)["rules"].([]any)
	marker := rules[len(rules)-2].(map[string]any)[rootRemoteRuleSetDomainRejectRouteMarker].(map[string]any)
	marker["domains"] = []any{"bad,domain"}
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_remote_ruleset_domain_reject_invalid" {
		t.Fatalf("invalid matcher accepted: %v", err)
	}
}
