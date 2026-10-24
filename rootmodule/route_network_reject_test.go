package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type rootNetworkParserFixtureCase struct {
	Name     string   `json:"name"`
	Raw      string   `json:"raw"`
	Valid    bool     `json:"valid"`
	Networks []string `json:"networks"`
}

func readRootNetworkRejectFixture(t *testing.T) struct {
	Spec           RootRouteNetworkRejectRulesSpec `json:"spec"`
	InputConfig    map[string]any                  `json:"inputConfig"`
	ExpectedConfig map[string]any                  `json:"expectedConfig"`
	ParserCases    []rootNetworkParserFixtureCase  `json:"parserCases"`
} {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_route_network_reject.json"))
	if err != nil {
		t.Fatal(err)
	}
	var shared struct {
		Spec           RootRouteNetworkRejectRulesSpec `json:"spec"`
		InputConfig    map[string]any                  `json:"inputConfig"`
		ExpectedConfig map[string]any                  `json:"expectedConfig"`
		ParserCases    []rootNetworkParserFixtureCase  `json:"parserCases"`
	}
	if err := json.Unmarshal(data, &shared); err != nil {
		t.Fatal(err)
	}
	return shared
}

func TestStructuredRouteNetworkRejectRulesMatchSharedFixtureAndPreserveOrder(t *testing.T) {
	shared := readRootNetworkRejectFixture(t)
	_, snapshot := fixture(t)
	snapshot.SchemaVersion = SnapshotVersionRouteNetworkRejectRules
	snapshot.RootInbound = &RootInboundSpec{Backend: "tun", IPv6Mode: 0, MTU: 1500}
	snapshot.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	snapshot.RootRoutePolicy = &RootRoutePolicy{AutoDetectInterface: true, FindProcess: true}
	snapshot.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote"}
	snapshot.RootDNSLocalServer = &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"}
	snapshot.RootDNSDirectServer = &RootDNSDirectServerSpec{Tag: "dns-direct"}
	snapshot.RootRouteFinal = &RootRouteFinalSpec{Final: "direct"}
	snapshot.RootRouteIPVersionRule = &RootRouteIPVersionRuleSpec{Index: 0, IPVersion: 6}
	snapshot.RootRouteProbeRule = &RootRouteProbeRuleSpec{Index: 0, Host: "probe.example", Outbound: "direct"}
	snapshot.RootRouteNetworkRejectRules = &shared.Spec
	var config map[string]any
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	config["route"] = shared.InputConfig["route"]
	config["dns"] = map[string]any{"servers": []any{map[string]any{"type": "https", "tag": "dns-remote"}}}
	var err error
	snapshot.Config, err = json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("valid v31 raw network-reject fixture rejected: %v", err)
	}
	gotBytes, err := materializeRootInbound(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(gotBytes, &got); err != nil {
		t.Fatal(err)
	}
	gotRoute := got["route"].(map[string]any)
	wantRules := shared.ExpectedConfig["route"].(map[string]any)["rules"].([]any)
	actualRules := gotRoute["rules"].([]any)
	if len(actualRules) < len(wantRules) {
		t.Fatalf("too few materialized route rules: got %d want tail %d", len(actualRules), len(wantRules))
	}
	actualTail := actualRules[len(actualRules)-len(wantRules):]
	if !jsonEqual(actualTail, wantRules) {
		t.Fatalf("materialized v31 route differs from shared fixture: got %#v want %#v", actualTail, wantRules)
	}
}

func TestStructuredRouteNetworkRejectRulesRejectInvalidAndMismatchedFixtures(t *testing.T) {
	shared := readRootNetworkRejectFixture(t)
	cloneConfig := func() map[string]any {
		data, err := json.Marshal(shared.InputConfig)
		if err != nil {
			t.Fatal(err)
		}
		var config map[string]any
		if err := json.Unmarshal(data, &config); err != nil {
			t.Fatal(err)
		}
		return config
	}
	if err := validateRootRouteNetworkRejectRules(&shared.Spec, cloneConfig()); err != nil {
		t.Fatalf("fixture itself rejected: %v", err)
	}
	badNetwork := shared.Spec
	badNetwork.Rules = append([]RootRouteNetworkRejectRuleSpec(nil), shared.Spec.Rules...)
	badNetwork.Rules[0].NetworkText = "tcp,sctp"
	duplicateID := shared.Spec
	duplicateID.Rules = append([]RootRouteNetworkRejectRuleSpec(nil), shared.Spec.Rules...)
	duplicateID.Rules[1].RuleID = duplicateID.Rules[0].RuleID
	mismatched := cloneConfig()
	rules := mismatched["route"].(map[string]any)["rules"].([]any)
	marker := rules[1].(map[string]any)[rootRouteNetworkRejectMarker].(map[string]any)
	marker["networkText"] = "tcp"

	for _, tc := range []struct {
		name   string
		spec   *RootRouteNetworkRejectRulesSpec
		config map[string]any
	}{
		{name: "missing spec", spec: nil, config: cloneConfig()},
		{name: "unsupported network", spec: &badNetwork, config: cloneConfig()},
		{name: "duplicate rule id", spec: &duplicateID, config: cloneConfig()},
		{name: "marker mismatch", spec: &shared.Spec, config: mismatched},
		{name: "missing marker", spec: &shared.Spec, config: map[string]any{"route": map[string]any{"rules": []any{}}}},
		{name: "empty spec", spec: &RootRouteNetworkRejectRulesSpec{}, config: cloneConfig()},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if err := validateRootRouteNetworkRejectRules(tc.spec, tc.config); err == nil {
				t.Fatal("invalid v31 network-reject fixture was accepted")
			}
		})
	}
}

func TestRootNetworkRejectParserMatchesSharedBoundaryCases(t *testing.T) {
	shared := readRootNetworkRejectFixture(t)
	if len(shared.ParserCases) == 0 {
		t.Fatal("shared parser boundary fixtures missing")
	}
	for _, tc := range shared.ParserCases {
		t.Run(tc.Name, func(t *testing.T) {
			got, err := parseRootRouteNetworkRejectNetworks(tc.Raw)
			if tc.Valid {
				if err != nil || !equalStrings(got, tc.Networks) {
					t.Fatalf("parse(%q) = %v, %v; want %v", tc.Raw, got, err, tc.Networks)
				}
			} else if err == nil {
				t.Fatalf("parse(%q) unexpectedly accepted %v", tc.Raw, got)
			}
		})
	}
}

func equalStrings(a, b []string) bool {
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
