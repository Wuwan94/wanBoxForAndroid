// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type appUIDRuleSetOutboundFixture struct {
	ProjectedRouteRule            map[string]any `json:"projectedRouteRule"`
	ExpectedMaterializedRouteRule map[string]any `json:"expectedMaterializedRouteRule"`
	RemoteRuleSets                []any          `json:"remoteRuleSets"`
}

func readAppUIDRuleSetOutboundFixture(t *testing.T) appUIDRuleSetOutboundFixture {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", "root_route_app_uid_ruleset_outbound_v83.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f appUIDRuleSetOutboundFixture
	if err = json.Unmarshal(b, &f); err != nil {
		t.Fatal(err)
	}
	return f
}
func appUIDRuleSetOutboundSnapshot(t *testing.T) (Snapshot, routeMigrationIntegrationFixture, appUIDRuleSetOutboundFixture) {
	t.Helper()
	_, base := loadRouteMigrationIntegrationFixture(t)
	f := readAppUIDRuleSetOutboundFixture(t)
	s := base.Snapshot
	s.SchemaVersion = SnapshotVersionAppUIDRuleSetOutboundRoutes
	c := map[string]any{}
	if err := json.Unmarshal(s.Config, &c); err != nil {
		t.Fatal(err)
	}
	route := c["route"].(map[string]any)
	rules := append([]any(nil), route["rules"].([]any)...)
	marker := f.ProjectedRouteRule[rootAppUIDRuleSetOutboundMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, f.ProjectedRouteRule)
	route["rules"] = rules
	route["rule_set"] = f.RemoteRuleSets
	c["route"] = route
	s.Config, _ = json.Marshal(c)
	s.RootAppUIDRuleSetOutboundRoutes = &RootRouteAppUIDRuleSetOutboundRoutesSpec{Rules: []RootRouteAppUIDRuleSetOutboundRuleSpec{{Index: len(rules) - 1, RuleID: 831, Packages: []string{"com.example.alpha", "com.example.beta"}, UIDs: []int{10001, 20001}, RuleSetTags: []string{"ruleset-domain-demo", "ruleset-domain-extra"}, Outbound: "direct", Invert: true}}}
	return s, base, f
}
func TestRootAppUIDRuleSetOutboundV83SnapshotPipeline(t *testing.T) {
	s, base, f := appUIDRuleSetOutboundSnapshot(t)
	if err := CheckSnapshot(s); err != nil {
		t.Fatalf("snapshot rejected: %v", err)
	}
	b, err := materializeRootInbound(s)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err = json.Unmarshal(b, &got); err != nil {
		t.Fatal(err)
	}
	route := got["route"].(map[string]any)
	want := append(append([]any(nil), base.ExpectedRouteRules...), f.ExpectedMaterializedRouteRule)
	if !jsonEqual(route["rules"], want) {
		t.Fatalf("route order/output mismatch: got=%#v want=%#v", route["rules"], want)
	}
	sets := route["rule_set"].([]any)
	for i, item := range sets {
		marker := f.RemoteRuleSets[i].(map[string]any)[rootRemoteRuleSetMarker].(map[string]any)
		if marker["tag"] != item.(map[string]any)["tag"] {
			t.Fatalf("ruleset definition not materialized in order: %#v", sets)
		}
	}
	if !jsonEqual(got["dns"], base.ExpectedDNS) {
		t.Fatal("route projection changed DNS output")
	}
}
func TestRootAppUIDRuleSetOutboundV83RejectsUnknownSetAndOldSchema(t *testing.T) {
	s, _, _ := appUIDRuleSetOutboundSnapshot(t)
	old := s
	old.SchemaVersion = SnapshotVersionPortOutboundRoutes
	if err := CheckSnapshot(old); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("older schema accepted v83 marker: %v", err)
	}
	c := map[string]any{}
	if err := json.Unmarshal(s.Config, &c); err != nil {
		t.Fatal(err)
	}
	marker := c["route"].(map[string]any)["rules"].([]any)[len(c["route"].(map[string]any)["rules"].([]any))-1].(map[string]any)[rootAppUIDRuleSetOutboundMarker].(map[string]any)
	marker["ruleSetTags"] = []any{"ruleset-not-migrated"}
	s.Config, _ = json.Marshal(c)
	if err := CheckSnapshot(s); err == nil || err.Error() != "root_app_uid_ruleset_outbound_invalid" {
		t.Fatalf("unknown ruleset accepted: %v", err)
	}
}
