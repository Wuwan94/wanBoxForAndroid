// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type rootRemoteRuleSetDomainOutboundFixture struct {
	ProjectedConfig    map[string]any `json:"projectedConfig"`
	ExpectedRouteRules []any          `json:"expectedRouteRules"`
	ExpectedRuleSets   []any          `json:"expectedRuleSets"`
}

func readRootRemoteRuleSetDomainOutboundFixture(t *testing.T) rootRemoteRuleSetDomainOutboundFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_remote_ruleset_domain_outbound_v68.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture rootRemoteRuleSetDomainOutboundFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func rootRemoteRuleSetDomainOutboundSnapshot(t *testing.T, shared rootRemoteRuleSetDomainOutboundFixture) (Snapshot, map[string]any) {
	t.Helper()
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetDomainOutboundRoutes
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	projectedRoute := shared.ProjectedConfig["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	var projectedRules []any
	for _, raw := range projectedRoute["rules"].([]any) {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRemoteRuleSetDomainOutboundRouteMarker]; exists {
				projectedRules = append(projectedRules, raw)
			}
		}
	}
	if len(projectedRules) != 1 {
		t.Fatal("fixture must contain exactly one projected route marker")
	}
	// Insert between existing rules to assert that materialization leaves neighbors/order intact.
	if len(rules) == 0 {
		t.Fatal("integration fixture has no route rules")
	}
	insert := len(rules) / 2
	projectedRules[0].(map[string]any)[rootRemoteRuleSetDomainOutboundRouteMarker].(map[string]any)["index"] = float64(insert)
	rules = append(rules, make([]any, len(projectedRules))...)
	copy(rules[insert+len(projectedRules):], rules[insert:len(rules)-len(projectedRules)])
	copy(rules[insert:insert+len(projectedRules)], projectedRules)
	route["rules"] = rules
	sets := append([]any{}, route["rule_set"].([]any)...)
	sets = append(sets, projectedRoute["rule_set"].([]any)...)
	route["rule_set"] = sets
	snapshot.Config, _ = json.Marshal(config)
	return snapshot, config
}

func TestRootGeneratedRemoteRuleSetDomainOutboundV68PreservesMatchersInversionAndPosition(t *testing.T) {
	shared := readRootRemoteRuleSetDomainOutboundFixture(t)
	snapshot, config := rootRemoteRuleSetDomainOutboundSnapshot(t, shared)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v68 remote-ruleset domain projection rejected: %v", err)
	}
	route := config["route"].(map[string]any)
	rules := route["rules"].([]any)
	beforeDNS, _ := json.Marshal(config["dns"])
	beforeRules := append([]any{}, rules...)
	if err := materializeRootRemoteRuleSetDomainOutboundRoutes(config); err != nil {
		t.Fatal(err)
	}
	rules = route["rules"].([]any)
	insert := -1
	for index, raw := range beforeRules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRemoteRuleSetDomainOutboundRouteMarker]; exists {
				insert = index
				break
			}
		}
	}
	if insert < 0 {
		t.Fatal("v68 marker missing from fixture snapshot")
	}
	expectedRule := shared.ExpectedRouteRules[1]
	if !jsonEqual(rules[insert], expectedRule) {
		t.Fatalf("generated rule_set/domain conjunction or invert changed: got %#v want %#v", rules[insert], expectedRule)
	}
	expectedRules := append([]any{}, beforeRules...)
	expectedRules[insert] = expectedRule
	if !jsonEqual(rules, expectedRules) {
		t.Fatalf("neighboring rule order changed: got %#v want %#v", rules, expectedRules)
	}
	afterDNS, _ := json.Marshal(config["dns"])
	if string(beforeDNS) != string(afterDNS) {
		t.Fatalf("route migration changed DNS effects: before %s after %s", beforeDNS, afterDNS)
	}
	if err := materializeRootRemoteRuleSets(route); err != nil {
		t.Fatal(err)
	}
	sets := route["rule_set"].([]any)
	if len(sets) == 0 || !jsonEqual(sets[len(sets)-1:], shared.ExpectedRuleSets) {
		t.Fatalf("remote set definitions/order changed: got %#v want suffix %#v", sets, shared.ExpectedRuleSets)
	}
}

func TestRootRemoteRuleSetDomainOutboundV68RejectsDowngradeAndUnmigratedSet(t *testing.T) {
	shared := readRootRemoteRuleSetDomainOutboundFixture(t)
	snapshot, config := rootRemoteRuleSetDomainOutboundSnapshot(t, shared)
	snapshot.SchemaVersion--
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("v68 marker accepted with old schema: %v", err)
	}
	snapshot, config = rootRemoteRuleSetDomainOutboundSnapshot(t, shared)
	rules := config["route"].(map[string]any)["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if marker, ok := rule[rootRemoteRuleSetDomainOutboundRouteMarker].(map[string]any); ok {
				marker["tags"] = []any{"not-migrated"}
			}
		}
	}
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_remote_ruleset_domain_outbound_invalid" {
		t.Fatalf("unmigrated rule set accepted: %v", err)
	}
	snapshot, config = rootRemoteRuleSetDomainOutboundSnapshot(t, shared)
	rules = config["route"].(map[string]any)["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if marker, ok := rule[rootRemoteRuleSetDomainOutboundRouteMarker].(map[string]any); ok {
				marker["domains"] = []any{"example.com,other.example"}
			}
		}
	}
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_remote_ruleset_domain_outbound_invalid" {
		t.Fatalf("ambiguous multi-domain matcher accepted: %v", err)
	}
}
