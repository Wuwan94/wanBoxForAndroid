// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

func testAPIConfig() APIConfig {
	return APIConfig{Listen: "127.0.0.1:0", Token: strings.Repeat("t", 64)}
}

func apiRequest(t *testing.T, h http.Handler, method, target, token, host string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, target, nil)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if host == "" {
		host = "127.0.0.1:8443"
	}
	req.Host = host
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

// 管理接口必须鉴权：缺 token、错 token 都不能读到状态。
func TestAPIRejectsMissingOrWrongToken(t *testing.T) {
	r, _ := fixture(t)
	cfg := testAPIConfig()
	h := r.apiHandler(cfg)

	for _, token := range []string{"", "wrong"} {
		rec := apiRequest(t, h, http.MethodGet, "/v1/status", token, "")
		if rec.Code != http.StatusUnauthorized {
			t.Fatal("expected 401, got", rec.Code, token)
		}
	}
}

// 只接受回环 Host，防止 DNS rebinding。
func TestAPIRejectsForeignHost(t *testing.T) {
	r, _ := fixture(t)
	cfg := testAPIConfig()
	h := r.apiHandler(cfg)

	rec := apiRequest(t, h, http.MethodGet, "/v1/status", cfg.Token, "evil.example.com")
	if rec.Code != http.StatusForbidden {
		t.Fatal("expected 403, got", rec.Code)
	}
}

// 核心停止（无 state.json）时管理接口仍必须可读，这是 P2 的硬要求。
func TestAPIReadableWhileServiceStopped(t *testing.T) {
	r, _ := fixture(t)
	cfg := testAPIConfig()
	h := r.apiHandler(cfg)

	rec := apiRequest(t, h, http.MethodGet, "/v1/status", cfg.Token, "")
	if rec.Code != http.StatusOK {
		t.Fatal("expected 200, got", rec.Code, rec.Body.String())
	}
	var body struct {
		OK    bool  `json:"ok"`
		State State `json:"state"`
	}
	if e := json.Unmarshal(rec.Body.Bytes(), &body); e != nil {
		t.Fatal(e)
	}
	if !body.OK || body.State.Phase != "stopped" {
		t.Fatal("unexpected body", rec.Body.String())
	}
}

// 节点切换走独立端点，复用热切换，不触发服务重启。
func TestAPISelectNodeHotSwitches(t *testing.T) {
	r, _ := fixture(t)
	fake := &fakeClash{selector: "proxy", now: "a", secret: "s3cret"}
	tags := map[string]ProfileTag{"1": {Tag: "a", Name: "node-a"}, "2": {Tag: "b", Name: "node-b"}}
	stageSelectNode(t, r, fake, tags, true)

	cfg := testAPIConfig()
	h := r.apiHandler(cfg)
	req := httptest.NewRequest(http.MethodPost, "/v1/select", strings.NewReader(`{"profileId":2}`))
	req.Host = "127.0.0.1:8443"
	req.Header.Set("Authorization", "Bearer "+cfg.Token)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatal("expected 200, got", rec.Code, rec.Body.String())
	}
	if fake.getPutCount() != 1 || fake.getNow() != "b" {
		t.Fatal("clash api not switched", fake.getPutCount(), fake.getNow())
	}
	var body struct {
		State State `json:"state"`
	}
	if e := json.Unmarshal(rec.Body.Bytes(), &body); e != nil {
		t.Fatal(e)
	}
	if body.State.ProfileID != 2 {
		t.Fatal("state not updated", body.State.ProfileID)
	}
}

// 凭据初始化必须幂等：重复调用不得轮换 token 或证书，否则已连接的管理端全部失效。
func TestAPIConfigIsStable(t *testing.T) {
	r, _ := fixture(t)
	first, e := r.EnsureAPIConfig()
	if e != nil {
		t.Fatal(e)
	}
	second, e := r.EnsureAPIConfig()
	if e != nil {
		t.Fatal(e)
	}
	if first.Token != second.Token || first.CertSHA256 != second.CertSHA256 {
		t.Fatal("api credentials rotated on re-read")
	}
	if _, e = os.Stat(r.path(apiCertFile)); e != nil {
		t.Fatal("certificate not written", e)
	}
	der, ok := loadAPICertificate(r.path(apiCertFile), r.path(apiKeyFile))
	if !ok {
		t.Fatal("certificate not loadable")
	}
	if sha256Hex(der) != first.CertSHA256 {
		t.Fatal("certificate fingerprint mismatch")
	}
}
