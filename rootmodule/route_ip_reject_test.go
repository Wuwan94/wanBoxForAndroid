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

type ipRejectFixture struct {
	Spec           RootRouteIPRejectRulesSpec `json:"spec"`
	InputConfig    map[string]any             `json:"inputConfig"`
	ExpectedConfig map[string]any             `json:"expectedConfig"`
	ParserCases    []struct {
		Name   string   `json:"name"`
		IP     string   `json:"ip"`
		Valid  bool     `json:"valid"`
		Values []string `json:"values"`
	} `json:"parserCases"`
}

func readIPRejectFixture(t *testing.T) ipRejectFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_route_ip_reject.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture ipRejectFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestIPRejectUsesRawFixtureAndPreservesOrder(t *testing.T) {
	fixture := readIPRejectFixture(t)
	if err := validateRootRouteIPRejectRules(&fixture.Spec, fixture.InputConfig); err != nil {
		t.Fatalf("fixture invalid: %v", err)
	}
	route, _ := fixture.InputConfig["route"].(map[string]any)
	if err := materializeRootRouteIPRejectRules(route, &fixture.Spec); err != nil {
		t.Fatal(err)
	}
	fixture.InputConfig["route"] = route
	got, _ := json.Marshal(fixture.InputConfig)
	want, _ := json.Marshal(fixture.ExpectedConfig)
	if string(got) != string(want) {
		t.Fatalf("materialized config mismatch\\n got: %s\\nwant: %s", got, want)
	}
}

func TestIPRejectParserAndMalformedInputs(t *testing.T) {
	fixture := readIPRejectFixture(t)
	for _, tc := range fixture.ParserCases {
		got, err := parseRootRouteIPRejectCIDRs(tc.IP)
		if tc.Valid {
			if err != nil || !reflect.DeepEqual(got, tc.Values) {
				t.Errorf("%s: got %v, %v; want %v", tc.Name, got, err, tc.Values)
			}
		} else if err == nil {
			t.Errorf("%s unexpectedly parsed as %v", tc.Name, got)
		}
	}
	bad := fixture.Spec
	bad.Rules = append([]RootRouteIPRejectRuleSpec(nil), fixture.Spec.Rules...)
	bad.Rules[0].IPText = "192.0.2.0/99"
	if err := validateRootRouteIPRejectRules(&bad, fixture.InputConfig); err == nil {
		t.Fatal("invalid CIDR should fail")
	}
	duplicate := fixture.Spec
	duplicate.Rules = append(append([]RootRouteIPRejectRuleSpec(nil), fixture.Spec.Rules...), fixture.Spec.Rules[0])
	if err := validateRootRouteIPRejectRules(&duplicate, fixture.InputConfig); err == nil {
		t.Fatal("duplicate rule ID should fail")
	}
	missing := map[string]any{"route": map[string]any{"rules": []any{}}}
	if err := validateRootRouteIPRejectRules(&fixture.Spec, missing); err == nil {
		t.Fatal("missing marker should fail")
	}
}
