// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"archive/tar"
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// B5（N2）红测：脱敏规则以 openspec/changes/borrow-netproxy-nightly/design.md §2 为准。
// 全部用例均为虚构数据（example.com / TEST-NET-3 / 文档用 IPv6 前缀），不得写入真实凭据。

func TestRedactSubscriptionAndNodeLinks(t *testing.T) {
	cases := []struct{ in, want string }{
		{"订阅地址 https://example.com/sub?token=abc 结束", "订阅地址 [订阅链接已隐藏] 结束"},
		{"http://192.0.2.10/plain", "[订阅链接已隐藏]"},
		{"vmess://eyJ2IjoiMiJ9", "[节点链接已隐藏]"},
		{"vless://11111111-2222-3333-4444-555555555555@203.0.113.7:443?security=tls", "[节点链接已隐藏]"},
		{"trojan://secretpass@203.0.113.7:443", "[节点链接已隐藏]"},
		{"ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ@203.0.113.7:8388", "[节点链接已隐藏]"},
		{"hysteria2://pass@203.0.113.7:443", "[节点链接已隐藏]"},
		{"tuic://uuid:pass@203.0.113.7:443", "[节点链接已隐藏]"},
		{"juicity://uuid:pass@203.0.113.7:443", "[节点链接已隐藏]"},
	}
	for _, c := range cases {
		if got := RedactText(c.in); got != c.want {
			t.Errorf("RedactText(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestRedactURLCredentials(t *testing.T) {
	// 完整 URL 随后会被全文隐藏；这里断言凭据不外泄、占位符出现。
	got := RedactText("GET https://user:pass123@example.com/path?token=abc&key=def&mode=fast")
	if strings.Contains(got, "pass123") || strings.Contains(got, "token=abc") || strings.Contains(got, "key=def") {
		t.Errorf("URL credentials leaked: %q", got)
	}
	if !strings.Contains(got, "[订阅链接已隐藏]") {
		t.Errorf("subscription URL not hidden: %q", got)
	}
	// 无 scheme 的 query 片段：敏感值打码，非敏感参数保留。
	got = RedactText("callback?token=abc&key=def&mode=fast&uuid=11111111-2222-3333-4444-555555555555")
	if strings.Contains(got, "token=abc") || strings.Contains(got, "key=def") || strings.Contains(got, "uuid=11111111") {
		t.Errorf("query credentials leaked: %q", got)
	}
	if !strings.Contains(got, "token=***") || !strings.Contains(got, "key=***") || !strings.Contains(got, "mode=fast") {
		t.Errorf("query redaction wrong: %q", got)
	}
	for _, key := range []string{"secret", "password", "auth", "hwid"} {
		got := RedactText("callback?" + key + "=s3cr3t&ok=1")
		if strings.Contains(got, "s3cr3t") || !strings.Contains(got, key+"=***") || !strings.Contains(got, "ok=1") {
			t.Errorf("query key %s not redacted: %q", key, got)
		}
	}
}

func TestRedactAuthHeadersAndLineSecrets(t *testing.T) {
	got := RedactText("Authorization: Bearer abcdef123456\nProxy-Authorization: Basic dXNlcjpwYXNz")
	if strings.Contains(got, "abcdef123456") || strings.Contains(got, "dXNlcjpwYXNz") {
		t.Errorf("auth header leaked: %q", got)
	}
	if !strings.Contains(got, "Authorization: Bearer ***") || !strings.Contains(got, "Proxy-Authorization: Basic ***") {
		t.Errorf("auth header redaction wrong: %q", got)
	}
	for _, line := range []string{
		"token=abcdef", "password: hunter2", "hwid=00-11-22-33", `uuid="11111111-2222-3333-4444-555555555555"`,
		"psk: sharedsecret", "private_key=-----BEGIN", "short_id=abcd1234",
	} {
		got := RedactText(line)
		if strings.Contains(got, "abcdef") || strings.Contains(got, "hunter2") || strings.Contains(got, "00-11-22-33") ||
			strings.Contains(got, "11111111") || strings.Contains(got, "sharedsecret") || strings.Contains(got, "BEGIN") || strings.Contains(got, "abcd1234") {
			t.Errorf("line secret leaked for %q: %q", line, got)
		}
		if !strings.Contains(got, "***") {
			t.Errorf("line secret not masked for %q: %q", line, got)
		}
	}
}

func TestRedactSensitiveJSONKeys(t *testing.T) {
	in := `{
  "type": "vmess", "tag": "proxy", "server_port": 443,
  "server": "203.0.113.7", "address": "203.0.113.8", "server_name": "example.com",
  "uuid": "11111111-2222-3333-4444-555555555555", "password": "hunter2",
  "url": "https://example.com/sub?token=abc",
  "auth": {"token": "tok", "api_key": "key123"},
  "headers": {"Authorization": "Bearer xyz"},
  "tls": {"private_key": "keydata", "public_key": "pubdata", "short_id": "abcd"},
  "nested": [{"secret": "s", "keep": "yes"}]
}`
	got := RedactText(in)
	for _, leaked := range []string{
		"203.0.113.7", "203.0.113.8", "11111111-2222-3333-4444-555555555555", "hunter2",
		"example.com/sub", "tok", "key123", "Bearer xyz", "keydata", "pubdata", "abcd",
	} {
		if strings.Contains(got, leaked) {
			t.Errorf("JSON secret leaked %q in: %s", leaked, got)
		}
	}
	for _, kept := range []string{`"type": "vmess"`, `"tag": "proxy"`, `"server_port": 443`, `"keep": "yes"`} {
		if !strings.Contains(got, kept) {
			t.Errorf("JSON non-sensitive field lost %q in: %s", kept, got)
		}
	}
	if !strings.Contains(got, `"server": "***"`) || !strings.Contains(got, `"address": "***"`) ||
		!strings.Contains(got, `"server_name": "***"`) || !strings.Contains(got, `"url": "***"`) {
		t.Errorf("server/url keys not masked to ***: %s", got)
	}
	// 键名大小写与 -/_ 等价。
	got = RedactText(`{"Access-Token": "abc", "PRIVATE_KEY": "k", "Auth_Str": "s"}`)
	if strings.Contains(got, "abc") || strings.Contains(got, `"k"`) || strings.Contains(got, `"s"`) {
		t.Errorf("case-insensitive JSON keys leaked: %s", got)
	}
}

func TestRedactSSIDLists(t *testing.T) {
	in := `{"wifi": {"blacklist": ["HomeWiFi", "Office WiFi"], "whitelist": ["Cafe, Guest"]}, "ssid": "MyHome"}`
	got := RedactText(in)
	for _, ssid := range []string{"HomeWiFi", "Office WiFi", "Cafe, Guest", "MyHome"} {
		if strings.Contains(got, ssid) {
			t.Errorf("SSID leaked %q in: %s", ssid, got)
		}
	}
	// 条数保留：两个黑名单条目仍是两个 ***。
	if strings.Count(got, `"***"`) < 4 {
		t.Errorf("SSID entries not kept per-item: %s", got)
	}
}

func TestRedactAppLists(t *testing.T) {
	in := `{"proxy_apps": ["com.example.a", "com.example.b"], "bypass_apps": ["com.example.c"], "include_package": "com.example.d", "exclude_package": "com.example.e"}`
	got := RedactText(in)
	for _, pkg := range []string{"com.example.a", "com.example.b", "com.example.c", "com.example.d", "com.example.e"} {
		if strings.Contains(got, pkg) {
			t.Errorf("package name leaked %q in: %s", pkg, got)
		}
	}
}

func TestRedactIPAddresses(t *testing.T) {
	cases := []struct{ in, want string }{
		{"peer 203.0.113.7:8080", "peer 203.0.x.x:8080"},
		{"peer 198.51.100.23", "peer 198.51.x.x"},
		{"dns 8.8.8.8", "dns 8.8.x.x"},
		{"gateway 192.168.1.1", "gateway <private>"},
		{"client 10.1.2.3", "client <private>"},
		{"client 172.16.0.9", "client <private>"},
		{"loop 127.0.0.1:9090", "loop <loopback>:9090"},
		{"link 169.254.10.20", "link <link-local>"},
		{"cgnat 100.64.0.5", "cgnat <cgnat>"},
		{"v6 2001:db8:85a3:8d3:1319:8a2e:370:7348", "v6 2001:db8:xxxx:xxxx::"},
		{"v6 2001:db8::1", "v6 2001:db8:xxxx:xxxx::"},
		{"v6 ::1", "v6 <loopback>"},
		{"v6 fe80::1", "v6 <link-local>"},
		{"v6 fc00::1", "v6 <private>"},
		{"v6 fd12:3456::9", "v6 <private>"},
	}
	for _, c := range cases {
		if got := RedactText(c.in); got != c.want {
			t.Errorf("RedactText(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestRedactMACAddresses(t *testing.T) {
	got := RedactText("client mac aa:bb:cc:dd:ee:ff connected")
	if strings.Contains(got, "aa:bb:cc") || !strings.Contains(got, "**:**:**:**:**:**") {
		t.Errorf("MAC not redacted: %q", got)
	}
}

func TestRedactMessageTruncates(t *testing.T) {
	short := RedactMessage("hello world")
	if short != "hello world" {
		t.Errorf("short message changed: %q", short)
	}
	long := RedactMessage("word  " + strings.Repeat("x", 2000))
	if runes := []rune(long); len(runes) != 1025 || !strings.HasSuffix(long, "…") {
		t.Errorf("long message not truncated to 1024 runes + …: len=%d suffix=%q", len(runes), long[len(long)-3:])
	}
	if strings.Contains(long, "  ") {
		t.Errorf("message whitespace not collapsed: %q", long[:40])
	}
}

// --- 导出行为 ---

const diagnosticsFixtureRev = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

func diagnosticsFixture(t *testing.T) (*Runtime, string, string) {
	t.Helper()
	root := t.TempDir()
	moduleDir := t.TempDir()
	if err := os.WriteFile(filepath.Join(moduleDir, "module.prop"),
		[]byte("id=wanbox\nversion=3.0.17-preview.1\nversionCode=2031\n"), 0600); err != nil {
		t.Fatal(err)
	}
	rev := diagnosticsFixtureRev
	gen := filepath.Join(root, "generations", rev)
	for _, dir := range []string{
		filepath.Join(gen, "run"), filepath.Join(gen, "assets", "srs"), filepath.Join(root, "logs"),
	} {
		if err := os.MkdirAll(dir, 0700); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(root, "current"), []byte(rev+"\n"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "state.json"),
		[]byte(`{"schemaVersion":1,"phase":"stopped","profileName":"Example Node","revision":"`+rev+`"}`), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "events.json"),
		[]byte(`[{"time":1793000000,"code":"node_hot_switch_ms:420"}]`), 0600); err != nil {
		t.Fatal(err)
	}
	// Root 快照：含凭据的 config、cache.db 与证书文件（导出时必须剔除/打码）。
	snapshot := `{"schemaVersion":1,"expectedRevision":"` + rev + `","profileId":7,"profileName":"Example Node",` +
		`"config":{"log":{"level":"info"},"outbounds":[{"type":"vmess","tag":"proxy","server":"203.0.113.7","server_port":443,"uuid":"11111111-2222-3333-4444-555555555555"}]},` +
		`"files":{"assets/geoip-cn.srs":"QUJD","cache.db":"REVG","files/plugin-443.crt":"Q0VSVA==","files/profile_tags.json":"e30="},"plugins":[]}`
	if err := os.WriteFile(filepath.Join(gen, "snapshot.json"), []byte(snapshot), 0600); err != nil {
		t.Fatal(err)
	}
	// 生成配置：含节点凭据、远程规则集（只导元数据）与本地路由规则。
	config := `{"log":{"level":"info"},"dns":{"servers":[{"address":"8.8.8.8"}]},` +
		`"outbounds":[{"type":"trojan","tag":"proxy","server":"203.0.113.9","server_port":443,"password":"hunter2"}],` +
		`"route":{"rule_set":[{"tag":"geoip-cn","type":"remote","format":"binary","url":"https://example.com/rules/geoip-cn.srs?token=abc123"}],` +
		`"rules":[{"domain_suffix":["example.com"],"outbound":"proxy"}]}}`
	if err := os.WriteFile(filepath.Join(gen, "run", "config.json"), []byte(config), 0600); err != nil {
		t.Fatal(err)
	}
	ruleContent := []byte("fake remote rule set content")
	if err := os.WriteFile(filepath.Join(gen, "assets", "srs", "geoip-cn.srs"), ruleContent, 0600); err != nil {
		t.Fatal(err)
	}
	// 日志：service.log 超 4 MiB（只取尾部），sing-box.log 小文件全取。
	big := make([]byte, 0, 4<<20+128)
	big = append(big, []byte("HEAD-MARKER secret vmess://eyJ2IjoiMiJ9\n")...)
	big = append(big, []byte(strings.Repeat("filler log line\n", (4<<20)/16))...)
	big = append(big, []byte("TAIL-MARKER peer 203.0.113.7\n")...)
	if err := os.WriteFile(filepath.Join(root, "logs", "service.log"), big, 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "logs", "sing-box.log"),
		[]byte("core started\noutbound dial 198.51.100.23:443\n"), 0600); err != nil {
		t.Fatal(err)
	}
	return New(root, moduleDir), root, moduleDir
}

func readDiagnosticsTar(t *testing.T, path string) map[string][]byte {
	t.Helper()
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	gz, err := gzip.NewReader(f)
	if err != nil {
		t.Fatal(err)
	}
	defer gz.Close()
	out := map[string][]byte{}
	tr := tar.NewReader(gz)
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatal(err)
		}
		b, err := io.ReadAll(tr)
		if err != nil {
			t.Fatal(err)
		}
		out[hdr.Name] = b
	}
	return out
}

func diagnosticsRequest(dest string) DiagnosticsRequest {
	return DiagnosticsRequest{
		Destination:       dest,
		AppVersion:        "3.0.14",
		AppVersionCode:    2026,
		PreviewVersion:    "3.0.17-preview.1",
		PreviewVersionCode: 2031,
		SingBoxVersion:    "v1.15.0-alpha.13-reF1nd",
	}
}

func TestExportDiagnostics(t *testing.T) {
	r, root, _ := diagnosticsFixture(t)
	dest := filepath.Join(root, "wanbox-diagnostics-2026-11-18T12-00-00+08-00.tar.gz")
	missing, err := r.ExportDiagnostics(diagnosticsRequest(dest))
	if err != nil {
		t.Fatal(err)
	}
	if len(missing) != 0 {
		t.Fatalf("unexpected missing files: %v", missing)
	}
	entries := readDiagnosticsTar(t, dest)
	for _, name := range []string{
		"logs/service.log", "logs/sing-box.log",
		"config/root-snapshot.json", "config/singbox-config.json", "config/module-status.json",
		"state/service.json", "rules/local/route-rules.json", "README.txt",
	} {
		if _, ok := entries[name]; !ok {
			t.Errorf("missing tar entry %s (have %v)", name, keysOf(entries))
		}
	}
	// 脱敏：包内不得含订阅链接、节点链接、凭据、服务器明文、完整公网 IP、MAC。
	joined := ""
	for name, content := range entries {
		if name == "README.txt" {
			continue
		}
		joined += string(content)
	}
	for _, leaked := range []string{
		"vmess://", "11111111-2222-3333-4444-555555555555", "hunter2",
		"203.0.113.7", "203.0.113.9", "198.51.100.23", "token=abc123",
		"fake remote rule set content",
	} {
		if strings.Contains(joined, leaked) {
			t.Errorf("diagnostics package leaked %q", leaked)
		}
	}
	// 日志尾部：超长 service.log 只留尾部 4 MiB，且仍脱敏。
	serviceLog := string(entries["logs/service.log"])
	if len(serviceLog) > 4<<20 {
		t.Errorf("service.log tail exceeds 4 MiB: %d", len(serviceLog))
	}
	if strings.Contains(serviceLog, "HEAD-MARKER") {
		t.Errorf("service.log head not trimmed")
	}
	if !strings.Contains(serviceLog, "TAIL-MARKER") {
		t.Errorf("service.log tail missing")
	}
	if !strings.Contains(serviceLog, "203.0.x.x") {
		t.Errorf("service.log IP not masked: tail=%q", serviceLog[len(serviceLog)-64:])
	}
	// 快照：cache.db 与证书剔除，其余 files 值打码；config 内凭据打码。
	snapshot := string(entries["config/root-snapshot.json"])
	if strings.Contains(snapshot, "cache.db") || strings.Contains(snapshot, ".crt") || strings.Contains(snapshot, "Q0VSVA==") {
		t.Errorf("snapshot still contains cache.db/certificate: %s", snapshot)
	}
	if !strings.Contains(snapshot, `"assets/geoip-cn.srs": "***"`) {
		t.Errorf("snapshot file blobs not masked: %s", snapshot)
	}
	// 远程规则：只导元数据，不导原文。
	for name := range entries {
		if strings.HasPrefix(name, "rules/remote/") || strings.HasPrefix(name, "config/singbox/rules/remote") {
			t.Errorf("remote rule content exported: %s", name)
		}
	}
	readme := string(entries["README.txt"])
	for _, want := range []string{
		"3.0.14", "2026", "3.0.17-preview.1", "2031", "v1.15.0-alpha.13-reF1nd",
		"geoip-cn", "example.com/rules/geoip-cn.srs",
	} {
		if !strings.Contains(readme, want) {
			t.Errorf("README missing %q:\n%s", want, readme)
		}
	}
	ruleHash := sha256.Sum256([]byte("fake remote rule set content"))
	if !strings.Contains(readme, hex.EncodeToString(ruleHash[:])) {
		t.Errorf("README missing remote rule hash:\n%s", readme)
	}
	if strings.Contains(readme, "token=abc123") {
		t.Errorf("README leaked rule URL token")
	}
	// 导出后同目录不得残留临时文件。
	ents, err := os.ReadDir(root)
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range ents {
		if strings.HasPrefix(e.Name(), ".diagnostics-") {
			t.Errorf("temp file left behind: %s", e.Name())
		}
	}
}

func TestExportDiagnosticsMissingFilesDoNotFail(t *testing.T) {
	root := t.TempDir()
	moduleDir := t.TempDir()
	if err := os.WriteFile(filepath.Join(moduleDir, "module.prop"),
		[]byte("id=wanbox\nversion=3.0.17-preview.1\nversionCode=2031\n"), 0600); err != nil {
		t.Fatal(err)
	}
	r := New(root, moduleDir)
	dest := filepath.Join(root, "wanbox-diagnostics-2026-11-18T12-00-00+08-00.tar.gz")
	missing, err := r.ExportDiagnostics(diagnosticsRequest(dest))
	if err != nil {
		t.Fatal(err)
	}
	if len(missing) == 0 {
		t.Fatalf("empty module should report missing files")
	}
	entries := readDiagnosticsTar(t, dest)
	readme := string(entries["README.txt"])
	for _, name := range missing {
		if !strings.Contains(readme, name) {
			t.Errorf("README does not list missing file %q:\n%s", name, readme)
		}
	}
	if _, ok := entries["config/module-status.json"]; !ok {
		t.Errorf("module-status.json missing even with no generation")
	}
}

func TestExportDiagnosticsRefusesNonRegularTarget(t *testing.T) {
	r, root, _ := diagnosticsFixture(t)
	// 目标是已存在目录。
	dirTarget := filepath.Join(root, "adir")
	if err := os.Mkdir(dirTarget, 0700); err != nil {
		t.Fatal(err)
	}
	if _, err := r.ExportDiagnostics(diagnosticsRequest(dirTarget)); err == nil {
		t.Errorf("directory target accepted")
	}
	// 目标是 symlink（指向普通文件也不接受）。
	real := filepath.Join(root, "real.tar.gz")
	if err := os.WriteFile(real, []byte("keep me"), 0600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(root, "wanbox-diagnostics-link.tar.gz")
	if err := os.Symlink(real, link); err != nil {
		t.Fatal(err)
	}
	if _, err := r.ExportDiagnostics(diagnosticsRequest(link)); err == nil {
		t.Errorf("symlink target accepted")
	}
	if b, err := os.ReadFile(real); err != nil || string(b) != "keep me" {
		t.Errorf("symlink target was overwritten: %q %v", b, err)
	}
	// 空目标拒绝。
	if _, err := r.ExportDiagnostics(diagnosticsRequest("")); err == nil {
		t.Errorf("empty destination accepted")
	}
}

func TestExportDiagnosticsCorruptedJSON(t *testing.T) {
	r, root, _ := diagnosticsFixture(t)
	rev := diagnosticsFixtureRev
	// 损坏的生成配置：原文（含凭据）不得进入包内。
	if err := os.WriteFile(filepath.Join(root, "generations", rev, "run", "config.json"),
		[]byte(`{"outbounds":[{"type":"trojan","password":"hunter2", broken`), 0600); err != nil {
		t.Fatal(err)
	}
	dest := filepath.Join(root, "wanbox-diagnostics-2026-11-18T12-00-00+08-00.tar.gz")
	missing, err := r.ExportDiagnostics(diagnosticsRequest(dest))
	if err != nil {
		t.Fatal(err)
	}
	entries := readDiagnosticsTar(t, dest)
	got := string(entries["config/singbox-config.json"])
	if !strings.Contains(got, `"diagnostic_error":"JSON 配置损坏，已省略原文以保护敏感信息"`) {
		t.Errorf("corrupted JSON not replaced: %q", got)
	}
	if strings.Contains(got, "hunter2") {
		t.Errorf("corrupted JSON leaked credentials")
	}
	readme := string(entries["README.txt"])
	if !strings.Contains(readme, "config/singbox-config.json") {
		t.Errorf("README does not note omitted file:\n%s", readme)
	}
	_ = missing
}

func TestExportDiagnosticsWhileStopped(t *testing.T) {
	r, root, _ := diagnosticsFixture(t)
	// state.json 明确 stopped：导出不得依赖核心运行。
	dest := filepath.Join(root, "wanbox-diagnostics-2026-11-18T12-00-00+08-00.tar.gz")
	if _, err := r.ExportDiagnostics(diagnosticsRequest(dest)); err != nil {
		t.Fatalf("export while stopped failed: %v", err)
	}
	if _, err := os.Stat(dest); err != nil {
		t.Fatal(err)
	}
}

func keysOf(m map[string][]byte) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	return out
}

func TestCappedLogWriterTrimsToTail(t *testing.T) {
	path := filepath.Join(t.TempDir(), "service.log")
	w, err := newCappedLogWriter(path)
	if err != nil {
		t.Fatal(err)
	}
	// 写入超过上限的内容，截尾后只留尾部 4 MiB。
	big := strings.Repeat("log line with filler text to grow the file\n", (9<<20)/44)
	if _, err := w.file.WriteString(big); err != nil {
		t.Fatal(err)
	}
	if err := trimLogFileToTail(w.file, MaxDiagnosticsLogBytes); err != nil {
		t.Fatal(err)
	}
	info, err := w.file.Stat()
	if err != nil {
		t.Fatal(err)
	}
	if info.Size() > MaxDiagnosticsLogBytes {
		t.Errorf("log not trimmed: %d bytes", info.Size())
	}
	w.WriteLine("after-trim line")
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasSuffix(string(content), "after-trim line\n") {
		t.Errorf("log lost tail write")
	}
	// 再次写到超过 8 MiB 上限后重开，直接截为尾部 4 MiB。
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_APPEND, 0600)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.WriteString(strings.Repeat("more filler log lines to exceed the cap\n", (5<<20)/42)); err != nil {
		t.Fatal(err)
	}
	f.Close()
	w2, err := newCappedLogWriter(path)
	if err != nil {
		t.Fatal(err)
	}
	defer w2.Close()
	if info, err := w2.file.Stat(); err != nil || info.Size() > MaxDiagnosticsLogBytes {
		t.Errorf("reopen did not trim: %v %d", err, info.Size())
	}
}
