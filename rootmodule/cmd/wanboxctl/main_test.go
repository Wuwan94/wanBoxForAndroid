// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
	module "wanbox/module"
)

func TestCommandAllowlistRejectsExtraArgumentsAndShellInput(t *testing.T) {
	r := module.New(filepath.Join(t.TempDir(), "data"), t.TempDir())
	for _, args := range [][]string{
		{}, {"status", "extra"}, {"start", "extra"}, {"sh", "-c", "echo unsafe"},
		{"config", "apply", "../file"}, {"config", "erase"}, {"autostart", "yes"},
		{"__internal", "unknown"}, {"stop; echo unsafe"}, {"--data-root", "/tmp"},
		{"__internal", "schedule-install", "1"}, {"__internal", "schedule-install", "2;stop"},
		{"__internal", "schedule-install", "02"}, {"__internal", "schedule-install", "2", "extra"},
		{"__internal", "finish-install", "2", "bad", "bad"},
		{"__internal", "finish-install", "2", "0", "../file"},
		{"__internal", "schedule-install", "2", "erase"},
		{"__internal", "schedule-install", "02", "nodes"},
		{"__internal", "finish-install", "2", "bad", "bad", "nodes"},
		{"__internal", "google-firewall", "--delay-seconds", "1", "extra"},
		{"__internal", "google-firewall", "--delay-seconds", "301"},
		{"data", "selection-finish", "../unsafe"},
		{"data", "selection-finish", "bad;clear"},
	} {
		if err := run(context.Background(), r, args); err == nil || err.Error() != "arguments_invalid" {
			t.Fatalf("unexpected command acceptance: %q: %v", args, err)
		}
	}
}

func TestGoogleFirewallDelayArgsAreFixedAndBounded(t *testing.T) {
	for _, tc := range []struct {
		args []string
		want time.Duration
		bad  bool
	}{
		{nil, 0, false},
		{[]string{"--delay-seconds", "60"}, time.Minute, false},
		{[]string{"--delay-seconds", "0"}, 0, false},
		{[]string{"--delay-seconds", "060"}, 0, true},
		{[]string{"--delay-seconds", "-1"}, 0, true},
		{[]string{"--delay-seconds", "301"}, 0, true},
		{[]string{"--delay-seconds", "1;stop"}, 0, true},
		{[]string{"--anything", "1"}, 0, true},
		{[]string{"--delay-seconds", "1", "extra"}, 0, true},
	} {
		got, err := parseGoogleFirewallDelayArgs(tc.args)
		if tc.bad {
			if err == nil || err.Error() != "arguments_invalid" {
				t.Errorf("args %q error=%v, want arguments_invalid", tc.args, err)
			}
			continue
		}
		if err != nil || got != tc.want {
			t.Errorf("args %q delay=%s error=%v, want %s", tc.args, got, err, tc.want)
		}
	}
}

func TestGoogleFirewallCommandRequiresRoot(t *testing.T) {
	r := module.New(filepath.Join(t.TempDir(), "data"), t.TempDir())
	args := []string{"__internal", "google-firewall"}
	if err := runAuthorized(context.Background(), r, args, 1000); err == nil || err.Error() != "root_required" {
		t.Fatalf("non-root repair command error = %v, want root_required", err)
	}
	if err := runAuthorized(context.Background(), r, args, 0); err != nil {
		t.Fatalf("root repair command did not dispatch safely: %v", err)
	}
}

func TestReadOnlyCommandsDoNotStartRuntime(t *testing.T) {
	r := module.New(filepath.Join(t.TempDir(), "absent"), t.TempDir())
	for _, command := range []string{"status", "module", "logs"} {
		if err := run(context.Background(), r, []string{command}); err != nil {
			t.Fatal(err)
		}
	}
}

func TestResumeDispatchAndStatus(t *testing.T) {
	r := module.New(filepath.Join(t.TempDir(), "data"), t.TempDir())
	if err := run(context.Background(), r, []string{"resume"}); err == nil || err.Error() != "module_not_suspended" {
		t.Fatalf("resume dispatch: %v", err)
	}
	if err := run(context.Background(), r, []string{"resume", "extra"}); err == nil || err.Error() != "arguments_invalid" {
		t.Fatalf("resume arity: %v", err)
	}
	if err := run(context.Background(), r, []string{"status"}); err != nil {
		t.Fatal(err)
	}
	if got := r.Status(); got.Phase != "disabled" || got.Core.PID != 0 || got.Supervisor.PID != 0 {
		t.Fatalf("resume unexpectedly started runtime: %+v", got)
	}
}

func TestNodeURLTestCommandReturnsDelay(t *testing.T) {
	r := newCLITestRuntime(t)
	stdin := strings.NewReader(`{"config":"{\"outbounds\":[]}","url":"http://127.0.0.1/generate_204","timeoutMs":5000}`)
	var stdout bytes.Buffer
	code := runNodeTestCommand(context.Background(), r, []string{"node", "urltest", "7"}, 0, stdin, &stdout)
	if code != 0 {
		t.Fatalf("exit code = %d, stdout = %q", code, stdout.String())
	}
	var result struct {
		DelayMs int32 `json:"delayMs"`
	}
	if err := json.Unmarshal(stdout.Bytes(), &result); err != nil || result.DelayMs != 42 {
		t.Fatalf("stdout = %q", stdout.String())
	}
}

func TestNodeSpeedTestCommandReturnsResult(t *testing.T) {
	r := newCLITestRuntime(t)
	stdin := strings.NewReader(`{"config":"{\"outbounds\":[]}","mode":"simple_download","timeoutMs":10000,"simpleDownloadURL":"http://127.0.0.1/download"}`)
	var stdout bytes.Buffer
	code := runNodeTestCommand(context.Background(), r, []string{"node", "speedtest", "7"}, 0, stdin, &stdout)
	if code != 0 {
		t.Fatalf("exit code = %d, stdout = %q", code, stdout.String())
	}
	var result module.SpeedTestResult
	if err := json.Unmarshal(stdout.Bytes(), &result); err != nil {
		t.Fatalf("stdout = %q", stdout.String())
	}
	if !result.Done || result.Stage != "complete" || result.DownloadBitsPerSecond != 123456 {
		t.Fatalf("unexpected result: %+v", result)
	}
}

func TestNodeTestCommandRejects(t *testing.T) {
	r := newCLITestRuntime(t)
	cases := []struct {
		name string
		args []string
		euid int
		body string
	}{
		{"non-root", []string{"node", "urltest", "7"}, 1000, `{}`},
		{"bad id", []string{"node", "urltest", "0"}, 0, `{}`},
		{"non-numeric id", []string{"node", "urltest", "x"}, 0, `{}`},
		{"broken json", []string{"node", "urltest", "7"}, 0, `{broken`},
		{"bad url", []string{"node", "urltest", "7"}, 0, `{"config":"{}","url":"ftp://x/y","timeoutMs":5000}`},
		{"bad mode", []string{"node", "speedtest", "7"}, 0, `{"config":"{}","mode":"bogus","timeoutMs":5000}`},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var stdout bytes.Buffer
			code := runNodeTestCommand(context.Background(), r, tc.args, tc.euid, strings.NewReader(tc.body), &stdout)
			if code != 1 {
				t.Fatalf("exit code = %d, stdout = %q", code, stdout.String())
			}
			var result struct {
				Error string `json:"error"`
			}
			if err := json.Unmarshal(stdout.Bytes(), &result); err != nil || result.Error == "" {
				t.Fatalf("stdout is not an error JSON: %q", stdout.String())
			}
		})
	}
}

func TestDiagnosticsExportCommandRejectsNonDownloadDestination(t *testing.T) {
	r := newCLITestRuntime(t)
	stdin := strings.NewReader(`{"destination":"/tmp/wanbox-diagnostics-2026-11-18.tar.gz","appVersion":"3.0.14","appVersionCode":2026,"singBoxVersion":"v1.15.0-alpha.13-reF1nd"}`)
	var stdout bytes.Buffer
	code := runDiagnosticsExportCommand(r, 0, stdin, &stdout)
	if code != 1 {
		t.Fatalf("exit code = %d, stdout = %q", code, stdout.String())
	}
	var result struct {
		Error string `json:"error"`
	}
	if err := json.Unmarshal(stdout.Bytes(), &result); err != nil || result.Error != "diagnostics_destination_unavailable" {
		t.Fatalf("stdout = %q", stdout.String())
	}
}

func TestDiagnosticsExportCommandAcceptsDownloadDestination(t *testing.T) {
	if os.Geteuid() != 0 {
		t.Skip("needs root to create /storage/emulated/0/Download")
	}
	download := "/storage/emulated/0/Download"
	if err := os.MkdirAll(download, 0700); err != nil {
		t.Skipf("cannot create %s: %v", download, err)
	}
	t.Cleanup(func() {
		_ = os.RemoveAll(download)
		_ = os.Remove("/storage/emulated/0")
		_ = os.Remove("/storage/emulated")
	})
	r := newCLITestRuntime(t)
	dest := filepath.Join(download, "wanbox-diagnostics-2026-11-18T00-00-00.tar.gz")
	body, _ := json.Marshal(map[string]any{
		"destination": dest, "appVersion": "3.0.14", "appVersionCode": 2026,
		"singBoxVersion": "v1.15.0-alpha.13-reF1nd",
	})
	var stdout bytes.Buffer
	code := runDiagnosticsExportCommand(r, 0, bytes.NewReader(body), &stdout)
	if code != 0 {
		t.Fatalf("exit code = %d, stdout = %q", code, stdout.String())
	}
	var result struct {
		Missing []string `json:"missing"`
	}
	if err := json.Unmarshal(stdout.Bytes(), &result); err != nil || result.Missing == nil {
		t.Fatalf("stdout = %q", stdout.String())
	}
	if _, err := os.Stat(dest); err != nil {
		t.Fatalf("diagnostics archive missing at %s: %v", dest, err)
	}
}

func TestSharedInterfacesCommand(t *testing.T) {
	var stdout bytes.Buffer
	// 非 Root 拒绝。
	if code := runSharedInterfacesCommand(1000, &stdout); code != 1 {
		t.Fatalf("non-root exit code = %d, stdout = %q", code, stdout.String())
	}
	// Root：返回接口数组，loopback 永不入选。
	stdout.Reset()
	if code := runSharedInterfacesCommand(0, &stdout); code != 0 {
		t.Fatalf("exit code = %d, stdout = %q", code, stdout.String())
	}
	var result struct {
		Interfaces []string `json:"interfaces"`
		Error      string   `json:"error"`
	}
	if err := json.Unmarshal(stdout.Bytes(), &result); err != nil || result.Interfaces == nil {
		t.Fatalf("stdout = %q", stdout.String())
	}
	for _, name := range result.Interfaces {
		if name == "lo" {
			t.Fatal("loopback must never be listed")
		}
	}
}

func TestSharedPreflightCommand(t *testing.T) {
	var stdout bytes.Buffer
	if code := runSharedPreflightCommand(1000, "socket_assign", &stdout); code != 1 {
		t.Fatalf("non-root exit code = %d, stdout = %q", code, stdout.String())
	}
	stdout.Reset()
	if code := runSharedPreflightCommand(0, "tc", &stdout); code != 1 {
		t.Fatalf("bad data plane exit code = %d, stdout = %q", code, stdout.String())
	}
	stdout.Reset()
	if code := runSharedPreflightCommand(0, "socket_assign", &stdout); code != 0 {
		t.Fatalf("exit code = %d, stdout = %q", code, stdout.String())
	}
	var result struct {
		OK      bool     `json:"ok"`
		Missing []string `json:"missing"`
		Checked []string `json:"checked"`
	}
	if err := json.Unmarshal(stdout.Bytes(), &result); err != nil || len(result.Checked) == 0 {
		t.Fatalf("stdout = %q", stdout.String())
	}
}

// newCLITestRuntime 建一个带桩 rootbox 的 Runtime：桩只回放固定结果 JSON。
func newCLITestRuntime(t *testing.T) *module.Runtime {
	t.Helper()
	dir := t.TempDir()
	mod := filepath.Join(dir, "module")
	if err := os.Mkdir(mod, 0700); err != nil {
		t.Fatal(err)
	}
	bin := filepath.Join(mod, "bin")
	if err := os.MkdirAll(bin, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(bin, "rootbox"), []byte(cliStubRootboxScript), 0700); err != nil {
		t.Fatal(err)
	}
	return module.New(filepath.Join(dir, "data"), mod)
}

const cliStubRootboxScript = `#!/bin/sh
if [ "$1" = "--url-test" ]; then echo '{"delayMs": 42}'; exit 0; fi
if [ "$1" = "--speed-test" ]; then echo '{"ProfileKey":"7","Mode":"simple_download","Stage":"complete","DownloadBitsPerSecond":123456,"UploadBitsPerSecond":0,"DownloadBytes":1048576,"UploadBytes":0,"LatencyMs":12,"ServerID":"","ServerName":"","ServerCountry":"","ServerSponsor":"","Error":"","Cancelled":false,"Done":true}'; exit 0; fi
exit 2
`
