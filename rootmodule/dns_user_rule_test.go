package module

import (
	"bytes"
	"encoding/json"
	"os"
	"testing"
)

type dnsUserRuleFixture struct {
	Config   map[string]any `json:"config"`
	Backend  string         `json:"backend"`
	Expected map[string]any `json:"expected"`
	Rules    []struct {
		ID         int64  `json:"id"`
		DomainText string `json:"domains"`
		Kind       string `json:"kind"`
		FakeDNS    bool   `json:"fakeDns"`
	} `json:"eligibleRules"`
}

func TestRootExactDomainDNSRuleFixture(t *testing.T) {
	b, err := os.ReadFile("testdata/root_dns_user_rule.json")
	if err != nil {
		t.Fatal(err)
	}
	var f dnsUserRuleFixture
	if err := json.Unmarshal(b, &f); err != nil {
		t.Fatal(err)
	}
	snapshot := Snapshot{RootInbound: &RootInboundSpec{Backend: f.Backend}}
	inputRules, _ := rootDNSRules(f.Config)
	if len(f.Rules) < 3 || len(inputRules) != len(f.Rules)+2 {
		t.Fatalf("fixture does not cover all typed rules and legacy neighbors")
	}
	for i, expected := range f.Rules {
		marker, exists, err := exactDomainDNSMarkerValue(inputRules[i+1].(map[string]any))
		if err != nil || !exists || marker["ruleId"] != float64(expected.ID) || marker["domainText"] != expected.DomainText || marker["kind"] != expected.Kind || marker["fakeDns"] != expected.FakeDNS {
			t.Fatalf("fixture marker %d does not match ConfigBuilder input", i)
		}
	}
	if err := validateRootExactDomainDNSRules(f.Config, snapshot); err != nil {
		t.Fatal(err)
	}
	if err := materializeRootExactDomainDNSRules(f.Config, snapshot); err != nil {
		t.Fatal(err)
	}
	gotJSON, _ := json.Marshal(f.Config)
	wantJSON, _ := json.Marshal(f.Expected)
	if !bytes.Equal(gotJSON, wantJSON) {
		got, _ := json.MarshalIndent(f.Config, "", "  ")
		want, _ := json.MarshalIndent(f.Expected, "", "  ")
		t.Fatalf("DNS rule materialization/order mismatch\ngot: %s\nwant:%s", got, want)
	}
}

func TestRootExactDomainDNSRuleRejectsMalformedAndUnsupportedMarkers(t *testing.T) {
	cases := []map[string]any{
		{"_wanbox_root_exact_domain_dns_rule_v53": map[string]any{"ruleId": float64(1), "domainText": "full:bad domain", "kind": "direct", "fakeDns": false}},
		{"_wanbox_root_exact_domain_dns_rule_v53": map[string]any{"ruleId": float64(1), "domainText": "regexp:^bad$", "kind": "direct", "fakeDns": false}},
		{"_wanbox_root_exact_domain_dns_rule_v53": map[string]any{"ruleId": float64(1), "domainText": "geosite:private", "kind": "direct", "fakeDns": false}},
		{"_wanbox_root_exact_domain_dns_rule_v53": map[string]any{"ruleId": float64(1), "domainText": "full:example.com", "kind": "proxy", "fakeDns": true}},
		{"_wanbox_root_exact_domain_dns_rule_v53": map[string]any{"ruleId": float64(1), "domainText": "full:example.com", "kind": "other", "fakeDns": false}},
	}
	for i, rule := range cases {
		config := map[string]any{"dns": map[string]any{"servers": []any{map[string]any{"tag": "dns-direct"}, map[string]any{"tag": "dns-remote"}}, "rules": []any{rule}}}
		if err := validateRootExactDomainDNSRules(config, Snapshot{RootInbound: &RootInboundSpec{Backend: "ebpf"}}); err == nil {
			t.Fatalf("case %d unexpectedly accepted", i)
		}
	}
}

func TestRootDomainAndRemoteRuleSetDNSEffectsMaterializeIndependentlyInOrder(t *testing.T) {
	data, err := os.ReadFile("testdata/root_dns_domain_remote_ruleset_composition_v78.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Projected map[string]any `json:"projectedConfig"`
		Expected  map[string]any `json:"expectedConfig"`
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	snapshot := Snapshot{RootInbound: &RootInboundSpec{Backend: "tun"}}
	route, ok := fixture.Projected["route"].(map[string]any)
	if !ok {
		t.Fatal("projected route missing")
	}
	if err := validateRootRemoteRuleSets(fixture.Projected); err != nil {
		t.Fatal(err)
	}
	if err := validateRootExactDomainDNSRules(fixture.Projected, snapshot); err != nil {
		t.Fatal(err)
	}
	if err := validateRootRemoteRuleSetDNSEffects(fixture.Projected, snapshot); err != nil {
		t.Fatal(err)
	}
	if err := materializeRootRemoteRuleSets(route); err != nil {
		t.Fatal(err)
	}
	if err := materializeRootExactDomainDNSRules(fixture.Projected, snapshot); err != nil {
		t.Fatal(err)
	}
	if err := materializeRootRemoteRuleSetDNSEffects(fixture.Projected); err != nil {
		t.Fatal(err)
	}
	got, _ := json.Marshal(fixture.Projected)
	want, _ := json.Marshal(fixture.Expected)
	if !bytes.Equal(got, want) {
		gotPretty, _ := json.MarshalIndent(fixture.Projected, "", "  ")
		wantPretty, _ := json.MarshalIndent(fixture.Expected, "", "  ")
		t.Fatalf("independent domain and ruleset DNS effects/order mismatch\ngot: %s\nwant: %s", gotPretty, wantPretty)
	}
}
