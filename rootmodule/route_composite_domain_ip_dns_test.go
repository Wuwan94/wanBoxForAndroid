package module

import (
	"bytes"
	"encoding/json"
	"os"
	"testing"
)

func TestCompositeDomainIPAndDNSEffectsMaterializeTogetherInOrder(t *testing.T) {
	data, err := os.ReadFile("testdata/root_composite_domain_ip_dns.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		InputConfig    map[string]any `json:"inputConfig"`
		ExpectedConfig map[string]any `json:"expectedConfig"`
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	snapshot := Snapshot{RootInbound: &RootInboundSpec{Backend: "tun"}}
	if err := validateRootCompositeDomainIPOutbound(fixture.InputConfig); err != nil {
		t.Fatal(err)
	}
	if err := validateRootExactDomainDNSRules(fixture.InputConfig, snapshot); err != nil {
		t.Fatal(err)
	}
	if err := materializeRootCompositeDomainIPOutbound(fixture.InputConfig); err != nil {
		t.Fatal(err)
	}
	if err := materializeRootExactDomainDNSRules(fixture.InputConfig, snapshot); err != nil {
		t.Fatal(err)
	}
	got, _ := json.Marshal(fixture.InputConfig)
	want, _ := json.Marshal(fixture.ExpectedConfig)
	if !bytes.Equal(got, want) {
		t.Fatalf("combined Root route/DNS materialization or ordering mismatch\ngot: %s\nwant:%s", got, want)
	}
}
