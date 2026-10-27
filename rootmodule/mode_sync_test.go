// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func setupTestRuntime(t *testing.T, rev string, configContent string) *Runtime {
	t.Helper()
	tmp := t.TempDir()
	r := &Runtime{
		Root:      tmp,
		ModuleDir: filepath.Join(tmp, "module"),
	}
	runDir := filepath.Join(tmp, "generations", rev, "run")
	if err := os.MkdirAll(runDir, 0755); err != nil {
		t.Fatalf("failed to mkdir: %v", err)
	}
	if err := os.WriteFile(filepath.Join(runDir, "config.json"), []byte(configContent), 0600); err != nil {
		t.Fatalf("failed to write config: %v", err)
	}
	if err := os.WriteFile(filepath.Join(tmp, "current"), []byte(rev), 0600); err != nil {
		t.Fatalf("failed to write current: %v", err)
	}
	return r
}

func TestReadClashModeTargetEdgeCases(t *testing.T) {
	validRev := strings.Repeat("a", 64)

	rMissing := setupTestRuntime(t, validRev, `{"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090","secret":"sec"}}}`)
	tgt, err := rMissing.readClashModeTarget(validRev)
	if err != nil || !tgt.Skip {
		t.Fatalf("expected skip on missing default_mode, got err=%v", err)
	}

	rEmpty := setupTestRuntime(t, validRev, `{"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090","secret":"sec","default_mode":""}}}`)
	tgtEmpty, err := rEmpty.readClashModeTarget(validRev)
	if err != nil || !tgtEmpty.Skip {
		t.Fatalf("expected skip on empty string default_mode, got err=%v", err)
	}

	validModes := map[string]string{
		"rule":   "rule",
		"Rule":   "rule",
		"global": "global",
		"GLOBAL": "global",
		"direct": "direct",
		"Direct": "direct",
	}
	for input, expected := range validModes {
		rValid := setupTestRuntime(t, validRev, `{"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090","secret":"sec","default_mode":"`+input+`"}}}`)
		tgtValid, err := rValid.readClashModeTarget(validRev)
		if err != nil || tgtValid.Skip || tgtValid.Mode != expected {
			t.Fatalf("mode %s expected %s, got err=%v, mode=%s", input, expected, err, tgtValid.Mode)
		}
	}

	rejectConfigs := []struct {
		name string
		json string
	}{
		{"null_value", `{"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090","secret":"s","default_mode":null}}}`},
		{"whitespace_only", `{"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090","secret":"s","default_mode":"   "}}}`},
		{"leading_space", `{"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090","secret":"s","default_mode":" rule"}}}`},
		{"trailing_space", `{"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090","secret":"s","default_mode":"rule "}}}`},
		{"unknown_enum", `{"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090","secret":"s","default_mode":"bypass"}}}`},
		{"number_type", `{"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090","secret":"s","default_mode":1}}}`},
		{"boolean_type", `{"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090","secret":"s","default_mode":true}}}`},
		{"wrong_controller", `{"experimental":{"clash_api":{"external_controller":"0.0.0.0:9090","secret":"s","default_mode":"rule"}}}`},
		{"secret_missing", `{"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090","secret":"","default_mode":"rule"}}}`},
	}
	for _, tc := range rejectConfigs {
		rBad := setupTestRuntime(t, validRev, tc.json)
		if _, err := rBad.readClashModeTarget(validRev); err == nil {
			t.Fatalf("case %s: expected error, got nil", tc.name)
		}
	}

	oversizedConfig := `{"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090","secret":"s","default_mode":"rule"}},"pad":"` + strings.Repeat("x", MaxConfigBytes+2) + `"}`
	rOver := setupTestRuntime(t, validRev, oversizedConfig)
	if _, err := rOver.readClashModeTarget(validRev); err == nil || err.Error() != "config_too_large" {
		t.Fatalf("expected config_too_large, got %v", err)
	}
}

type loopbackHostRewriter struct {
	targetHost string
	rt         http.RoundTripper
}

func (t *loopbackHostRewriter) RoundTrip(req *http.Request) (*http.Response, error) {
	if req.URL.Host == "127.0.0.1:9090" {
		cloneReq := req.Clone(req.Context())
		cloneURL := *req.URL
		cloneURL.Host = t.targetHost
		cloneReq.URL = &cloneURL
		if t.rt == nil {
			return http.DefaultTransport.RoundTrip(cloneReq)
		}
		return t.rt.RoundTrip(cloneReq)
	}
	if t.rt == nil {
		return http.DefaultTransport.RoundTrip(req)
	}
	return t.rt.RoundTrip(req)
}

func TestAlignClashModeNetworkEdgeCases(t *testing.T) {
	validRev := strings.Repeat("e", 64)

	var mu sync.Mutex
	serverMode := "rule"
	forcedGetMode := ""
	getTrailingJunk := false
	getSecondJSON := false
	getValidTrailingWhitespace := false
	getOverflow := false
	patchOverflow := false
	patchDelay := time.Duration(0)
	patchStatus := http.StatusNoContent
	getStatus := http.StatusOK
	callCount := 0
	onGetHook := func() {}

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		mu.Lock()
		callCount++
		delay := patchDelay
		pOverflow := patchOverflow
		pStatus := patchStatus
		gOverflow := getOverflow
		gStatus := getStatus
		gTrail := getTrailingJunk
		gSecond := getSecondJSON
		gTrailWS := getValidTrailingWhitespace
		forced := forcedGetMode
		current := serverMode
		hook := onGetHook
		mu.Unlock()

		if req.Header.Get("Authorization") != "Bearer secret-val" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}

		if req.Method == http.MethodPatch {
			if delay > 0 {
				select {
				case <-time.After(delay):
				case <-req.Context().Done():
					return
				}
			}
			if pOverflow {
				w.WriteHeader(http.StatusOK)
				_, _ = w.Write([]byte(strings.Repeat("p", maxClashAPIResponseBytes+10)))
				return
			}
			if pStatus != http.StatusNoContent {
				w.WriteHeader(pStatus)
				return
			}
			var body map[string]string
			if err := json.NewDecoder(req.Body).Decode(&body); err != nil {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			mu.Lock()
			serverMode = body["mode"]
			mu.Unlock()
			w.WriteHeader(http.StatusNoContent)
			return
		}

		if req.Method == http.MethodGet {
			hook()
			if gOverflow {
				w.Header().Set("Content-Type", "application/json")
				_, _ = w.Write([]byte(`{"mode":"` + current + `","pad":"` + strings.Repeat("o", maxClashAPIResponseBytes+10) + `"}`))
				return
			}
			if gStatus != http.StatusOK {
				w.WriteHeader(gStatus)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			modeToReturn := current
			if forced != "" {
				modeToReturn = forced
			}
			if gTrail {
				_, _ = w.Write([]byte(`{"mode":"` + modeToReturn + `"} trailing_garbage`))
				return
			}
			if gSecond {
				_, _ = w.Write([]byte(`{"mode":"` + modeToReturn + `"} {"mode":"second"}`))
				return
			}
			if gTrailWS {
				_, _ = w.Write([]byte("{\n  \"mode\": \"" + modeToReturn + "\"\n}\n\r\n\t  \n"))
				return
			}
			_ = json.NewEncoder(w).Encode(map[string]string{"mode": modeToReturn})
			return
		}
		w.WriteHeader(http.StatusMethodNotAllowed)
	}))
	defer ts.Close()

	u, _ := url.Parse(ts.URL)
	testClient := &http.Client{
		Transport: &loopbackHostRewriter{targetHost: u.Host, rt: ts.Client().Transport},
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return errors.New("redirect_forbidden")
		},
	}

	validCfg := `{"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090","secret":"secret-val","default_mode":"direct"}}}`
	r := setupTestRuntime(t, validRev, validCfg)
	passGuard := func() error { return nil }

	// 1. 成功对齐与确认流程
	err := r.alignClashModeInternal(context.Background(), validRev, testClient, "http://127.0.0.1:9090/configs", passGuard)
	if err != nil {
		t.Fatalf("expected success, got: %v", err)
	}
	mu.Lock()
	modeSnapshot := serverMode
	mu.Unlock()
	if modeSnapshot != "direct" {
		t.Fatalf("expected direct on server, got: %s", modeSnapshot)
	}

	// 2. 字段缺失与空字符串 -> 严格 0 次网络调用
	mu.Lock()
	callCount = 0
	mu.Unlock()
	rMissing := setupTestRuntime(t, validRev, `{"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090","secret":"secret-val"}}}`)
	err = rMissing.alignClashModeInternal(context.Background(), validRev, testClient, "http://127.0.0.1:9090/configs", passGuard)
	if err != nil {
		t.Fatalf("expected nil on missing mode, got: %v", err)
	}
	mu.Lock()
	missingCalls := callCount
	callCount = 0
	mu.Unlock()
	if missingCalls != 0 {
		t.Fatalf("expected zero calls on missing, got: %d", missingCalls)
	}

	rEmpty := setupTestRuntime(t, validRev, `{"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090","secret":"secret-val","default_mode":""}}}`)
	err = rEmpty.alignClashModeInternal(context.Background(), validRev, testClient, "http://127.0.0.1:9090/configs", passGuard)
	if err != nil {
		t.Fatalf("expected nil on empty string mode, got: %v", err)
	}
	mu.Lock()
	emptyCalls := callCount
	mu.Unlock()
	if emptyCalls != 0 {
		t.Fatalf("expected zero calls on empty string, got: %d", emptyCalls)
	}

	// 3. GET 回显不匹配
	mu.Lock()
	forcedGetMode = "rule"
	mu.Unlock()
	err = r.alignClashModeInternal(context.Background(), validRev, testClient, "http://127.0.0.1:9090/configs", passGuard)
	mu.Lock()
	forcedGetMode = ""
	mu.Unlock()
	if err == nil || err.Error() != "mode_mismatch" {
		t.Fatalf("expected mode_mismatch, got: %v", err)
	}

	// 4. 合法尾部空白与换行允许正常解析通过
	mu.Lock()
	getValidTrailingWhitespace = true
	mu.Unlock()
	err = r.alignClashModeInternal(context.Background(), validRev, testClient, "http://127.0.0.1:9090/configs", passGuard)
	mu.Lock()
	getValidTrailingWhitespace = false
	mu.Unlock()
	if err != nil {
		t.Fatalf("expected trailing whitespace accepted, got: %v", err)
	}

	// 5. 短尾部垃圾拒绝
	mu.Lock()
	getTrailingJunk = true
	mu.Unlock()
	err = r.alignClashModeInternal(context.Background(), validRev, testClient, "http://127.0.0.1:9090/configs", passGuard)
	mu.Lock()
	getTrailingJunk = false
	mu.Unlock()
	if err == nil || err.Error() != "get_payload_malformed" {
		t.Fatalf("expected get_payload_malformed for trailing junk, got: %v", err)
	}

	// 6. 第二个 JSON 拒绝
	mu.Lock()
	getSecondJSON = true
	mu.Unlock()
	err = r.alignClashModeInternal(context.Background(), validRev, testClient, "http://127.0.0.1:9090/configs", passGuard)
	mu.Lock()
	getSecondJSON = false
	mu.Unlock()
	if err == nil || err.Error() != "get_payload_malformed" {
		t.Fatalf("expected get_payload_malformed for second JSON, got: %v", err)
	}

	// 7. 有效前缀 + 超长尾部垃圾拒绝（超出 4096 字节限额）
	mu.Lock()
	getOverflow = true
	mu.Unlock()
	err = r.alignClashModeInternal(context.Background(), validRev, testClient, "http://127.0.0.1:9090/configs", passGuard)
	mu.Lock()
	getOverflow = false
	mu.Unlock()
	if err == nil || err.Error() != "response_overflow" {
		t.Fatalf("expected response_overflow, got: %v", err)
	}

	// 8. PATCH 响应体溢出拒绝
	mu.Lock()
	patchOverflow = true
	mu.Unlock()
	err = r.alignClashModeInternal(context.Background(), validRev, testClient, "http://127.0.0.1:9090/configs", passGuard)
	mu.Lock()
	patchOverflow = false
	mu.Unlock()
	if err == nil || err.Error() != "response_overflow" {
		t.Fatalf("expected response_overflow, got: %v", err)
	}

	// 9. GET 执行阶段中途 Guard 拒绝
	generationAltered := false
	mu.Lock()
	onGetHook = func() {
		mu.Lock()
		generationAltered = true
		mu.Unlock()
	}
	mu.Unlock()
	dynamicGuard := func() error {
		mu.Lock()
		defer mu.Unlock()
		if generationAltered {
			return errors.New("revision_mismatch")
		}
		return nil
	}
	err = r.alignClashModeInternal(context.Background(), validRev, testClient, "http://127.0.0.1:9090/configs", dynamicGuard)
	mu.Lock()
	onGetHook = func() {}
	mu.Unlock()
	if err == nil || err.Error() != "revision_mismatch" {
		t.Fatalf("expected revision_mismatch upon GET transition, got: %v", err)
	}

	// 10. 已取消上下文立即断开
	cancelCtx, cancel := context.WithCancel(context.Background())
	cancel()
	err = r.alignClashModeInternal(cancelCtx, validRev, testClient, "http://127.0.0.1:9090/configs", passGuard)
	if err == nil || !strings.Contains(err.Error(), "patch_network_failed") {
		t.Fatalf("expected patch_network_failed on canceled context, got: %v", err)
	}

	// 11. 超时拦截：服务端挂起超过请求短限额
	mu.Lock()
	patchDelay = 120 * time.Millisecond
	mu.Unlock()
	shortTimeoutCtx, shortCancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer shortCancel()
	start := time.Now()
	err = r.alignClashModeInternal(shortTimeoutCtx, validRev, testClient, "http://127.0.0.1:9090/configs", passGuard)
	elapsed := time.Since(start)
	mu.Lock()
	patchDelay = 0
	mu.Unlock()
	if err == nil || !strings.Contains(err.Error(), "patch_network_failed") {
		t.Fatalf("expected patch_network_failed on timeout, got: %v", err)
	}
	if elapsed > 250*time.Millisecond {
		t.Fatalf("timeout took too long: %v", elapsed)
	}

	// 12. 307 重定向拦截并断言目标服务 0 命中
	var targetHitCount int
	targetTs := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		mu.Lock()
		targetHitCount++
		mu.Unlock()
		w.WriteHeader(http.StatusOK)
	}))
	defer targetTs.Close()

	redirectTs := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		http.Redirect(w, req, targetTs.URL, http.StatusTemporaryRedirect)
	}))
	defer redirectTs.Close()

	uRedirect, _ := url.Parse(redirectTs.URL)
	redirectClient := &http.Client{
		Transport: &loopbackHostRewriter{targetHost: uRedirect.Host, rt: redirectTs.Client().Transport},
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return errors.New("redirect_forbidden")
		},
	}
	err = r.alignClashModeInternal(context.Background(), validRev, redirectClient, "http://127.0.0.1:9090/configs", passGuard)
	if err == nil || !strings.Contains(err.Error(), "patch_network_failed") {
		t.Fatalf("expected patch_network_failed on redirect, got: %v", err)
	}
	mu.Lock()
	hitSnapshot := targetHitCount
	mu.Unlock()
	if hitSnapshot != 0 {
		t.Fatalf("redirect target must receive 0 hits, got: %d", hitSnapshot)
	}
}
