package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type networkPortRejectFixture struct {
	Spec           RootRouteNetworkPortRejectRulesSpec `json:"spec"`
	InputConfig    map[string]any                      `json:"inputConfig"`
	ExpectedConfig map[string]any                      `json:"expectedConfig"`
}

func readNetworkPortRejectFixture(t *testing.T) networkPortRejectFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_route_network_port_reject.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture networkPortRejectFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestNetworkPortRejectUsesRawFixtureAndPreservesOrder(t *testing.T) {
	fixture := readNetworkPortRejectFixture(t)
	if err := validateRootRouteNetworkPortRejectRules(&fixture.Spec, fixture.InputConfig); err != nil {
		t.Fatal(err)
	}
	route := fixture.InputConfig["route"].(map[string]any)
	if err := materializeRootRouteNetworkPortRejectRules(route, &fixture.Spec); err != nil {
		t.Fatal(err)
	}
	if !jsonEqual(fixture.InputConfig, fixture.ExpectedConfig) {
		got, _ := json.Marshal(fixture.InputConfig)
		want, _ := json.Marshal(fixture.ExpectedConfig)
		t.Fatalf("materialized fixture mismatch: got %s want %s", got, want)
	}
}

func TestNetworkPortRejectRejectsMismatchesAndMalformedInputs(t *testing.T) {
	fixture := readNetworkPortRejectFixture(t)
	bad := fixture.Spec
	bad.Rules = append([]RootRouteNetworkPortRejectRuleSpec(nil), fixture.Spec.Rules...)
	bad.Rules[0].PortText = "80-90"
	if err := validateRootRouteNetworkPortRejectRules(&bad, fixture.InputConfig); err == nil {
		t.Fatal("malformed range accepted")
	}
	duplicate := fixture.Spec
	duplicate.Rules = append(append([]RootRouteNetworkPortRejectRuleSpec(nil), fixture.Spec.Rules...), fixture.Spec.Rules[0])
	if err := validateRootRouteNetworkPortRejectRules(&duplicate, fixture.InputConfig); err == nil {
		t.Fatal("duplicate id/spec accepted")
	}
	config := map[string]any{"route": map[string]any{"rules": []any{}}}
	if err := validateRootRouteNetworkPortRejectRules(&fixture.Spec, config); err == nil {
		t.Fatal("missing marker accepted")
	}
}
