// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type rootProtocolOutboundFixture struct {
	ProjectedConfig map[string]any `json:"projectedConfig"`
	ExpectedRules   []any          `json:"expectedRules"`
}

func readRootProtocolOutboundFixture(t *testing.T) rootProtocolOutboundFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_protocol_outbound_v78.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture rootProtocolOutboundFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func appendRootProtocolOutboundFixture(t *testing.T, snapshot *Snapshot, fixture rootProtocolOutboundFixture) map[string]any {
	t.Helper()
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	projected := fixture.ProjectedConfig["route"].(map[string]any)["rules"].([]any)
	marker := projected[1].(map[string]any)[rootProtocolOutboundRouteMarker].(map[string]any)
	insert := len(rules)
	before := map[string]any{"network": []any{"udp"}, "port": []any{53}, "action": "reject"}
	after := map[string]any{"protocol": []any{"dns"}, "outbound": "proxy"}
	marker["index"] = float64(insert + 1)
	rules = append(rules, before, projected[1], after)
	route["rules"] = rules
	config["route"] = route
	snapshot.Config, _ = json.Marshal(config)
	return config
}

func TestRootProtocolOutboundV78FullSnapshotPreservesOrderAndDNS(t *testing.T) {
	fixture := readRootProtocolOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionProtocolOutboundRoutes
	appendRootProtocolOutboundFixture(t, &snapshot, fixture)
	baselineSnapshot := integration.Snapshot
	baselineSnapshot.SchemaVersion = SnapshotVersionProtocolOutboundRoutes
	baselineOutput, err := materializeRootInbound(baselineSnapshot)
	if err != nil {
		t.Fatalf("materialize baseline snapshot: %v", err)
	}
	var baseline map[string]any
	if err := json.Unmarshal(baselineOutput, &baseline); err != nil {
		t.Fatal(err)
	}
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v78 protocol outbound snapshot rejected: %v", err)
	}
	output, err := materializeRootInbound(snapshot)
	if err != nil {
		t.Fatalf("materialize v78 snapshot: %v", err)
	}
	var got map[string]any
	if err := json.Unmarshal(output, &got); err != nil {
		t.Fatal(err)
	}
	gotRules := got["route"].(map[string]any)["rules"].([]any)
	if len(gotRules) < 3 || !jsonEqual(gotRules[len(gotRules)-3:], []any{
		map[string]any{"network": []any{"udp"}, "port": []any{float64(53)}, "action": "reject"},
		fixture.ExpectedRules[1],
		map[string]any{"protocol": []any{"dns"}, "outbound": "proxy"},
	}) {
		t.Fatalf("protocol outbound rule order/materialization differs: %#v", gotRules)
	}
	if !jsonEqual(got["dns"], baseline["dns"]) {
		t.Fatalf("protocol outbound migration changed DNS behavior: got %#v want baseline %#v", got["dns"], baseline["dns"])
	}
}

func TestRootProtocolOutboundV78RejectsDowngradeAndUnsupportedProtocol(t *testing.T) {
	fixture := readRootProtocolOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	config := appendRootProtocolOutboundFixture(t, &snapshot, fixture)
	snapshot.SchemaVersion = SnapshotVersionProtocolOutboundRoutes - 1
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("v78 marker accepted in old schema: %v", err)
	}
	snapshot.SchemaVersion = SnapshotVersionProtocolOutboundRoutes
	rules := config["route"].(map[string]any)["rules"].([]any)
	rules[len(rules)-2].(map[string]any)[rootProtocolOutboundRouteMarker].(map[string]any)["protocols"] = []any{"bogus"}
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_protocol_outbound_invalid" {
		t.Fatalf("unsupported protocol accepted: %v", err)
	}
}
