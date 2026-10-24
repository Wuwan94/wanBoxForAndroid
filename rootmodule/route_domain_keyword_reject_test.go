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

type domainKeywordRejectFixture struct {
	Spec           RootRouteDomainKeywordRejectRulesSpec `json:"spec"`
	InputConfig    map[string]any                        `json:"inputConfig"`
	ExpectedConfig map[string]any                        `json:"expectedConfig"`
	ParserCases    []struct {
		Name       string   `json:"name"`
		DomainText string   `json:"domainText"`
		Valid      bool     `json:"valid"`
		Domains    []string `json:"domains"`
	} `json:"parserCases"`
}

func readDomainKeywordRejectFixture(t *testing.T) domainKeywordRejectFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_route_domain_keyword_reject.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture domainKeywordRejectFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestDomainKeywordRejectUsesSharedRawFixtureAndPreservesOrder(t *testing.T) {
	fixture := readDomainKeywordRejectFixture(t)
	if err := validateRootRouteDomainKeywordRejectRules(&fixture.Spec, fixture.InputConfig); err != nil {
		t.Fatal(err)
	}
	route := fixture.InputConfig["route"].(map[string]any)
	if err := materializeRootRouteDomainKeywordRejectRules(route, &fixture.Spec); err != nil {
		t.Fatal(err)
	}
	if !jsonEqual(fixture.InputConfig, fixture.ExpectedConfig) {
		got, _ := json.Marshal(fixture.InputConfig)
		want, _ := json.Marshal(fixture.ExpectedConfig)
		t.Fatalf("materialized fixture mismatch: got %s want %s", got, want)
	}
}

func TestDomainKeywordRejectParserMatchesSharedFixtureAndRejectsMalformedContracts(t *testing.T) {
	fixture := readDomainKeywordRejectFixture(t)
	for _, item := range fixture.ParserCases {
		got, err := parseRootRouteKeywordDomains(item.DomainText)
		if item.Valid {
			if err != nil || !reflect.DeepEqual(got, item.Domains) {
				t.Errorf("%s: got %v, err %v, want %v", item.Name, got, err, item.Domains)
			}
		} else if err == nil {
			t.Errorf("%s unexpectedly accepted: %v", item.Name, got)
		}
	}
	bad := fixture.Spec
	bad.Rules = append([]RootRouteDomainKeywordRejectRuleSpec(nil), fixture.Spec.Rules...)
	bad.Rules[0].DomainText = "full:ads.example"
	if err := validateRootRouteDomainKeywordRejectRules(&bad, fixture.InputConfig); err == nil {
		t.Fatal("keyword matcher accepted by domain-keyword contract")
	}
	missing := map[string]any{"route": map[string]any{"rules": []any{}}}
	if err := validateRootRouteDomainKeywordRejectRules(&fixture.Spec, missing); err == nil {
		t.Fatal("missing marker accepted")
	}
}
