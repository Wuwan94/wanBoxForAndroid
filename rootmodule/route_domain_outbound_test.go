// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type domainOutboundRouteFixture struct {
	ProjectedRouteRule map[string]any `json:"projectedRouteRule"`
	ExpectedRouteRule  map[string]any `json:"expectedRouteRule"`
}

func readDomainOutboundRouteFixture(t *testing.T) domainOutboundRouteFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_domain_outbound_v55.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture domainOutboundRouteFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestRootDomainOutboundRouteV55GeneratesSupportedMatchersInPlace(t *testing.T) {
	fixtureData := readDomainOutboundRouteFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionDomainOutboundRoutes
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	marker := fixtureData.ProjectedRouteRule[rootDomainOutboundRouteMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, fixtureData.ProjectedRouteRule)
	route["rules"] = rules
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v55 domain outbound snapshot rejected: %v", err)
	}
	if err := materializeRootDomainOutboundRoutes(config); err != nil {
		t.Fatal(err)
	}
	got := config["route"].(map[string]any)["rules"].([]any)
	if len(got) != len(rules) || !jsonEqual(got[len(got)-1], fixtureData.ExpectedRouteRule) {
		t.Fatalf("domain route output/order mismatch: %#v", got)
	}
	if !jsonEqual(got[:len(got)-1], rules[:len(rules)-1]) {
		t.Fatalf("preceding route rules were reordered or modified: %#v", got[:len(got)-1])
	}
}

func TestRootDomainOutboundRouteV55RejectsUnsupportedMatcherAndDowngrade(t *testing.T) {
	f := readDomainOutboundRouteFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionDomainOutboundRoutes - 1
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	marker := f.ProjectedRouteRule[rootDomainOutboundRouteMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	marker["domainText"] = "regexp:.*example.*"
	rules = append(rules, f.ProjectedRouteRule)
	route["rules"] = rules
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("v55 marker accepted by v54 schema: %v", err)
	}
	snapshot.SchemaVersion = SnapshotVersionDomainOutboundRoutes
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_domain_outbound_invalid" {
		t.Fatalf("unsupported regexp matcher accepted: %v", err)
	}
}
