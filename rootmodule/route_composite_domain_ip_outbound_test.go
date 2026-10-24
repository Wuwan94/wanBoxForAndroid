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

type compositeDomainIPOutboundFixture struct {
	InputConfig            map[string]any `json:"inputConfig"`
	ExpectedConfig         map[string]any `json:"expectedConfig"`
	InvertedConfig         map[string]any `json:"invertedConfig"`
	InvertedExpectedConfig map[string]any `json:"invertedExpectedConfig"`
}

func readCompositeDomainIPOutboundFixture(t *testing.T) compositeDomainIPOutboundFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_composite_domain_ip_outbound_v51.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f compositeDomainIPOutboundFixture
	if err := json.Unmarshal(data, &f); err != nil {
		t.Fatal(err)
	}
	return f
}

func assertCompositeMaterialization(t *testing.T, input, expected map[string]any) {
	t.Helper()
	if err := validateRootCompositeDomainIPOutbound(input); err != nil {
		t.Fatal(err)
	}
	if err := materializeRootCompositeDomainIPOutbound(input); err != nil {
		t.Fatal(err)
	}
	got, _ := json.Marshal(input)
	want, _ := json.Marshal(expected)
	if !bytes.Equal(got, want) {
		t.Fatalf("got %s want %s", got, want)
	}
}

func TestCompositeDomainIPOutboundRootGenerationMatchesSharedFixture(t *testing.T) {
	f := readCompositeDomainIPOutboundFixture(t)
	assertCompositeMaterialization(t, f.InputConfig, f.ExpectedConfig)
	assertCompositeMaterialization(t, f.InvertedConfig, f.InvertedExpectedConfig)
}

func TestCompositeDomainIPOutboundRejectsUnsupportedOrMalformedMarker(t *testing.T) {
	f := readCompositeDomainIPOutboundFixture(t)
	legacy := map[string]any{"route": map[string]any{"rules": []any{map[string]any{"domain_regex": []string{".*"}, "outbound": "proxy-main"}}}}
	if hasRootCompositeDomainIPOutboundMarker(legacy) {
		t.Fatal("legacy rule unexpectedly moved")
	}
	if err := validateRootCompositeDomainIPOutbound(legacy); err != nil {
		t.Fatalf("unmarked legacy rule should pass through: %v", err)
	}

	b, _ := json.Marshal(f.InputConfig)
	var malformed map[string]any
	if err := json.Unmarshal(b, &malformed); err != nil {
		t.Fatal(err)
	}
	marker := malformed["route"].(map[string]any)["rules"].([]any)[1].(map[string]any)[rootCompositeDomainIPOutboundMarker].(map[string]any)
	marker["ipText"] = "geoip:private"
	if err := validateRootCompositeDomainIPOutbound(malformed); err == nil {
		t.Fatal("unsupported IP syntax accepted")
	}
}
