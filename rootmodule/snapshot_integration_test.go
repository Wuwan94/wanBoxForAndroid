// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type routeMigrationIntegrationFixture struct {
	Snapshot           Snapshot       `json:"snapshot"`
	ExpectedRouteRules []any          `json:"expectedRouteRules"`
	ExpectedDNS        map[string]any `json:"expectedDns"`
}

// loadRouteMigrationIntegrationFixture is the reusable test-only starting point for
// end-to-end route migration tests. It intentionally carries the existing v32 marker,
// current v42 marker, Root schema specs and the required DNS policy together.
func loadRouteMigrationIntegrationFixture(t *testing.T) (*Runtime, routeMigrationIntegrationFixture) {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", "root_snapshot_integration_v42.json"))
	if err != nil {
		t.Fatal(err)
	}
	var sample routeMigrationIntegrationFixture
	if err := json.Unmarshal(b, &sample); err != nil {
		t.Fatal(err)
	}
	r, _ := fixture(t)
	return r, sample
}

func TestFullSchemaRouteMigrationIntegrationSnapshot(t *testing.T) {
	r, sample := loadRouteMigrationIntegrationFixture(t)
	s := sample.Snapshot
	if s.SchemaVersion != SnapshotVersionRouteDomainKeywordRejectRules {
		t.Fatalf("fixture schema=%d want %d", s.SchemaVersion, SnapshotVersionRouteDomainKeywordRejectRules)
	}
	if err := CheckSnapshot(s); err != nil {
		t.Fatalf("complete integration snapshot rejected: %v", err)
	}
	revision, err := r.Stage(context.Background(), s)
	if err != nil {
		t.Fatalf("stage complete integration snapshot: %v", err)
	}
	b, err := os.ReadFile(r.path("generations", revision, "run", "config.json"))
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(b, &got); err != nil {
		t.Fatal(err)
	}
	gotRoute := got["route"].(map[string]any)
	if !jsonEqual(gotRoute["rules"], sample.ExpectedRouteRules) {
		t.Fatalf("materialized route rules mismatch\n got: %#v\nwant: %#v", gotRoute["rules"], sample.ExpectedRouteRules)
	}
	if !jsonEqual(got["dns"], sample.ExpectedDNS) {
		t.Fatalf("materialized DNS mismatch\n got: %#v\nwant: %#v", got["dns"], sample.ExpectedDNS)
	}
	if gotRoute["final"] != "proxy" || gotRoute["default_domain_resolver"] != "dns-direct" || gotRoute["default_network_strategy"] != "hybrid" {
		t.Fatalf("Root route policy/final fields missing: %#v", gotRoute)
	}
	var saved Snapshot
	if err := readJSON(r.path("generations", revision, "snapshot.json"), &saved, MaxSnapshotBytes); err != nil {
		t.Fatal(err)
	}
	if !jsonEqual(saved.RootRouteNetworkPortRejectRules, s.RootRouteNetworkPortRejectRules) || !jsonEqual(saved.RootRouteDomainKeywordRejectRules, s.RootRouteDomainKeywordRejectRules) || !jsonEqual(saved.RootDNSPolicy, s.RootDNSPolicy) || !jsonEqual(saved.RootRouteIPVersionRule, s.RootRouteIPVersionRule) || !jsonEqual(saved.RootRouteProbeRule, s.RootRouteProbeRule) {
		t.Fatalf("persisted Root schema fields differ from input: network-port=%#v keyword=%#v dns=%#v", saved.RootRouteNetworkPortRejectRules, saved.RootRouteDomainKeywordRejectRules, saved.RootDNSPolicy)
	}
}

func TestFullSchemaRouteMigrationFixtureRequiresDNSPolicy(t *testing.T) {
	_, sample := loadRouteMigrationIntegrationFixture(t)
	sample.Snapshot.RootDNSPolicy = nil
	if err := CheckSnapshot(sample.Snapshot); err == nil || err.Error() != "root_dns_policy_invalid" {
		t.Fatalf("missing DNS policy error=%v, want root_dns_policy_invalid", err)
	}
}
