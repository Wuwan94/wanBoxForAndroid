// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type domainIPVersionOutboundFixture struct {
	ProjectedRouteRule map[string]any `json:"projectedRouteRule"`
	ExpectedRouteRule  map[string]any `json:"expectedRouteRule"`
}

func readDomainIPVersionOutboundFixture(t *testing.T) domainIPVersionOutboundFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_domain_ip_version_outbound_v67.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture domainIPVersionOutboundFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestRootDomainIPVersionOutboundV67GeneratesConjunctionInPlace(t *testing.T) {
	fixture := readDomainIPVersionOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionDomainIPVersionOutboundRoutes
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
	marker := fixture.ProjectedRouteRule[rootDomainIPVersionOutboundRouteMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, fixture.ProjectedRouteRule, after)
	route["rules"] = rules
	config["route"] = route
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v67 snapshot rejected: %v", err)
	}
	if err := materializeRootDomainIPVersionOutboundRoutes(config); err != nil {
		t.Fatal(err)
	}
	got := config["route"].(map[string]any)["rules"].([]any)
	if len(got) != len(rules) || !jsonEqual(got[len(got)-2], fixture.ExpectedRouteRule) {
		t.Fatalf("domain AND ip_version output or order mismatch: %#v", got)
	}
	if !jsonEqual(got[:len(got)-2], rules[:len(rules)-2]) || !jsonEqual(got[len(got)-1], after) {
		t.Fatalf("neighboring rules changed or reordered: %#v", got)
	}
	if !jsonEqual(config["dns"], dnsBefore) {
		t.Fatalf("route-only ip_version predicate changed DNS config: %#v", config["dns"])
	}
}

func TestRootDomainIPVersionOutboundV67RejectsDowngradeAndUnsupportedMatchers(t *testing.T) {
	fixture := readDomainIPVersionOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	marker := fixture.ProjectedRouteRule[rootDomainIPVersionOutboundRouteMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, fixture.ProjectedRouteRule)
	route["rules"] = rules
	config["route"] = route
	snapshot.Config, _ = json.Marshal(config)
	snapshot.SchemaVersion = SnapshotVersionDomainIPVersionOutboundRoutes - 1
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("downgrade accepted: %v", err)
	}
	snapshot.SchemaVersion = SnapshotVersionDomainIPVersionOutboundRoutes
	for _, domainText := range []string{"regexp:.*example.*", "geosite:cn"} {
		broken := cloneJSONMap(t, config)
		badRules := broken["route"].(map[string]any)["rules"].([]any)
		badRules[len(badRules)-1].(map[string]any)[rootDomainIPVersionOutboundRouteMarker].(map[string]any)["domainText"] = domainText
		snapshot.Config, _ = json.Marshal(broken)
		if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_domain_ip_version_outbound_invalid" {
			t.Fatalf("unsupported domain matcher %q accepted: %v", domainText, err)
		}
	}
	broken := cloneJSONMap(t, config)
	badRules := broken["route"].(map[string]any)["rules"].([]any)
	badRules[len(badRules)-1].(map[string]any)[rootDomainIPVersionOutboundRouteMarker].(map[string]any)["ipVersion"] = float64(5)
	snapshot.Config, _ = json.Marshal(broken)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_domain_ip_version_outbound_invalid" {
		t.Fatalf("invalid ip_version accepted: %v", err)
	}
}
