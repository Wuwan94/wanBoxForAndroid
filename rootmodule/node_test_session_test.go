//go:build linux

package module

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// B5（N1）：Root 侧节点测试会话。bin/rootbox 用桩脚本代替，
// 只回放固定结果 JSON，不启动真实 box。

const stubRootboxScript = `#!/bin/sh
if [ "$1" = "--url-test" ]; then
  if [ -n "$WANBOX_TEST_RECORD" ]; then cp "$2" "$WANBOX_TEST_RECORD"; fi
  echo '{"delayMs": 42}'
  exit 0
fi
if [ "$1" = "--speed-test" ]; then
  if [ -n "$WANBOX_TEST_RECORD" ]; then cp "$4" "$WANBOX_TEST_RECORD"; fi
  echo '{"ProfileKey":"7","Mode":"simple_download","Stage":"complete","DownloadBitsPerSecond":123456,"UploadBitsPerSecond":0,"DownloadBytes":1048576,"UploadBytes":0,"LatencyMs":12,"ServerID":"","ServerName":"","ServerCountry":"","ServerSponsor":"","Error":"","Cancelled":false,"Done":true}'
  exit 0
fi
exit 2
`

const failingRootboxScript = `#!/bin/sh
echo "url_test_failed" >&2
exit 1
`

const garbageRootboxScript = `#!/bin/sh
echo "not json at all"
exit 0
`

func installStubRootbox(t *testing.T, r *Runtime, script string) {
	t.Helper()
	bin := filepath.Join(r.ModuleDir, "bin")
	if err := os.MkdirAll(bin, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(bin, "rootbox"), []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
}

func applyFixtureGeneration(t *testing.T, r *Runtime, snapshot Snapshot) {
	t.Helper()
	if _, err := r.Apply(context.Background(), snapshot); err != nil {
		t.Fatal(err)
	}
}

func TestNodeURLTestStubReturnsDelay(t *testing.T) {
	r, snapshot := fixture(t)
	applyFixtureGeneration(t, r, snapshot)
	installStubRootbox(t, r, stubRootboxScript)
	record := filepath.Join(t.TempDir(), "config.json")
	t.Setenv("WANBOX_TEST_RECORD", record)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	delay, err := r.NodeURLTest(ctx, 7, `{"outbounds":[{"type":"direct","tag":"direct"}]}`, "http://127.0.0.1/generate_204", 5000)
	if err != nil {
		t.Fatalf("NodeURLTest failed: %v", err)
	}
	if delay != 42 {
		t.Errorf("delay = %d, want 42", delay)
	}
	recorded, err := os.ReadFile(record)
	if err != nil {
		t.Fatalf("stub did not receive the test config: %v", err)
	}
	if strings.TrimSpace(string(recorded)) != `{"outbounds":[{"type":"direct","tag":"direct"}]}` {
		t.Errorf("test config passed to rootbox = %q", recorded)
	}
	// 临时测试配置必须清理，不留痕。
	matches, _ := filepath.Glob(filepath.Join(r.ModuleDir, ".node-test-*.json"))
	if len(matches) != 0 {
		t.Errorf("test config temp files left behind: %v", matches)
	}
}

func TestNodeURLTestWithoutGenerationUsesModuleDirAssets(t *testing.T) {
	r, _ := fixture(t)
	installStubRootbox(t, r, stubRootboxScript)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if _, err := r.NodeURLTest(ctx, 7, `{"outbounds":[]}`, "http://127.0.0.1/generate_204", 5000); err != nil {
		t.Fatalf("NodeURLTest without a current generation failed: %v", err)
	}
}

func TestNodeURLTestValidation(t *testing.T) {
	r, _ := fixture(t)
	installStubRootbox(t, r, stubRootboxScript)
	ctx := context.Background()
	cases := []struct {
		name      string
		profileID int64
		config    string
		link      string
		timeoutMs int
	}{
		{"profile id", 0, `{}`, "http://127.0.0.1/x", 5000},
		{"empty config", 7, ``, "http://127.0.0.1/x", 5000},
		{"broken config", 7, `{"outbounds": [broken`, "http://127.0.0.1/x", 5000},
		{"bad url", 7, `{}`, "ftp://example.com/x", 5000},
		{"empty url", 7, `{}`, "", 5000},
		{"timeout low", 7, `{}`, "http://127.0.0.1/x", 999},
		{"timeout high", 7, `{}`, "http://127.0.0.1/x", 60001},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := r.NodeURLTest(ctx, tc.profileID, tc.config, tc.link, tc.timeoutMs); err == nil {
				t.Errorf("NodeURLTest accepted %s", tc.name)
			}
		})
	}
}

func TestNodeURLTestRootboxFailure(t *testing.T) {
	r, _ := fixture(t)
	installStubRootbox(t, r, failingRootboxScript)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if _, err := r.NodeURLTest(ctx, 7, `{}`, "http://127.0.0.1/x", 5000); err == nil {
		t.Errorf("rootbox failure accepted")
	}
}

func TestNodeURLTestGarbageResult(t *testing.T) {
	r, _ := fixture(t)
	installStubRootbox(t, r, garbageRootboxScript)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if _, err := r.NodeURLTest(ctx, 7, `{}`, "http://127.0.0.1/x", 5000); err == nil {
		t.Errorf("garbage rootbox output accepted")
	}
}

func TestNodeSpeedTestStubReturnsResult(t *testing.T) {
	r, snapshot := fixture(t)
	applyFixtureGeneration(t, r, snapshot)
	installStubRootbox(t, r, stubRootboxScript)
	record := filepath.Join(t.TempDir(), "params.json")
	t.Setenv("WANBOX_TEST_RECORD", record)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	result, err := r.NodeSpeedTest(ctx, 7, `{"outbounds":[]}`, SpeedTestParams{
		Mode:              SpeedTestModeSimpleDownload,
		TimeoutMs:         10000,
		SimpleDownloadURL: "http://127.0.0.1/download",
	})
	if err != nil {
		t.Fatalf("NodeSpeedTest failed: %v", err)
	}
	if result == nil || !result.Done || result.Stage != SpeedTestStageComplete {
		t.Fatalf("unexpected speed-test result: %+v", result)
	}
	if result.DownloadBitsPerSecond != 123456 || result.DownloadBytes != 1048576 {
		t.Errorf("unexpected speed-test outcome: %+v", result)
	}
	var params SpeedTestParams
	raw, err := os.ReadFile(record)
	if err != nil {
		t.Fatalf("stub did not receive the params file: %v", err)
	}
	if err := json.Unmarshal(raw, &params); err != nil {
		t.Fatalf("params file is not JSON: %v", err)
	}
	if params.ProfileKey != "7" || params.Mode != SpeedTestModeSimpleDownload {
		t.Errorf("params passed to rootbox = %+v", params)
	}
	matches, _ := filepath.Glob(filepath.Join(r.ModuleDir, ".node-test-*.json"))
	if len(matches) != 0 {
		t.Errorf("test temp files left behind: %v", matches)
	}
}

func TestNodeSpeedTestValidation(t *testing.T) {
	r, _ := fixture(t)
	installStubRootbox(t, r, stubRootboxScript)
	ctx := context.Background()
	cases := []struct {
		name   string
		params SpeedTestParams
	}{
		{"bad mode", SpeedTestParams{Mode: "bogus", TimeoutMs: 5000}},
		{"timeout high", SpeedTestParams{Mode: SpeedTestModeSimpleDownload, TimeoutMs: 60001, SimpleDownloadURL: "http://127.0.0.1/d"}},
		{"timeout negative", SpeedTestParams{Mode: SpeedTestModeSimpleDownload, TimeoutMs: -1, SimpleDownloadURL: "http://127.0.0.1/d"}},
		{"bad simple url", SpeedTestParams{Mode: SpeedTestModeSimpleDownload, TimeoutMs: 5000, SimpleDownloadURL: "ftp://example.com/d"}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := r.NodeSpeedTest(ctx, 7, `{}`, tc.params); err == nil {
				t.Errorf("NodeSpeedTest accepted %s", tc.name)
			}
		})
	}
	if _, err := r.NodeSpeedTest(ctx, 0, `{}`, SpeedTestParams{Mode: SpeedTestModeSimpleDownload, TimeoutMs: 5000, SimpleDownloadURL: "http://127.0.0.1/d"}); err == nil {
		t.Errorf("NodeSpeedTest accepted profile id 0")
	}
}

func TestNodeSpeedTestRootboxFailure(t *testing.T) {
	r, _ := fixture(t)
	installStubRootbox(t, r, failingRootboxScript)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if _, err := r.NodeSpeedTest(ctx, 7, `{}`, SpeedTestParams{Mode: SpeedTestModeSimpleDownload, TimeoutMs: 5000, SimpleDownloadURL: "http://127.0.0.1/d"}); err == nil {
		t.Errorf("rootbox failure accepted")
	}
}
