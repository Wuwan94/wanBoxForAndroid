// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

type exactDomainOutboundFixture struct {
	InputConfig    map[string]any `json:"inputConfig"`
	ExpectedConfig map[string]any `json:"expectedConfig"`
	ParserCases    []struct {
		DomainText string   `json:"domainText"`
		Valid      bool     `json:"valid"`
		Domains    []string `json:"domains"`
	} `json:"parserCases"`
}

func readExactDomainOutboundFixture(t *testing.T) exactDomainOutboundFixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "root_exact_domain_outbound_v50.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f exactDomainOutboundFixture
	if err := json.Unmarshal(data, &f); err != nil {
		t.Fatal(err)
	}
	return f
}

func TestExactDomainOutboundRootGenerationMatchesSharedFixture(t *testing.T) {
	f := readExactDomainOutboundFixture(t)
	if err := validateRootExactDomainOutbound(f.InputConfig); err != nil {
		t.Fatal(err)
	}
	if err := materializeRootExactDomainOutbound(f.InputConfig); err != nil {
		t.Fatal(err)
	}
	got, _ := json.Marshal(f.InputConfig)
	want, _ := json.Marshal(f.ExpectedConfig)
	if !bytes.Equal(got, want) {
		t.Fatalf("got %s want %s", got, want)
	}
}

func TestExactDomainOutboundRejectsMalformedOrMisindexedMarkers(t *testing.T) {
	f := readExactDomainOutboundFixture(t)
	for _, mutate := range []func(map[string]any){
		func(c map[string]any) {
			r := c["route"].(map[string]any)["rules"].([]any)[1].(map[string]any)[rootExactDomainOutboundMarker].(map[string]any)
			r["index"] = float64(0)
		},
		func(c map[string]any) {
			r := c["route"].(map[string]any)["rules"].([]any)[1].(map[string]any)[rootExactDomainOutboundMarker].(map[string]any)
			r["domainText"] = "domain:example.com"
		},
	} {
		data, _ := json.Marshal(f.InputConfig)
		var c map[string]any
		_ = json.Unmarshal(data, &c)
		mutate(c)
		if err := validateRootExactDomainOutbound(c); err == nil {
			t.Fatal("invalid marker accepted")
		}
	}
	for _, c := range f.ParserCases {
		got, err := parseRootRouteExactDomains(c.DomainText)
		if c.Valid {
			if err != nil || !reflect.DeepEqual(got, c.Domains) {
				t.Errorf("parse %q: got %v err %v", c.DomainText, got, err)
			}
		} else if err == nil {
			t.Errorf("accepted unsupported domain %q", c.DomainText)
		}
	}
}
