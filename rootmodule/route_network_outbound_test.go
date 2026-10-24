// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type rootNetworkOutboundFixture struct {
	ProjectedConfig           map[string]any                    `json:"projectedConfig"`
	Spec                      RootRouteNetworkOutboundRulesSpec `json:"spec"`
	ExpectedMaterializedRules []any                             `json:"expectedMaterializedRules"`
	RegexConflictConfig       map[string]any                    `json:"regexConflictConfig"`
	RegexConflictLegacyRule   json.RawMessage                   `json:"regexConflictLegacyRule"`
}

func readRootNetworkOutboundFixture(t *testing.T) rootNetworkOutboundFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_route_network_outbound_v44.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture rootNetworkOutboundFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func rootNetworkOutboundV44Snapshot(t *testing.T, shared rootNetworkOutboundFixture) (Snapshot, []any, int) {
	t.Helper()
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionRouteNetworkOutboundRules
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := route["rules"].([]any)
	baseCount := len(rules)
	localRules := shared.ProjectedConfig["route"].(map[string]any)["rules"].([]any)
	for localIndex, raw := range localRules {
		if rule, ok := raw.(map[string]any); ok {
			if marker, exists := rule[rootRouteNetworkOutboundMarker]; exists {
				marker.(map[string]any)["index"] = float64(baseCount + localIndex)
			}
		}
		rules = append(rules, raw)
	}
	route["rules"] = rules
	config["route"] = route
	snapshot.Config, _ = json.Marshal(config)
	spec := shared.Spec
	spec.Rules = append([]RootRouteNetworkOutboundRuleSpec(nil), shared.Spec.Rules...)
	for i := range spec.Rules {
		spec.Rules[i].Index += baseCount
	}
	snapshot.RootRouteNetworkOutboundRules = &spec
	regexIndex := baseCount + len(localRules)
	expected := append([]any(nil), integration.ExpectedRouteRules...)
	expected = append(expected, shared.ExpectedMaterializedRules[1:]...)
	return snapshot, expected, regexIndex
}

func TestRootNetworkOutboundV44MaterializesExactMatcherOutboundAndSourceOrder(t *testing.T) {
	shared := readRootNetworkOutboundFixture(t)
	snapshot, expectedRules, _ := rootNetworkOutboundV44Snapshot(t, shared)

	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("valid isolated v44 network-outbound snapshot rejected: %v", err)
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
	if !jsonEqual(gotRules, expectedRules) {
		t.Fatalf("materialized route changed matcher, outbound selection, fallback, or order:\n got: %#v\nwant: %#v", gotRules, shared.ExpectedMaterializedRules)
	}
	// This ordinary route-style domain_regex rule stays byte-for-byte equivalent as a JSON value;
	// v44 must not invoke the separate v43 reject/domain_regex projection when no marker exists.
	var regexRule map[string]any
	for _, raw := range gotRules {
		rule, ok := raw.(map[string]any)
		if ok && rule["outbound"] == "direct" {
			if _, exists := rule["domain_regex"]; exists {
				regexRule = rule
				break
			}
		}
	}
	if !jsonEqual(regexRule, map[string]any{"domain_regex": []any{"^keep\\.example$"}, "outbound": "direct"}) {
		t.Fatalf("v44 altered the legacy domain_regex rule: %#v", regexRule)
	}
}

func TestRootNetworkOutboundV44RejectsInvalidIndexAndUnknownOutbound(t *testing.T) {
	shared := readRootNetworkOutboundFixture(t)
	base := func() Snapshot { snapshot, _, _ := rootNetworkOutboundV44Snapshot(t, shared); return snapshot }
	badIndex := base()
	badIndex.RootRouteNetworkOutboundRules = &RootRouteNetworkOutboundRulesSpec{Rules: append([]RootRouteNetworkOutboundRuleSpec(nil), shared.Spec.Rules...)}
	badIndex.RootRouteNetworkOutboundRules.Rules[0].Index++
	if err := CheckSnapshot(badIndex); err == nil {
		t.Fatal("mismatched original rule index was accepted")
	}
	badOutbound := base()
	badOutbound.RootRouteNetworkOutboundRules = &RootRouteNetworkOutboundRulesSpec{Rules: append([]RootRouteNetworkOutboundRuleSpec(nil), shared.Spec.Rules...)}
	badOutbound.RootRouteNetworkOutboundRules.Rules[0].Outbound = "missing"
	if err := CheckSnapshot(badOutbound); err == nil {
		t.Fatal("unknown outbound selection was accepted")
	}
}

func TestRootNetworkOutboundV44RejectsCouplingToV43RegexProjection(t *testing.T) {
	shared := readRootNetworkOutboundFixture(t)
	snapshot, _, regexIndex := rootNetworkOutboundV44Snapshot(t, shared)
	snapshot.RootRouteDomainRegexRejectRules = &RootRouteDomainRegexRejectRulesSpec{Rules: []RootRouteDomainRegexRejectRuleSpec{{Index: regexIndex, Rule: shared.RegexConflictLegacyRule}}}
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	rules := config["route"].(map[string]any)["rules"].([]any)
	rules = append(rules, map[string]any{rootRouteDomainRegexRejectMarker: map[string]any{"index": float64(regexIndex)}})
	config["route"].(map[string]any)["rules"] = rules
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_route_network_outbound_regex_conflict" {
		t.Fatalf("mixed v44/v43 projection should be rejected explicitly, got %v", err)
	}
}
