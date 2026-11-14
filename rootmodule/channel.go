// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Loopback long-poll channel: a read-only HTTP view of the exact status and
// dashboard payloads the wanboxctl CLI prints, served by the supervisor on
// 127.0.0.1 only. The manager currently spawns one `su` bridge process per
// poll (100-300ms each); with this channel it can instead hold a long poll
// that returns the moment the state revision advances.
//
// Why best-effort here, when routing is strictly Fail-Closed: a failed route
// guard means traffic could leak or die, so hot_update.go aborts. A failed
// channel only loses an optimization — the manager falls back to the
// su/stdio bridge and supervision runs unchanged — so startup failure is
// recorded as an event and never propagated, and the server exposes no
// mutating endpoint at all: the two GET views above are the whole surface.

// channelFile is the on-disk channel.json contract: how a root CLI process
// (or any local root reader) finds the supervisor's loopback server. The
// token is a per-supervisor random secret; the file is 0600 in the same
// private state directory as state.json.
type channelFile struct {
	Port  int    `json:"port"`
	Token string `json:"token"`
	PID   int    `json:"pid"`
}

const maxChannelWait = 30 * time.Second

// changeNotifier fans a state-write out to any number of long-poll waiters.
// notify closes the current generation channel and arms a fresh one; waiters
// snapshot the channel before blocking, so a notify that lands between the
// revision check and the snapshot can never be missed (they re-check the
// revision after every wake).
type changeNotifier struct {
	mu sync.Mutex
	ch chan struct{}
}

func newChangeNotifier() *changeNotifier {
	return &changeNotifier{ch: make(chan struct{})}
}

func (n *changeNotifier) notify() {
	n.mu.Lock()
	defer n.mu.Unlock()
	close(n.ch)
	n.ch = make(chan struct{})
}

func (n *changeNotifier) channel() chan struct{} {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.ch
}

// notifyStateChanged is called by the supervisor after every successful
// state.json write (the persist path and the connected transition). It is a
// no-op for Runtime values that never run a supervisor (CLI one-shots, most
// tests), where changes is nil.
func (r *Runtime) notifyStateChanged() {
	if r.changes != nil {
		r.changes.notify()
	}
}

func (r *Runtime) stateChanged() chan struct{} {
	if r.changes == nil {
		return nil // a nil channel blocks forever; the wait deadline still fires
	}
	return r.changes.channel()
}

// ChannelEndpoint resolves the running supervisor's loopback channel from
// channel.json for the read-only `channel` CLI command. A missing/stale file
// or a dead supervisor pid is channel_unavailable, following the module's
// fixed error-code contract.
func (r *Runtime) ChannelEndpoint() (State, error) {
	state := r.Status()
	var file channelFile
	if e := readJSON(r.path("channel.json"), &file, 4<<10); e != nil {
		return state, errors.New("channel_unavailable")
	}
	if file.Port <= 0 || file.Port > 65535 || file.PID <= 1 ||
		len(file.Token) != 32 || strings.Trim(file.Token, "0123456789abcdef") != "" {
		return state, errors.New("channel_unavailable")
	}
	if _, e := Identity(file.PID); e != nil {
		return state, errors.New("channel_unavailable")
	}
	state.Channel = &ChannelEndpoint{Port: file.Port, Token: file.Token}
	return state, nil
}

// waitStateResponse renders the response for a channel GET. When since is
// non-empty it differs from the payload's current state.revision, or wait
// expires — whichever comes first. Revision advances are pushed by
// notifyStateChanged; no polling loop is involved. A nil result means the
// client went away before the wait ended.
func (r *Runtime) waitStateResponse(ctx context.Context, command, since string, wait time.Duration) []byte {
	deadline := time.Now().Add(wait)
	for {
		body := r.CommandResponse(command, nil)
		if since == "" || renderedRevision(body) != since {
			return body
		}
		remaining := time.Until(deadline)
		if remaining <= 0 {
			return body
		}
		changed := r.stateChanged()
		timer := time.NewTimer(remaining)
		select {
		case <-changed:
			timer.Stop()
		case <-timer.C:
		case <-ctx.Done():
			timer.Stop()
			return nil
		}
	}
}

// renderedRevision extracts state.revision from a rendered channel body,
// so the long-poll comparison is made against the very snapshot that will
// be returned — never against a second, later read of the state.
func renderedRevision(body []byte) string {
	var payload struct {
		State struct {
			Revision string `json:"revision"`
		} `json:"state"`
	}
	if json.Unmarshal(body, &payload) != nil {
		return ""
	}
	return payload.State.Revision
}

// channelWaitSeconds parses the ?wait= parameter: whole seconds, clamped to
// the documented 1..30 range; anything unusable means "do not wait".
func channelWaitSeconds(raw string) time.Duration {
	if raw == "" {
		return 0
	}
	n, e := strconv.Atoi(raw)
	if e != nil || n <= 0 {
		return 0
	}
	if d := time.Duration(n) * time.Second; d < maxChannelWait {
		return d
	}
	return maxChannelWait
}

type channelServer struct {
	runtime *Runtime
	token   string
	server  *http.Server
	file    string
	once    sync.Once
}

// startChannelServer binds 127.0.0.1 on a kernel-assigned port, publishes
// channel.json (0600, atomic via jsonWrite) and serves until ctx ends or
// stop is called. Any failure is recorded as an event and reported as a nil
// server: the caller (Supervise) must run identically without the channel.
func (r *Runtime) startChannelServer(ctx context.Context) *channelServer {
	raw := make([]byte, 16)
	if _, e := rand.Read(raw); e != nil {
		r.recordEvent("channel_start_failed")
		return nil
	}
	listener, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		r.recordEvent("channel_start_failed")
		return nil
	}
	cs := &channelServer{
		runtime: r,
		token:   hex.EncodeToString(raw),
		file:    r.path("channel.json"),
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/status", func(w http.ResponseWriter, req *http.Request) {
		cs.serveState(w, req, "status")
	})
	mux.HandleFunc("/v1/dashboard", func(w http.ResponseWriter, req *http.Request) {
		cs.serveState(w, req, "dashboard")
	})
	cs.server = &http.Server{Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	addr, ok := listener.Addr().(*net.TCPAddr)
	if !ok {
		_ = listener.Close()
		r.recordEvent("channel_start_failed")
		return nil
	}
	if e := jsonWrite(cs.file, channelFile{Port: addr.Port, Token: cs.token, PID: os.Getpid()}); e != nil {
		_ = listener.Close()
		r.recordEvent("channel_start_failed")
		return nil
	}
	go func() { _ = cs.server.Serve(listener) }()
	go func() {
		<-ctx.Done()
		cs.stop()
	}()
	r.recordEvent("channel_started")
	return cs
}

// stop shuts the server down and removes channel.json so a later CLI read
// fails closed with channel_unavailable instead of dialing a dead port. It
// is best-effort and idempotent (ctx watcher and the supervisor's defer can
// both call it).
func (cs *channelServer) stop() {
	cs.once.Do(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = cs.server.Shutdown(ctx)
		_ = os.Remove(cs.file)
	})
}

func (cs *channelServer) authorized(req *http.Request) bool {
	got := req.Header.Get("X-Wanbox-Token")
	return subtle.ConstantTimeCompare([]byte(got), []byte(cs.token)) == 1
}

func (cs *channelServer) serveState(w http.ResponseWriter, req *http.Request, command string) {
	if !cs.authorized(req) {
		// 403 with an empty body: never confirm anything about the payload.
		w.WriteHeader(http.StatusForbidden)
		return
	}
	if req.Method != http.MethodGet {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	query := req.URL.Query()
	body := cs.runtime.waitStateResponse(req.Context(), command,
		query.Get("since"), channelWaitSeconds(query.Get("wait")))
	if body == nil {
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write(body)
}
