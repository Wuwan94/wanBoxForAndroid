// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

type sourceDestinationIPRejectFixture struct {
	Spec              RootRouteSourceDestinationIPRejectRulesSpec `json:"spec"`
	InputConfig       map[string]any                              `json:"inputConfig"`
	ExpectedConfig    map[string]any                              `json:"expectedConfig"`
	SourceParserCases []struct {
		Name   string   `json:"name"`
		IP     string   `json:"ip"`
		Valid  bool     `json:"valid"`
		Values []string `json:"values"`
	} `json:"sourceParserCases"`
	DestinationParserCases []struct {
		Name   string   `json:"name"`
		IP     string   `json:"ip"`
		Valid  bool     `json:"valid"`
		Values []string `json:"values"`
	} `json:"destinationParserCases"`
}

func readSourceDestinationIPRejectFixture(t *testing.T) sourceDestinationIPRejectFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_route_source_destination_ip_reject.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture sourceDestinationIPRejectFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestSourceDestinationIPRejectUsesRawFixtureAndPreservesOrder(t *testing.T) {
	fixture := readSourceDestinationIPRejectFixture(t)
	if err := validateRootRouteSourceDestinationIPRejectRules(&fixture.Spec, fixture.InputConfig); err != nil {
		t.Fatalf("fixture invalid: %v", err)
	}
	route, _ := fixture.InputConfig["route"].(map[string]any)
	if err := materializeRootRouteSourceDestinationIPRejectRules(route, &fixture.Spec); err != nil {
		t.Fatal(err)
	}
	fixture.InputConfig["route"] = route
	got, _ := json.Marshal(fixture.InputConfig)
	want, _ := json.Marshal(fixture.ExpectedConfig)
	if string(got) != string(want) {
		t.Fatalf("materialized config mismatch\n got: %s\nwant: %s", got, want)
	}
}

func TestSourceDestinationIPRejectParsersAndMalformedInputs(t *testing.T) {
	fixture := readSourceDestinationIPRejectFixture(t)
	for _, tc := range fixture.SourceParserCases {
		got, err := parseRootRouteSourceIPRejectCIDRs(tc.IP)
		if tc.Valid {
			if err != nil || !reflect.DeepEqual(got, tc.Values) {
				t.Errorf("source %s: got %v, %v; want %v", tc.Name, got, err, tc.Values)
			}
		} else if err == nil {
			t.Errorf("source %s unexpectedly parsed as %v", tc.Name, got)
		}
	}
	for _, tc := range fixture.DestinationParserCases {
		got, err := parseRootRouteIPRejectCIDRs(tc.IP)
		if tc.Valid {
			if err != nil || !reflect.DeepEqual(got, tc.Values) {
				t.Errorf("destination %s: got %v, %v; want %v", tc.Name, got, err, tc.Values)
			}
		} else if err == nil {
			t.Errorf("destination %s unexpectedly parsed as %v", tc.Name, got)
		}
	}
	bad := fixture.Spec
	bad.Rules = append([]RootRouteSourceDestinationIPRejectRuleSpec(nil), fixture.Spec.Rules...)
	bad.Rules[0].SourceText = "192.0.2.1"
	if err := validateRootRouteSourceDestinationIPRejectRules(&bad, fixture.InputConfig); err == nil {
		t.Fatal("invalid source CIDR should fail")
	}
	bad = fixture.Spec
	bad.Rules = append([]RootRouteSourceDestinationIPRejectRuleSpec(nil), fixture.Spec.Rules...)
	bad.Rules[0].IPText = "203.0.113.0/99"
	if err := validateRootRouteSourceDestinationIPRejectRules(&bad, fixture.InputConfig); err == nil {
		t.Fatal("invalid destination CIDR should fail")
	}
	duplicate := fixture.Spec
	duplicate.Rules = append(append([]RootRouteSourceDestinationIPRejectRuleSpec(nil), fixture.Spec.Rules...), fixture.Spec.Rules[0])
	if err := validateRootRouteSourceDestinationIPRejectRules(&duplicate, fixture.InputConfig); err == nil {
		t.Fatal("duplicate rule ID should fail")
	}
	missing := map[string]any{"route": map[string]any{"rules": []any{}}}
	if err := validateRootRouteSourceDestinationIPRejectRules(&fixture.Spec, missing); err == nil {
		t.Fatal("missing marker should fail")
	}
}
