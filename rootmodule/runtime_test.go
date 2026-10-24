// @author 雾晚
package module

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

func fixture(t *testing.T) (*Runtime, Snapshot) {
	t.Helper()
	dir := t.TempDir()
	mod := filepath.Join(dir, "module")
	if e := os.Mkdir(mod, 0700); e != nil {
		t.Fatal(e)
	}
	if e := os.WriteFile(filepath.Join(mod, "module.prop"), []byte("id=wanbox"), 0600); e != nil {
		t.Fatal(e)
	}
	r := New(filepath.Join(dir, "data"), mod)
	r.Validate = func(context.Context, string, string) error { return nil }
	return r, Snapshot{SchemaVersion: Version, Config: json.RawMessage(`{"inbounds":[{"type":"tun","interface_name":"tun0","auto_route":true}],"outbounds":[{"type":"direct","tag":"direct"}],"experimental":{"cache_file":{"enabled":true,"path":"cache.db"}}}`), Files: map[string][]byte{"assets/example.srs": {1, 2}}, ProfileID: 7, ProfileName: "fixture", AutoStart: true}
}

func jsonEqual(a, b any) bool {
	left, err := json.Marshal(a)
	if err != nil {
		return false
	}
	right, err := json.Marshal(b)
	return err == nil && bytes.Equal(left, right)
}

func TestStructuredRootInboundIsMaterializedByModule(t *testing.T) {
	r, s := fixture(t)
	s.SchemaVersion = SnapshotVersion
	s.Config = json.RawMessage(`{"inbounds":[{"type":"mixed","tag":"mixed-in","listen_port":2080}],"outbounds":[{"type":"direct","tag":"direct"}]}`)
	tests := []struct {
		name     string
		spec     RootInboundSpec
		wantType string
	}{
		{"ebpf", RootInboundSpec{Backend: "ebpf", IPv6Mode: 1, BypassPrivateAddress: true, IncludeUIDs: []int{10001}}, "ebpf"},
		{"tun", RootInboundSpec{Backend: "tun", IPv6Mode: 3, MTU: 9000, StrictRoute: true, ExcludeUIDs: []int{0}}, "tun"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			s.RootInbound = &tc.spec
			rev, err := r.Stage(context.Background(), s)
			if err != nil {
				t.Fatal(err)
			}
			b, err := os.ReadFile(r.path("generations", rev, "run", "config.json"))
			if err != nil {
				t.Fatal(err)
			}
			var config map[string]any
			if err = json.Unmarshal(b, &config); err != nil {
				t.Fatal(err)
			}
			inbounds := config["inbounds"].([]any)
			if len(inbounds) != 2 {
				t.Fatalf("want mixed and module inbound, got %d", len(inbounds))
			}
			rootInbound := inbounds[1].(map[string]any)
			if rootInbound["tag"] != "tun-in" || rootInbound["type"] != tc.wantType {
				t.Fatalf("unexpected inbound: %#v", rootInbound)
			}
			if tc.wantType == "ebpf" {
				local := rootInbound["local"].(map[string]any)
				if local["data_plane"] != "cgroup" || local["bypass_private_address"] != true {
					t.Fatalf("bad ebpf settings: %#v", local)
				}
			} else if rootInbound["auto_redirect"] != true || rootInbound["address"].([]any)[0] != "fdfe:dcba:9876::1/126" {
				t.Fatalf("bad tun settings: %#v", rootInbound)
			}
			var saved Snapshot
			if err = readJSON(r.path("generations", rev, "snapshot.json"), &saved, MaxSnapshotBytes); err != nil || saved.RootInbound == nil {
				t.Fatalf("declarative snapshot not retained: %v", err)
			}
		})
	}
}
func TestStructuredRootInboundRejectsInvalidSpecAndDuplicateTag(t *testing.T) {
	_, s := fixture(t)
	s.SchemaVersion = SnapshotVersion
	s.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 9000}
	s.Config = json.RawMessage(`{"inbounds":[],"outbounds":[]}`)
	if err := CheckSnapshot(s); err != nil {
		t.Fatalf("valid structured spec rejected: %v", err)
	}
	s.Config = json.RawMessage(`{"inbounds":[{"type":"tun","tag":"tun-in"}],"outbounds":[]}`)
	if err := CheckSnapshot(s); err == nil {
		t.Fatal("accepted duplicate module inbound tag")
	}
	s.Config = json.RawMessage(`{"inbounds":[],"outbounds":[]}`)
	s.RootInbound.Backend = "unknown"
	if err := CheckSnapshot(s); err == nil || err.Error() != "root_inbound_invalid" {
		t.Fatalf("unexpected invalid spec result: %v", err)
	}
}

func TestStructuredRootProxyGroupIsMaterializedByModule(t *testing.T) {
	for _, tc := range []struct {
		name string
		spec RootProxyGroupSpec
		want map[string]any
	}{
		{"selector", RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"node-a", "node-b"}, Default: "node-a"}, map[string]any{"type": "selector", "default": "node-a"}},
		{"urltest", RootProxyGroupSpec{Type: "urltest", Tag: "proxy", Members: []string{"node-a", "node-b"}, URL: "https://example.test/ping", Interval: "10m", Tolerance: 100, IdleTimeout: "30m"}, map[string]any{"type": "urltest", "url": "https://example.test/ping", "interval": "10m", "tolerance": float64(100), "idle_timeout": "30m", "interrupt_exist_connections": false}},
		{"loadbalance", RootProxyGroupSpec{Type: "loadbalance", Tag: "proxy", Members: []string{"node-a", "node-b"}, Strategy: "round_robin", URL: "https://example.test/ping", Interval: "5m", Tolerance: 300, IdleTimeout: "5m", InterruptExisting: true}, map[string]any{"type": "loadbalance", "strategy": "round_robin", "url": "https://example.test/ping", "interval": "5m", "tolerance": float64(300), "idle_timeout": "5m", "interrupt_exist_connections": true}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r, s := fixture(t)
			s.SchemaVersion = SnapshotVersionProxyGroup
			s.RootInbound = &RootInboundSpec{Backend: "tun", IPv6Mode: 1, MTU: 1500}
			s.RootProxyGroup = &tc.spec
			s.Config = json.RawMessage(`{"inbounds":[],"outbounds":[{"type":"shadowsocks","tag":"node-a"},{"type":"direct","tag":"node-b"},{"type":"direct","tag":"direct"}]}`)
			rev, err := r.Stage(context.Background(), s)
			if err != nil {
				t.Fatal(err)
			}
			b, err := os.ReadFile(r.path("generations", rev, "run", "config.json"))
			if err != nil {
				t.Fatal(err)
			}
			var config map[string]any
			if err = json.Unmarshal(b, &config); err != nil {
				t.Fatal(err)
			}
			outbounds := config["outbounds"].([]any)
			var group map[string]any
			for _, raw := range outbounds {
				item := raw.(map[string]any)
				if item["tag"] == "proxy" {
					group = item
				}
			}
			if group == nil {
				t.Fatal("module did not create proxy group")
			}
			for key, value := range tc.want {
				if group[key] != value {
					t.Fatalf("group[%s]=%#v, want %#v", key, group[key], value)
				}
			}
			members := group["outbounds"].([]any)
			if len(members) != 2 || members[0] != "node-a" || members[1] != "node-b" {
				t.Fatalf("unexpected group members: %#v", members)
			}
			var saved Snapshot
			if err = readJSON(r.path("generations", rev, "snapshot.json"), &saved, MaxSnapshotBytes); err != nil || saved.RootProxyGroup == nil {
				t.Fatalf("declarative proxy group not retained: %v", err)
			}
		})
	}
}

func TestStructuredRootOutboundsAreMaterializedByModule(t *testing.T) {
	r, s := fixture(t)
	s.SchemaVersion = SnapshotVersionOutbounds
	s.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500}
	s.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"ss-node", "socks-node"}, Default: "ss-node"}
	s.RootOutbounds = []RootOutboundSpec{
		{Protocol: "shadowsocks", Tag: "ss-node", Server: "ss.example", ServerPort: 443, Method: "aes-256-gcm", Password: "secret", Plugin: "obfs-local", PluginOptions: "obfs=tls", DomainStrategy: "ipv4_only", UDPOverTCP: true},
		{Protocol: "socks", Tag: "socks-node", Server: "127.0.0.1", ServerPort: 1080, Version: "5", Username: "user", Password: "pass"},
	}
	s.Config = json.RawMessage(`{"inbounds":[],"outbounds":[{"type":"direct","tag":"direct"}]}`)
	rev, err := r.Stage(context.Background(), s)
	if err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(r.path("generations", rev, "run", "config.json"))
	if err != nil {
		t.Fatal(err)
	}
	var config map[string]any
	if err = json.Unmarshal(b, &config); err != nil {
		t.Fatal(err)
	}
	outbounds := config["outbounds"].([]any)
	byTag := map[string]map[string]any{}
	for _, raw := range outbounds {
		item := raw.(map[string]any)
		byTag[item["tag"].(string)] = item
	}
	ss := byTag["ss-node"]
	if ss == nil || ss["type"] != "shadowsocks" || ss["method"] != "aes-256-gcm" || ss["plugin"] != "obfs-local" || ss["plugin_opts"] != "obfs=tls" || ss["domain_strategy"] != "ipv4_only" || ss["udp_over_tcp"] != true {
		t.Fatalf("unexpected module Shadowsocks outbound: %#v", ss)
	}
	socks := byTag["socks-node"]
	if socks == nil || socks["type"] != "socks" || socks["version"] != "5" || socks["username"] != "user" || socks["password"] != "pass" {
		t.Fatalf("unexpected module SOCKS outbound: %#v", socks)
	}
	if byTag["proxy"] == nil {
		t.Fatal("missing generated proxy group")
	}
}

func TestStructuredRootOutboundsRejectInvalidAndDuplicateSpecs(t *testing.T) {
	_, s := fixture(t)
	s.SchemaVersion = SnapshotVersionOutbounds
	s.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500}
	s.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"node"}, Default: "node"}
	s.Config = json.RawMessage(`{"inbounds":[],"outbounds":[]}`)
	s.RootOutbounds = []RootOutboundSpec{{Protocol: "shadowsocks", Tag: "node", Server: "host", ServerPort: 443, Method: "aes-256-gcm", Password: "p"}}
	if err := CheckSnapshot(s); err != nil {
		t.Fatalf("valid outbound rejected: %v", err)
	}
	s.RootOutbounds[0].Protocol = "custom"
	if err := CheckSnapshot(s); err == nil || err.Error() != "root_outbound_invalid" {
		t.Fatalf("bad protocol result: %v", err)
	}
	s.RootOutbounds[0].Protocol = "shadowsocks"
	s.Config = json.RawMessage(`{"inbounds":[],"outbounds":[{"type":"direct","tag":"node"}]}`)
	if err := CheckSnapshot(s); err == nil || err.Error() != "root_outbound_duplicate" {
		t.Fatalf("duplicate tag result: %v", err)
	}
}

func TestStructuredRootProxyGroupRejectsInvalidSpecs(t *testing.T) {
	_, s := fixture(t)
	s.SchemaVersion = SnapshotVersionProxyGroup
	s.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500}
	s.Config = json.RawMessage(`{"inbounds":[],"outbounds":[]}`)
	valid := RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"node-a"}, Default: "node-a"}
	cases := []struct {
		name   string
		mutate func(*RootProxyGroupSpec)
	}{
		{"unknown_type", func(p *RootProxyGroupSpec) { p.Type = "unknown" }},
		{"empty_members", func(p *RootProxyGroupSpec) { p.Members = nil }},
		{"default_not_member", func(p *RootProxyGroupSpec) { p.Default = "missing" }},
		{"duplicate_member", func(p *RootProxyGroupSpec) { p.Members = []string{"node-a", "node-a"} }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			spec := valid
			spec.Members = append([]string(nil), valid.Members...)
			tc.mutate(&spec)
			s.RootProxyGroup = &spec
			if err := CheckSnapshot(s); err == nil || err.Error() != "root_proxy_group_invalid" {
				t.Fatalf("unexpected validation result: %v", err)
			}
		})
	}
	s.RootProxyGroup = &valid
	s.Config = json.RawMessage(`{"inbounds":[],"outbounds":[{"type":"direct","tag":"proxy"}]}`)
	if err := CheckSnapshot(s); err == nil || err.Error() != "root_proxy_group_duplicate" {
		t.Fatalf("unexpected duplicate-tag result: %v", err)
	}
}

func TestActualSnapshotRoundTripAndConflict(t *testing.T) {
	r, s := fixture(t)
	encoded, _ := json.Marshal(s)
	decoded, e := DecodeSnapshot(bytes.NewReader(encoded))
	if e != nil {
		t.Fatal(e)
	}
	out, e := r.Apply(context.Background(), decoded)
	if e != nil {
		t.Fatal(e)
	}
	if out.Revision == "" {
		t.Fatal("missing revision")
	}
	rev, got, e := r.Current()
	if e != nil || got.ProfileID != s.ProfileID || !bytes.Equal(got.Config, s.Config) {
		t.Fatal("round trip", e)
	}
	if _, e = r.Apply(context.Background(), s); e == nil || e.Error() != "revision_conflict" {
		t.Fatal("lost conflict", e)
	}
	s.ExpectedRevision = rev
	s.ProfileName = "changed"
	if _, e = r.Apply(context.Background(), s); e != nil {
		t.Fatal(e)
	}
	old, _, e := r.Current()
	if e != nil || old == rev {
		t.Fatal("no new generation")
	}
	if _, e = os.Stat(r.path("generations", rev, "snapshot.json")); e != nil {
		t.Fatal("rollback snapshot lost")
	}
}
func TestValidationFailureLeavesCommittedSnapshotUntouched(t *testing.T) {
	r, s := fixture(t)
	out, e := r.Apply(context.Background(), s)
	if e != nil {
		t.Fatal(e)
	}
	r.Validate = func(context.Context, string, string) error { return errors.New("core_config_invalid") }
	s.ExpectedRevision = out.Revision
	s.Config = json.RawMessage(`{"broken":true}`)
	if _, e = r.Apply(context.Background(), s); e == nil {
		t.Fatal("accepted invalid core config")
	}
	rev, _, e := r.Current()
	if e != nil || rev != out.Revision {
		t.Fatal("overwrote last valid config")
	}
	dirs, _ := os.ReadDir(r.path("generations"))
	for _, d := range dirs {
		if strings.HasPrefix(d.Name(), ".stage-") {
			t.Fatal("stage leaked")
		}
	}
}
func TestConcurrentApplyOneWriterWins(t *testing.T) {
	r, s := fixture(t)
	var wg sync.WaitGroup
	var mu sync.Mutex
	success, conflict := 0, 0
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, e := r.Apply(context.Background(), s)
			mu.Lock()
			defer mu.Unlock()
			if e == nil {
				success++
			} else if e.Error() == "revision_conflict" {
				conflict++
			} else {
				t.Error(e)
			}
		}()
	}
	wg.Wait()
	if success != 1 || conflict != 7 {
		t.Fatal(success, conflict)
	}
}
func TestRejectTraversalAndUnsupportedInputs(t *testing.T) {
	_, base := fixture(t)
	for _, name := range []string{"../evil", "assets/../../evil", "/data/evil", "assets\\evil", "assets//evil", "assets/./evil", "runtime/ready"} {
		s := base
		s.Files = map[string][]byte{name: {1}}
		if CheckSnapshot(s) == nil {
			t.Errorf("accepted %q", name)
		}
	}
	for _, input := range []string{"{}", `{"schemaVersion":2}`, `{} {}`, `{"schemaVersion":1,"unknown":true}`} {
		if _, e := DecodeSnapshot(strings.NewReader(input)); e == nil {
			t.Error("accepted", input)
		}
	}
}
func TestFileLimitAndMissingOrAppResource(t *testing.T) {
	_, s := fixture(t)
	s.Files = map[string][]byte{}
	for i := 0; i <= MaxFiles; i++ {
		s.Files["assets/"+strings.Repeat("a", i+1)] = []byte{1}
	}
	if CheckSnapshot(s) == nil {
		t.Fatal("file limit ignored")
	}
	_, s = fixture(t)
	for _, p := range []string{"/data/user/0/private/key", "../files/missing", "../../secret"} {
		s.Config = json.RawMessage(`{"route":{"rule_set":[{"type":"local","path":"` + p + `"}]}}`)
		if CheckSnapshot(s) == nil {
			t.Error("accepted", p)
		}
	}
	s.Config = json.RawMessage(`{"dns":{"servers":[{"type":"https","path":"/dns-query"}]},"outbounds":[{"type":"vless","transport":{"type":"ws","path":"/socket"}}]}`)
	if e := CheckSnapshot(s); e != nil {
		t.Fatal("confused HTTP URL path with filesystem path", e)
	}
}
func TestPersistedAutoStartOptOutAndNoConfigBoot(t *testing.T) {
	r, s := fixture(t)
	if e := r.Boot(context.Background(), func() bool { return true }); e != nil {
		t.Fatal(e)
	}
	if _, e := r.Apply(context.Background(), s); e != nil {
		t.Fatal(e)
	}
	if e := r.SetAutoStart(false); e != nil {
		t.Fatal(e)
	}
	r.Validate = func(context.Context, string, string) error { t.Fatal("opted out but tried boot startup"); return nil }
	if e := r.Boot(context.Background(), func() bool { return true }); e != nil {
		t.Fatal(e)
	}
	if e := os.WriteFile(filepath.Join(r.ModuleDir, "disable"), nil, 0600); e != nil {
		t.Fatal(e)
	}
	if e := r.Start(context.Background()); e == nil {
		t.Fatal("started disabled module")
	}
}
func TestCancelledBootAndStaleConnectedState(t *testing.T) {
	r, _ := fixture(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if r.Boot(ctx, func() bool { return false }) == nil {
		t.Fatal("ignored cancel")
	}
	if e := r.init(); e != nil {
		t.Fatal(e)
	}
	_ = jsonWrite(r.path("state.json"), State{SchemaVersion: Version, Phase: "connected", Stats: json.RawMessage(`{"tx":999}`), Supervisor: ProcessIdentity{PID: 2147483647, Start: "invalid"}})
	state := r.Status()
	if state.Phase == "connected" || state.Stats != nil {
		t.Fatal("stale success")
	}
}
func TestPrivatePermissionsAndUpgradePersistence(t *testing.T) {
	r, s := fixture(t)
	if _, e := r.Apply(context.Background(), s); e != nil {
		t.Fatal(e)
	}
	before, _, _ := r.Current()
	upgraded := New(r.Root, r.ModuleDir)
	after, _, e := upgraded.Current()
	if e != nil || before != after {
		t.Fatal("update discarded data", e)
	}
	if e := r.Stop(context.Background()); e != nil {
		t.Fatal(e)
	}
	after, _, _ = r.Current()
	if before != after {
		t.Fatal("stop discarded user config")
	}
	info, e := os.Stat(r.Root)
	if e != nil {
		t.Fatal(e)
	}
	if info.Mode().Perm()&0077 != 0 && os.PathSeparator == '/' {
		t.Fatal("data not root private")
	}
}
func TestErrorResponseNeverExposesCoreCredentials(t *testing.T) {
	var out bytes.Buffer
	Response(&out, State{}, errors.New("token=fake-secret.example"))
	if strings.Contains(out.String(), "fake-secret") {
		t.Fatal("leaked secret")
	}
	if !strings.Contains(out.String(), "module_operation_failed") {
		t.Fatal("missing safe error")
	}
}

// @author 雾晚
func TestCancellationBeforeCommitAndRollbackCorruptPointer(t *testing.T) {
	r, s := fixture(t)
	first, e := r.Apply(context.Background(), s)
	if e != nil {
		t.Fatal(e)
	}
	s.ExpectedRevision = first.Revision
	s.ProfileName = "second"
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, e = r.Apply(ctx, s); e == nil {
		t.Fatal("cancelled update committed")
	}
	rev, _ := r.CurrentRevision()
	if rev != first.Revision {
		t.Fatal("cancel discarded original")
	}
	if _, e = r.Apply(context.Background(), s); e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(r.path("current"), []byte("corrupt"), 0600); e != nil {
		t.Fatal(e)
	}
	if e = r.Rollback(context.Background()); e != nil {
		t.Fatal(e)
	}
	rev, _, e = r.Current()
	if e != nil || rev != first.Revision {
		t.Fatal("rollback did not recover", e)
	}
}
func TestFailureStateAndBoundedRedactedEvents(t *testing.T) {
	r, _ := fixture(t)
	if e := r.init(); e != nil {
		t.Fatal(e)
	}
	_ = jsonWrite(r.path("state.json"), State{SchemaVersion: Version, Phase: "failed", Error: "cgroup_move_failed", Stats: json.RawMessage(`{"tx":999}`)})
	state := r.Status()
	if state.Phase != "failed" || state.Error != "cgroup_move_failed" || state.Stats != nil {
		t.Fatal("lost actionable failure", state)
	}
	for i := 0; i < 45; i++ {
		r.recordEvent("config_applied")
	}
	r.recordEvent("password=fake-secret")
	events := r.Events()
	if len(events) != 40 {
		t.Fatal("unbounded events", len(events))
	}
	for _, event := range events {
		if event.Code != "config_applied" {
			t.Fatal("unsafe event")
		}
	}
}
func TestNonObjectConfigIsRejected(t *testing.T) {
	_, s := fixture(t)
	for _, config := range []string{"null", "[]", "true", "1"} {
		s.Config = json.RawMessage(config)
		if CheckSnapshot(s) == nil {
			t.Fatal("accepted", config)
		}
	}
}

func TestGeoAliasesRequirePortableBackingAssets(t *testing.T) {
	_, s := fixture(t)
	for _, alias := range []string{"geoip:cn", "geosite-cn", "geosite:cn@ads", "geoip-cn.srs"} {
		s.Config = json.RawMessage(`{"route":{"rule_set":[{"type":"local","path":"` + alias + `"}]}}`)
		if CheckSnapshot(s) == nil {
			t.Fatal("missing geo asset accepted")
		}
		s.Files = map[string][]byte{"assets/geoip.db": {1}, "assets/geosite.db": {1}}
		if e := CheckSnapshot(s); e != nil {
			t.Fatal("legacy geo alias rejected", alias, e)
		}
		s.Files = map[string][]byte{}
	}
	s.Config = json.RawMessage(`{"route":{"rule_set":[{"type":"local","path":"geoip:../../evil"}]}}`)
	if CheckSnapshot(s) == nil {
		t.Fatal("traversal geo alias accepted")
	}
}

func TestCacheLogAndDashboardResourcesRemainInsideSnapshot(t *testing.T) {
	_, s := fixture(t)
	for _, cfg := range []string{`{"experimental":{"cache_file":{"enabled":true,"path":"../../other.db"}}}`, `{"log":{"output":"/data/adb/other/file"}}`, `{"experimental":{"clash_api":{"external_ui":"../files/yacd"}}}`} {
		s.Config = json.RawMessage(cfg)
		if CheckSnapshot(s) == nil {
			t.Fatal("unsafe or missing resource accepted")
		}
	}
	s.Config = json.RawMessage(`{"experimental":{"clash_api":{"external_ui":"../files/yacd"}}}`)
	s.Files["files/yacd/index.html"] = []byte("<!doctype html>")
	if e := CheckSnapshot(s); e != nil {
		t.Fatal(e)
	}
}

func TestPluginBinaryAbiAndResourceContract(t *testing.T) {
	_, s := fixture(t)
	bin := make([]byte, 64)
	copy(bin, []byte{127, 'E', 'L', 'F', 2, 1})
	binary.LittleEndian.PutUint16(bin[18:20], 183)
	s.Files["plugins/mieru/core"] = bin
	s.Files["files/plugin.json"] = []byte(`{}`)
	s.Plugins = []Plugin{{Kind: "mieru", Binary: "plugins/mieru/core", Config: "files/plugin.json"}}
	if err := CheckSnapshot(s); err != nil {
		t.Fatal(err)
	}
	binary.LittleEndian.PutUint16(bin[18:20], 62)
	if CheckSnapshot(s) == nil {
		t.Fatal("accepted x86 helper in ARM64 module")
	}
	binary.LittleEndian.PutUint16(bin[18:20], 183)
	delete(s.Files, "files/plugin.json")
	if CheckSnapshot(s) == nil {
		t.Fatal("accepted missing helper config")
	}
}

func TestStructuredTLSOutboundsMaterializeHTTPAndTrojan(t *testing.T) {
	_, s := fixture(t)
	s.SchemaVersion = SnapshotVersionTLSOutbounds
	s.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500}
	s.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"web", "secure"}, Default: "secure"}
	s.Config = json.RawMessage(`{"inbounds":[],"outbounds":[{"type":"direct","tag":"direct"}]}`)
	s.RootOutbounds = []RootOutboundSpec{
		{Protocol: "http", Tag: "web", Server: "proxy.example", ServerPort: 8080, Username: "user", Password: "pass"},
		{Protocol: "trojan", Tag: "secure", Server: "secure.example", ServerPort: 443, Password: "secret", TLS: true, TLSInsecure: true, TLSALPN: []string{"h2", "http/1.1"}},
	}
	encoded, err := materializeRootInbound(s)
	if err != nil {
		t.Fatal(err)
	}
	var config map[string]any
	if err := json.Unmarshal(encoded, &config); err != nil {
		t.Fatal(err)
	}
	items := config["outbounds"].([]any)
	byTag := map[string]map[string]any{}
	for _, raw := range items {
		item := raw.(map[string]any)
		if tag, ok := item["tag"].(string); ok {
			byTag[tag] = item
		}
	}
	if byTag["web"]["type"] != "http" || byTag["web"]["username"] != "user" || byTag["web"]["password"] != "pass" {
		t.Fatalf("unexpected HTTP outbound: %#v", byTag["web"])
	}
	tls := byTag["secure"]["tls"].(map[string]any)
	if byTag["secure"]["type"] != "trojan" || byTag["secure"]["password"] != "secret" || tls["enabled"] != true || tls["server_name"] != "secure.example" || tls["insecure"] != true {
		t.Fatalf("unexpected Trojan outbound: %#v", byTag["secure"])
	}
	if got := tls["alpn"].([]any); len(got) != 2 || got[0] != "h2" || got[1] != "http/1.1" {
		t.Fatalf("unexpected ALPN: %#v", tls["alpn"])
	}
	if byTag["proxy"]["type"] != "selector" {
		t.Fatalf("proxy group missing: %#v", byTag["proxy"])
	}
}

func TestStructuredTLSOutboundsRejectUnsupportedTLSAndLegacySchema(t *testing.T) {
	_, s := fixture(t)
	s.SchemaVersion = SnapshotVersionTLSOutbounds
	s.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500}
	s.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"t"}, Default: "t"}
	s.Config = json.RawMessage(`{"inbounds":[],"outbounds":[]}`)
	s.RootOutbounds = []RootOutboundSpec{{Protocol: "trojan", Tag: "t", Server: "host", ServerPort: 443, Password: "p", TLS: true}}
	if err := CheckSnapshot(s); err != nil {
		t.Fatalf("valid Trojan rejected: %v", err)
	}
	s.SchemaVersion = SnapshotVersionOutbounds
	if err := CheckSnapshot(s); err == nil || err.Error() != "root_outbound_invalid" {
		t.Fatalf("legacy schema accepted TLS outbound: %v", err)
	}
	s.SchemaVersion = SnapshotVersionTLSOutbounds
	s.RootOutbounds[0].Protocol = "vless"
	if err := CheckSnapshot(s); err == nil || err.Error() != "root_outbound_invalid" {
		t.Fatalf("unsupported protocol accepted: %v", err)
	}
}

func TestStructuredRootRoutePolicyIsMaterializedByModule(t *testing.T) {
	r, s := fixture(t)
	s.SchemaVersion = SnapshotVersionRoutePolicy
	s.RootInbound = &RootInboundSpec{Backend: "tun", IPv6Mode: 1, MTU: 1500}
	s.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"node-a"}, Default: "node-a"}
	s.RootRoutePolicy = &RootRoutePolicy{
		AutoDetectInterface:    true,
		OverrideAndroidVPN:     false,
		FindProcess:            true,
		DefaultDomainResolver:  "dns-direct",
		DefaultNetworkStrategy: "hybrid",
	}
	s.Config = json.RawMessage(`{"inbounds":[],"outbounds":[{"type":"direct","tag":"direct"},{"type":"dns","tag":"dns-direct"}],"route":{"rules":[{"action":"sniff"}],"rule_set":[]}}`)
	rev, err := r.Stage(context.Background(), s)
	if err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(r.path("generations", rev, "run", "config.json"))
	if err != nil {
		t.Fatal(err)
	}
	var config map[string]any
	if err = json.Unmarshal(b, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	for key, want := range map[string]any{
		"auto_detect_interface":    true,
		"override_android_vpn":     false,
		"find_process":             true,
		"default_domain_resolver":  "dns-direct",
		"default_network_strategy": "hybrid",
	} {
		if route[key] != want {
			t.Fatalf("route[%s]=%#v want %#v", key, route[key], want)
		}
	}
	if len(route["rules"].([]any)) != 1 {
		t.Fatal("module route policy dropped unrelated legacy rules")
	}
	var saved Snapshot
	if err = readJSON(r.path("generations", rev, "snapshot.json"), &saved, MaxSnapshotBytes); err != nil || saved.RootRoutePolicy == nil {
		t.Fatalf("structured route policy was not retained: %v", err)
	}
}

func TestStructuredDNSPolicyAndLocalServerMatchSharedProjectionFixture(t *testing.T) {
	fixtureBytes, err := os.ReadFile(filepath.Join("testdata", "root_dns_policy_projection.json"))
	if err != nil {
		t.Fatal(err)
	}
	var sample struct {
		Config       map[string]any          `json:"config"`
		Policy       RootDNSPolicySpec       `json:"rootDnsPolicy"`
		LocalServer  RootDNSLocalServerSpec  `json:"rootDnsLocalServer"`
		DirectServer RootDNSDirectServerSpec `json:"rootDnsDirectServer"`
		ExpectedDNS  map[string]any          `json:"expectedDns"`
	}
	if err := json.Unmarshal(fixtureBytes, &sample); err != nil {
		t.Fatal(err)
	}
	r, s := fixture(t)
	s.SchemaVersion = SnapshotVersionDNSDefaultDirectServer
	s.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500}
	s.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	s.RootRoutePolicy = &RootRoutePolicy{AutoDetectInterface: true, FindProcess: true}
	s.RootDNSPolicy = &sample.Policy
	s.RootDNSLocalServer = &sample.LocalServer
	s.RootDNSDirectServer = &sample.DirectServer
	s.RootDNSDirectServer = &sample.DirectServer
	dns := sample.Config["dns"].(map[string]any)
	servers := dns["servers"].([]any)
	dns["servers"] = servers[2:]
	delete(dns, "final")
	delete(dns, "strategy")
	delete(dns, "independent_cache")
	configBytes, err := json.Marshal(sample.Config)
	if err != nil {
		t.Fatal(err)
	}
	s.Config = configBytes
	if err := CheckSnapshot(s); err != nil {
		t.Fatalf("shared DNS policy fixture rejected: %v", err)
	}
	revision, err := r.Stage(context.Background(), s)
	if err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(r.path("generations", revision, "run", "config.json"))
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(b, &got); err != nil {
		t.Fatal(err)
	}
	gotDNS := got["dns"].(map[string]any)
	if !jsonEqual(gotDNS, sample.ExpectedDNS) {
		t.Fatalf("module DNS output differs from shared Kotlin projection fixture: got %#v want %#v", gotDNS, sample.ExpectedDNS)
	}
}

func TestStructuredRootRouteFinalMatchesSharedProjectionFixture(t *testing.T) {
	fixtureBytes, err := os.ReadFile(filepath.Join("testdata", "root_dns_policy_projection.json"))
	if err != nil {
		t.Fatal(err)
	}
	var shared struct {
		Projection struct {
			Config        map[string]any     `json:"config"`
			RootFinal     RootRouteFinalSpec `json:"rootRouteFinal"`
			ExpectedRoute map[string]any     `json:"expectedRoute"`
		} `json:"routeFinalProjection"`
	}
	if err := json.Unmarshal(fixtureBytes, &shared); err != nil {
		t.Fatal(err)
	}
	r, s := fixture(t)
	s.SchemaVersion = SnapshotVersionRouteFinal
	s.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500}
	s.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	s.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote"}
	s.RootDNSLocalServer = &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"}
	s.RootDNSDirectServer = &RootDNSDirectServerSpec{Tag: "dns-direct"}
	s.RootRouteFinal = &shared.Projection.RootFinal
	config := shared.Projection.Config
	config["dns"] = map[string]any{"servers": []any{map[string]any{"type": "https", "tag": "dns-remote"}}}
	route := config["route"].(map[string]any)
	delete(route, "final")
	s.Config, err = json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	if err := CheckSnapshot(s); err != nil {
		t.Fatalf("shared route-final fixture rejected: %v", err)
	}
	revision, err := r.Stage(context.Background(), s)
	if err != nil {
		t.Fatal(err)
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
	if !jsonEqual(gotRoute, shared.Projection.ExpectedRoute) {
		t.Fatalf("module route differs from shared Kotlin projection fixture: got %#v want %#v", gotRoute, shared.Projection.ExpectedRoute)
	}
}

func TestStructuredRootRouteLoopbackMatchesSharedProjectionFixture(t *testing.T) {
	fixtureBytes, err := os.ReadFile(filepath.Join("testdata", "root_dns_policy_projection.json"))
	if err != nil {
		t.Fatal(err)
	}
	var shared struct {
		Projection struct {
			Config        map[string]any        `json:"config"`
			RootLoopback  RootRouteLoopbackSpec `json:"rootRouteLoopback"`
			ExpectedRoute map[string]any        `json:"expectedRoute"`
		} `json:"routeLoopbackProjection"`
	}
	if err := json.Unmarshal(fixtureBytes, &shared); err != nil {
		t.Fatal(err)
	}
	r, s := fixture(t)
	s.SchemaVersion = SnapshotVersionRouteLoopback
	s.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500}
	s.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	s.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote"}
	s.RootDNSLocalServer = &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"}
	s.RootDNSDirectServer = &RootDNSDirectServerSpec{Tag: "dns-direct"}
	s.RootRouteFinal = &RootRouteFinalSpec{Final: "direct"}
	s.RootRouteLoopback = &shared.Projection.RootLoopback
	config := shared.Projection.Config
	config["dns"] = map[string]any{"servers": []any{map[string]any{"type": "https", "tag": "dns-remote"}}}
	route := config["route"].(map[string]any)
	delete(route, "final")
	rules := route["rules"].([]any)
	index := shared.Projection.RootLoopback.Index
	route["rules"] = append(rules[:index], rules[index+1:]...) // App removes the projected rule before sending the snapshot.
	s.Config, err = json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	if err := CheckSnapshot(s); err != nil {
		t.Fatalf("shared route-loopback fixture rejected: %v", err)
	}
	revision, err := r.Stage(context.Background(), s)
	if err != nil {
		t.Fatal(err)
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
	if !jsonEqual(gotRoute, shared.Projection.ExpectedRoute) {
		t.Fatalf("module route differs from shared Kotlin projection fixture: got %#v want %#v", gotRoute, shared.Projection.ExpectedRoute)
	}
}

func TestStructuredDNSOutboundAnyRuleMatchesSharedProjectionFixture(t *testing.T) {
	fixtureBytes, err := os.ReadFile(filepath.Join("testdata", "root_dns_policy_projection.json"))
	if err != nil {
		t.Fatal(err)
	}
	var shared struct {
		Projection struct {
			Config              map[string]any             `json:"config"`
			RootRule            RootDNSOutboundAnyRuleSpec `json:"rootDnsOutboundAnyRule"`
			ExpectedLegacyRules []any                      `json:"expectedLegacyRules"`
			ExpectedRules       []any                      `json:"expectedRules"`
		} `json:"dnsOutboundAnyRuleProjection"`
	}
	if err := json.Unmarshal(fixtureBytes, &shared); err != nil {
		t.Fatal(err)
	}
	dns := shared.Projection.Config["dns"].(map[string]any)
	dns["rules"] = shared.Projection.ExpectedLegacyRules                // App removes the projected rule before sending.
	delete(shared.Projection.Config["route"].(map[string]any), "final") // App sends route.final as its typed field.
	snapshotBytes, err := json.Marshal(shared.Projection.Config)
	if err != nil {
		t.Fatal(err)
	}
	_, s := fixture(t)
	s.SchemaVersion = SnapshotVersionDNSOutboundAnyRule
	s.Config = snapshotBytes
	s.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500}
	s.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	s.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote"}
	s.RootDNSLocalServer = &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"}
	s.RootDNSDirectServer = &RootDNSDirectServerSpec{Tag: "dns-direct"}
	s.RootRouteFinal = &RootRouteFinalSpec{Final: "direct"}
	s.RootRouteMulticast = &RootRouteMulticastSpec{Index: 0}
	s.RootDNSOutboundAnyRule = &shared.Projection.RootRule
	if err := CheckSnapshot(s); err != nil {
		t.Fatalf("valid DNS outbound-any projection rejected: %v", err)
	}
	gotBytes, err := materializeRootInbound(s)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(gotBytes, &got); err != nil {
		t.Fatal(err)
	}
	gotRules := got["dns"].(map[string]any)["rules"].([]any)
	if !jsonEqual(gotRules, shared.Projection.ExpectedRules) {
		t.Fatalf("module DNS rules differ from shared Kotlin projection fixture: got %#v want %#v", gotRules, shared.Projection.ExpectedRules)
	}
}

func TestStructuredDNSIPv6QueryRuleMatchesSharedProjectionFixture(t *testing.T) {
	fixtureBytes, err := os.ReadFile(filepath.Join("testdata", "root_dns_policy_projection.json"))
	if err != nil {
		t.Fatal(err)
	}
	var shared struct {
		Projection struct {
			Config              map[string]any           `json:"config"`
			RootRule            RootDNSIPv6QueryRuleSpec `json:"rootDnsIPv6QueryRule"`
			ExpectedLegacyRules []any                    `json:"expectedLegacyRules"`
			ExpectedRules       []any                    `json:"expectedRules"`
		} `json:"dnsIPv6QueryRuleProjection"`
	}
	if err := json.Unmarshal(fixtureBytes, &shared); err != nil {
		t.Fatal(err)
	}
	dns := shared.Projection.Config["dns"].(map[string]any)
	dns["rules"] = shared.Projection.ExpectedLegacyRules // App removes the typed IPv6 reject rule before sending.
	delete(dns, "strategy")                              // App sends address-family strategy as typed DNS policy.
	configBytes, err := json.Marshal(shared.Projection.Config)
	if err != nil {
		t.Fatal(err)
	}
	_, s := fixture(t)
	s.SchemaVersion = SnapshotVersionDNSIPv6QueryRule
	s.Config = configBytes
	s.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500}
	s.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	s.RootRouteFinal = &RootRouteFinalSpec{Final: "proxy"}
	s.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote", Strategy: "ipv4_only"}
	s.RootDNSLocalServer = &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"}
	s.RootDNSDirectServer = &RootDNSDirectServerSpec{Tag: "dns-direct"}
	s.RootDNSIPv6QueryRule = &shared.Projection.RootRule
	s.RootRouteMulticast = &RootRouteMulticastSpec{Index: 0}
	if err := CheckSnapshot(s); err != nil {
		t.Fatalf("valid DNS IPv6 query rule projection rejected: %v", err)
	}
	gotBytes, err := materializeRootInbound(s)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(gotBytes, &got); err != nil {
		t.Fatal(err)
	}
	gotRules := got["dns"].(map[string]any)["rules"].([]any)
	if !jsonEqual(gotRules, shared.Projection.ExpectedRules) {
		t.Fatalf("module DNS rules differ from shared Kotlin projection fixture: got %#v want %#v", gotRules, shared.Projection.ExpectedRules)
	}
}

func TestStructuredDNSIPv6QueryRuleRejectsInvalidIndexStrategyAndDuplicate(t *testing.T) {
	policy := &RootDNSPolicySpec{Final: "dns-remote", Strategy: "ipv4_only"}
	config := map[string]any{"dns": map[string]any{"rules": []any{map[string]any{"domain": []any{"example.org"}}}}}
	if err := validateRootDNSIPv6QueryRule(&RootDNSIPv6QueryRuleSpec{Index: 2, QueryType: "AAAA"}, policy, config); err == nil || err.Error() != "root_dns_ipv6_query_invalid" {
		t.Fatalf("out-of-range DNS rule index accepted: %v", err)
	}
	if err := validateRootDNSIPv6QueryRule(&RootDNSIPv6QueryRuleSpec{Index: 0, QueryType: "A"}, policy, config); err == nil || err.Error() != "root_dns_ipv6_query_invalid" {
		t.Fatalf("strategy-inconsistent query type accepted: %v", err)
	}
	rule := map[string]any{"query_type": []any{"AAAA"}, "action": "reject"}
	config["dns"].(map[string]any)["rules"] = []any{rule}
	if err := validateRootDNSIPv6QueryRule(&RootDNSIPv6QueryRuleSpec{Index: 0, QueryType: "AAAA"}, policy, config); err == nil || err.Error() != "root_dns_ipv6_query_duplicate" {
		t.Fatalf("duplicate legacy DNS query rule accepted: %v", err)
	}
}

func TestStructuredDNSOutboundAnyRuleRejectsInvalidIndexAndDuplicate(t *testing.T) {
	config := map[string]any{"dns": map[string]any{"rules": []any{map[string]any{"domain": []any{"example.org"}}}}}
	if err := validateRootDNSOutboundAnyRule(&RootDNSOutboundAnyRuleSpec{Index: 2}, config); err == nil || err.Error() != "root_dns_outbound_any_invalid" {
		t.Fatalf("out-of-range DNS rule index accepted: %v", err)
	}
	rule := map[string]any{"outbound": []any{"any"}, "server": "dns-direct"}
	config["dns"].(map[string]any)["rules"] = []any{rule}
	if err := validateRootDNSOutboundAnyRule(&RootDNSOutboundAnyRuleSpec{Index: 0}, config); err == nil || err.Error() != "root_dns_outbound_any_duplicate" {
		t.Fatalf("duplicate legacy DNS rule accepted: %v", err)
	}
	config["dns"].(map[string]any)["rules"] = "malformed"
	if err := validateRootDNSOutboundAnyRule(&RootDNSOutboundAnyRuleSpec{Index: 0}, config); err == nil || err.Error() != "root_dns_outbound_any_invalid" {
		t.Fatalf("malformed DNS rules accepted: %v", err)
	}
}

func TestStructuredRootRouteMulticastMatchesSharedProjectionFixture(t *testing.T) {
	fixtureBytes, err := os.ReadFile(filepath.Join("testdata", "root_dns_policy_projection.json"))
	if err != nil {
		t.Fatal(err)
	}
	var shared struct {
		Projection struct {
			Config              map[string]any         `json:"config"`
			RootRouteMulticast  RootRouteMulticastSpec `json:"rootRouteMulticast"`
			ExpectedLegacyRules []any                  `json:"expectedLegacyRules"`
			ExpectedRoute       map[string]any         `json:"expectedRoute"`
		} `json:"routeMulticastProjection"`
	}
	if err := json.Unmarshal(fixtureBytes, &shared); err != nil {
		t.Fatal(err)
	}
	configBytes, err := json.Marshal(shared.Projection.Config)
	if err != nil {
		t.Fatal(err)
	}
	var config map[string]any
	if err := json.Unmarshal(configBytes, &config); err != nil {
		t.Fatal(err)
	}
	route := config["route"].(map[string]any)
	delete(route, "final") // App sends the independently typed v12 route.final.
	route["rules"] = shared.Projection.ExpectedLegacyRules
	_, s := fixture(t)
	s.SchemaVersion = SnapshotVersionRouteMulticast
	s.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500}
	s.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	s.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote"}
	s.RootDNSLocalServer = &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"}
	s.RootDNSDirectServer = &RootDNSDirectServerSpec{Tag: "dns-direct"}
	s.RootRouteFinal = &RootRouteFinalSpec{Final: "direct"}
	s.RootRouteMulticast = &shared.Projection.RootRouteMulticast
	s.Config, err = json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	if err := CheckSnapshot(s); err != nil {
		t.Fatalf("valid multicast projection rejected: %v", err)
	}
	gotBytes, err := materializeRootInbound(s)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(gotBytes, &got); err != nil {
		t.Fatal(err)
	}
	if !jsonEqual(got["route"], shared.Projection.ExpectedRoute) {
		t.Fatalf("materialized route differs from shared fixture: got %#v want %#v", got["route"], shared.Projection.ExpectedRoute)
	}
}

func TestStructuredRootRouteMulticastRejectsInvalidIndexAndDuplicate(t *testing.T) {
	config := map[string]any{"route": map[string]any{"rules": []any{map[string]any{"action": "sniff"}}}}
	if err := validateRootRouteMulticast(&RootRouteMulticastSpec{Index: 2}, config, Snapshot{}); err == nil || err.Error() != "root_route_multicast_invalid" {
		t.Fatalf("out-of-range multicast index accepted: %v", err)
	}
	rule := map[string]any{"ip_cidr": []any{"224.0.0.0/3", "ff00::/8"}, "source_ip_cidr": []any{"224.0.0.0/3", "ff00::/8"}, "action": "reject"}
	config["route"].(map[string]any)["rules"] = []any{rule}
	if err := validateRootRouteMulticast(&RootRouteMulticastSpec{Index: 0}, config, Snapshot{}); err == nil || err.Error() != "root_route_multicast_duplicate" {
		t.Fatalf("duplicate legacy multicast rule accepted: %v", err)
	}
}

func TestStructuredRootRouteLoopbackRejectsInvalidIndexAndDuplicateLegacyRule(t *testing.T) {
	config := map[string]any{"route": map[string]any{"rules": []any{map[string]any{"action": "sniff"}}}}
	if err := validateRootRouteLoopback(&RootRouteLoopbackSpec{Index: 2}, config, Snapshot{}); err == nil || err.Error() != "root_route_loopback_invalid" {
		t.Fatalf("out-of-range route index accepted: %v", err)
	}
	guard := map[string]any{"ip_cidr": []any{"127.0.0.0/8", "::1/128", "224.0.0.0/4", "169.254.0.0/16", "fe80::/10"}, "outbound": "direct"}
	config["route"].(map[string]any)["rules"] = []any{guard}
	if err := validateRootRouteLoopback(&RootRouteLoopbackSpec{Index: 0}, config, Snapshot{}); err == nil || err.Error() != "root_route_loopback_duplicate" {
		t.Fatalf("duplicate legacy route rule accepted: %v", err)
	}
}

func TestStructuredRootRouteFinalRejectsMissingOutboundAndDuplicateLegacyValue(t *testing.T) {
	_, s := fixture(t)
	s.SchemaVersion = SnapshotVersionRouteFinal
	s.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500}
	s.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	s.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote"}
	s.RootDNSLocalServer = &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"}
	s.RootDNSDirectServer = &RootDNSDirectServerSpec{Tag: "dns-direct"}
	s.Config = json.RawMessage(`{"inbounds":[],"outbounds":[{"type":"direct","tag":"direct"}],"dns":{"servers":[{"type":"https","tag":"dns-remote"}]},"route":{"rules":[]}}`)
	s.RootRouteFinal = &RootRouteFinalSpec{Final: "missing"}
	if err := CheckSnapshot(s); err == nil || err.Error() != "root_route_final_invalid" {
		t.Fatalf("missing outbound accepted: %v", err)
	}
	s.RootRouteFinal = &RootRouteFinalSpec{Final: "direct"}
	s.Config = json.RawMessage(`{"inbounds":[],"outbounds":[{"type":"direct","tag":"direct"}],"dns":{"servers":[{"type":"https","tag":"dns-remote"}]},"route":{"final":"direct","rules":[]}}`)
	if err := CheckSnapshot(s); err == nil || err.Error() != "root_route_final_duplicate" {
		t.Fatalf("duplicate legacy final accepted: %v", err)
	}
}

func TestStructuredDNSLocalServerValidationAndMaterialization(t *testing.T) {
	_, s := fixture(t)
	s.SchemaVersion = SnapshotVersionDNSLocalServer
	s.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500}
	s.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	s.RootRoutePolicy = &RootRoutePolicy{AutoDetectInterface: true, FindProcess: true}
	s.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote"}
	s.RootDNSLocalServer = &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"}
	s.Config = json.RawMessage(`{"inbounds":[],"outbounds":[{"type":"direct","tag":"direct"}],"dns":{"servers":[{"type":"https","tag":"dns-remote"}]}}`)
	if err := CheckSnapshot(s); err != nil {
		t.Fatalf("valid local DNS server rejected: %v", err)
	}
	out, err := materializeRootInbound(s)
	if err != nil {
		t.Fatal(err)
	}
	var config map[string]any
	if err := json.Unmarshal(out, &config); err != nil {
		t.Fatal(err)
	}
	servers := config["dns"].(map[string]any)["servers"].([]any)
	local := servers[0].(map[string]any)
	if !jsonEqual(local, map[string]any{"type": "local", "tag": "dns-local", "detour": "direct"}) || servers[1].(map[string]any)["tag"] != "dns-remote" {
		t.Fatalf("unexpected local DNS materialization/order: %#v", servers)
	}

	missing := s
	missing.RootDNSLocalServer = nil
	if err := CheckSnapshot(missing); err == nil || err.Error() != "root_dns_local_server_invalid" {
		t.Fatalf("schema 10 without local server accepted: %v", err)
	}
	duplicate := s
	duplicate.Config = json.RawMessage(`{"inbounds":[],"outbounds":[{"type":"direct","tag":"direct"}],"dns":{"servers":[{"type":"local","tag":"dns-local"},{"type":"https","tag":"dns-remote"}]}}`)
	if err := CheckSnapshot(duplicate); err == nil || err.Error() != "root_dns_local_server_duplicate" {
		t.Fatalf("duplicate legacy local server accepted: %v", err)
	}
	invalid := s
	invalid.RootDNSLocalServer = &RootDNSLocalServerSpec{Tag: "custom", Detour: "direct"}
	if err := CheckSnapshot(invalid); err == nil || err.Error() != "root_dns_local_server_invalid" {
		t.Fatalf("invalid local server accepted: %v", err)
	}
}

func TestDNSCachePolicyMigrationPreservesSchema8AndRejectsDuplicateSchema9(t *testing.T) {
	legacyConfig := json.RawMessage(`{"inbounds":[],"outbounds":[{"type":"direct","tag":"direct"}],"dns":{"independent_cache":true,"servers":[{"type":"https","tag":"dns-remote"}]}}`)
	_, v8 := fixture(t)
	v8.SchemaVersion = SnapshotVersionDNSPolicy
	v8.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500}
	v8.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	v8.RootRoutePolicy = &RootRoutePolicy{AutoDetectInterface: true, FindProcess: true}
	v8.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote"}
	v8.Config = legacyConfig
	if err := CheckSnapshot(v8); err != nil {
		t.Fatalf("schema 8 must preserve the legacy DNS cache field: %v", err)
	}
	materialized, err := materializeRootInbound(v8)
	if err != nil {
		t.Fatalf("schema 8 materialization failed: %v", err)
	}
	var legacyOutput map[string]any
	if err = json.Unmarshal(materialized, &legacyOutput); err != nil {
		t.Fatal(err)
	}
	legacyDNS := legacyOutput["dns"].(map[string]any)
	if legacyDNS["independent_cache"] != true || legacyDNS["final"] != "dns-remote" {
		t.Fatalf("schema 8 did not retain cache while applying DNS final policy: %#v", legacyDNS)
	}

	_, v9 := fixture(t)
	v9.SchemaVersion = SnapshotVersionDNSCachePolicy
	v9.RootInbound = v8.RootInbound
	v9.RootProxyGroup = v8.RootProxyGroup
	v9.RootRoutePolicy = v8.RootRoutePolicy
	cache := true
	v9.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote", IndependentCache: &cache}
	v9.Config = legacyConfig
	if err := CheckSnapshot(v9); err == nil || err.Error() != "root_dns_policy_duplicate" {
		t.Fatalf("schema 9 accepted duplicated legacy independent_cache: %v", err)
	}
}

func TestStructuredDNSPolicyRejectsInvalidAndDuplicateLegacyFields(t *testing.T) {
	config := map[string]any{"dns": map[string]any{"servers": []any{map[string]any{"tag": "dns-remote"}}}}
	for _, policy := range []*RootDNSPolicySpec{
		{Final: "missing"},
		{Final: "dns-remote", Strategy: "parallel"},
		{Final: "../dns-remote"},
	} {
		if err := validateRootDNSPolicy(policy, config); err == nil || err.Error() != "root_dns_policy_invalid" {
			t.Fatalf("invalid DNS policy accepted: %#v, %v", policy, err)
		}
	}
	_, s := fixture(t)
	s.SchemaVersion = SnapshotVersionDNSPolicy
	s.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500}
	s.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	s.RootRoutePolicy = &RootRoutePolicy{AutoDetectInterface: true, FindProcess: true}
	s.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote", Strategy: "ipv4_only"}
	s.Config = json.RawMessage(`{"inbounds":[],"outbounds":[{"type":"direct","tag":"direct"}],"dns":{"final":"dns-remote","servers":[{"type":"https","tag":"dns-remote"}]}}`)
	if err := CheckSnapshot(s); err == nil || err.Error() != "root_dns_policy_duplicate" {
		t.Fatalf("legacy DNS policy fields were not detected: %v", err)
	}
}

func TestStructuredStaticDNSHostsAreMaterializedByModule(t *testing.T) {
	r, s := fixture(t)
	s.SchemaVersion = SnapshotVersionDNSHosts
	s.RootInbound = &RootInboundSpec{Backend: "tun", IPv6Mode: 1, MTU: 1500}
	s.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	s.RootRoutePolicy = &RootRoutePolicy{AutoDetectInterface: true, FindProcess: true}
	s.RootDNSHosts = &RootDNSHostsSpec{Raw: "# local overrides\nexample.org 1.1.1.1 2001:4860:4860::8888\nexample.org 1.1.1.1\nignored.invalid not-an-ip\n"}
	s.Config = json.RawMessage(`{"inbounds":[],"outbounds":[{"type":"direct","tag":"direct"}],"route":{"rules":[{"action":"sniff"}]},"dns":{"servers":[{"type":"local","tag":"dns-local"}]}}`)
	if err := CheckSnapshot(s); err != nil {
		t.Fatalf("valid structured DNS hosts rejected: %v", err)
	}
	rev, err := r.Stage(context.Background(), s)
	if err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(r.path("generations", rev, "run", "config.json"))
	if err != nil {
		t.Fatal(err)
	}
	var config map[string]any
	if err = json.Unmarshal(b, &config); err != nil {
		t.Fatal(err)
	}
	dns := config["dns"].(map[string]any)
	servers := dns["servers"].([]any)
	if len(servers) != 2 || servers[0].(map[string]any)["tag"] != "dns-local" {
		t.Fatalf("legacy DNS servers changed: %#v", servers)
	}
	hostServer := servers[1].(map[string]any)
	if hostServer["type"] != "hosts" || hostServer["tag"] != "dns-hosts" {
		t.Fatalf("module hosts DNS server missing: %#v", hostServer)
	}
	predefined := hostServer["predefined"].(map[string]any)
	addresses := predefined["example.org"].([]any)
	if len(addresses) != 2 || addresses[0] != "1.1.1.1" || addresses[1] != "2001:4860:4860::8888" {
		t.Fatalf("unexpected normalized hosts: %#v", predefined)
	}
	if _, exists := predefined["ignored.invalid"]; exists {
		t.Fatalf("invalid address was retained: %#v", predefined)
	}
	var saved Snapshot
	if err = readJSON(r.path("generations", rev, "snapshot.json"), &saved, MaxSnapshotBytes); err != nil || saved.RootDNSHosts == nil {
		t.Fatalf("structured DNS hosts were not retained: %v", err)
	}
}

func TestStructuredStaticDNSHostsRejectInvalidInputAndLegacyDuplicate(t *testing.T) {
	if err := validateRootDNSHosts(&RootDNSHostsSpec{Raw: strings.Repeat("a", (64<<10)+1)}); err == nil {
		t.Fatal("oversized hosts source accepted")
	}
	if err := validateRootDNSHosts(&RootDNSHostsSpec{Raw: "bad\x00domain 1.1.1.1"}); err == nil {
		t.Fatal("control character in domain accepted")
	}
	_, s := fixture(t)
	s.SchemaVersion = SnapshotVersionDNSHosts
	s.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500}
	s.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	s.RootDNSHosts = &RootDNSHostsSpec{Raw: "example.org 1.1.1.1"}
	s.Config = json.RawMessage(`{"inbounds":[],"outbounds":[{"type":"direct","tag":"direct"}],"dns":{"servers":[{"type":"hosts","tag":"dns-hosts"}]}}`)
	if err := CheckSnapshot(s); err == nil || err.Error() != "root_dns_hosts_duplicate" {
		t.Fatalf("legacy hosts server duplication accepted: %v", err)
	}
}

func TestStructuredRootRoutePolicyRejectsInvalidAndDuplicateFields(t *testing.T) {
	_, s := fixture(t)
	s.SchemaVersion = SnapshotVersionRoutePolicy
	s.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500}
	s.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"node-a"}, Default: "node-a"}
	s.RootRoutePolicy = &RootRoutePolicy{AutoDetectInterface: true, FindProcess: true, DefaultNetworkStrategy: "parallel"}
	s.Config = json.RawMessage(`{"inbounds":[],"outbounds":[]}`)
	if err := CheckSnapshot(s); err == nil || err.Error() != "root_route_policy_invalid" {
		t.Fatalf("invalid strategy accepted: %v", err)
	}
	s.RootRoutePolicy.DefaultNetworkStrategy = "hybrid"
	s.Config = json.RawMessage(`{"inbounds":[],"outbounds":[],"route":{"find_process":false}}`)
	if err := CheckSnapshot(s); err == nil || err.Error() != "root_route_policy_duplicate" {
		t.Fatalf("duplicate route policy accepted: %v", err)
	}
	s.Config = json.RawMessage(`{"inbounds":[],"outbounds":[]}`)
	s.SchemaVersion = SnapshotVersionTLSOutbounds
	if err := CheckSnapshot(s); err == nil || err.Error() != "snapshot_invalid" {
		t.Fatalf("new field accepted by older schema: %v", err)
	}
}

func TestStructuredRouteDNSPort53RuleMatchesSharedProjectionFixture(t *testing.T) {
	fixtureBytes, err := os.ReadFile(filepath.Join("testdata", "root_dns_policy_projection.json"))
	if err != nil {
		t.Fatal(err)
	}
	var shared struct {
		Projection struct {
			Config              map[string]any             `json:"config"`
			RootRule            RootRouteDNSPort53RuleSpec `json:"rootRouteDNSPort53Rule"`
			ExpectedLegacyRules []any                      `json:"expectedLegacyRules"`
			ExpectedRules       []any                      `json:"expectedRules"`
		} `json:"routeDNSPort53RuleProjection"`
	}
	if err := json.Unmarshal(fixtureBytes, &shared); err != nil {
		t.Fatal(err)
	}
	route := shared.Projection.Config["route"].(map[string]any)
	route["rules"] = shared.Projection.ExpectedLegacyRules
	outbounds := shared.Projection.Config["outbounds"].([]any)
	legacy := make([]any, 0, len(outbounds)-1)
	for _, raw := range outbounds {
		if raw.(map[string]any)["tag"] != "proxy" {
			legacy = append(legacy, raw)
		}
	}
	shared.Projection.Config["outbounds"] = legacy // The proxy group travels in typed module input.
	shared.Projection.Config["dns"] = map[string]any{"servers": []any{map[string]any{"type": "https", "tag": "dns-remote", "server": "dns.example"}}, "rules": []any{}}
	configBytes, err := json.Marshal(shared.Projection.Config)
	if err != nil {
		t.Fatal(err)
	}
	_, s := fixture(t)
	s.SchemaVersion = SnapshotVersionRouteDNSPort53Rule
	s.Config = configBytes
	s.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500}
	s.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	s.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote"}
	s.RootDNSLocalServer = &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"}
	s.RootDNSDirectServer = &RootDNSDirectServerSpec{Tag: "dns-direct"}
	s.RootRouteFinal = &RootRouteFinalSpec{Final: "direct"}
	s.RootRouteMulticast = &RootRouteMulticastSpec{Index: 1}
	s.RootRouteDNSPort53Rule = &shared.Projection.RootRule
	if err := CheckSnapshot(s); err != nil {
		t.Fatalf("valid route DNS port 53 projection rejected: %v", err)
	}
	gotBytes, err := materializeRootInbound(s)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(gotBytes, &got); err != nil {
		t.Fatal(err)
	}
	gotRules := got["route"].(map[string]any)["rules"].([]any)
	wantMulticast := map[string]any{"ip_cidr": []any{"224.0.0.0/3", "ff00::/8"}, "source_ip_cidr": []any{"224.0.0.0/3", "ff00::/8"}, "action": "reject"}
	if len(gotRules) != len(shared.Projection.ExpectedRules)+1 || !jsonEqual(gotRules[0], shared.Projection.ExpectedRules[0]) || !jsonEqual(gotRules[1], wantMulticast) || !jsonEqual(gotRules[2:], shared.Projection.ExpectedRules[1:]) {
		t.Fatalf("materialized route differs from shared Kotlin projection fixture: got %#v want DNS rule with intervening multicast guard", gotRules)
	}
}

func TestStructuredRouteBypassLANMatchesSharedProjectionFixtureAndRestoresOrder(t *testing.T) {
	fixtureBytes, err := os.ReadFile(filepath.Join("testdata", "root_dns_policy_projection.json"))
	if err != nil {
		t.Fatal(err)
	}
	var shared struct {
		Projection struct {
			Config              map[string]any               `json:"config"`
			RootBypass          RootRouteBypassLANSpec       `json:"rootRouteBypassLan"`
			RootLoopback        RootRouteLoopbackSpec        `json:"rootRouteLoopback"`
			RootMulticast       RootRouteMulticastSpec       `json:"rootRouteMulticast"`
			RootPort53          RootRouteDNSPort53RuleSpec   `json:"rootRouteDNSPort53Rule"`
			RootProtocol        RootRouteDNSProtocolRuleSpec `json:"rootRouteDNSProtocolRule"`
			ExpectedLegacyRules []any                        `json:"expectedLegacyRules"`
			ExpectedRules       []any                        `json:"expectedRules"`
		} `json:"routeBypassLanProjection"`
	}
	if err := json.Unmarshal(fixtureBytes, &shared); err != nil {
		t.Fatal(err)
	}
	route := shared.Projection.Config["route"].(map[string]any)
	route["rules"] = shared.Projection.ExpectedLegacyRules
	shared.Projection.Config["outbounds"] = []any{}
	configBytes, err := json.Marshal(shared.Projection.Config)
	if err != nil {
		t.Fatal(err)
	}
	_, snapshot := fixture(t)
	snapshot.SchemaVersion = SnapshotVersionRouteBypassLAN
	snapshot.Config = configBytes
	snapshot.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500, IPv6Mode: 0}
	snapshot.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	snapshot.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote"}
	snapshot.RootDNSLocalServer = &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"}
	snapshot.RootDNSDirectServer = &RootDNSDirectServerSpec{Tag: "dns-direct"}
	snapshot.RootRouteFinal = &RootRouteFinalSpec{Final: "proxy"}
	shared.Projection.Config["dns"] = map[string]any{"servers": []any{map[string]any{"type": "https", "tag": "dns-remote", "server": "dns.example"}}, "rules": []any{}}
	configBytes, err = json.Marshal(shared.Projection.Config)
	if err != nil {
		t.Fatal(err)
	}
	snapshot.Config = configBytes
	snapshot.RootRouteDNSPort53Rule = &shared.Projection.RootPort53
	snapshot.RootRouteDNSProtocolRule = &shared.Projection.RootProtocol
	snapshot.RootRouteBypassLAN = &shared.Projection.RootBypass
	snapshot.RootRouteMulticast = &shared.Projection.RootMulticast
	snapshot.RootRouteLoopback = &shared.Projection.RootLoopback
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("valid projected route rejected: %v", err)
	}
	gotBytes, err := materializeRootInbound(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(gotBytes, &got); err != nil {
		t.Fatal(err)
	}
	gotRules := got["route"].(map[string]any)["rules"].([]any)
	if !jsonEqual(gotRules, shared.Projection.ExpectedRules) {
		t.Fatalf("materialized route order differs: got %#v want %#v", gotRules, shared.Projection.ExpectedRules)
	}
}

func TestStructuredRouteBypassLANRejectsInvalidAndDuplicateCases(t *testing.T) {
	config := map[string]any{"route": map[string]any{"rules": []any{map[string]any{"action": "sniff"}}}}
	if err := validateRootRouteBypassLAN(&RootRouteBypassLANSpec{Index: 2}, config, Snapshot{}); err == nil || err.Error() != "root_route_bypass_lan_invalid" {
		t.Fatalf("out-of-range index accepted: %v", err)
	}
	config["route"].(map[string]any)["rules"] = []any{map[string]any{"ip_cidr": []any{"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"}, "outbound": "direct"}}
	if err := validateRootRouteBypassLAN(&RootRouteBypassLANSpec{Index: 0}, config, Snapshot{}); err == nil || err.Error() != "root_route_bypass_lan_duplicate" {
		t.Fatalf("duplicate accepted: %v", err)
	}
}

func TestStructuredRouteIPVersionRuleMatchesSharedProjectionFixtureAndRestoresOrder(t *testing.T) {
	fixtureBytes, err := os.ReadFile(filepath.Join("testdata", "root_dns_policy_projection.json"))
	if err != nil {
		t.Fatal(err)
	}
	var shared struct {
		Projection struct {
			Config              map[string]any               `json:"config"`
			RootIP              RootRouteIPVersionRuleSpec   `json:"rootRouteIPVersionRule"`
			RootBypass          RootRouteBypassLANSpec       `json:"rootRouteBypassLan"`
			RootLoopback        RootRouteLoopbackSpec        `json:"rootRouteLoopback"`
			RootMulticast       RootRouteMulticastSpec       `json:"rootRouteMulticast"`
			RootPort53          RootRouteDNSPort53RuleSpec   `json:"rootRouteDNSPort53Rule"`
			RootProtocol        RootRouteDNSProtocolRuleSpec `json:"rootRouteDNSProtocolRule"`
			ExpectedLegacyRules []any                        `json:"expectedLegacyRules"`
			ExpectedRules       []any                        `json:"expectedRules"`
		} `json:"routeIPVersionRuleProjection"`
	}
	if err := json.Unmarshal(fixtureBytes, &shared); err != nil {
		t.Fatal(err)
	}
	route := shared.Projection.Config["route"].(map[string]any)
	route["rules"] = shared.Projection.ExpectedLegacyRules
	// ConfigBuilder transfers this selector out of the transition config in schema v3+.
	delete(shared.Projection.Config, "outbounds")
	configBytes, err := json.Marshal(shared.Projection.Config)
	if err != nil {
		t.Fatal(err)
	}
	_, snapshot := fixture(t)
	snapshot.SchemaVersion = SnapshotVersionRouteIPVersionRule
	snapshot.Config = configBytes
	snapshot.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500, IPv6Mode: 0}
	snapshot.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	snapshot.RootRoutePolicy = &RootRoutePolicy{}
	snapshot.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote"}
	snapshot.RootDNSLocalServer = &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"}
	snapshot.RootDNSDirectServer = &RootDNSDirectServerSpec{Tag: "dns-direct"}
	snapshot.RootRouteFinal = &RootRouteFinalSpec{Final: "proxy"}
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	config["dns"] = map[string]any{"servers": []any{map[string]any{"type": "https", "tag": "dns-remote"}}}
	snapshot.Config, err = json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	snapshot.RootRouteDNSPort53Rule = &shared.Projection.RootPort53
	snapshot.RootRouteDNSProtocolRule = &shared.Projection.RootProtocol
	snapshot.RootRouteBypassLAN = &shared.Projection.RootBypass
	snapshot.RootRouteMulticast = &shared.Projection.RootMulticast
	snapshot.RootRouteLoopback = &shared.Projection.RootLoopback
	snapshot.RootRouteIPVersionRule = &shared.Projection.RootIP
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("valid projected single-stack rule rejected: %v", err)
	}
	gotBytes, err := materializeRootInbound(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(gotBytes, &got); err != nil {
		t.Fatal(err)
	}
	gotRules := got["route"].(map[string]any)["rules"].([]any)
	if !jsonEqual(gotRules, shared.Projection.ExpectedRules) {
		t.Fatalf("materialized route order differs: got %#v want %#v", gotRules, shared.Projection.ExpectedRules)
	}
}

func TestStructuredRouteDNSHijackPort53MatchesSharedProjectionFixtureAndRestoresOrder(t *testing.T) {
	fixtureBytes, err := os.ReadFile(filepath.Join("testdata", "root_dns_policy_projection.json"))
	if err != nil {
		t.Fatal(err)
	}
	var shared struct {
		Projection struct {
			Config              map[string]any                   `json:"config"`
			RootIP              RootRouteIPVersionRuleSpec       `json:"rootRouteIPVersionRule"`
			RootBypass          RootRouteBypassLANSpec           `json:"rootRouteBypassLan"`
			RootLoopback        RootRouteLoopbackSpec            `json:"rootRouteLoopback"`
			RootMulticast       RootRouteMulticastSpec           `json:"rootRouteMulticast"`
			RootPort53          RootRouteDNSPort53RuleSpec       `json:"rootRouteDNSPort53Rule"`
			RootProtocol        RootRouteDNSProtocolRuleSpec     `json:"rootRouteDNSProtocolRule"`
			RootPrivate         RootRoutePrivateBypassSpec       `json:"rootRoutePrivateBypass"`
			RootPrivateModule   RootRoutePrivateBypassSpec       `json:"rootRoutePrivateBypassModuleIndex"`
			RootHijack          RootRouteDNSHijackPort53RuleSpec `json:"rootRouteDNSHijackPort53Rule"`
			ExpectedLegacyRules []any                            `json:"expectedLegacyRules"`
			ExpectedRules       []any                            `json:"expectedRules"`
		} `json:"routePrivateBypassProjection"`
	}
	if err := json.Unmarshal(fixtureBytes, &shared); err != nil {
		t.Fatal(err)
	}
	route := shared.Projection.Config["route"].(map[string]any)
	route["rules"] = shared.Projection.ExpectedLegacyRules
	delete(shared.Projection.Config, "outbounds")
	configBytes, err := json.Marshal(shared.Projection.Config)
	if err != nil {
		t.Fatal(err)
	}
	_, snapshot := fixture(t)
	snapshot.SchemaVersion = SnapshotVersionRouteDNSHijackPort53Rule
	snapshot.Config = configBytes
	snapshot.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500, IPv6Mode: 0}
	snapshot.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	snapshot.RootRoutePolicy = &RootRoutePolicy{}
	snapshot.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote"}
	snapshot.RootDNSLocalServer = &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"}
	snapshot.RootDNSDirectServer = &RootDNSDirectServerSpec{Tag: "dns-direct"}
	snapshot.RootRouteFinal = &RootRouteFinalSpec{Final: "proxy"}
	config := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &config); err != nil {
		t.Fatal(err)
	}
	config["dns"] = map[string]any{"servers": []any{map[string]any{"type": "https", "tag": "dns-remote"}}}
	snapshot.Config, err = json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	snapshot.RootRouteDNSPort53Rule = &shared.Projection.RootPort53
	snapshot.RootRouteDNSProtocolRule = &shared.Projection.RootProtocol
	snapshot.RootRouteBypassLAN = &shared.Projection.RootBypass
	snapshot.RootRouteMulticast = &shared.Projection.RootMulticast
	snapshot.RootRouteLoopback = &shared.Projection.RootLoopback
	snapshot.RootRouteIPVersionRule = &shared.Projection.RootIP
	snapshot.RootRoutePrivateBypass = &shared.Projection.RootPrivateModule
	snapshot.RootRouteDNSHijackPort53Rule = &shared.Projection.RootHijack
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("valid projected DNS hijack rule rejected: %v", err)
	}
	gotBytes, err := materializeRootInbound(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(gotBytes, &got); err != nil {
		t.Fatal(err)
	}
	gotRules := got["route"].(map[string]any)["rules"].([]any)
	if !jsonEqual(gotRules, shared.Projection.ExpectedRules) {
		t.Fatalf("materialized route order differs: got %#v want %#v", gotRules, shared.Projection.ExpectedRules)
	}
}

func TestStructuredRouteSniffMatchesSharedProjectionFixtureAndRestoresOrder(t *testing.T) {
	fixtureBytes, err := os.ReadFile(filepath.Join("testdata", "root_dns_policy_projection.json"))
	if err != nil {
		t.Fatal(err)
	}
	var shared struct {
		Projection struct {
			Config              map[string]any                     `json:"config"`
			RootSniff           RootRouteSniffRuleSpec             `json:"rootRouteSniffRule"`
			RootIP              RootRouteIPVersionRuleSpec         `json:"rootRouteIPVersionRule"`
			RootDNSHijack       RootRouteDNSProtocolHijackRuleSpec `json:"rootRouteDNSProtocolHijackRule"`
			ExpectedLegacyRules []any                              `json:"expectedLegacyRules"`
			ExpectedRules       []any                              `json:"expectedRules"`
		} `json:"routeSniffRuleProjection"`
	}
	if err := json.Unmarshal(fixtureBytes, &shared); err != nil {
		t.Fatal(err)
	}
	route := shared.Projection.Config["route"].(map[string]any)
	route["rules"] = shared.Projection.ExpectedLegacyRules
	config, err := json.Marshal(shared.Projection.Config)
	if err != nil {
		t.Fatal(err)
	}
	configObject := map[string]any{}
	if err := json.Unmarshal(config, &configObject); err != nil {
		t.Fatal(err)
	}
	configObject["dns"] = map[string]any{"servers": []any{map[string]any{"type": "https", "tag": "dns-remote"}}}
	config, err = json.Marshal(configObject)
	if err != nil {
		t.Fatal(err)
	}
	_, snapshot := fixture(t)
	snapshot.SchemaVersion = SnapshotVersionRouteSniffRule
	snapshot.Config = config
	snapshot.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500, IPv6Mode: 0}
	snapshot.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	snapshot.RootRoutePolicy = &RootRoutePolicy{AutoDetectInterface: true, FindProcess: true}
	snapshot.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote"}
	snapshot.RootDNSLocalServer = &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"}
	snapshot.RootDNSDirectServer = &RootDNSDirectServerSpec{Tag: "dns-direct"}
	snapshot.RootRouteFinal = &RootRouteFinalSpec{Final: "direct"}
	snapshot.RootRouteIPVersionRule = &RootRouteIPVersionRuleSpec{Index: shared.Projection.RootIP.Index, IPVersion: 6}
	snapshot.RootRouteDNSProtocolHijackRule = &shared.Projection.RootDNSHijack
	snapshot.RootRouteSniffRule = &shared.Projection.RootSniff
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("valid projected sniff rule rejected: %v", err)
	}
	gotBytes, err := materializeRootInbound(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(gotBytes, &got); err != nil {
		t.Fatal(err)
	}
	gotRules := got["route"].(map[string]any)["rules"].([]any)
	if !jsonEqual(gotRules, shared.Projection.ExpectedRules) {
		t.Fatalf("materialized route order differs: got %#v want %#v", gotRules, shared.Projection.ExpectedRules)
	}
}

func TestStructuredRouteProbeMatchesSharedFixtureAndRestoresFirstPosition(t *testing.T) {
	fixtureBytes, err := os.ReadFile(filepath.Join("testdata", "root_dns_policy_projection.json"))
	if err != nil {
		t.Fatal(err)
	}
	var shared struct {
		Projection struct {
			Config              map[string]any             `json:"config"`
			Root                RootRouteProbeRuleSpec     `json:"rootRouteProbeRule"`
			RootIP              RootRouteIPVersionRuleSpec `json:"rootRouteIPVersionRule"`
			Sniff               RootRouteSniffRuleSpec     `json:"rootRouteSniffRule"`
			ExpectedLegacyRules []any                      `json:"expectedLegacyRules"`
			ExpectedRules       []any                      `json:"expectedRules"`
		} `json:"routeProbeRuleProjection"`
	}
	if err := json.Unmarshal(fixtureBytes, &shared); err != nil {
		t.Fatal(err)
	}
	route := shared.Projection.Config["route"].(map[string]any)
	route["rules"] = shared.Projection.ExpectedLegacyRules
	config, err := json.Marshal(shared.Projection.Config)
	if err != nil {
		t.Fatal(err)
	}
	_, snapshot := fixture(t)
	snapshot.SchemaVersion = SnapshotVersionRouteProbeRule
	snapshot.Config = config
	snapshot.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500, IPv6Mode: 0}
	snapshot.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	snapshot.RootRoutePolicy = &RootRoutePolicy{AutoDetectInterface: true, FindProcess: true}
	snapshot.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote"}
	snapshot.RootDNSLocalServer = &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"}
	snapshot.RootDNSDirectServer = &RootDNSDirectServerSpec{Tag: "dns-direct"}
	snapshot.RootRouteFinal = &RootRouteFinalSpec{Final: "direct"}
	snapshot.RootRouteIPVersionRule = &shared.Projection.RootIP
	snapshot.RootRouteSniffRule = &shared.Projection.Sniff
	configWithDNS := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &configWithDNS); err != nil {
		t.Fatal(err)
	}
	configWithDNS["dns"] = map[string]any{"servers": []any{map[string]any{"type": "https", "tag": "dns-remote"}}}
	snapshot.Config, err = json.Marshal(configWithDNS)
	if err != nil {
		t.Fatal(err)
	}
	snapshot.RootRouteProbeRule = &shared.Projection.Root
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("valid projected probe route rejected: %v", err)
	}
	gotBytes, err := materializeRootInbound(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(gotBytes, &got); err != nil {
		t.Fatal(err)
	}
	gotRules := got["route"].(map[string]any)["rules"].([]any)
	if !jsonEqual(gotRules, shared.Projection.ExpectedRules) {
		t.Fatalf("materialized probe route order differs: got %#v want %#v", gotRules, shared.Projection.ExpectedRules)
	}
}

func TestStructuredRouteResolveMatchesSharedFixtureAndRestoresOrder(t *testing.T) {
	fixtureBytes, err := os.ReadFile(filepath.Join("testdata", "root_dns_policy_projection.json"))
	if err != nil {
		t.Fatal(err)
	}
	var shared struct {
		Projection struct {
			Config              map[string]any             `json:"config"`
			Probe               RootRouteProbeRuleSpec     `json:"rootRouteProbeRule"`
			Sniff               RootRouteSniffRuleSpec     `json:"rootRouteSniffRule"`
			IPVersion           RootRouteIPVersionRuleSpec `json:"rootRouteIPVersionRule"`
			Resolve             RootRouteResolveRuleSpec   `json:"rootRouteResolveRule"`
			ExpectedLegacyRules []any                      `json:"expectedLegacyRules"`
			ExpectedRules       []any                      `json:"expectedRules"`
		} `json:"routeResolveRuleProjection"`
	}
	if err := json.Unmarshal(fixtureBytes, &shared); err != nil {
		t.Fatal(err)
	}
	route := shared.Projection.Config["route"].(map[string]any)
	route["rules"] = shared.Projection.ExpectedLegacyRules
	config, err := json.Marshal(shared.Projection.Config)
	if err != nil {
		t.Fatal(err)
	}
	_, snapshot := fixture(t)
	snapshot.SchemaVersion = SnapshotVersionRouteResolveRule
	snapshot.Config = config
	snapshot.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500, IPv6Mode: 0}
	snapshot.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	snapshot.RootRoutePolicy = &RootRoutePolicy{AutoDetectInterface: true, FindProcess: true}
	snapshot.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote"}
	snapshot.RootDNSLocalServer = &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"}
	snapshot.RootDNSDirectServer = &RootDNSDirectServerSpec{Tag: "dns-direct"}
	snapshot.RootRouteFinal = &RootRouteFinalSpec{Final: "direct"}
	snapshot.RootRouteProbeRule = &shared.Projection.Probe
	snapshot.RootRouteSniffRule = &shared.Projection.Sniff
	snapshot.RootRouteIPVersionRule = &shared.Projection.IPVersion
	snapshot.RootRouteResolveRule = &shared.Projection.Resolve
	configWithDNS := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &configWithDNS); err != nil {
		t.Fatal(err)
	}
	configWithDNS["dns"] = map[string]any{"servers": []any{map[string]any{"type": "https", "tag": "dns-remote"}}}
	snapshot.Config, err = json.Marshal(configWithDNS)
	if err != nil {
		t.Fatal(err)
	}
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("valid projected resolve route rejected: %v", err)
	}
	gotBytes, err := materializeRootInbound(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(gotBytes, &got); err != nil {
		t.Fatal(err)
	}
	gotRules := got["route"].(map[string]any)["rules"].([]any)
	if !jsonEqual(gotRules, shared.Projection.ExpectedRules) {
		t.Fatalf("materialized resolve route order differs: got %#v want %#v", gotRules, shared.Projection.ExpectedRules)
	}
}

func TestStructuredRouteModePairMatchesSharedFixtureAndRestoresFullOrder(t *testing.T) {
	fixtureBytes, err := os.ReadFile(filepath.Join("testdata", "root_dns_policy_projection.json"))
	if err != nil {
		t.Fatal(err)
	}
	var shared struct {
		Projection struct {
			Config              map[string]any             `json:"config"`
			Probe               RootRouteProbeRuleSpec     `json:"rootRouteProbeRule"`
			Sniff               RootRouteSniffRuleSpec     `json:"rootRouteSniffRule"`
			IPVersion           RootRouteIPVersionRuleSpec `json:"rootRouteIPVersionRule"`
			Resolve             RootRouteResolveRuleSpec   `json:"rootRouteResolveRule"`
			ModeRules           RootRouteModeRulesSpec     `json:"rootRouteModeRules"`
			ExpectedLegacyRules []any                      `json:"expectedLegacyRules"`
			ExpectedRules       []any                      `json:"expectedRules"`
		} `json:"routeModeRulesProjection"`
	}
	if err := json.Unmarshal(fixtureBytes, &shared); err != nil {
		t.Fatal(err)
	}
	route := shared.Projection.Config["route"].(map[string]any)
	route["rules"] = shared.Projection.ExpectedLegacyRules
	config, err := json.Marshal(shared.Projection.Config)
	if err != nil {
		t.Fatal(err)
	}
	_, snapshot := fixture(t)
	snapshot.SchemaVersion = SnapshotVersionRouteModeRules
	snapshot.Config = config
	snapshot.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500, IPv6Mode: 0}
	snapshot.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	snapshot.RootRoutePolicy = &RootRoutePolicy{AutoDetectInterface: true, FindProcess: true}
	snapshot.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote"}
	snapshot.RootDNSLocalServer = &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"}
	snapshot.RootDNSDirectServer = &RootDNSDirectServerSpec{Tag: "dns-direct"}
	snapshot.RootRouteFinal = &RootRouteFinalSpec{Final: "direct"}
	snapshot.RootRouteProbeRule = &shared.Projection.Probe
	snapshot.RootRouteSniffRule = &shared.Projection.Sniff
	snapshot.RootRouteIPVersionRule = &shared.Projection.IPVersion
	snapshot.RootRouteResolveRule = &shared.Projection.Resolve
	snapshot.RootRouteModeRules = &shared.Projection.ModeRules
	configWithDNS := map[string]any{}
	if err := json.Unmarshal(snapshot.Config, &configWithDNS); err != nil {
		t.Fatal(err)
	}
	configWithDNS["dns"] = map[string]any{"servers": []any{map[string]any{"type": "https", "tag": "dns-remote"}}}
	snapshot.Config, err = json.Marshal(configWithDNS)
	if err != nil {
		t.Fatal(err)
	}
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("valid mode-pair snapshot rejected: %v", err)
	}
	gotBytes, err := materializeRootInbound(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(gotBytes, &got); err != nil {
		t.Fatal(err)
	}
	gotRules := got["route"].(map[string]any)["rules"].([]any)
	if !jsonEqual(gotRules, shared.Projection.ExpectedRules) {
		t.Fatalf("materialized mode-pair route order differs: got %#v want %#v", gotRules, shared.Projection.ExpectedRules)
	}
}

func TestStructuredRouteModePairRejectsInvalidAndDuplicate(t *testing.T) {
	_, snapshot := fixture(t)
	snapshot.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500}
	snapshot.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	config := map[string]any{"outbounds": []any{map[string]any{"type": "direct", "tag": "direct"}}, "route": map[string]any{"rules": []any{map[string]any{"domain": []any{"example.org"}, "outbound": "direct"}}}}
	if err := validateRootRouteModeRules(&RootRouteModeRulesSpec{Index: -1}, config, snapshot); err == nil || err.Error() != "root_route_mode_rules_invalid" {
		t.Fatalf("negative mode-pair index accepted: %v", err)
	}
	snapshot.RootRouteProbeRule = &RootRouteProbeRuleSpec{Index: 0}
	if err := validateRootRouteModeRules(&RootRouteModeRulesSpec{Index: 3}, config, snapshot); err == nil || err.Error() != "root_route_mode_rules_invalid" {
		t.Fatalf("out-of-range mode-pair index accepted: %v", err)
	}
	config["route"].(map[string]any)["rules"] = []any{map[string]any{"clash_mode": "global", "outbound": "proxy"}}
	if err := validateRootRouteModeRules(&RootRouteModeRulesSpec{Index: 0}, config, snapshot); err == nil || err.Error() != "root_route_mode_rules_duplicate" {
		t.Fatalf("legacy mode rule was not rejected as a duplicate: %v", err)
	}
}

func TestStructuredRouteResolveRejectsInvalidAndDuplicate(t *testing.T) {
	config := map[string]any{"route": map[string]any{"rules": []any{}}}
	if err := validateRootRouteResolveRule(&RootRouteResolveRuleSpec{Strategy: "bad"}, config); err == nil || err.Error() != "root_route_resolve_invalid" {
		t.Fatalf("invalid resolve strategy accepted: %v", err)
	}
	config["route"].(map[string]any)["rules"] = []any{map[string]any{"action": "resolve", "strategy": "prefer_ipv6"}}
	if err := validateRootRouteResolveRule(&RootRouteResolveRuleSpec{Strategy: "prefer_ipv4"}, config); err == nil || err.Error() != "root_route_resolve_duplicate" {
		t.Fatalf("duplicate resolve action with different strategy accepted: %v", err)
	}
}

func TestStructuredRouteProbeRejectsInvalidAndDuplicate(t *testing.T) {
	config := map[string]any{"outbounds": []any{map[string]any{"tag": "proxy"}}, "route": map[string]any{"rules": []any{}}}
	snapshot := Snapshot{}
	if err := validateRootRouteProbeRule(&RootRouteProbeRuleSpec{Index: 1, Host: "probe.example", Outbound: "proxy"}, config, snapshot); err == nil || err.Error() != "root_route_probe_invalid" {
		t.Fatalf("non-first probe index accepted: %v", err)
	}
	if err := validateRootRouteProbeRule(&RootRouteProbeRuleSpec{Index: 0, Host: "probe example", Outbound: "proxy"}, config, snapshot); err == nil || err.Error() != "root_route_probe_invalid" {
		t.Fatalf("invalid probe host accepted: %v", err)
	}
	if err := validateRootRouteProbeRule(&RootRouteProbeRuleSpec{Index: 0, Host: "probe.example", Outbound: "missing"}, config, snapshot); err == nil || err.Error() != "root_route_probe_invalid" {
		t.Fatalf("missing outbound accepted: %v", err)
	}
	config["route"].(map[string]any)["rules"] = []any{map[string]any{"inbound": []any{"mixed"}, "domain": []any{"probe.example"}, "outbound": "proxy"}}
	if err := validateRootRouteProbeRule(&RootRouteProbeRuleSpec{Index: 0, Host: "probe.example", Outbound: "proxy"}, config, snapshot); err == nil || err.Error() != "root_route_probe_duplicate" {
		t.Fatalf("duplicate probe rule accepted: %v", err)
	}
}

func TestStructuredRouteSniffRejectsInvalidAndDuplicate(t *testing.T) {
	config := map[string]any{"route": map[string]any{"rules": []any{map[string]any{"action": "reject"}}}}
	if err := validateRootRouteSniffRule(&RootRouteSniffRuleSpec{Index: 2}, config, Snapshot{}); err == nil || err.Error() != "root_route_sniff_invalid" {
		t.Fatalf("out-of-range sniff index accepted: %v", err)
	}
	config["route"].(map[string]any)["rules"] = []any{map[string]any{"action": "sniff"}}
	if err := validateRootRouteSniffRule(&RootRouteSniffRuleSpec{Index: 0}, config, Snapshot{}); err == nil || err.Error() != "root_route_sniff_duplicate" {
		t.Fatalf("duplicate exact sniff rule accepted: %v", err)
	}
}

func TestStructuredRoutePrivateBypassRejectsInvalidDuplicate(t *testing.T) {
	config := map[string]any{"route": map[string]any{"rules": []any{map[string]any{"action": "sniff"}}}}
	if err := validateRootRoutePrivateBypass(&RootRoutePrivateBypassSpec{Index: 2}, config, Snapshot{}); err == nil || err.Error() != "root_route_private_bypass_invalid" {
		t.Fatalf("out-of-range index accepted: %v", err)
	}
	config["route"].(map[string]any)["rules"] = []any{map[string]any{"ip_is_private": true, "outbound": "bypass"}}
	if err := validateRootRoutePrivateBypass(&RootRoutePrivateBypassSpec{Index: 1}, config, Snapshot{}); err == nil || err.Error() != "root_route_private_bypass_duplicate" {
		t.Fatalf("duplicate exact rule accepted: %v", err)
	}
}

func TestStructuredRouteDNSProtocolHijackMatchesSharedFixtureAndRestoresOrder(t *testing.T) {
	fixtureBytes, err := os.ReadFile(filepath.Join("testdata", "root_dns_policy_projection.json"))
	if err != nil {
		t.Fatal(err)
	}
	var shared struct {
		Projection struct {
			Config                                   map[string]any                     `json:"config"`
			RootRule                                 RootRouteDNSProtocolHijackRuleSpec `json:"rootRouteDNSProtocolHijackRule"`
			ExpectedLegacyRules                      []any                              `json:"expectedLegacyRules"`
			ExpectedLegacyRulesAfterPriorProjections []any                              `json:"expectedLegacyRulesAfterPriorProjections"`
			ExpectedRules                            []any                              `json:"expectedRules"`
		} `json:"routeDNSProtocolHijackRuleProjection"`
	}
	if err := json.Unmarshal(fixtureBytes, &shared); err != nil {
		t.Fatal(err)
	}
	route := shared.Projection.Config["route"].(map[string]any)
	route["rules"] = shared.Projection.ExpectedLegacyRulesAfterPriorProjections
	configBytes, err := json.Marshal(shared.Projection.Config)
	if err != nil {
		t.Fatal(err)
	}
	_, snapshot := fixture(t)
	config := map[string]any{}
	if err := json.Unmarshal(configBytes, &config); err != nil {
		t.Fatal(err)
	}
	config["dns"] = map[string]any{"servers": []any{map[string]any{"type": "https", "tag": "dns-remote"}}}
	configBytes, err = json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	snapshot.SchemaVersion = SnapshotVersionRouteDNSProtocolHijackRule
	snapshot.Config = configBytes
	snapshot.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500, IPv6Mode: 0}
	snapshot.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	snapshot.RootRoutePolicy = &RootRoutePolicy{AutoDetectInterface: true, FindProcess: true}
	snapshot.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote"}
	snapshot.RootDNSLocalServer = &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"}
	snapshot.RootDNSDirectServer = &RootDNSDirectServerSpec{Tag: "dns-direct"}
	snapshot.RootRouteFinal = &RootRouteFinalSpec{Final: "direct"}
	snapshot.RootRouteIPVersionRule = &RootRouteIPVersionRuleSpec{Index: 0, IPVersion: 6}
	snapshot.RootRouteDNSProtocolHijackRule = &shared.Projection.RootRule
	if err := CheckSnapshot(snapshot); err != nil {
		t.Fatalf("valid protocol DNS hijack projection rejected: %v", err)
	}
	gotBytes, err := materializeRootInbound(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(gotBytes, &got); err != nil {
		t.Fatal(err)
	}
	gotRules := got["route"].(map[string]any)["rules"].([]any)
	if !jsonEqual(gotRules, shared.Projection.ExpectedRules) {
		t.Fatalf("materialized rules differ from shared projection fixture: got %#v want %#v", gotRules, shared.Projection.ExpectedRules)
	}
}

func TestStructuredRouteDNSProtocolHijackRejectsInvalidIndexAndDuplicate(t *testing.T) {
	config := map[string]any{"route": map[string]any{"rules": []any{map[string]any{"action": "sniff"}}}}
	if err := validateRootRouteDNSProtocolHijackRule(&RootRouteDNSProtocolHijackRuleSpec{Index: 2}, config, Snapshot{}); err == nil || err.Error() != "root_route_dns_protocol_hijack_invalid" {
		t.Fatalf("out-of-range index accepted: %v", err)
	}
	config["route"].(map[string]any)["rules"] = []any{map[string]any{"protocol": []any{"dns"}, "action": "hijack-dns"}}
	if err := validateRootRouteDNSProtocolHijackRule(&RootRouteDNSProtocolHijackRuleSpec{Index: 1}, config, Snapshot{}); err == nil || err.Error() != "root_route_dns_protocol_hijack_duplicate" {
		t.Fatalf("duplicate exact rule accepted: %v", err)
	}
}

func TestStructuredRouteDNSHijackPort53RejectsInvalidIndexAndDuplicate(t *testing.T) {
	config := map[string]any{"route": map[string]any{"rules": []any{map[string]any{"action": "sniff"}}}}
	if err := validateRootRouteDNSHijackPort53Rule(&RootRouteDNSHijackPort53RuleSpec{Index: 2}, config, Snapshot{}); err == nil || err.Error() != "root_route_dns_hijack_port53_invalid" {
		t.Fatalf("out-of-range index accepted: %v", err)
	}
	config["route"].(map[string]any)["rules"] = []any{map[string]any{"port": []any{float64(53)}, "action": "hijack-dns"}}
	if err := validateRootRouteDNSHijackPort53Rule(&RootRouteDNSHijackPort53RuleSpec{Index: 1}, config, Snapshot{}); err == nil || err.Error() != "root_route_dns_hijack_port53_duplicate" {
		t.Fatalf("duplicate exact rule accepted: %v", err)
	}
}

func TestStructuredRouteIPVersionRuleRejectsInvalidMismatchAndDuplicate(t *testing.T) {
	config := map[string]any{"route": map[string]any{"rules": []any{map[string]any{"action": "sniff"}}}}
	snapshot := Snapshot{RootInbound: &RootInboundSpec{Backend: "tun", MTU: 1500, IPv6Mode: 0}}
	if err := validateRootRouteIPVersionRule(&RootRouteIPVersionRuleSpec{Index: 2, IPVersion: 6}, config, snapshot); err == nil || err.Error() != "root_route_ip_version_invalid" {
		t.Fatalf("out-of-range index accepted: %v", err)
	}
	if err := validateRootRouteIPVersionRule(&RootRouteIPVersionRuleSpec{Index: 0, IPVersion: 4}, config, snapshot); err == nil || err.Error() != "root_route_ip_version_invalid" {
		t.Fatalf("mismatched single-stack mode accepted: %v", err)
	}
	snapshot.RootInbound.IPv6Mode = 1
	if err := validateRootRouteIPVersionRule(&RootRouteIPVersionRuleSpec{Index: 0, IPVersion: 6}, config, snapshot); err == nil || err.Error() != "root_route_ip_version_invalid" {
		t.Fatalf("dual-stack mode accepted a single-stack rule: %v", err)
	}
	snapshot.RootInbound.IPv6Mode = 0
	config["route"].(map[string]any)["rules"] = []any{map[string]any{"ip_version": float64(6), "action": "reject"}}
	if err := validateRootRouteIPVersionRule(&RootRouteIPVersionRuleSpec{Index: 0, IPVersion: 6}, config, snapshot); err == nil || err.Error() != "root_route_ip_version_duplicate" {
		t.Fatalf("duplicate exact rule accepted: %v", err)
	}
}

func TestStructuredRouteDNSProtocolRuleMatchesSharedProjectionFixtureAndRestoresOrder(t *testing.T) {
	fixtureBytes, err := os.ReadFile(filepath.Join("testdata", "root_dns_policy_projection.json"))
	if err != nil {
		t.Fatal(err)
	}
	var shared struct {
		Projection struct {
			Config              map[string]any               `json:"config"`
			RootProtocol        RootRouteDNSProtocolRuleSpec `json:"rootRouteDNSProtocolRule"`
			RootPort53          RootRouteDNSPort53RuleSpec   `json:"rootRouteDNSPort53Rule"`
			RootMulticast       RootRouteMulticastSpec       `json:"rootRouteMulticast"`
			ExpectedLegacyRules []any                        `json:"expectedLegacyRules"`
			ExpectedRules       []any                        `json:"expectedRules"`
		} `json:"routeDNSProtocolRuleProjection"`
	}
	if err := json.Unmarshal(fixtureBytes, &shared); err != nil {
		t.Fatal(err)
	}
	route := shared.Projection.Config["route"].(map[string]any)
	route["rules"] = shared.Projection.ExpectedLegacyRules
	outbounds := shared.Projection.Config["outbounds"].([]any)
	legacy := make([]any, 0, len(outbounds)-1)
	for _, raw := range outbounds {
		if raw.(map[string]any)["tag"] != "proxy" {
			legacy = append(legacy, raw)
		}
	}
	shared.Projection.Config["outbounds"] = legacy
	shared.Projection.Config["dns"] = map[string]any{"servers": []any{map[string]any{"type": "https", "tag": "dns-remote", "server": "dns.example"}}, "rules": []any{}}
	configBytes, err := json.Marshal(shared.Projection.Config)
	if err != nil {
		t.Fatal(err)
	}
	_, s := fixture(t)
	s.SchemaVersion = SnapshotVersionRouteDNSProtocolRule
	s.Config = configBytes
	s.RootInbound = &RootInboundSpec{Backend: "tun", MTU: 1500}
	s.RootProxyGroup = &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}
	s.RootDNSPolicy = &RootDNSPolicySpec{Final: "dns-remote"}
	s.RootDNSLocalServer = &RootDNSLocalServerSpec{Tag: "dns-local", Detour: "direct"}
	s.RootDNSDirectServer = &RootDNSDirectServerSpec{Tag: "dns-direct"}
	s.RootRouteFinal = &RootRouteFinalSpec{Final: "direct"}
	s.RootRouteDNSPort53Rule = &shared.Projection.RootPort53
	s.RootRouteDNSProtocolRule = &shared.Projection.RootProtocol
	s.RootRouteMulticast = &shared.Projection.RootMulticast
	if err := CheckSnapshot(s); err != nil {
		t.Fatalf("valid DNS route projections rejected: %v", err)
	}
	gotBytes, err := materializeRootInbound(s)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(gotBytes, &got); err != nil {
		t.Fatal(err)
	}
	gotRules := got["route"].(map[string]any)["rules"].([]any)
	if !jsonEqual(gotRules, shared.Projection.ExpectedRules) {
		t.Fatalf("materialized route order differs: got %#v want %#v", gotRules, shared.Projection.ExpectedRules)
	}
}

func TestStructuredRouteDNSProtocolRuleRejectsInvalidAndDuplicateCases(t *testing.T) {
	snapshot := Snapshot{RootProxyGroup: &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}}
	config := map[string]any{"route": map[string]any{"rules": []any{map[string]any{"action": "sniff"}}}}
	if err := validateRootRouteDNSProtocolRule(&RootRouteDNSProtocolRuleSpec{Index: 2, Outbound: "proxy"}, config, snapshot); err == nil || err.Error() != "root_route_dns_protocol_invalid" {
		t.Fatalf("out of range index accepted: %v", err)
	}
	if err := validateRootRouteDNSProtocolRule(&RootRouteDNSProtocolRuleSpec{Index: 0, Outbound: "missing"}, config, snapshot); err == nil || err.Error() != "root_route_dns_protocol_invalid" {
		t.Fatalf("unknown outbound accepted: %v", err)
	}
	config["route"].(map[string]any)["rules"] = []any{map[string]any{"protocol": []any{"dns"}, "outbound": "proxy"}}
	if err := validateRootRouteDNSProtocolRule(&RootRouteDNSProtocolRuleSpec{Index: 0, Outbound: "proxy"}, config, snapshot); err == nil || err.Error() != "root_route_dns_protocol_duplicate" {
		t.Fatalf("duplicate accepted: %v", err)
	}
}

func TestStructuredRouteDNSPort53RuleRejectsInvalidAndDuplicateCases(t *testing.T) {
	snapshot := Snapshot{RootProxyGroup: &RootProxyGroupSpec{Type: "selector", Tag: "proxy", Members: []string{"direct"}, Default: "direct"}}
	config := map[string]any{"route": map[string]any{"rules": []any{map[string]any{"action": "sniff"}}}}
	if err := validateRootRouteDNSPort53Rule(&RootRouteDNSPort53RuleSpec{Index: 2, Outbound: "proxy"}, config, snapshot); err == nil || err.Error() != "root_route_dns_port53_invalid" {
		t.Fatalf("out of range index accepted: %v", err)
	}
	if err := validateRootRouteDNSPort53Rule(&RootRouteDNSPort53RuleSpec{Index: 0, Outbound: "missing"}, config, snapshot); err == nil || err.Error() != "root_route_dns_port53_invalid" {
		t.Fatalf("unknown outbound accepted: %v", err)
	}
	config["route"].(map[string]any)["rules"] = []any{map[string]any{"port": []any{float64(53)}, "outbound": "proxy"}}
	if err := validateRootRouteDNSPort53Rule(&RootRouteDNSPort53RuleSpec{Index: 0, Outbound: "proxy"}, config, snapshot); err == nil || err.Error() != "root_route_dns_port53_duplicate" {
		t.Fatalf("duplicate legacy rule accepted: %v", err)
	}
	config["route"].(map[string]any)["rules"] = "malformed"
	if err := validateRootRouteDNSPort53Rule(&RootRouteDNSPort53RuleSpec{Index: 0, Outbound: "proxy"}, config, snapshot); err == nil || err.Error() != "root_route_dns_port53_invalid" {
		t.Fatalf("malformed route rules accepted: %v", err)
	}
}

func TestStructuredRouteDNSTargetRulesMatchSharedProjectionFixtureAndAnchorBeforeFallback(t *testing.T) {
	fixtureBytes, err := os.ReadFile("testdata/root_dns_policy_projection.json")
	if err != nil {
		t.Fatal(err)
	}
	var file map[string]json.RawMessage
	if err := json.Unmarshal(fixtureBytes, &file); err != nil {
		t.Fatal(err)
	}
	var shared struct {
		Config     map[string]any              `json:"config"`
		Root       RootRouteDNSTargetRulesSpec `json:"rootRouteDnsTargetRules"`
		Expected   []any                       `json:"expectedAfterProjectionRules"`
		Normalized struct {
			DirectDomains  []string `json:"directDomains"`
			OverlapRemoved []string `json:"overlapRemoved"`
			RemoteDomains  []string `json:"remoteDomains"`
		} `json:"normalizedDnsTargets"`
	}
	if err := json.Unmarshal(file["routeDNSTargetRulesProjection"], &shared); err != nil {
		t.Fatal(err)
	}
	if !jsonEqual(shared.Normalized.DirectDomains, []string{"dns.local"}) || !jsonEqual(shared.Normalized.OverlapRemoved, []string{"shared.example"}) || !jsonEqual(shared.Normalized.RemoteDomains, []string{"dns.google", "shared.example"}) {
		t.Fatalf("fixture no longer records DNS endpoint normalization and direct-domain overlap: %#v", shared.Normalized)
	}
	route := shared.Config["route"].(map[string]any)
	want := append([]any(nil), route["rules"].([]any)...)
	route["rules"] = shared.Expected
	snapshot := Snapshot{
		RootInbound:              &RootInboundSpec{Backend: "tun", MTU: 1500, IPv6Mode: 1},
		RootRouteDNSPort53Rule:   &RootRouteDNSPort53RuleSpec{Index: 10, Outbound: "proxy"},
		RootRouteDNSProtocolRule: &RootRouteDNSProtocolRuleSpec{Index: 11, Outbound: "proxy"},
	}
	if err := materializeRootRouteDNSTargetRules(route, &shared.Root, shared.Config, snapshot); err != nil {
		t.Fatalf("valid shared DNS-target projection rejected: %v", err)
	}
	got := route["rules"].([]any)
	if !jsonEqual(got, want) {
		t.Fatalf("materialized target block did not return immediately before DNS fallback guards: got %#v want %#v", got, want)
	}

	invalid := shared.Root
	invalid.Index = -1
	if err := validateRootRouteDNSTargetRules(&invalid, shared.Config, snapshot); err == nil {
		t.Fatal("negative source index accepted")
	}
	invalid = shared.Root
	invalid.Rules = append([]RootRouteDNSTargetRuleSpec(nil), shared.Root.Rules...)
	invalid.Rules[1].Targets = []string{"192.0.2.53/32", "2001:db8::53/128"}
	snapshot.RootInbound.IPv6Mode = 0
	if err := validateRootRouteDNSTargetRules(&invalid, shared.Config, snapshot); err == nil {
		t.Fatal("IPv6 target accepted in IPv6-disabled mode")
	}
	snapshot.RootInbound.IPv6Mode = 1

	duplicateRules := append([]any(nil), shared.Expected...)
	duplicateRules = append(duplicateRules, map[string]any{"domain": []any{"dns.local"}, "outbound": "direct"})
	duplicateConfig := map[string]any{"outbounds": []any{map[string]any{"tag": "direct"}, map[string]any{"tag": "proxy"}}, "route": map[string]any{"rules": duplicateRules}}
	if err := validateRootRouteDNSTargetRules(&shared.Root, duplicateConfig, snapshot); err == nil || err.Error() != "root_route_dns_targets_duplicate" {
		t.Fatalf("duplicate legacy DNS-target rule was not rejected: %v", err)
	}

	brokenRoute := map[string]any{"rules": append([]any(nil), shared.Expected...)}
	brokenRules := brokenRoute["rules"].([]any)
	brokenRules[7].(map[string]any)["outbound"] = "direct"
	if err := materializeRootRouteDNSTargetRules(brokenRoute, &shared.Root, shared.Config, snapshot); err == nil || err.Error() != "root_route_dns_targets_fallback_invalid" {
		t.Fatalf("nonadjacent fallback pair accepted: %v", err)
	}
}
