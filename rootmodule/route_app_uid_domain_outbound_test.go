// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type rootAppUIDDomainOutboundFixture struct {
	ProjectedRouteRule            map[string]any                          `json:"projectedRouteRule"`
	Spec                          RootRouteAppUIDDomainOutboundRoutesSpec `json:"spec"`
	ExpectedMaterializedRouteRule map[string]any                          `json:"expectedMaterializedRouteRule"`
}

func readRootAppUIDDomainOutboundFixture(t *testing.T) rootAppUIDDomainOutboundFixture {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", "root_route_app_uid_domain_outbound_v81.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture rootAppUIDDomainOutboundFixture
	if err := json.Unmarshal(b, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func rootAppUIDDomainOutboundSnapshot(t *testing.T, fixture rootAppUIDDomainOutboundFixture) (Snapshot, []any, any) {
	t.Helper()
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionAppUIDDomainOutboundRoutes
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := append([]any(nil), route["rules"].([]any)...)
	index := len(rules)
	marker := fixture.ProjectedRouteRule[rootAppUIDDomainOutboundRouteMarker].(map[string]any)
	marker["index"] = float64(index)
	rules = append(rules, fixture.ProjectedRouteRule)
	route["rules"] = rules
	config["route"] = route
	snapshot.Config, _ = json.Marshal(config)
	spec := fixture.Spec
	spec.Rules = append([]RootRouteAppUIDDomainOutboundRuleSpec(nil), fixture.Spec.Rules...)
	spec.Rules[0].Index = index
	snapshot.RootAppUIDDomainOutboundRoutes = &spec
	return snapshot, integration.ExpectedRouteRules, integration.ExpectedDNS
}

func TestRootAppUIDDomainOutboundV81SnapshotPipelinePreservesConjunctionInvertAndOrder(t *testing.T) {
	fixture := readRootAppUIDDomainOutboundFixture(t)
	snapshot, prior, expectedDNS := rootAppUIDDomainOutboundSnapshot(t, fixture)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v81 snapshot rejected: %v", err)
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
	want := append(append([]any(nil), prior...), fixture.ExpectedMaterializedRouteRule)
	if !jsonEqual(gotRules, want) {
		t.Fatalf("v81 route matcher or ordering mismatch:\n got=%#v\nwant=%#v", gotRules, want)
	}
	if !jsonEqual(got["dns"], expectedDNS) {
		t.Fatal("route migration changed unrelated DNS config")
	}
}

func TestRootAppUIDDomainOutboundV81RejectsLegacySchemaAndUnsupportedRegex(t *testing.T) {
	fixture := readRootAppUIDDomainOutboundFixture(t)
	snapshot, _, _ := rootAppUIDDomainOutboundSnapshot(t, fixture)
	old := snapshot
	old.SchemaVersion = SnapshotVersionAppUIDDomainOutboundRoutes - 1
	if err := CheckSnapshot(old); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("v80 accepted v81 marker: %v", err)
	}
	bad, _, _ := rootAppUIDDomainOutboundSnapshot(t, fixture)
	var config map[string]any
	if err := json.Unmarshal(bad.Config, &config); err != nil {
		t.Fatal(err)
	}
	rules := config["route"].(map[string]any)["rules"].([]any)
	marker := rules[len(rules)-1].(map[string]any)[rootAppUIDDomainOutboundRouteMarker].(map[string]any)
	marker["domainText"] = "regex:.*example.test"
	bad.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(bad); err == nil || err.Error() != "root_app_uid_domain_outbound_invalid" {
		t.Fatalf("unproven regexp accepted: %v", err)
	}
}
