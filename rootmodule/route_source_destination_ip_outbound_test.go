// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type sourceDestinationIPOutboundFixture struct {
	ProjectedRouteRule map[string]any `json:"projectedRouteRule"`
	ExpectedRouteRule  map[string]any `json:"expectedRouteRule"`
}

func readSourceDestinationIPOutboundFixture(t *testing.T) sourceDestinationIPOutboundFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_source_destination_ip_outbound_v60.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture sourceDestinationIPOutboundFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestRootSourceDestinationIPOutboundV60PreservesConjunctionOrderAndDNS(t *testing.T) {
	fixture := readSourceDestinationIPOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionSourceDestinationIPOutboundRoutes
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
	marker := fixture.ProjectedRouteRule[rootSourceDestinationIPOutboundMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, fixture.ProjectedRouteRule, after)
	route["rules"] = rules
	config["route"] = route
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v60 snapshot rejected: %v", err)
	}
	if err := materializeRootSourceDestinationIPOutbound(config); err != nil {
		t.Fatal(err)
	}
	gotRoute := config["route"].(map[string]any)
	got := gotRoute["rules"].([]any)
	if len(got) != len(rules) || !jsonEqual(got[len(got)-2], fixture.ExpectedRouteRule) {
		t.Fatalf("source/destination conjunction or order mismatch: %#v", got)
	}
	if !jsonEqual(got[:len(got)-2], rules[:len(rules)-2]) || !jsonEqual(got[len(got)-1], after) {
		t.Fatalf("neighboring rules changed/reordered: %#v", got)
	}
	if !jsonEqual(config["dns"], dnsBefore) {
		t.Fatalf("migration changed DNS side effects: got %#v want %#v", config["dns"], dnsBefore)
	}
}

func TestRootSourceDestinationIPOutboundV60RejectsDowngradeAndInvalidCIDRs(t *testing.T) {
	fixture := readSourceDestinationIPOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	marker := fixture.ProjectedRouteRule[rootSourceDestinationIPOutboundMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, fixture.ProjectedRouteRule)
	route["rules"] = rules
	snapshot.Config, _ = json.Marshal(config)
	snapshot.SchemaVersion = SnapshotVersionSourceDestinationIPOutboundRoutes - 1
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("downgrade accepted: %v", err)
	}
	snapshot.SchemaVersion = SnapshotVersionSourceDestinationIPOutboundRoutes
	for _, field := range []string{"sourceText", "ipText"} {
		broken := cloneJSONMap(t, config)
		brokenRules := broken["route"].(map[string]any)["rules"].([]any)
		bad := brokenRules[len(brokenRules)-1].(map[string]any)[rootSourceDestinationIPOutboundMarker].(map[string]any)
		bad[field] = "geoip:private"
		badConfig, _ := json.Marshal(broken)
		snapshot.Config = badConfig
		if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_source_destination_ip_outbound_invalid" {
			t.Fatalf("invalid %s accepted: %v", field, err)
		}
	}
}
