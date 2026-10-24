// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type rootRemoteRuleSetIPOutboundFixture struct {
	ProjectedConfig    map[string]any `json:"projectedConfig"`
	ExpectedRouteRules []any          `json:"expectedRouteRules"`
	ExpectedRuleSets   []any          `json:"expectedRuleSets"`
}

func readRootRemoteRuleSetIPOutboundFixture(t *testing.T) rootRemoteRuleSetIPOutboundFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_remote_ruleset_ip_outbound_v69.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture rootRemoteRuleSetIPOutboundFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func rootRemoteRuleSetIPOutboundSnapshot(t *testing.T, shared rootRemoteRuleSetIPOutboundFixture) (Snapshot, map[string]any) {
	t.Helper()
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionRemoteRuleSetIPOutboundRoutes
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	projectedRoute := shared.ProjectedConfig["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	projectedRules := projectedRoute["rules"].([]any)
	if len(projectedRules) != 1 {
		t.Fatalf("fixture needs one projected rule, got %d", len(projectedRules))
	}
	marker := projectedRules[0].(map[string]any)[rootRemoteRuleSetIPOutboundRouteMarker].(map[string]any)
	insert := len(rules) / 2
	marker["index"] = float64(insert)
	rules = append(rules, nil)
	copy(rules[insert+1:], rules[insert:len(rules)-1])
	rules[insert] = projectedRules[0]
	route["rules"] = rules
	sets := append([]any{}, route["rule_set"].([]any)...)
	sets = append(sets, projectedRoute["rule_set"].([]any)...)
	route["rule_set"] = sets
	snapshot.Config, _ = json.Marshal(config)
	return snapshot, config
}

func TestRootGeneratedRemoteRuleSetIPOutboundV69PreservesCIDRConjunctionInversionAndOrder(t *testing.T) {
	shared := readRootRemoteRuleSetIPOutboundFixture(t)
	snapshot, config := rootRemoteRuleSetIPOutboundSnapshot(t, shared)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v69 remote ruleset+IP projection rejected: %v", err)
	}
	route := config["route"].(map[string]any)
	rules := route["rules"].([]any)
	before := append([]any{}, rules...)
	beforeDNS, _ := json.Marshal(config["dns"])
	insert := -1
	for index, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRemoteRuleSetIPOutboundRouteMarker]; exists {
				insert = index
				break
			}
		}
	}
	if insert < 0 {
		t.Fatal("v69 marker missing")
	}
	if err := materializeRootRemoteRuleSetIPOutboundRoutes(config); err != nil {
		t.Fatal(err)
	}
	rules = route["rules"].([]any)
	if !jsonEqual(rules[insert], shared.ExpectedRouteRules[0]) {
		t.Fatalf("wrong generated conjunction: got %#v", rules[insert])
	}
	before[insert] = shared.ExpectedRouteRules[0]
	if !jsonEqual(rules, before) {
		t.Fatalf("rule order/neighbor changed: got %#v want %#v", rules, before)
	}
	afterDNS, _ := json.Marshal(config["dns"])
	if string(beforeDNS) != string(afterDNS) {
		t.Fatalf("route-only migration changed DNS: before %s after %s", beforeDNS, afterDNS)
	}
	if err := materializeRootRemoteRuleSets(route); err != nil {
		t.Fatal(err)
	}
	sets := route["rule_set"].([]any)
	if !jsonEqual(sets[len(sets)-1:], shared.ExpectedRuleSets) {
		t.Fatalf("ruleset definition changed: %#v", sets)
	}
}

func TestRootRemoteRuleSetIPOutboundV69RejectsDowngradeAndInvalidCIDR(t *testing.T) {
	shared := readRootRemoteRuleSetIPOutboundFixture(t)
	snapshot, _ := rootRemoteRuleSetIPOutboundSnapshot(t, shared)
	snapshot.SchemaVersion--
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("accepted v69 marker under old schema: %v", err)
	}
	snapshot, config := rootRemoteRuleSetIPOutboundSnapshot(t, shared)
	rules := config["route"].(map[string]any)["rules"].([]any)
	var marker map[string]any
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if candidate, exists := rule[rootRemoteRuleSetIPOutboundRouteMarker].(map[string]any); exists {
				marker = candidate
				break
			}
		}
	}
	if marker == nil {
		t.Fatal("v69 marker missing")
	}
	marker["cidrs"] = []any{"example.org/24"}
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_remote_ruleset_ip_outbound_invalid" {
		t.Fatalf("accepted invalid CIDR: %v", err)
	}
}
