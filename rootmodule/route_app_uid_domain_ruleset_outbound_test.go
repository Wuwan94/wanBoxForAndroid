// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type appUIDDomainRuleSetOutboundFixture struct {
	ProjectedRouteRule            map[string]any                                 `json:"projectedRouteRule"`
	Spec                          RootRouteAppUIDDomainRuleSetOutboundRoutesSpec `json:"spec"`
	ExpectedMaterializedRouteRule map[string]any                                 `json:"expectedMaterializedRouteRule"`
	RemoteRuleSets                []any                                          `json:"remoteRuleSets"`
}

func readAppUIDDomainRuleSetOutboundFixture(t *testing.T) appUIDDomainRuleSetOutboundFixture {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", "root_route_app_uid_domain_ruleset_outbound_v84.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f appUIDDomainRuleSetOutboundFixture
	if err = json.Unmarshal(b, &f); err != nil {
		t.Fatal(err)
	}
	return f
}

func appUIDDomainRuleSetOutboundSnapshot(t *testing.T) (Snapshot, routeMigrationIntegrationFixture, appUIDDomainRuleSetOutboundFixture) {
	t.Helper()
	_, base := loadRouteMigrationIntegrationFixture(t)
	f := readAppUIDDomainRuleSetOutboundFixture(t)
	s := base.Snapshot
	s.SchemaVersion = SnapshotVersionAppUIDDomainRuleSetOutboundRoutes
	c := map[string]any{}
	if err := json.Unmarshal(s.Config, &c); err != nil {
		t.Fatal(err)
	}
	route := c["route"].(map[string]any)
	rules := append([]any(nil), route["rules"].([]any)...)
	marker := f.ProjectedRouteRule[rootAppUIDDomainRuleSetOutboundMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, f.ProjectedRouteRule)
	route["rules"] = rules
	route["rule_set"] = f.RemoteRuleSets
	c["route"] = route
	s.Config, _ = json.Marshal(c)
	spec := f.Spec
	spec.Rules = append([]RootRouteAppUIDDomainRuleSetOutboundRuleSpec(nil), f.Spec.Rules...)
	spec.Rules[0].Index = len(rules) - 1
	s.RootAppUIDDomainRuleSetOutboundRoutes = &spec
	return s, base, f
}

func TestRootAppUIDDomainRuleSetOutboundV84FullSnapshotPreservesDomainConjunctionAndOrder(t *testing.T) {
	s, base, f := appUIDDomainRuleSetOutboundSnapshot(t)
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
	if len(sets) != len(f.RemoteRuleSets) {
		t.Fatalf("ruleset count=%d want=%d", len(sets), len(f.RemoteRuleSets))
	}
	for i, item := range sets {
		marker := f.RemoteRuleSets[i].(map[string]any)[rootRemoteRuleSetMarker].(map[string]any)
		if marker["tag"] != item.(map[string]any)["tag"] {
			t.Fatalf("ruleset order lost: %#v", sets)
		}
	}
	if !jsonEqual(got["dns"], base.ExpectedDNS) {
		t.Fatal("route projection changed independent DNS effects")
	}
}

func TestRootAppUIDDomainRuleSetOutboundV84RejectsMixedKindsRegexAndDowngrade(t *testing.T) {
	s, _, _ := appUIDDomainRuleSetOutboundSnapshot(t)
	old := s
	old.SchemaVersion = SnapshotVersionAppUIDRuleSetOutboundRoutes
	if err := CheckSnapshot(old); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("older schema accepted v84 marker: %v", err)
	}

	for _, mutate := range []func(*Snapshot){
		func(s *Snapshot) { s.RootAppUIDDomainRuleSetOutboundRoutes.Rules[0].RuleSetKinds[1] = "ip" },
		func(s *Snapshot) {
			s.RootAppUIDDomainRuleSetOutboundRoutes.Rules[0].DomainText = "regex:.*example.test"
		},
	} {
		bad, _, _ := appUIDDomainRuleSetOutboundSnapshot(t)
		mutate(&bad)
		if err := CheckSnapshot(bad); err == nil || err.Error() != "root_app_uid_domain_ruleset_outbound_invalid" {
			t.Fatalf("unsupported domain/ruleset combination accepted: %v", err)
		}
	}
}
