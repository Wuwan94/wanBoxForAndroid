// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type networkPortOutboundFixture struct {
	ProjectedRouteRule map[string]any `json:"projectedRouteRule"`
	ExpectedRouteRule  map[string]any `json:"expectedRouteRule"`
}

func readNetworkPortOutboundFixture(t *testing.T) networkPortOutboundFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_network_port_outbound_v57.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture networkPortOutboundFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestRootNetworkPortOutboundV57GeneratesConjunctionInPlace(t *testing.T) {
	fixture := readNetworkPortOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionNetworkPortOutboundRoutes
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	marker := fixture.ProjectedRouteRule[rootNetworkPortOutboundRouteMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, fixture.ProjectedRouteRule)
	route["rules"] = rules
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v57 snapshot rejected: %v", err)
	}
	if err := materializeRootNetworkPortOutboundRoutes(config); err != nil {
		t.Fatal(err)
	}
	got := config["route"].(map[string]any)["rules"].([]any)
	if len(got) != len(rules) || !jsonEqual(got[len(got)-1], fixture.ExpectedRouteRule) {
		t.Fatalf("network/port route output mismatch: %#v", got)
	}
	if !jsonEqual(got[:len(got)-1], rules[:len(rules)-1]) {
		t.Fatalf("preceding rules changed or reordered: %#v", got[:len(got)-1])
	}
}

func TestRootNetworkPortOutboundV57RejectsDowngradeAndUnsafePorts(t *testing.T) {
	fixture := readNetworkPortOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionNetworkPortOutboundRoutes - 1
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	rules = append(rules, fixture.ProjectedRouteRule)
	route["rules"] = rules
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("expected downgrade rejection, got %v", err)
	}
	for _, raw := range []string{"80:90", "65536", "-1", "abc"} {
		if _, err := parseRootNetworkPortOutboundPorts(raw); err == nil {
			t.Fatalf("unsafe or unsupported port input accepted: %q", raw)
		}
	}
}
