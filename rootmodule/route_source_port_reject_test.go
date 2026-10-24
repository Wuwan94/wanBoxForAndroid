package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type sourcePortRejectFixture struct {
	Spec           RootRouteSourcePortRejectRulesSpec `json:"spec"`
	InputConfig    map[string]any                     `json:"inputConfig"`
	ExpectedConfig map[string]any                     `json:"expectedConfig"`
}

func readSourcePortRejectFixture(t *testing.T) sourcePortRejectFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_route_source_port_reject.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture sourcePortRejectFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestSourcePortRejectUsesRawFixtureAndPreservesOrder(t *testing.T) {
	fixture := readSourcePortRejectFixture(t)
	if err := validateRootRouteSourcePortRejectRules(&fixture.Spec, fixture.InputConfig); err != nil {
		t.Fatal(err)
	}
	route := fixture.InputConfig["route"].(map[string]any)
	if err := materializeRootRouteSourcePortRejectRules(route, &fixture.Spec); err != nil {
		t.Fatal(err)
	}
	if !jsonEqual(fixture.InputConfig, fixture.ExpectedConfig) {
		got, _ := json.Marshal(fixture.InputConfig)
		want, _ := json.Marshal(fixture.ExpectedConfig)
		t.Fatalf("materialized fixture mismatch: got %s want %s", got, want)
	}
}

func TestSourcePortRejectRejectsMismatchesAndMalformedInputs(t *testing.T) {
	fixture := readSourcePortRejectFixture(t)
	bad := fixture.Spec
	bad.Rules = append([]RootRouteSourcePortRejectRuleSpec(nil), fixture.Spec.Rules...)
	bad.Rules[0].SourcePortText = "53:55"
	if err := validateRootRouteSourcePortRejectRules(&bad, fixture.InputConfig); err == nil {
		t.Fatal("source port range accepted")
	}
	duplicate := fixture.Spec
	duplicate.Rules = append(append([]RootRouteSourcePortRejectRuleSpec(nil), fixture.Spec.Rules...), fixture.Spec.Rules[0])
	if err := validateRootRouteSourcePortRejectRules(&duplicate, fixture.InputConfig); err == nil {
		t.Fatal("duplicate id/spec accepted")
	}
	config := map[string]any{"route": map[string]any{"rules": []any{}}}
	if err := validateRootRouteSourcePortRejectRules(&fixture.Spec, config); err == nil {
		t.Fatal("missing marker accepted")
	}
}
