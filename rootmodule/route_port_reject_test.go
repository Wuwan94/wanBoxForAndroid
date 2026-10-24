package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestStructuredRoutePortRejectRulesMatchSharedFixtureAndPreserveOrder(t *testing.T) {
	fixtureBytes, err := os.ReadFile(filepath.Join("testdata", "root_route_port_reject.json"))
	if err != nil {
		t.Fatal(err)
	}
	var shared struct {
		Spec           RootRoutePortRejectRulesSpec `json:"spec"`
		InputConfig    map[string]any               `json:"inputConfig"`
		ExpectedConfig map[string]any               `json:"expectedConfig"`
	}
	if err := json.Unmarshal(fixtureBytes, &shared); err != nil {
		t.Fatal(err)
	}

	_, snapshot := fixture(t)
	snapshot.SchemaVersion = SnapshotVersionRoutePortRejectRules
	snapshot.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500}
	snapshot.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	snapshot.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote"}
	snapshot.RootDNSLocalServer = &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"}
	snapshot.RootDNSDirectServer = &RootDNSDirectServerSpec{Tag: "dns-direct"}
	snapshot.RootRouteFinal = &RootRouteFinalSpec{Final: "direct"}
	snapshot.RootRouteIPVersionRule = &RootRouteIPVersionRuleSpec{Index: 0, IPVersion: 6}
	snapshot.RootRouteProbeRule = &RootRouteProbeRuleSpec{Index: 0, Host: "probe.example", Outbound: "direct"}
	snapshot.RootRoutePortRejectRules = &shared.Spec
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	config["route"] = shared.InputConfig["route"]
	config["dns"] = map[string]any{"servers": []any{map[string]any{"type": "https", "tag": "dns-remote"}}}
	snapshot.Config, err = json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("valid v30 raw port-reject fixture rejected: %v", err)
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
	wantRoute := shared.ExpectedConfig["route"].(map[string]any)
	wantRules := []any{
		map[string]any{"inbound": []any{"mixed"}, "domain": []any{"probe.example"}, "outbound": "direct"},
		map[string]any{"ip_version": 6, "action": "reject"},
	}
	wantRules = append(wantRules, wantRoute["rules"].([]any)...)
	if !jsonEqual(gotRoute["rules"], wantRules) {
		t.Fatalf("materialized v30 route differs from shared fixture: got %#v want %#v", gotRoute["rules"], wantRules)
	}
}

func TestStructuredRoutePortRejectRulesRejectInvalidAndMismatchedFixtures(t *testing.T) {
	fixtureBytes, err := os.ReadFile(filepath.Join("testdata", "root_route_port_reject.json"))
	if err != nil {
		t.Fatal(err)
	}
	var shared struct {
		Spec        RootRoutePortRejectRulesSpec `json:"spec"`
		InputConfig map[string]any               `json:"inputConfig"`
	}
	if err := json.Unmarshal(fixtureBytes, &shared); err != nil {
		t.Fatal(err)
	}
	cloneConfig := func() map[string]any {
		bytes, err := json.Marshal(shared.InputConfig)
		if err != nil {
			t.Fatal(err)
		}
		var config map[string]any
		if err := json.Unmarshal(bytes, &config); err != nil {
			t.Fatal(err)
		}
		return config
	}
	valid := cloneConfig()
	if err := validateRootRoutePortRejectRules(&shared.Spec, valid); err != nil {
		t.Fatalf("fixture itself rejected: %v", err)
	}

	badPort := shared.Spec
	badPort.Rules = append([]RootRoutePortRejectRuleSpec(nil), shared.Spec.Rules...)
	badPort.Rules[0].PortText = "80:90"
	duplicateID := shared.Spec
	duplicateID.Rules = append([]RootRoutePortRejectRuleSpec(nil), shared.Spec.Rules...)
	duplicateID.Rules[1].RuleID = duplicateID.Rules[0].RuleID
	mismatchedMarker := cloneConfig()
	rules := mismatchedMarker["route"].(map[string]any)["rules"].([]any)
	marker := rules[1].(map[string]any)[rootPortRejectRouteMarker].(map[string]any)
	marker["portText"] = "80"

	for _, tc := range []struct {
		name   string
		spec   *RootRoutePortRejectRulesSpec
		config map[string]any
	}{
		{name: "missing spec", spec: nil, config: cloneConfig()},
		{name: "invalid port range", spec: &badPort, config: cloneConfig()},
		{name: "duplicate rule id", spec: &duplicateID, config: cloneConfig()},
		{name: "marker mismatch", spec: &shared.Spec, config: mismatchedMarker},
		{name: "missing marker", spec: &shared.Spec, config: map[string]any{"route": map[string]any{"rules": []any{}}}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if err := validateRootRoutePortRejectRules(tc.spec, tc.config); err == nil {
				t.Fatal("invalid v30 route-port fixture was accepted")
			}
		})
	}
}
