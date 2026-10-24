// @author 雾晚
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type domainRegexIPVersionContractFixture struct {
	Cases []struct {
		Name                      string                     `json:"name"`
		IPv6Mode                  int                        `json:"ipv6Mode"`
		InputConfig               map[string]any             `json:"inputConfig"`
		RootRouteIPVersionRule    RootRouteIPVersionRuleSpec `json:"rootRouteIPVersionRule"`
		AfterIPProjectionRules    []any                      `json:"afterIPProjectionRules"`
		ExpectedMaterializedRules []any                      `json:"expectedMaterializedRules"`
	} `json:"cases"`
	UnsupportedFallback struct {
		InputRouteRules    []any `json:"inputRouteRules"`
		ExpectedRouteRules []any `json:"expectedRouteRules"`
	} `json:"unsupportedFallback"`
}

func readDomainRegexIPVersionContractFixture(t *testing.T) domainRegexIPVersionContractFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_route_domain_regex_ip_version_contract.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture domainRegexIPVersionContractFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestDomainRegexFallbackKeepsRootIPVersionRuleAtSourceOrder(t *testing.T) {
	contract := readDomainRegexIPVersionContractFixture(t)
	for _, tc := range contract.Cases {
		t.Run(tc.Name, func(t *testing.T) {
			config := tc.InputConfig
			route := config["route"].(map[string]any)
			route["rules"] = tc.AfterIPProjectionRules
			config["route"] = route
			config["dns"] = map[string]any{"servers": []any{map[string]any{"type": "https", "tag": "dns-remote"}}}
			configBytes, err := json.Marshal(config)
			if err != nil {
				t.Fatal(err)
			}

			_, snapshot := fixture(t)
			snapshot.SchemaVersion = SnapshotVersionRouteIPVersionRule
			snapshot.Config = configBytes
			snapshot.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500, IPv6Mode: tc.IPv6Mode}
			snapshot.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
			snapshot.RootRoutePolicy = &RootRoutePolicy{}
			snapshot.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote"}
			snapshot.RootDNSLocalServer = &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"}
			snapshot.RootDNSDirectServer = &RootDNSDirectServerSpec{Tag: "dns-direct"}
			snapshot.RootRouteFinal = &RootRouteFinalSpec{Final: "proxy"}
			snapshot.RootRouteIPVersionRule = &tc.RootRouteIPVersionRule
			if err := CheckSnapshot(snapshot); err != nil {
				t.Fatalf("valid shared fallback projection rejected: %v", err)
			}
			materialized, err := materializeRootInbound(snapshot)
			if err != nil {
				t.Fatal(err)
			}
			var got map[string]any
			if err := json.Unmarshal(materialized, &got); err != nil {
				t.Fatal(err)
			}
			gotRules := got["route"].(map[string]any)["rules"]
			if !jsonEqual(gotRules, tc.ExpectedMaterializedRules) {
				t.Fatalf("Root insertion changed fallback rule order: got %#v want %#v", gotRules, tc.ExpectedMaterializedRules)
			}
		})
	}
}

func TestUnsupportedDomainRegexFallbackIsUntouched(t *testing.T) {
	contract := readDomainRegexIPVersionContractFixture(t)
	_, snapshot := fixture(t)
	snapshot.SchemaVersion = SnapshotVersion - 1
	config := map[string]any{
		"inbounds":  []any{},
		"outbounds": []any{map[string]any{"type": "direct", "tag": "direct"}},
		"route":     map[string]any{"rules": contract.UnsupportedFallback.InputRouteRules},
	}
	b, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	snapshot.Config = b
	gotBytes, err := materializeRootInbound(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(gotBytes, &got); err != nil {
		t.Fatal(err)
	}
	gotRules := got["route"].(map[string]any)["rules"]
	if !jsonEqual(gotRules, contract.UnsupportedFallback.ExpectedRouteRules) {
		t.Fatalf("unsupported domain_regex fallback changed: got %#v want %#v", gotRules, contract.UnsupportedFallback.ExpectedRouteRules)
	}
}
