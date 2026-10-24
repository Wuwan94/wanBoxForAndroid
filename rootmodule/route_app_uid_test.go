// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type rootRouteAppUIDFixture struct {
	GeneratedConfig           map[string]any               `json:"generatedConfig"`
	Spec                      RootRouteAppUIDRulesSpec     `json:"spec"`
	ExpectedMaterializedRules []any                        `json:"expectedMaterializedRules"`
	PackageOnlyFallback       map[string]any               `json:"packageOnlyFallback"`
	UnsupportedCases          []map[string]json.RawMessage `json:"unsupportedCases"`
}

func readRootRouteAppUIDFixture(t *testing.T) rootRouteAppUIDFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_route_app_uid_v45.json"))
	if err != nil {
		t.Fatal(err)
	}
	var sample rootRouteAppUIDFixture
	if err := json.Unmarshal(data, &sample); err != nil {
		t.Fatal(err)
	}
	return sample
}

func rootRouteAppUIDSnapshot(t *testing.T, shared rootRouteAppUIDFixture) Snapshot {
	t.Helper()
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionRouteAppUIDRules
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := route["rules"].([]any)
	baseCount := len(rules)
	localRules := shared.GeneratedConfig["route"].(map[string]any)["rules"].([]any)
	for localIndex, raw := range localRules {
		if rule, ok := raw.(map[string]any); ok {
			if marker, exists := rule[rootRouteAppUIDMarker]; exists {
				marker.(map[string]any)["index"] = float64(baseCount + localIndex)
			}
		}
		rules = append(rules, raw)
	}
	route["rules"] = rules
	config["route"] = route
	snapshot.Config, _ = json.Marshal(config)
	spec := shared.Spec
	spec.Rules = append([]RootRouteAppUIDRuleSpec(nil), shared.Spec.Rules...)
	for i := range spec.Rules {
		spec.Rules[i].Index += baseCount
	}
	snapshot.RootRouteAppUIDRules = &spec
	return snapshot
}

func TestRootRouteAppUIDV45PreservesRawInputOrderAndLegacyMatcherShape(t *testing.T) {
	shared := readRootRouteAppUIDFixture(t)
	snapshot := rootRouteAppUIDSnapshot(t, shared)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("valid v45 snapshot rejected: %v", err)
	}
	materialized, err := materializeRootInbound(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(materialized, &got); err != nil {
		t.Fatal(err)
	}
	gotRules := got["route"].(map[string]any)["rules"].([]any)
	want := append([]any(nil), loadExpectedIntegrationRules(t)...)
	want = append(want, shared.ExpectedMaterializedRules...)
	if !jsonEqual(gotRules, want) {
		t.Fatalf("v45 changed package/UID matcher, outbound or source order:\n got: %#v\nwant: %#v", gotRules, want)
	}
}

func loadExpectedIntegrationRules(t *testing.T) []any {
	t.Helper()
	_, sample := loadRouteMigrationIntegrationFixture(t)
	return sample.ExpectedRouteRules
}

func TestRootRouteAppUIDV45RejectsSpecMarkerMismatch(t *testing.T) {
	shared := readRootRouteAppUIDFixture(t)
	snapshot := rootRouteAppUIDSnapshot(t, shared)
	snapshot.RootRouteAppUIDRules = &RootRouteAppUIDRulesSpec{Rules: append([]RootRouteAppUIDRuleSpec(nil), snapshot.RootRouteAppUIDRules.Rules...)}
	snapshot.RootRouteAppUIDRules.Rules[0].UIDs[0]++
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_route_app_uid_invalid" {
		t.Fatalf("mismatched marker/spec payload accepted: %v", err)
	}
}

func TestRootRouteAppUIDV45RejectsUnsupportedSchemaAndUnknownOutbound(t *testing.T) {
	shared := readRootRouteAppUIDFixture(t)
	snapshot := rootRouteAppUIDSnapshot(t, shared)
	old := snapshot
	old.SchemaVersion = SnapshotVersionRouteNetworkOutboundRules
	if err := CheckSnapshot(old); err == nil {
		t.Fatal("v45 app UID marker accepted in v44 snapshot")
	}
	bad := rootRouteAppUIDSnapshot(t, shared)
	bad.RootRouteAppUIDRules = &RootRouteAppUIDRulesSpec{Rules: append([]RootRouteAppUIDRuleSpec(nil), bad.RootRouteAppUIDRules.Rules...)}
	bad.RootRouteAppUIDRules.Rules[0].Outbound = "missing"
	if err := CheckSnapshot(bad); err == nil || err.Error() != "root_route_app_uid_invalid" {
		t.Fatalf("unknown outbound accepted: %v", err)
	}
}
