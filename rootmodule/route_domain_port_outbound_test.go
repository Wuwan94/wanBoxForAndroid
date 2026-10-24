// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type domainPortOutboundFixture struct {
	ProjectedRouteRule map[string]any `json:"projectedRouteRule"`
	ExpectedRouteRule  map[string]any `json:"expectedRouteRule"`
}

func readDomainPortOutboundFixture(t *testing.T) domainPortOutboundFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_domain_port_outbound_v59.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture domainPortOutboundFixture
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	return fixture
}

func TestRootDomainPortOutboundV59GeneratesConjunctionInPlace(t *testing.T) {
	f := readDomainPortOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	snapshot.SchemaVersion = SnapshotVersionDomainPortOutboundRoutes
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	before := map[string]any{"protocol": []any{"dns"}, "action": "hijack-dns"}
	after := map[string]any{"network": []any{"udp"}, "port": []any{53}, "action": "reject"}
	rules = append(rules, before)
	marker := f.ProjectedRouteRule[rootDomainPortOutboundRouteMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, f.ProjectedRouteRule, after)
	route["rules"] = rules
	snapshot.Config, _ = json.Marshal(config)
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("v59 snapshot rejected: %v", err)
	}
	if err := materializeRootDomainPortOutboundRoutes(config); err != nil {
		t.Fatal(err)
	}
	got := config["route"].(map[string]any)["rules"].([]any)
	if len(got) != len(rules) || !jsonEqual(got[len(got)-2], f.ExpectedRouteRule) {
		t.Fatalf("domain/port output or ordering mismatch: %#v", got)
	}
	if !jsonEqual(got[:len(got)-2], rules[:len(rules)-2]) || !jsonEqual(got[len(got)-1], after) {
		t.Fatalf("neighboring DNS/route rules changed or reordered: %#v", got)
	}
}

func TestRootDomainPortOutboundV59RejectsUnsupportedMatchersAndDowngrade(t *testing.T) {
	f := readDomainPortOutboundFixture(t)
	_, integration := loadRouteMigrationIntegrationFixture(t)
	snapshot := integration.Snapshot
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	rules := append([]any{}, route["rules"].([]any)...)
	marker := f.ProjectedRouteRule[rootDomainPortOutboundRouteMarker].(map[string]any)
	marker["index"] = float64(len(rules))
	rules = append(rules, f.ProjectedRouteRule)
	route["rules"] = rules
	snapshot.Config, _ = json.Marshal(config)
	snapshot.SchemaVersion = SnapshotVersionDomainPortOutboundRoutes - 1
	if err := CheckSnapshot(snapshot); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("downgrade accepted: %v", err)
	}
	snapshot.SchemaVersion = SnapshotVersionDomainPortOutboundRoutes
	for _, unsupported := range []struct{ key, value string }{
		{"domainText", "regexp:.*example.*"},
		{"domainText", "geosite:cn"},
		{"portText", "80:90"},
	} {
		broken := cloneJSONMap(t, config)
		brokenRules := broken["route"].(map[string]any)["rules"].([]any)
		bad := brokenRules[len(brokenRules)-1].(map[string]any)[rootDomainPortOutboundRouteMarker].(map[string]any)
		bad[unsupported.key] = unsupported.value
		badConfig, _ := json.Marshal(broken)
		snapshot.Config = badConfig
		if err := CheckSnapshot(snapshot); err == nil || err.Error() != "root_domain_port_outbound_invalid" {
			t.Fatalf("unsupported %s %q accepted: %v", unsupported.key, unsupported.value, err)
		}
	}
}
