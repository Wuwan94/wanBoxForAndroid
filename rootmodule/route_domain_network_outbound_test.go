// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type domainNetworkOutboundFixture struct {
	ProjectedRouteRule map[string]any `json:"projectedRouteRule"`
	ExpectedRouteRule  map[string]any `json:"expectedRouteRule"`
}

func readDomainNetworkOutboundFixture(t *testing.T) domainNetworkOutboundFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_domain_network_outbound_v58.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture domainNetworkOutboundFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestRootDomainNetworkOutboundV58GeneratesConjunctionInPlace(t *testing.T) {
	f := readDomainNetworkOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionDomainNetworkOutboundRoutes
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	before := map[string]any{"protocol": []any{"dns"}, "action": "hijack-dns"}
	after := map[string]any{"network": []any{"udp"}, "port": []any{53}, "action": "reject"}
	rules = append(rules, before)
	marker := f.ProjectedRouteRule[rootDomainNetworkOutboundRouteMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, f.ProjectedRouteRule, after)
	route["rules"] = rules
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v58 snapshot rejected: %v", err)
	}
	if err := materializeRootDomainNetworkOutboundRoutes(config); err != nil {
		t.Fatal(err)
	}
	got := config["route"].(map[string]any)["rules"].([]any)
	if len(got) != len(rules) || !jsonEqual(got[len(got)-2], f.ExpectedRouteRule) {
		t.Fatalf("domain/network output or slot mismatch: %#v", got)
	}
	if !jsonEqual(got[:len(got)-2], rules[:len(rules)-2]) || !jsonEqual(got[len(got)-1], after) {
		t.Fatalf("neighboring DNS/route rules changed or reordered: %#v", got)
	}
}

func TestRootDomainNetworkOutboundV58RejectsLegacyOnlyMatchersAndDowngrade(t *testing.T) {
	f := readDomainNetworkOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	marker := f.ProjectedRouteRule[rootDomainNetworkOutboundRouteMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, f.ProjectedRouteRule)
	route["rules"] = rules
	snapshot.Config, _ = json.Marshal(config)
	snapshot.SchemaVersion = SnapshotVersionDomainNetworkOutboundRoutes - 1
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("downgrade accepted: %v", err)
	}
	snapshot.SchemaVersion = SnapshotVersionDomainNetworkOutboundRoutes
	for _, domains := range []string{"regexp:.*example.*", "geosite:cn"} {
		broken := map[string]any{}
		_ = json.Unmarshal(snapshot.Config, &broken)
		brokenRules := broken["route"].(map[string]any)["rules"].([]any)
		bad := brokenRules[len(brokenRules)-1].(map[string]any)[rootDomainNetworkOutboundRouteMarker].(map[string]any)
		bad["domainText"] = domains
		snapshot.Config, _ = json.Marshal(broken)
		if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_domain_network_outbound_invalid" {
			t.Fatalf("legacy-only matcher %q accepted: %v", domains, err)
		}
	}
}
