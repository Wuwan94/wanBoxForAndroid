// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestRootInvertedGroupV49BuildsNotOrFromSharedConfigBuilderFixture(t *testing.T) {
	data, err := os.ReadFile(filepath.Join("testdata", "root_remote_ruleset_v47.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture map[string]any
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	generated := fixture["generatedConfig"].(map[string]any)
	route := generated["route"].(map[string]any)
	rules := route["rules"].([]any)
	original := rules[0].(map[string]any)
	children := original["rules"].([]any)
	action := map[string]any{"outbound": original["outbound"]}
	rules[0] = map[string]any{rootRouteInvertedGroupMarker: map[string]any{
		"index": float64(0), "rules": children, "action": action,
	}}
	configBytes, err := json.Marshal(generated)
	if err != nil {
		t.Fatal(err)
	}
	var config map[string]any
	if err := json.Unmarshal(configBytes, &config); err != nil {
		t.Fatal(err)
	}
	if err := validateRootInvertedGroups(config); err != nil {
		t.Fatalf("valid marker rejected: %v", err)
	}
	if err := materializeRootInvertedGroups(config); err != nil {
		t.Fatal(err)
	}
	got := config["route"].(map[string]any)["rules"].([]any)[0]
	want := map[string]any{"type": "logical", "mode": "or", "rules": children, "invert": true, "outbound": "direct"}
	if !jsonEqual(got, want) {
		t.Fatalf("Root built wrong NOT(OR) group:\n got %#v\nwant %#v", got, want)
	}
}

func TestRootInvertedGroupV49RejectsMalformedAndDowngrade(t *testing.T) {
	config := map[string]any{"route": map[string]any{"rules": []any{
		map[string]any{rootRouteInvertedGroupMarker: map[string]any{"index": float64(0), "rules": []any{map[string]any{"domain": []any{"a"}}}, "action": map[string]any{"outbound": "direct"}}},
	}}}
	if err := validateRootInvertedGroups(config); err == nil || err.Error() != "root_route_inverted_group_invalid" {
		t.Fatalf("malformed group accepted: %v", err)
	}
	if !hasRootInvertedGroupMarker(config) {
		t.Fatal("test marker not detected")
	}
}
