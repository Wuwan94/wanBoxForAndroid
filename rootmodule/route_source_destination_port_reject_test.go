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

type sourceDestinationPortRejectFixture struct {
	Spec           RootRouteSourceDestinationPortRejectRulesSpec `json:"spec"`
	InputConfig    map[string]any                                `json:"inputConfig"`
	ExpectedConfig map[string]any                                `json:"expectedConfig"`
	ParserCases    []struct {
		Name           string `json:"name"`
		SourcePortText string `json:"sourcePortText"`
		PortText       string `json:"portText"`
		Valid          bool   `json:"valid"`
		SourcePorts    []int  `json:"sourcePorts"`
		Ports          []int  `json:"ports"`
	} `json:"parserCases"`
}

func readSourceDestinationPortRejectFixture(t *testing.T) sourceDestinationPortRejectFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_route_source_destination_port_reject.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f sourceDestinationPortRejectFixture
	if err := json.Unmarshal(data, &f); err != nil {
		t.Fatal(err)
	}
	return f
}
func TestSourceDestinationPortRejectUsesRawFixtureAndPreservesOrder(t *testing.T) {
	f := readSourceDestinationPortRejectFixture(t)
	if err := validateRootRouteSourceDestinationPortRejectRules(&f.Spec, f.InputConfig); err != nil {
		t.Fatal(err)
	}
	route := f.InputConfig["route"].(map[string]any)
	if err := materializeRootRouteSourceDestinationPortRejectRules(route, &f.Spec); err != nil {
		t.Fatal(err)
	}
	if !jsonEqual(f.InputConfig, f.ExpectedConfig) {
		got, _ := json.Marshal(f.InputConfig)
		want, _ := json.Marshal(f.ExpectedConfig)
		t.Fatalf("got %s want %s", got, want)
	}
}
func TestSourceDestinationPortRejectParsersRejectMalformedAndRanges(t *testing.T) {
	f := readSourceDestinationPortRejectFixture(t)
	for _, tc := range f.ParserCases {
		source, sourceErr := parseRootPortRejectPorts(tc.SourcePortText)
		dest, destErr := parseRootPortRejectPorts(tc.PortText)
		if tc.Valid {
			if sourceErr != nil || destErr != nil || !reflect.DeepEqual(source, tc.SourcePorts) || !reflect.DeepEqual(dest, tc.Ports) {
				t.Errorf("%s: got %v/%v errors %v/%v", tc.Name, source, dest, sourceErr, destErr)
			}
		} else if sourceErr == nil && destErr == nil {
			t.Errorf("%s unexpectedly accepted", tc.Name)
		}
	}
	f.Spec.Rules[0].PortText = "80:90"
	if err := validateRootRouteSourceDestinationPortRejectRules(&f.Spec, f.InputConfig); err == nil {
		t.Fatal("range accepted")
	}
}
