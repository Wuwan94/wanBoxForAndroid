// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type rootRemoteRuleSetReferenceFixture struct {
	GeneratedConfig          map[string]any `json:"generatedConfig"`
	ProjectedConfig          map[string]any `json:"projectedConfig"`
	ProjectedConfigV48       map[string]any `json:"projectedConfigV48"`
	ExpectedMaterializedSets []any          `json:"expectedMaterializedRuleSets"`
	ExpectedRouteRules       []any          `json:"expectedRouteRules"`
	ExpectedDNSRules         []any          `json:"expectedDnsRules"`
}

func readRootRemoteRuleSetReferenceFixture(t *testing.T) rootRemoteRuleSetReferenceFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_remote_ruleset_v47.json"))
	if err != nil {
		t.Fatal(err)
	}
	var sample rootRemoteRuleSetReferenceFixture
	if err := json.Unmarshal(data, &sample); err != nil {
		t.Fatal(err)
	}
	return sample
}

func appendAny(base []any, values []any) []any { return append(append([]any(nil), base...), values...) }
func cloneJSONMap(t *testing.T, value map[string]any) map[string]any {
	t.Helper()
	encoded, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	var result map[string]any
	if err := json.Unmarshal(encoded, &result); err != nil {
		t.Fatal(err)
	}
	return result
}

func rootRemoteRuleSetReferenceV47Snapshot(t *testing.T, shared rootRemoteRuleSetReferenceFixture) (Snapshot, []any, []any, []any) {
	t.Helper()
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionRouteRemoteRuleSetReferences
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	generatedRoute := shared.GeneratedConfig["route"].(map[string]any)
	projectedRoute := shared.ProjectedConfig["route"].(map[string]any)
	route := config["route"].(map[string]any)
	baseRules := route["rules"].([]any)
	baseSets, _ := route["rule_set"].([]any)
	addedRules := generatedRoute["rules"].([]any)
	addedSets := projectedRoute["rule_set"].([]any)
	addedRefs := projectedRoute["rules"].([]any)
	// This test fixture is appended after pre-existing projections; rebase only the exact
	// fixture indices to the positions actually serialized in this snapshot.
	for i, raw := range addedSets {
		item, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, ok := item[rootRemoteRuleSetMarker].(map[string]any)
		if ok {
			marker["index"] = float64(len(baseSets) + i)
		}
	}
	for i, raw := range addedRefs {
		item, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, ok := item[rootRemoteRuleSetReferenceMarker].(map[string]any)
		if ok {
			marker["index"] = float64(len(baseRules) + i)
		}
	}
	route["rules"] = appendAny(baseRules, addedRefs)
	route["rule_set"] = appendAny(baseSets, addedSets)
	config["route"] = route
	dns, _ := config["dns"].(map[string]any)
	baseDNS, _ := dns["rules"].([]any)
	generatedDNS := shared.ProjectedConfig["dns"].(map[string]any)["rules"].([]any)
	for i, raw := range generatedDNS {
		item, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, ok := item[rootRemoteRuleSetReferenceMarker].(map[string]any)
		if ok {
			marker["index"] = float64(len(baseDNS) + i)
		}
	}
	dns["rules"] = appendAny(baseDNS, generatedDNS)
	config["dns"] = dns
	snapshot.Config, _ = json.Marshal(config)
	wantRules := appendAny(integration.ExpectedRouteRules, addedRules)
	wantDNS := appendAny(nil, shared.ExpectedDNSRules)
	wantSets := appendAny(baseSets, shared.ExpectedMaterializedSets)
	return snapshot, wantRules, wantDNS, wantSets
}

func TestRootRemoteRuleSetReferencesV47RestoresCompositeInvertAndDNSEffects(t *testing.T) {
	shared := readRootRemoteRuleSetReferenceFixture(t)
	snapshot, wantRules, wantDNSRules, wantSets := rootRemoteRuleSetReferenceV47Snapshot(t, shared)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("valid v47 snapshot rejected: %v", err)
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
	if !jsonEqual(route["rules"], wantRules) {
		t.Fatalf("route predicates/order/action changed:\n got %#v\nwant %#v", route["rules"], wantRules)
	}
	if !jsonEqual(route["rule_set"], wantSets) {
		t.Fatalf("remote set definitions/order changed:\n got %#v\nwant %#v", route["rule_set"], wantSets)
	}
	dns := got["dns"].(map[string]any)
	if !jsonEqual(dns["rules"], wantDNSRules) {
		t.Fatalf("DNS matching/effect/order changed:\n got %#v\nwant %#v", dns["rules"], wantDNSRules)
	}
}

func TestRootRemoteRuleSetReferencesV47RejectsBadReferenceAndDowngrade(t *testing.T) {
	shared := readRootRemoteRuleSetReferenceFixture(t)
	snapshot, _, _, _ := rootRemoteRuleSetReferenceV47Snapshot(t, shared)
	var config map[string]any
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := route["rules"].([]any)
	for _, raw := range rules {
		item, _ := raw.(map[string]any)
		if marker, ok := item[rootRemoteRuleSetReferenceMarker].(map[string]any); ok {
			marker["index"] = float64(999)
			break
		}
	}
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_remote_rule_set_reference_invalid" {
		t.Fatalf("bad v47 index accepted: %v", err)
	}
	snapshot.SchemaVersion = SnapshotVersionRouteRemoteRuleSets
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("v47 rule marker accepted under v46 schema: %v", err)
	}
}

func TestRootRemoteRuleSetReferencesV47RejectsUnmigratedSetAndRegex(t *testing.T) {
	shared := readRootRemoteRuleSetReferenceFixture(t)
	snapshot, _, _, _ := rootRemoteRuleSetReferenceV47Snapshot(t, shared)
	var config map[string]any
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := route["rules"].([]any)
	// Forge a marker around the unsupported legacy regex rule: marker validation must not
	// authorize changing matcher semantics merely because a remote definition also moved.
	for index, raw := range rules {
		item, _ := raw.(map[string]any)
		if rule, ok := item["domain_regex"]; ok {
			rules[index] = map[string]any{rootRemoteRuleSetReferenceMarker: map[string]any{"index": float64(index), "rule": map[string]any{"domain_regex": rule, "rule_set": []any{"ruleset-site"}, "outbound": "direct"}}}
			break
		}
	}
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_remote_rule_set_reference_invalid" {
		t.Fatalf("domain_regex reference marker accepted: %v", err)
	}
}

func TestRootRemoteRuleSetReferenceMaterializerIgnoresIndexShiftAfterValidation(t *testing.T) {
	shared := readRootRemoteRuleSetReferenceFixture(t)
	snapshot, _, _, _ := rootRemoteRuleSetReferenceV47Snapshot(t, shared)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatal(err)
	}
	var config map[string]any
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := route["rules"].([]any)
	markerIndex := -1
	for index, raw := range rules {
		if item, ok := raw.(map[string]any); ok {
			if _, exists := item[rootRemoteRuleSetReferenceMarker]; exists {
				markerIndex = index
				break
			}
		}
	}
	if markerIndex < 0 {
		t.Fatal("fixture has no reference marker")
	}
	// A prior Root projection can insert before these markers after the snapshot's indices
	// have been validated. Materialization replaces at the current slot, not stale indices.
	rules = append([]any{map[string]any{"outbound": "direct"}}, rules...)
	route["rules"] = rules
	config["route"] = route
	if err := materializeRootRemoteRuleSetReferences(config); err != nil {
		t.Fatal(err)
	}
	if got := route["rules"].([]any)[markerIndex+1].(map[string]any)["type"]; got != "logical" {
		t.Fatalf("shifted marker was not restored in place: %#v", route["rules"].([]any)[markerIndex+1])
	}
}

func TestRootRemoteRuleSetReferenceFixtureExpectedMatchesGeneratedConfig(t *testing.T) {
	shared := readRootRemoteRuleSetReferenceFixture(t)
	if !jsonEqual(shared.ProjectedConfig["route"].(map[string]any)["rules"].([]any)[0].(map[string]any)[rootRemoteRuleSetReferenceMarker].(map[string]any)["rule"], shared.GeneratedConfig["route"].(map[string]any)["rules"].([]any)[0]) {
		t.Fatal("fixture projection does not preserve ConfigBuilder-generated composite/invert rule")
	}
}

func TestRootRemoteRuleSetReferenceExpectedDNSIncludesOriginalSideEffect(t *testing.T) {
	shared := readRootRemoteRuleSetReferenceFixture(t)
	original := shared.GeneratedConfig["dns"].(map[string]any)["rules"].([]any)[0]
	projected := shared.ProjectedConfig["dns"].(map[string]any)["rules"].([]any)[0].(map[string]any)[rootRemoteRuleSetReferenceMarker].(map[string]any)["rule"]
	if !jsonEqual(original, projected) {
		t.Fatalf("DNS server/inbound/query-type effect changed: got %#v want %#v", projected, original)
	}
}

func TestRootRemoteRuleSetReferenceFixtureHasSourceOrder(t *testing.T) {
	shared := readRootRemoteRuleSetReferenceFixture(t)
	rules := shared.GeneratedConfig["route"].(map[string]any)["rules"].([]any)
	if len(rules) < 4 {
		t.Fatalf("fixture lacks supported/legacy ordering cases: %d", len(rules))
	}
	if _, ok := rules[2].(map[string]any)["domain_regex"]; !ok {
		t.Fatal("legacy regex fallback is not represented")
	}
}

func TestRootRawRulesV48RestoresAllRemainingRouteAndDNSSemantics(t *testing.T) {
	shared := readRootRemoteRuleSetReferenceFixture(t)
	snapshot, wantRules, wantDNSRules, _ := rootRemoteRuleSetReferenceV47Snapshot(t, shared)
	var config map[string]any
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	addedRouteRules := shared.GeneratedConfig["route"].(map[string]any)["rules"].([]any)
	route := config["route"].(map[string]any)
	routeRules := route["rules"].([]any)
	baseRouteCount := len(routeRules) - len(addedRouteRules)
	projected48Route := shared.ProjectedConfigV48["route"].(map[string]any)["rules"].([]any)
	for i, raw := range projected48Route {
		if i >= len(addedRouteRules) {
			break
		}
		item, _ := raw.(map[string]any)
		marker, ok := item[rootRouteRawRuleMarker].(map[string]any)
		if !ok {
			continue
		}
		marker["index"] = float64(baseRouteCount + i)
		routeRules[baseRouteCount+i] = item
	}
	dns := config["dns"].(map[string]any)
	dnsRules := dns["rules"].([]any)
	baseDNSCount := len(dnsRules) - len(shared.ExpectedDNSRules)
	projected48DNS := shared.ProjectedConfigV48["dns"].(map[string]any)["rules"].([]any)
	for i, raw := range projected48DNS {
		item, _ := raw.(map[string]any)
		marker, ok := item[rootRouteRawRuleMarker].(map[string]any)
		if !ok {
			continue
		}
		marker["index"] = float64(baseDNSCount + i)
		dnsRules[baseDNSCount+i] = item
	}
	snapshot.SchemaVersion = SnapshotVersionRouteRawRules
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v48 raw-rule snapshot rejected: %v", err)
	}
	materialized, err := materializeRootInbound(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(materialized, &got); err != nil {
		t.Fatal(err)
	}
	if !jsonEqual(got["route"].(map[string]any)["rules"], wantRules) {
		t.Fatalf("route order/matcher/invert/action changed: got %#v want %#v", got["route"].(map[string]any)["rules"], wantRules)
	}
	if !jsonEqual(got["dns"].(map[string]any)["rules"], wantDNSRules) {
		t.Fatalf("DNS side effects/order changed: got %#v want %#v", got["dns"].(map[string]any)["rules"], wantDNSRules)
	}
}

func TestRootRawRulesV48RejectsRegexAndDowngrade(t *testing.T) {
	shared := readRootRemoteRuleSetReferenceFixture(t)
	snapshot, _, _, _ := rootRemoteRuleSetReferenceV47Snapshot(t, shared)
	var config map[string]any
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := route["rules"].([]any)
	index := -1
	for i, raw := range rules {
		item, _ := raw.(map[string]any)
		if _, ok := item["domain_regex"]; ok {
			index = i
			break
		}
	}
	if index < 0 {
		t.Fatal("legacy regex fixture missing")
	}
	rules[index] = map[string]any{rootRouteRawRuleMarker: map[string]any{
		"index": float64(index), "rule": map[string]any{"domain_regex": []any{".*"}, "outbound": "direct"},
	}}
	snapshot.SchemaVersion = SnapshotVersionRouteRawRules
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_route_raw_rule_invalid" {
		t.Fatalf("v48 regex migration marker accepted: %v", err)
	}
	snapshot.SchemaVersion = SnapshotVersionRouteRemoteRuleSetReferences
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("v48 marker accepted under v47 schema: %v", err)
	}
}
