// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type sourcePortOutboundFixture struct {
	ProjectedRouteRule map[string]any `json:"projectedRouteRule"`
	ExpectedRouteRule  map[string]any `json:"expectedRouteRule"`
}

func readSourcePortOutboundFixture(t *testing.T) sourcePortOutboundFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_source_port_outbound_v63.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture sourcePortOutboundFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestRootSourcePortOutboundV63PreservesOrderAndDNS(t *testing.T) {
	fixture := readSourcePortOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionSourcePortOutboundRoutes
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	dnsBefore := cloneJSONMap(t, config["dns"].(map[string]any))
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	before := map[string]any{"protocol": []any{"dns"}, "action": "hijack-dns"}
	after := map[string]any{"network": []any{"udp"}, "port": []any{53}, "action": "reject"}
	rules = append(rules, before)
	marker := fixture.ProjectedRouteRule[rootSourcePortOutboundMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, fixture.ProjectedRouteRule, after)
	route["rules"] = rules
	config["route"] = route
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v63 snapshot rejected: %v", err)
	}
	if err := materializeRootSourcePortOutbound(config); err != nil {
		t.Fatal(err)
	}
	got := config["route"].(map[string]any)["rules"].([]any)
	if len(got) != len(rules) || !jsonEqual(got[len(got)-2], fixture.ExpectedRouteRule) {
		t.Fatalf("source-port matcher or order mismatch: %#v", got)
	}
	if !jsonEqual(got[:len(got)-2], rules[:len(rules)-2]) || !jsonEqual(got[len(got)-1], after) {
		t.Fatalf("neighboring route rules changed: %#v", got)
	}
	if !jsonEqual(config["dns"], dnsBefore) {
		t.Fatalf("source-port route changed DNS config: %#v", config["dns"])
	}
}

func TestRootSourcePortOutboundV63RejectsDowngradeAndUnsupportedPorts(t *testing.T) {
	fixture := readSourcePortOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	marker := fixture.ProjectedRouteRule[rootSourcePortOutboundMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, fixture.ProjectedRouteRule)
	route["rules"] = rules
	config["route"] = route
	snapshot.Config, _ = json.Marshal(config)
	snapshot.SchemaVersion = SnapshotVersionSourcePortOutboundRoutes - 1
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("downgrade accepted: %v", err)
	}
	snapshot.SchemaVersion = SnapshotVersionSourcePortOutboundRoutes
	for _, sourcePort := range []string{"53-54", "0", "65536", "udp:53"} {
		broken := cloneJSONMap(t, config)
		badRules := broken["route"].(map[string]any)["rules"].([]any)
		badRules[len(badRules)-1].(map[string]any)[rootSourcePortOutboundMarker].(map[string]any)["sourcePortText"] = sourcePort
		snapshot.Config, _ = json.Marshal(broken)
		if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_source_port_outbound_invalid" {
			t.Fatalf("unsupported source port %q accepted: %v", sourcePort, err)
		}
	}
}
