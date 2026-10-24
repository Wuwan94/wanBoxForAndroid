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

type sourceIPRejectFixture struct {
	Spec           RootRouteSourceIPRejectRulesSpec `json:"spec"`
	InputConfig    map[string]any                   `json:"inputConfig"`
	ExpectedConfig map[string]any                   `json:"expectedConfig"`
	ParserCases    []struct {
		Name   string   `json:"name"`
		Source string   `json:"source"`
		Valid  bool     `json:"valid"`
		Values []string `json:"values"`
	} `json:"parserCases"`
}

func readSourceIPRejectFixture(t *testing.T) sourceIPRejectFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_route_source_ip_reject.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture sourceIPRejectFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestSourceIPRejectUsesRawFixtureAndPreservesOrder(t *testing.T) {
	fixture := readSourceIPRejectFixture(t)
	if err := validateRootRouteSourceIPRejectRules(&fixture.Spec, fixture.InputConfig); err != nil {
		t.Fatalf("fixture invalid: %v", err)
	}
	route, _ := fixture.InputConfig["route"].(map[string]any)
	if err := materializeRootRouteSourceIPRejectRules(route, &fixture.Spec); err != nil {
		t.Fatal(err)
	}
	fixture.InputConfig["route"] = route
	got, _ := json.Marshal(fixture.InputConfig)
	want, _ := json.Marshal(fixture.ExpectedConfig)
	if string(got) != string(want) {
		t.Fatalf("materialized config mismatch\\n got: %s\\nwant: %s", got, want)
	}
}

func TestSourceIPRejectParserAndMalformedInputs(t *testing.T) {
	fixture := readSourceIPRejectFixture(t)
	for _, tc := range fixture.ParserCases {
		got, err := parseRootRouteSourceIPRejectCIDRs(tc.Source)
		if tc.Valid {
			if err != nil || !reflect.DeepEqual(got, tc.Values) {
				t.Errorf("%s: got %v, %v; want %v", tc.Name, got, err, tc.Values)
			}
		} else if err == nil {
			t.Errorf("%s unexpectedly parsed as %v", tc.Name, got)
		}
	}
	bad := fixture.Spec
	bad.Rules = append([]RootRouteSourceIPRejectRuleSpec(nil), fixture.Spec.Rules...)
	bad.Rules[0].SourceText = "192.0.2.0/99"
	if err := validateRootRouteSourceIPRejectRules(&bad, fixture.InputConfig); err == nil {
		t.Fatal("invalid CIDR should fail")
	}
	duplicate := fixture.Spec
	duplicate.Rules = append(append([]RootRouteSourceIPRejectRuleSpec(nil), fixture.Spec.Rules...), fixture.Spec.Rules[0])
	if err := validateRootRouteSourceIPRejectRules(&duplicate, fixture.InputConfig); err == nil {
		t.Fatal("duplicate rule ID should fail")
	}
	missing := map[string]any{"route": map[string]any{"rules": []any{}}}
	if err := validateRootRouteSourceIPRejectRules(&fixture.Spec, missing); err == nil {
		t.Fatal("missing marker should fail")
	}
}
