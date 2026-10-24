// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type ipVersionOutboundFixture struct {
	ProjectedRouteRule map[string]any `json:"projectedRouteRule"`
	ExpectedRouteRule  map[string]any `json:"expectedRouteRule"`
}

func readIPVersionOutboundFixture(t *testing.T) ipVersionOutboundFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_ip_version_outbound_v62.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture ipVersionOutboundFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestRootIPVersionOutboundV62PreservesOrderAndDNS(t *testing.T) {
	fixture := readIPVersionOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionIPVersionOutboundRoutes
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
	marker := fixture.ProjectedRouteRule[rootIPVersionOutboundMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, fixture.ProjectedRouteRule, after)
	route["rules"] = rules
	config["route"] = route
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v62 snapshot rejected: %v", err)
	}
	if err := materializeRootIPVersionOutbound(config); err != nil {
		t.Fatal(err)
	}
	got := config["route"].(map[string]any)["rules"].([]any)
	if len(got) != len(rules) || !jsonEqual(got[len(got)-2], fixture.ExpectedRouteRule) {
		t.Fatalf("IP-version matcher or order mismatch: %#v", got)
	}
	if !jsonEqual(got[:len(got)-2], rules[:len(rules)-2]) || !jsonEqual(got[len(got)-1], after) {
		t.Fatalf("neighboring route rules changed: %#v", got)
	}
	if !jsonEqual(config["dns"], dnsBefore) {
		t.Fatalf("IP-version-only route changed DNS config: %#v", config["dns"])
	}
}

func TestRootIPVersionOutboundV62RejectsDowngradeAndInvalidVersion(t *testing.T) {
	fixture := readIPVersionOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	marker := fixture.ProjectedRouteRule[rootIPVersionOutboundMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, fixture.ProjectedRouteRule)
	route["rules"] = rules
	config["route"] = route
	snapshot.Config, _ = json.Marshal(config)
	snapshot.SchemaVersion = SnapshotVersionIPVersionOutboundRoutes - 1
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("downgrade accepted: %v", err)
	}
	snapshot.SchemaVersion = SnapshotVersionIPVersionOutboundRoutes
	for _, version := range []float64{0, 5, 4.5} {
		broken := cloneJSONMap(t, config)
		badRules := broken["route"].(map[string]any)["rules"].([]any)
		badRules[len(badRules)-1].(map[string]any)[rootIPVersionOutboundMarker].(map[string]any)["ipVersion"] = version
		snapshot.Config, _ = json.Marshal(broken)
		if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_ip_version_outbound_invalid" {
			t.Fatalf("invalid IP version %v accepted: %v", version, err)
		}
	}
}
