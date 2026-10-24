// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

type rawDomainRegexProjectionFixture struct {
	Cases []struct {
		Name          string          `json:"name"`
		Rule          json.RawMessage `json:"rule"`
		PatternError  bool            `json:"patternError"`
		PatternChecks []struct {
			Host             string `json:"host"`
			Matches          bool   `json:"matches"`
			EffectiveMatches bool   `json:"effectiveMatches"`
		} `json:"patternChecks"`
	} `json:"rawProjectionCases"`
	Fallbacks []struct {
		Name string          `json:"name"`
		Rule json.RawMessage `json:"rule"`
	} `json:"rawProjectionFallbacks"`
}

func readRawDomainRegexProjectionFixture(t *testing.T) rawDomainRegexProjectionFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_route_domain_regex_ip_version_contract.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture rawDomainRegexProjectionFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestDomainRegexRawProjectionKeepsPayloadAndGoCorePatternSemantics(t *testing.T) {
	fixture := readRawDomainRegexProjectionFixture(t)
	for _, tc := range fixture.Cases {
		t.Run(tc.Name, func(t *testing.T) {
			var rule struct {
				Patterns []string `json:"domain_regex"`
				Invert   bool     `json:"invert"`
				Action   string   `json:"action"`
				Method   string   `json:"method"`
				NoDrop   bool     `json:"no_drop"`
				Protocol []string `json:"protocol"`
			}
			if err := json.Unmarshal(tc.Rule, &rule); err != nil {
				t.Fatal(err)
			}
			if rule.Action != "reject" || len(rule.Patterns) == 0 {
				t.Fatalf("fixture rule is not a domain_regex reject: %#v", rule)
			}

			compiled := make([]*regexp.Regexp, 0, len(rule.Patterns))
			var compileErr error
			for _, pattern := range rule.Patterns {
				re, err := regexp.Compile(pattern)
				if err != nil {
					compileErr = err
					break
				}
				compiled = append(compiled, re)
			}
			if tc.PatternError {
				if compileErr == nil {
					t.Fatal("invalid RE2 vector unexpectedly compiled")
				}
			} else if compileErr != nil {
				t.Fatalf("valid RE2 vector failed to compile: %v", compileErr)
			}
			if (rule.Method == "reply") != rule.NoDrop && rule.NoDrop {
				t.Fatalf("fixture exercises invalid reject method/no_drop combination: %#v", rule)
			}
			for _, check := range tc.PatternChecks {
				rawMatch := false
				for _, matcher := range compiled {
					if matcher.MatchString(strings.ToLower(check.Host)) {
						rawMatch = true
						break
					}
				}
				effective := rawMatch
				if rule.Invert {
					effective = !effective
				}
				if rawMatch != check.Matches || effective != check.EffectiveMatches {
					t.Errorf("host %q: raw=%v effective=%v, want raw=%v effective=%v", check.Host, rawMatch, effective, check.Matches, check.EffectiveMatches)
				}
			}

			marker := map[string]any{rootRouteDomainRegexRejectMarker: map[string]any{"index": float64(1)}}
			config := map[string]any{"route": map[string]any{"rules": []any{map[string]any{"action": "sniff"}, marker, map[string]any{"domain_suffix": []any{"tail.example"}, "outbound": "direct"}}}}
			spec := &RootRouteDomainRegexRejectRulesSpec{Rules: []RootRouteDomainRegexRejectRuleSpec{{Index: 1, Rule: tc.Rule}}}
			if err := validateRootRouteDomainRegexRejectRules(spec, config); err != nil {
				t.Fatalf("raw rule projection rejected: %v", err)
			}
			if err := materializeRootRouteDomainRegexRejectRules(config["route"].(map[string]any), spec); err != nil {
				t.Fatalf("raw rule materialization failed: %v", err)
			}
			got := config["route"].(map[string]any)["rules"].([]any)[1].(json.RawMessage)
			if !bytes.Equal(got, tc.Rule) {
				t.Fatalf("raw matcher/action payload changed: got %s want %s", got, tc.Rule)
			}
		})
	}
}

func TestDomainRegexProjectionRejectsFallbackCasesWithoutMutatingConfig(t *testing.T) {
	fixture := readRawDomainRegexProjectionFixture(t)
	for _, tc := range fixture.Fallbacks {
		t.Run(tc.Name, func(t *testing.T) {
			marker := map[string]any{rootRouteDomainRegexRejectMarker: map[string]any{"index": float64(1)}}
			config := map[string]any{"route": map[string]any{"rules": []any{map[string]any{"action": "sniff"}, marker, map[string]any{"domain_suffix": []any{"tail.example"}}}}}
			spec := &RootRouteDomainRegexRejectRulesSpec{Rules: []RootRouteDomainRegexRejectRuleSpec{{Index: 1, Rule: tc.Rule}}}
			before, _ := json.Marshal(config)
			if err := validateRootRouteDomainRegexRejectRules(spec, config); err == nil {
				t.Fatal("ineligible matcher/action was accepted for Root projection")
			}
			after, _ := json.Marshal(config)
			if !bytes.Equal(before, after) {
				t.Fatalf("fallback validation mutated the original config: %s != %s", before, after)
			}
		})
	}
}

func TestDomainRegexRawProjectionMaterializesAtOriginalPositionAndWritesRootboxConfig(t *testing.T) {
	raw := readRawDomainRegexProjectionFixture(t)
	var selected json.RawMessage
	for _, item := range raw.Cases {
		if item.Name == "unanchored-pattern" {
			selected = item.Rule
		}
	}
	if len(selected) == 0 {
		t.Fatal("valid matcher/action vector missing")
	}
	_, sample := loadRouteMigrationIntegrationFixture(t)
	s := sample.Snapshot
	s.SchemaVersion = SnapshotVersionRouteDomainRegexRejectRules
	var config map[string]any
	if err := json.Unmarshal(s.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := route["rules"].([]any)
	index := len(rules)
	marker := map[string]any{rootRouteDomainRegexRejectMarker: map[string]any{"index": float64(index)}}
	rules = append(rules, marker, map[string]any{"domain_suffix": []any{"after-regex.example"}, "outbound": "proxy"})
	route["rules"] = rules
	config["route"] = route
	s.Config, _ = json.Marshal(config)
	s.RootRouteDomainRegexRejectRules = &RootRouteDomainRegexRejectRulesSpec{Rules: []RootRouteDomainRegexRejectRuleSpec{{Index: index, Rule: selected}}}
	if err := CheckSnapshot(s); err != nil {
		t.Fatalf("valid full integration snapshot rejected: %v", err)
	}
	materialized, err := materializeRootInbound(s)
	if err != nil {
		t.Fatal(err)
	}
	var output map[string]any
	if err := json.Unmarshal(materialized, &output); err != nil {
		t.Fatal(err)
	}
	outRules := output["route"].(map[string]any)["rules"].([]any)
	regexIndex, ipVersionIndex, suffixIndex := -1, -1, -1
	var expectedRule any
	if err := json.Unmarshal(selected, &expectedRule); err != nil {
		t.Fatal(err)
	}
	for i, item := range outRules {
		rule, _ := item.(map[string]any)
		if rule != nil && rule["domain_regex"] != nil && jsonEqual(rule, expectedRule) {
			regexIndex = i
		}
		if rule != nil && rule["ip_version"] == float64(6) && rule["action"] == "reject" {
			ipVersionIndex = i
		}
		if rule != nil && rule["domain_suffix"] != nil {
			suffixIndex = i
		}
	}
	if regexIndex < 0 || ipVersionIndex < 0 || suffixIndex < 0 || !(ipVersionIndex < regexIndex && regexIndex < suffixIndex) {
		t.Fatalf("Root insertion changed IP/domain_regex/fallback order: ip=%d regex=%d suffix=%d rules=%s", ipVersionIndex, regexIndex, suffixIndex, mustJSON(t, outRules))
	}
	if path := os.Getenv("WANBOX_DOMAIN_REGEX_ROOT_CONFIG_OUT"); path != "" {
		if err := os.WriteFile(path, materialized, 0600); err != nil {
			t.Fatal(err)
		}
	}
}

func mustJSON(t *testing.T, value any) string {
	t.Helper()
	b, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}
