// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type ipOutboundFixture struct {
	InputConfig         map[string]any `json:"inputConfig"`
	ExpectedConfig      map[string]any `json:"expectedConfig"`
	InvertedInputConfig map[string]any `json:"invertedInputConfig"`
	InvertedExpected    map[string]any `json:"invertedExpectedConfig"`
	IPv6InputConfig     map[string]any `json:"ipv6InputConfig"`
	IPv6ExpectedConfig  map[string]any `json:"ipv6ExpectedConfig"`
}

func readIPOutboundFixture(t *testing.T) ipOutboundFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_ip_outbound_v52.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f ipOutboundFixture
	if err := json.Unmarshal(data, &f); err != nil {
		t.Fatal(err)
	}
	return f
}

func assertIPOutboundMaterialization(t *testing.T, input, expected map[string]any) {
	t.Helper()
	snapshot := Snapshot{}
	if err := validateRootIPOutboundRoute(input, snapshot); err != nil {
		t.Fatal(err)
	}
	if err := materializeRootIPOutboundRoute(input); err != nil {
		t.Fatal(err)
	}
	got, _ := json.Marshal(input)
	want, _ := json.Marshal(expected)
	if !bytes.Equal(got, want) {
		t.Fatalf("got %s want %s", got, want)
	}
}

func TestIPOutboundRootGenerationMatchesSharedFixtureAndOrder(t *testing.T) {
	f := readIPOutboundFixture(t)
	assertIPOutboundMaterialization(t, f.InputConfig, f.ExpectedConfig)
	assertIPOutboundMaterialization(t, f.InvertedInputConfig, f.InvertedExpected)
}

func TestIPOutboundAcceptsIPv6CIDRsAndPreservesOrder(t *testing.T) {
	f := readIPOutboundFixture(t)
	assertIPOutboundMaterialization(t, f.IPv6InputConfig, f.IPv6ExpectedConfig)
}

func TestIPOutboundRejectsMalformedMarkersAndPreservesLegacyRules(t *testing.T) {
	f := readIPOutboundFixture(t)
	legacy := map[string]any{"route": map[string]any{"rules": []any{map[string]any{"domain_regex": []string{".*"}, "outbound": "proxy-main"}}}}
	if hasRootIPOutboundRouteMarker(legacy) {
		t.Fatal("legacy rule unexpectedly moved")
	}
	if err := validateRootIPOutboundRoute(legacy, Snapshot{}); err != nil {
		t.Fatalf("unmarked legacy rule should pass through: %v", err)
	}
	data, _ := json.Marshal(f.InputConfig)
	var malformed map[string]any
	if err := json.Unmarshal(data, &malformed); err != nil {
		t.Fatal(err)
	}
	marker := malformed["route"].(map[string]any)["rules"].([]any)[1].(map[string]any)[rootIPOutboundRouteMarker].(map[string]any)
	marker["ipText"] = "geoip:private"
	if err := validateRootIPOutboundRoute(malformed, Snapshot{}); err == nil {
		t.Fatal("unsupported IP syntax accepted")
	}
}
