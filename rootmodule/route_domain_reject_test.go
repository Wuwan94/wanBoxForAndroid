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

type domainRejectFixture struct {
	Spec           RootRouteDomainRejectRulesSpec `json:"spec"`
	InputConfig    map[string]any                 `json:"inputConfig"`
	ExpectedConfig map[string]any                 `json:"expectedConfig"`
	ParserCases    []struct {
		Name       string   `json:"name"`
		DomainText string   `json:"domainText"`
		Valid      bool     `json:"valid"`
		Domains    []string `json:"domains"`
	} `json:"parserCases"`
}

func readDomainRejectFixture(t *testing.T) domainRejectFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_route_domain_reject.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture domainRejectFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestDomainRejectUsesSharedRawFixtureAndPreservesOrder(t *testing.T) {
	fixture := readDomainRejectFixture(t)
	if err := validateRootRouteDomainRejectRules(&fixture.Spec, fixture.InputConfig); err != nil {
		t.Fatal(err)
	}
	route := fixture.InputConfig["route"].(map[string]any)
	if err := materializeRootRouteDomainRejectRules(route, &fixture.Spec); err != nil {
		t.Fatal(err)
	}
	if !jsonEqual(fixture.InputConfig, fixture.ExpectedConfig) {
		got, _ := json.Marshal(fixture.InputConfig)
		want, _ := json.Marshal(fixture.ExpectedConfig)
		t.Fatalf("materialized fixture mismatch: got %s want %s", got, want)
	}
}

func TestDomainRejectParserMatchesSharedFixtureAndRejectsMalformedContracts(t *testing.T) {
	fixture := readDomainRejectFixture(t)
	for _, item := range fixture.ParserCases {
		got, err := parseRootRouteExactDomains(item.DomainText)
		if item.Valid {
			if err != nil || !reflect.DeepEqual(got, item.Domains) {
				t.Errorf("%s: got %v, err %v, want %v", item.Name, got, err, item.Domains)
			}
		} else if err == nil {
			t.Errorf("%s unexpectedly accepted: %v", item.Name, got)
		}
	}
	bad := fixture.Spec
	bad.Rules = append([]RootRouteDomainRejectRuleSpec(nil), fixture.Spec.Rules...)
	bad.Rules[0].DomainText = "domain:ads.example"
	if err := validateRootRouteDomainRejectRules(&bad, fixture.InputConfig); err == nil {
		t.Fatal("suffix matcher accepted by exact-domain contract")
	}
	missing := map[string]any{"route": map[string]any{"rules": []any{}}}
	if err := validateRootRouteDomainRejectRules(&fixture.Spec, missing); err == nil {
		t.Fatal("missing marker accepted")
	}
}
