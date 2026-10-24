// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type portRangeOutboundFixture struct {
	ProjectedRouteRule map[string]any `json:"projectedRouteRule"`
	ExpectedRouteRule  map[string]any `json:"expectedRouteRule"`
}

func readPortRangeOutboundFixture(t *testing.T, name string) portRangeOutboundFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatal(err)
	}
	var fixture portRangeOutboundFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestRootPortRangeOutboundV85MaterializesBothFieldsInPlace(t *testing.T) {
	for _, test := range []struct {
		name   string
		marker string
		field  string
	}{
		{"root_port_range_outbound_v85.json", rootPortRangeOutboundMarker, "port_range"},
		{"root_source_port_range_outbound_v85.json", rootSourcePortRangeOutboundMarker, "source_port_range"},
	} {
		t.Run(test.field, func(t *testing.T) {
			fixture := readPortRangeOutboundFixture(t, test.name)
			snapshot, _, _ := appUIDDomainRuleSetOutboundSnapshot(t)
			snapshot.SchemaVersion = SnapshotVersionPortRangeOutboundRoutes
			var sourceConfig map[string]any
			if err := json.Unmarshal(snapshot.Config, &sourceConfig); err != nil {
				t.Fatal(err)
			}
			config := cloneJSONMap(t, sourceConfig)
			dnsBefore := cloneJSONMap(t, config["dns"].(map[string]any))
			route := config["route"].(map[string]any)
			rules := append([]any{}, route["rules"].([]any)...)
			before := map[string]any{"protocol": []any{"dns"}, "action": "hijack-dns"}
			after := map[string]any{"network": []any{"udp"}, "port": []any{53}, "action": "reject"}
			rules = append(rules, before)
			marker := fixture.ProjectedRouteRule[test.marker].(map[string]any)
			marker["index"] = float64(len(rules))
			rules = append(rules, fixture.ProjectedRouteRule, after)
			route["rules"] = rules
			config["route"] = route
			snapshot.Config, _ = json.Marshal(config)
			if err := CheckSnapshot(snapshot); err != nil {
				t.Fatalf("v85 snapshot rejected: %v", err)
			}
			if err := materializeRootPortRangeOutbound(config, test.marker, test.field); err != nil {
				t.Fatal(err)
			}
			got := config["route"].(map[string]any)["rules"].([]any)
			if len(got) != len(rules) || !jsonEqual(got[len(got)-2], fixture.ExpectedRouteRule) {
				t.Fatalf("%s matcher or position mismatch: %#v", test.field, got)
			}
			if !jsonEqual(got[:len(got)-2], rules[:len(rules)-2]) || !jsonEqual(got[len(got)-1], after) {
				t.Fatalf("neighboring route rules changed: %#v", got)
			}
			if !jsonEqual(config["dns"], dnsBefore) {
				t.Fatalf("range route changed DNS config: %#v", config["dns"])
			}
		})
	}
}

func TestRootPortRangeOutboundV85RejectsDowngradeAndInvalidRanges(t *testing.T) {
	fixture := readPortRangeOutboundFixture(t, "root_port_range_outbound_v85.json")
	snapshot, _, _ := appUIDDomainRuleSetOutboundSnapshot(t)
	snapshot.SchemaVersion = SnapshotVersionPortRangeOutboundRoutes
	var sourceConfig map[string]any
	if err := json.Unmarshal(snapshot.Config, &sourceConfig); err != nil {
		t.Fatal(err)
	}
	config := cloneJSONMap(t, sourceConfig)
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	marker := fixture.ProjectedRouteRule[rootPortRangeOutboundMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, fixture.ProjectedRouteRule)
	route["rules"] = rules
	config["route"] = route
	snapshot.Config, _ = json.Marshal(config)
	snapshot.SchemaVersion = SnapshotVersionAppUIDDomainRuleSetOutboundRoutes
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("v84 snapshot accepted v85 marker: %v", err)
	}
	snapshot.SchemaVersion = SnapshotVersionPortRangeOutboundRoutes
	for _, broken := range []struct {
		name string
		edit func(map[string]any)
	}{
		{"open-ended", func(m map[string]any) { m["ranges"] = []any{":80"} }},
		{"malformed", func(m map[string]any) { m["ranges"] = []any{"80:bad"} }},
		{"signed-number", func(m map[string]any) { m["ranges"] = []any{"+80:90"} }},
		{"out-of-range", func(m map[string]any) { m["ranges"] = []any{"0:80"} }},
		{"reversed", func(m map[string]any) { m["ranges"] = []any{"90:80"} }},
		{"blocked-action", func(m map[string]any) { m["outbound"] = "block" }},
	} {
		t.Run(broken.name, func(t *testing.T) {
			bad := cloneJSONMap(t, config)
			badRules := bad["route"].(map[string]any)["rules"].([]any)
			badMarker := badRules[len(badRules)-1].(map[string]any)[rootPortRangeOutboundMarker].(map[string]any)
			broken.edit(badMarker)
			snapshot.Config, _ = json.Marshal(bad)
			if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_port_range_outbound_invalid" {
				t.Fatalf("invalid marker accepted: %v", err)
			}
		})
	}
}
