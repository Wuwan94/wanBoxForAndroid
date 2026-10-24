// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type protocolRejectFixture struct {
	Spec           RootRouteProtocolRejectRulesSpec `json:"spec"`
	InputConfig    map[string]any                   `json:"inputConfig"`
	ExpectedConfig map[string]any                   `json:"expectedConfig"`
	ParserCases    []struct {
		Name      string   `json:"name"`
		Protocols string   `json:"protocols"`
		Valid     bool     `json:"valid"`
		Values    []string `json:"values"`
	} `json:"parserCases"`
}

func readProtocolRejectFixture(t *testing.T) protocolRejectFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_route_protocol_reject.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture protocolRejectFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestProtocolRejectUsesRawFixtureAndPreservesOrder(t *testing.T) {
	fixture := readProtocolRejectFixture(t)
	if err := validateRootRouteProtocolRejectRules(&fixture.Spec, fixture.InputConfig); err != nil {
		t.Fatal(err)
	}
	route := fixture.InputConfig["route"].(map[string]any)
	if err := materializeRootRouteProtocolRejectRules(route, &fixture.Spec); err != nil {
		t.Fatal(err)
	}
	if !jsonEqual(fixture.InputConfig, fixture.ExpectedConfig) {
		got, _ := json.Marshal(fixture.InputConfig)
		want, _ := json.Marshal(fixture.ExpectedConfig)
		t.Fatalf("materialized fixture mismatch: got %s want %s", got, want)
	}
}

func TestProtocolRejectParserAndMalformedInputs(t *testing.T) {
	fixture := readProtocolRejectFixture(t)
	for _, item := range fixture.ParserCases {
		got, err := parseRootRouteRejectProtocols(item.Protocols)
		if item.Valid {
			if err != nil || !equalStringSlices(got, item.Values) {
				t.Fatalf("%s: got %v, err %v, want %v", item.Name, got, err, item.Values)
			}
		} else if err == nil {
			t.Fatalf("%s: invalid protocols accepted: %v", item.Name, got)
		}
	}
	bad := fixture.Spec
	bad.Rules = append([]RootRouteProtocolRejectRuleSpec(nil), fixture.Spec.Rules...)
	bad.Rules[0].ProtocolText = "tls, invalid"
	if err := validateRootRouteProtocolRejectRules(&bad, fixture.InputConfig); err == nil {
		t.Fatal("unknown protocol accepted")
	}
	duplicate := fixture.Spec
	duplicate.Rules = append(append([]RootRouteProtocolRejectRuleSpec(nil), fixture.Spec.Rules...), fixture.Spec.Rules[0])
	if err := validateRootRouteProtocolRejectRules(&duplicate, fixture.InputConfig); err == nil {
		t.Fatal("duplicate id/spec accepted")
	}
	missing := map[string]any{"route": map[string]any{"rules": []any{}}}
	if err := validateRootRouteProtocolRejectRules(&fixture.Spec, missing); err == nil {
		t.Fatal("missing marker accepted")
	}
}

func equalStringSlices(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}
