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

type protocolPortRejectFixture struct {
	Spec           RootRouteProtocolPortRejectRulesSpec `json:"spec"`
	InputConfig    map[string]any                       `json:"inputConfig"`
	ExpectedConfig map[string]any                       `json:"expectedConfig"`
	ParserCases    []struct {
		Name, ProtocolText, PortText string
		Valid                        bool
		Protocols                    []string
		Ports                        []int
	} `json:"parserCases"`
}

func readProtocolPortRejectFixture(t *testing.T) protocolPortRejectFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_route_protocol_port_reject.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f protocolPortRejectFixture
	if err = json.Unmarshal(data, &f); err != nil {
		t.Fatal(err)
	}
	return f
}
func TestProtocolPortRejectUsesSharedRawFixtureAndPreservesOrder(t *testing.T) {
	f := readProtocolPortRejectFixture(t)
	if err := validateRootRouteProtocolPortRejectRules(&f.Spec, f.InputConfig); err != nil {
		t.Fatal(err)
	}
	route := f.InputConfig["route"].(map[string]any)
	if err := materializeRootRouteProtocolPortRejectRules(route, &f.Spec); err != nil {
		t.Fatal(err)
	}
	if !jsonEqual(f.InputConfig, f.ExpectedConfig) {
		got, _ := json.Marshal(f.InputConfig)
		want, _ := json.Marshal(f.ExpectedConfig)
		t.Fatalf("got %s want %s", got, want)
	}
}
func TestProtocolPortRejectParsersMatchSharedFixture(t *testing.T) {
	f := readProtocolPortRejectFixture(t)
	for _, tc := range f.ParserCases {
		protocols, pe := parseRootRouteRejectProtocols(tc.ProtocolText)
		ports, oe := parseRootPortRejectPorts(tc.PortText)
		if tc.Valid {
			if pe != nil || oe != nil || !reflect.DeepEqual(protocols, tc.Protocols) || !reflect.DeepEqual(ports, tc.Ports) {
				t.Errorf("%s: got %v/%v errors %v/%v", tc.Name, protocols, ports, pe, oe)
			}
		} else if pe == nil && oe == nil {
			t.Errorf("%s unexpectedly accepted", tc.Name)
		}
	}
}
