// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func newChannelTestRuntime(t *testing.T) *Runtime {
	t.Helper()
	root := filepath.Join(t.TempDir(), "data")
	if e := os.MkdirAll(root, 0700); e != nil {
		t.Fatal(e)
	}
	return New(root, t.TempDir())
}

func startTestChannel(t *testing.T, r *Runtime) *channelServer {
	t.Helper()
	cs := r.startChannelServer(context.Background())
	if cs == nil {
		t.Fatal("channel server did not start")
	}
	t.Cleanup(cs.stop)
	return cs
}

func channelBase(t *testing.T, r *Runtime) (string, string) {
	t.Helper()
	state, err := r.ChannelEndpoint()
	if err != nil {
		t.Fatal(err)
	}
	if state.Channel == nil {
		t.Fatal("channel endpoint missing")
	}
	return fmt.Sprintf("http://127.0.0.1:%d", state.Channel.Port), state.Channel.Token
}

func getChannelRaw(url, token string) (int, []byte, error) {
	req, e := http.NewRequest(http.MethodGet, url, nil)
	if e != nil {
		return 0, nil, e
	}
	if token != "" {
		req.Header.Set("X-Wanbox-Token", token)
	}
	resp, e := http.DefaultClient.Do(req)
	if e != nil {
		return 0, nil, e
	}
	defer resp.Body.Close()
	body, e := io.ReadAll(resp.Body)
	return resp.StatusCode, body, e
}

func getChannel(t *testing.T, url, token string) (int, []byte) {
	t.Helper()
	code, body, e := getChannelRaw(url, token)
	if e != nil {
		t.Fatal(e)
	}
	return code, body
}

func writeCurrentRevision(t *testing.T, r *Runtime, rev string) {
	t.Helper()
	if e := os.WriteFile(r.path("current"), []byte(rev), 0600); e != nil {
		t.Fatal(e)
	}
}

func payloadRevision(t *testing.T, body []byte) string {
	t.Helper()
	var payload struct {
		State struct {
			Revision string `json:"revision"`
		} `json:"state"`
	}
	if e := json.Unmarshal(body, &payload); e != nil {
		t.Fatal(e)
	}
	return payload.State.Revision
}

func TestChannelServerTokenAuth(t *testing.T) {
	r := newChannelTestRuntime(t)
	startTestChannel(t, r)
	base, token := channelBase(t, r)
	if code, body := getChannel(t, base+"/v1/status", ""); code != http.StatusForbidden || len(body) != 0 {
		t.Fatal("missing token accepted:", code, string(body))
	}
	if code, body := getChannel(t, base+"/v1/status", strings.Repeat("0", 32)); code != http.StatusForbidden || len(body) != 0 {
		t.Fatal("wrong token accepted:", code, string(body))
	}
	if code, body := getChannel(t, base+"/v1/status", token); code != http.StatusOK || len(body) == 0 {
		t.Fatal("correct token rejected:", code, string(body))
	}
	if code, _ := getChannel(t, base+"/v1/unknown", token); code != http.StatusNotFound {
		t.Fatal("unknown path not 404:", code)
	}
}

// The channel must serve the same bytes the CLI prints: both go through
// Runtime.CommandResponse, and this pins that against Response(Status()).
func TestChannelResponsesMatchCLIRender(t *testing.T) {
	r := newChannelTestRuntime(t)
	startTestChannel(t, r)
	base, token := channelBase(t, r)
	for _, tc := range []struct {
		path    string
		command string
	}{
		{"/v1/status", "status"},
		{"/v1/dashboard", "dashboard"},
	} {
		code, body := getChannel(t, base+tc.path, token)
		if code != http.StatusOK {
			t.Fatal(tc.path, "status", code)
		}
		if want := r.CommandResponse(tc.command, nil); string(body) != string(want) {
			t.Fatalf("%s body drifted from CLI render:\n got %s\nwant %s", tc.path, body, want)
		}
	}
}

func TestChannelLongPollReturnsImmediatelyWhenRevisionDiffers(t *testing.T) {
	r := newChannelTestRuntime(t)
	startTestChannel(t, r)
	base, token := channelBase(t, r)
	revA := strings.Repeat("a", 64)
	writeCurrentRevision(t, r, revA)
	start := time.Now()
	code, body := getChannel(t, base+"/v1/status?wait=10&since="+strings.Repeat("b", 64), token)
	if elapsed := time.Since(start); elapsed > 5*time.Second {
		t.Fatal("long poll blocked despite differing revision:", elapsed)
	}
	if code != http.StatusOK || payloadRevision(t, body) != revA {
		t.Fatal("unexpected long poll response:", code, string(body))
	}
}

func TestChannelLongPollWakesOnRevisionChange(t *testing.T) {
	r := newChannelTestRuntime(t)
	startTestChannel(t, r)
	base, token := channelBase(t, r)
	revA := strings.Repeat("a", 64)
	revB := strings.Repeat("b", 64)
	writeCurrentRevision(t, r, revA)
	type result struct {
		code int
		body []byte
		err  error
	}
	done := make(chan result, 1)
	go func() {
		code, body, err := getChannelRaw(base+"/v1/status?wait=10&since="+revA, token)
		done <- result{code, body, err}
	}()
	time.Sleep(200 * time.Millisecond)
	writeCurrentRevision(t, r, revB)
	r.notifyStateChanged()
	select {
	case res := <-done:
		if res.err != nil {
			t.Fatal(res.err)
		}
		if res.code != http.StatusOK || payloadRevision(t, res.body) != revB {
			t.Fatal("long poll did not return the advanced revision:", res.code, string(res.body))
		}
	case <-time.After(5 * time.Second):
		t.Fatal("long poll did not wake on revision change")
	}
}

func TestChannelLongPollExpiresWithCurrentPayload(t *testing.T) {
	r := newChannelTestRuntime(t)
	startTestChannel(t, r)
	base, token := channelBase(t, r)
	revA := strings.Repeat("a", 64)
	writeCurrentRevision(t, r, revA)
	start := time.Now()
	code, body := getChannel(t, base+"/v1/status?wait=1&since="+revA, token)
	elapsed := time.Since(start)
	if elapsed < 900*time.Millisecond || elapsed > 5*time.Second {
		t.Fatal("long poll expiry off:", elapsed)
	}
	if code != http.StatusOK || payloadRevision(t, body) != revA {
		t.Fatal("unexpected expiry response:", code, string(body))
	}
}

func TestChannelFileContract(t *testing.T) {
	r := newChannelTestRuntime(t)
	cs := startTestChannel(t, r)
	info, e := os.Stat(r.path("channel.json"))
	if e != nil {
		t.Fatal(e)
	}
	if info.Mode().Perm() != 0600 {
		t.Fatal("channel.json mode:", info.Mode().Perm())
	}
	raw, e := os.ReadFile(r.path("channel.json"))
	if e != nil {
		t.Fatal(e)
	}
	var file struct {
		Port  int    `json:"port"`
		Token string `json:"token"`
		PID   int    `json:"pid"`
	}
	if e := json.Unmarshal(raw, &file); e != nil {
		t.Fatal(e)
	}
	if file.Port <= 0 || file.PID != os.Getpid() ||
		len(file.Token) != 32 || strings.Trim(file.Token, "0123456789abcdef") != "" {
		t.Fatal("channel.json fields invalid:", string(raw))
	}
	state, e := r.ChannelEndpoint()
	if e != nil || state.Channel == nil ||
		state.Channel.Port != file.Port || state.Channel.Token != file.Token {
		t.Fatal("channel endpoint does not match file:", state, e)
	}
	cs.stop()
	if _, e := os.Stat(r.path("channel.json")); !os.IsNotExist(e) {
		t.Fatal("channel.json not removed on stop:", e)
	}
	if _, e := r.ChannelEndpoint(); e == nil || e.Error() != "channel_unavailable" {
		t.Fatal("stopped channel still resolvable:", e)
	}
}

func TestChannelUnavailableWithoutServer(t *testing.T) {
	r := newChannelTestRuntime(t)
	if _, e := r.ChannelEndpoint(); e == nil || e.Error() != "channel_unavailable" {
		t.Fatal("missing channel.json accepted:", e)
	}
	stale := fmt.Sprintf(`{"port":4321,"token":%q,"pid":%d}`,
		strings.Repeat("0123456789abcdef", 2), 1<<22)
	if e := os.WriteFile(r.path("channel.json"), []byte(stale), 0600); e != nil {
		t.Fatal(e)
	}
	if _, e := r.ChannelEndpoint(); e == nil || e.Error() != "channel_unavailable" {
		t.Fatal("stale channel.json with dead pid accepted:", e)
	}
}

func TestChannelWaitSeconds(t *testing.T) {
	for raw, want := range map[string]time.Duration{
		"":    0,
		"0":   0,
		"-3":  0,
		"abc": 0,
		"1":   time.Second,
		"30":  30 * time.Second,
		"31":  30 * time.Second,
		"600": 30 * time.Second,
	} {
		if got := channelWaitSeconds(raw); got != want {
			t.Fatalf("wait %q: got %v, want %v", raw, got, want)
		}
	}
}
