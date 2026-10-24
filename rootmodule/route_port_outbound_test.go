// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type portOutboundFixture struct {
	ProjectedRouteRule map[string]any `json:"projectedRouteRule"`
	ExpectedRouteRule  map[string]any `json:"expectedRouteRule"`
}

func readPortOutboundFixture(t *testing.T) portOutboundFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_port_outbound_v82.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture portOutboundFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestRootPortOutboundV82FullSnapshotPreservesOrderAndDNS(t *testing.T) {
	fixture := readPortOutboundFixture(t)
	appFixture := readRootAppUIDDomainOutboundFixture(t)
	snapshot, prior, expectedDNS := rootAppUIDDomainOutboundSnapshot(t, appFixture)
	snapshot.SchemaVersion = SnapshotVersionPortOutboundRoutes
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	before := map[string]any{"protocol": []any{"dns"}, "action": "hijack-dns"}
	after := map[string]any{"network": []any{"udp"}, "port": []any{53}, "action": "reject"}
	rules = append(rules, before)
	marker := cloneJSONMap(t, fixture.ProjectedRouteRule)
	marker[rootPortOutboundMarker].(map[string]any)["index"] = float64(len(rules))
	rules = append(rules, marker, after)
	route["rules"] = rules
	config["route"] = route
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v82 snapshot rejected: %v", err)
	}
	data, err := materializeRootInbound(snapshot)
	if err != nil {
		t.Fatalf("materialize complete v82 snapshot: %v", err)
	}
	var got map[string]any
	if err := json.Unmarshal(data, &got); err != nil {
		t.Fatal(err)
	}
	gotRules := got["route"].(map[string]any)["rules"].([]any)
	wantRules := append(append(append(append([]any{}, prior...), appFixture.ExpectedMaterializedRouteRule), before), fixture.ExpectedRouteRule, after)
	if !jsonEqual(gotRules, wantRules) {
		t.Fatalf("materialized route/order mismatch\n got: %#v\nwant: %#v", gotRules, wantRules)
	}
	if !jsonEqual(got["dns"], expectedDNS) {
		t.Fatalf("port-only route changed DNS effects\n got: %#v\nwant: %#v", got["dns"], expectedDNS)
	}
}

func TestRootPortOutboundV82RejectsDowngradeAndUnsupportedPorts(t *testing.T) {
	fixture := readPortOutboundFixture(t)
	appFixture := readRootAppUIDDomainOutboundFixture(t)
	snapshot, _, _ := rootAppUIDDomainOutboundSnapshot(t, appFixture)
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	marker := cloneJSONMap(t, fixture.ProjectedRouteRule)
	marker[rootPortOutboundMarker].(map[string]any)["index"] = float64(len(rules))
	rules = append(rules, marker)
	route["rules"] = rules
	config["route"] = route
	snapshot.Config, _ = json.Marshal(config)
	snapshot.SchemaVersion = SnapshotVersionPortOutboundRoutes - 1
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("downgrade accepted: %v", err)
	}
	snapshot.SchemaVersion = SnapshotVersionPortOutboundRoutes
	for _, portText := range []string{"80-81", "0", "65536", "udp:53"} {
		broken := cloneJSONMap(t, config)
		badRules := broken["route"].(map[string]any)["rules"].([]any)
		badRules[len(badRules)-1].(map[string]any)[rootPortOutboundMarker].(map[string]any)["portText"] = portText
		badJSON, _ := json.Marshal(broken)
		snapshot.Config = badJSON
		if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_port_outbound_invalid" {
			t.Fatalf("unsupported port %q accepted: %v", portText, err)
		}
	}
}
