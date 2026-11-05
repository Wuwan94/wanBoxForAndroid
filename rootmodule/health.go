// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

// Phase 2 stability: data-plane health checks, network-change validation,
// optional same-group failover and disconnect-reason classification.
//
// Everything here is bounded: fixed probe cadence, capped retries, capped
// rebuild/failover budgets. Nothing restarts forever, nothing switches nodes
// unless the user enabled it in the manager (default is notify-only).

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// healthCheckFile is staged via the snapshot Files channel (files/), like
// profile_tags.json, so older modules keep accepting newer snapshots.
const healthCheckFile = "health_check.json"

const (
	HealthModeOff    = "off"
	HealthModeNotify = "notify" // default: only report, never switch
	HealthModeAuto   = "auto"   // opt-in: switch to the fastest node of the same group
)

// HealthCheck is the manager-controlled policy. Missing file = notify-only defaults.
type HealthCheck struct {
	Mode            string `json:"mode"`
	IntervalSeconds int    `json:"intervalSeconds"`
	Threshold       int    `json:"threshold"`
	URL             string `json:"url"`
}

func defaultHealthCheck() HealthCheck {
	return HealthCheck{Mode: HealthModeNotify, IntervalSeconds: 300, Threshold: 3, URL: "https://www.gstatic.com/generate_204"}
}

// normalize clamps every field into a safe range; unknown modes fall back to notify.
func (h HealthCheck) normalize() HealthCheck {
	d := defaultHealthCheck()
	switch h.Mode {
	case HealthModeOff, HealthModeNotify, HealthModeAuto:
	default:
		h.Mode = d.Mode
	}
	if h.IntervalSeconds < 60 || h.IntervalSeconds > 3600 {
		h.IntervalSeconds = d.IntervalSeconds
	}
	if h.Threshold < 2 || h.Threshold > 10 {
		h.Threshold = d.Threshold
	}
	if u, e := url.Parse(h.URL); e != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || len(h.URL) > 512 {
		h.URL = d.URL
	}
	return h
}

func (r *Runtime) loadHealthCheck(rev string) HealthCheck {
	var h HealthCheck
	if readJSON(r.path("generations", rev, "files", healthCheckFile), &h, 16<<10) != nil {
		return defaultHealthCheck()
	}
	return h.normalize()
}

// Disconnect / reconnect reason classes. These are the only codes the manager
// needs to understand to explain "why did it drop".
const (
	ReasonCoreExit      = "disconnect_core_exit"
	ReasonNetworkChange = "disconnect_network_change"
	ReasonNodeFail      = "disconnect_node_fail"
	ReasonTunFail       = "disconnect_tun_fail"
	ReasonUnknown       = "disconnect_unknown"
)

// errNetworkRebuild asks the supervisor loop to rebuild the generation (TUN)
// after a network change failed validation. It never consumes crash budget.
var errNetworkRebuild = errors.New("network_rebuild")

// classifyExit maps a generation exit error to a reason class.
// startup_timeout means the core never reported ready (TUN/inbound setup) within
// the startup window, so it is classified as a TUN failure (best available signal).
func classifyExit(err error) string {
	if err == nil {
		return ""
	}
	switch err.Error() {
	case "core_exited", "core_killed_after_timeout", "plugin_exited", "plugin_start_failed", "plugin_identity_failed":
		return ReasonCoreExit
	case "startup_timeout", "core_start_failed", "core_identity_failed":
		return ReasonTunFail
	case errNetworkRebuild.Error():
		return ReasonNetworkChange
	default:
		return ReasonUnknown
	}
}

// exitCode returns a safe lowercase event suffix for an exit error.
func exitCode(err error) string {
	if err == nil {
		return "none"
	}
	code := err.Error()
	if !eventCode.MatchString(code) || strings.Contains(code, ":") {
		return "unknown"
	}
	return code
}

// maxCoreAttempts / maxRetryBackoff bound the supervisor watchdog.
const maxCoreAttempts = 5
const maxRetryBackoff = 30 * time.Second

// networkFingerprint summarizes the physical uplinks (name + global addresses).
// Tunnels, loopback and down interfaces are ignored, so our own TUN never
// counts as a network change.
func networkFingerprint() string {
	ifaces, e := net.Interfaces()
	if e != nil {
		return ""
	}
	var parts []string
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 || ignoredInterface(iface.Name) {
			continue
		}
		addrs, e := iface.Addrs()
		if e != nil {
			continue
		}
		for _, a := range addrs {
			ipnet, ok := a.(*net.IPNet)
			if !ok || !ipnet.IP.IsGlobalUnicast() || ipnet.IP.IsLinkLocalUnicast() {
				continue
			}
			parts = append(parts, iface.Name+"="+ipnet.IP.String())
		}
	}
	sort.Strings(parts)
	sum := sha256.Sum256([]byte(strings.Join(parts, ",")))
	return hex.EncodeToString(sum[:8])
}

func ignoredInterface(name string) bool {
	for _, prefix := range []string{"tun", "wanbox", "dummy", "lo", "ifb", "sit", "ip6tnl", "ip_vti", "p2p"} {
		if strings.HasPrefix(name, prefix) {
			return true
		}
	}
	return false
}

// clashClient talks to the running generation's Clash API on loopback only.
type clashClient struct {
	base   string
	secret string
	client *http.Client
}

func (r *Runtime) clashClientFor(rev string) (*clashClient, error) {
	var cfg struct {
		Experimental struct {
			ClashAPI *struct {
				ExternalController string `json:"external_controller"`
				Secret             string `json:"secret"`
			} `json:"clash_api"`
		} `json:"experimental"`
	}
	if !revisionName.MatchString(rev) {
		return nil, errors.New("module_not_running")
	}
	if e := readJSON(r.path("generations", rev, "run", "config.json"), &cfg, MaxConfigBytes); e != nil {
		return nil, errors.New("clash_api_disabled")
	}
	api := cfg.Experimental.ClashAPI
	if api == nil || api.ExternalController == "" {
		return nil, errors.New("clash_api_disabled")
	}
	_, port, e := net.SplitHostPort(api.ExternalController)
	if e != nil || port == "" {
		return nil, errors.New("clash_api_disabled")
	}
	return &clashClient{
		base:   "http://127.0.0.1:" + port,
		secret: api.Secret,
		client: &http.Client{
			Timeout:   8 * time.Second,
			Transport: &http.Transport{Proxy: nil, MaxIdleConns: 1, IdleConnTimeout: 5 * time.Second, DisableCompression: true},
			CheckRedirect: func(*http.Request, []*http.Request) error {
				return errors.New("redirect_forbidden")
			},
		},
	}, nil
}

func (c *clashClient) call(ctx context.Context, method, path string, body []byte) (int, []byte, error) {
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req, e := http.NewRequestWithContext(ctx, method, c.base+path, reader)
	if e != nil {
		return 0, nil, e
	}
	if c.secret != "" {
		req.Header.Set("Authorization", "Bearer "+c.secret)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, e := c.client.Do(req)
	if e != nil {
		return 0, nil, e
	}
	defer resp.Body.Close()
	data, e := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	return resp.StatusCode, data, e
}

// delay measures one outbound through the core (Clash API /proxies/<name>/delay).
func (c *clashClient) delay(ctx context.Context, name, testURL string, timeout time.Duration) (int, bool) {
	q := url.Values{}
	q.Set("url", testURL)
	q.Set("timeout", strconv.FormatInt(timeout.Milliseconds(), 10))
	code, data, e := c.call(ctx, http.MethodGet, "/proxies/"+url.PathEscape(name)+"/delay?"+q.Encode(), nil)
	if e != nil || code != http.StatusOK {
		return 0, false
	}
	var out struct {
		Delay int `json:"delay"`
	}
	if json.Unmarshal(data, &out) != nil || out.Delay <= 0 {
		return 0, false
	}
	return out.Delay, true
}

func (c *clashClient) selector(ctx context.Context) (now string, all []string, err error) {
	code, data, e := c.call(ctx, http.MethodGet, "/proxies/"+clashProxySelector, nil)
	if e != nil || code != http.StatusOK {
		return "", nil, errors.New("node_select_failed")
	}
	var sel struct {
		Type string   `json:"type"`
		Now  string   `json:"now"`
		All  []string `json:"all"`
	}
	if json.Unmarshal(data, &sel) != nil || !strings.EqualFold(sel.Type, "selector") {
		return "", nil, errors.New("node_select_unsupported")
	}
	return sel.Now, sel.All, nil
}

// maxFailoverCandidates bounds how many members one failover round probes.
const maxFailoverCandidates = 24

// bestCandidate probes the selector members (excluding the current one) in
// parallel and returns the fastest healthy tag.
func (c *clashClient) bestCandidate(ctx context.Context, now string, all []string, testURL string) (string, bool) {
	type result struct {
		tag   string
		delay int
	}
	var candidates []string
	for _, tag := range all {
		if tag != now && tag != "" && tag != "direct" && tag != "block" {
			candidates = append(candidates, tag)
		}
		if len(candidates) >= maxFailoverCandidates {
			break
		}
	}
	results := make(chan result, len(candidates))
	sem := make(chan struct{}, 4)
	var wg sync.WaitGroup
	for _, tag := range candidates {
		wg.Add(1)
		go func(tag string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			if d, ok := c.delay(ctx, tag, testURL, 5*time.Second); ok {
				results <- result{tag, d}
			}
		}(tag)
	}
	wg.Wait()
	close(results)
	best := result{}
	for res := range results {
		if best.tag == "" || res.delay < best.delay {
			best = res
		}
	}
	return best.tag, best.tag != ""
}

// profileForTag maps a selector tag back to the manager profile id.
func (r *Runtime) profileForTag(rev, tag string) (int64, ProfileTag, bool) {
	var tags map[string]ProfileTag
	if readJSON(r.path("generations", rev, "files", profileTagFile), &tags, 1<<20) != nil {
		return 0, ProfileTag{}, false
	}
	for id, pt := range tags {
		if pt.Tag == tag {
			n, e := strconv.ParseInt(id, 10, 64)
			if e == nil && n > 0 {
				return n, pt, true
			}
		}
	}
	return 0, ProfileTag{}, false
}

// Failover performs one manual "switch to the fastest node in this group"
// (the one-tap action offered by notify mode). It reuses SelectNode so the
// switch is confirmed by read-back exactly like a user selection.
func (r *Runtime) Failover(ctx context.Context) error {
	st := r.Status()
	if st.Phase != "connected" || st.RunningRevision == "" {
		return errors.New("module_not_running")
	}
	rev := st.RunningRevision
	c, e := r.clashClientFor(rev)
	if e != nil {
		return e
	}
	hc := r.loadHealthCheck(rev)
	now, all, e := c.selector(ctx)
	if e != nil {
		return e
	}
	tag, ok := c.bestCandidate(ctx, now, all, hc.URL)
	if !ok {
		r.recordEvent("node_failover_no_candidate")
		return errors.New("node_failover_no_candidate")
	}
	id, _, ok := r.profileForTag(rev, tag)
	if !ok {
		return errors.New("node_tag_not_found")
	}
	if e := r.SelectNode(ctx, id); e != nil {
		return e
	}
	r.recordEvent("node_failover_manual")
	return nil
}

// monitor runs alongside a connected generation. It returns errNetworkRebuild
// when a network change could not be validated after bounded retries and the
// rebuild budget allows it; otherwise it runs until ctx is done.
type monitorDeps struct {
	fingerprint func() string
	probe       func(ctx context.Context) bool
	failover    func(ctx context.Context) bool
	record      func(code string)
	setHealth   func(health string)
	now         func() time.Time
	tick        time.Duration
	retryDelays []time.Duration
}

// rebuildBudget allows at most 2 TUN rebuilds per 10 minutes (shared across generations).
type budget struct {
	mu     sync.Mutex
	window time.Duration
	max    int
	hits   []time.Time
}

func (b *budget) allow(now time.Time) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	kept := b.hits[:0]
	for _, t := range b.hits {
		if now.Sub(t) < b.window {
			kept = append(kept, t)
		}
	}
	b.hits = kept
	if len(b.hits) >= b.max {
		return false
	}
	b.hits = append(b.hits, now)
	return true
}

var rebuildBudget = &budget{window: 10 * time.Minute, max: 2}
var failoverBudget = &budget{window: time.Hour, max: 3}

func runMonitor(ctx context.Context, hc HealthCheck, d monitorDeps) error {
	last := d.fingerprint()
	interval := time.Duration(hc.IntervalSeconds) * time.Second
	nextHealth := d.now().Add(interval)
	failures := 0
	degraded := false
	t := time.NewTicker(d.tick)
	defer t.Stop()
	wait := func(delay time.Duration) bool {
		select {
		case <-ctx.Done():
			return false
		case <-time.After(delay):
			return true
		}
	}
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-t.C:
		}
		if fp := d.fingerprint(); fp != "" && fp != last {
			last = fp
			d.record("network_changed")
			validated := false
			for _, delay := range d.retryDelays {
				if !wait(delay) {
					return nil
				}
				if d.probe(ctx) {
					validated = true
					break
				}
			}
			if validated {
				d.record("network_validated")
				if degraded {
					degraded = false
					failures = 0
					d.setHealth("ok")
				}
			} else {
				d.record(ReasonNetworkChange)
				if rebuildBudget.allow(d.now()) {
					d.record("tun_rebuild")
					return errNetworkRebuild
				}
				d.record("tun_rebuild_limited")
				d.setHealth("degraded")
				degraded = true
			}
			nextHealth = d.now().Add(interval)
			continue
		}
		if hc.Mode == HealthModeOff || d.now().Before(nextHealth) {
			continue
		}
		nextHealth = d.now().Add(interval)
		if d.probe(ctx) {
			if degraded {
				d.record("node_recovered")
				d.setHealth("ok")
			}
			failures = 0
			degraded = false
			continue
		}
		failures++
		if failures < hc.Threshold || degraded {
			continue
		}
		degraded = true
		d.record("node_unhealthy")
		d.record(ReasonNodeFail)
		d.setHealth("degraded")
		if hc.Mode == HealthModeAuto {
			if failoverBudget.allow(d.now()) && d.failover(ctx) {
				d.record("node_failover")
				failures = 0
				degraded = false
				d.setHealth("ok")
			} else {
				d.record("node_failover_skipped")
			}
		}
	}
}

// monitorGeneration wires runMonitor to the live generation and supervisor state.
func (r *Runtime) monitorGeneration(ctx context.Context, rev string, s *State, mu *sync.Mutex, persist func()) error {
	hc := r.loadHealthCheck(rev)
	c, e := r.clashClientFor(rev)
	if e != nil {
		// No Clash API: network changes are still logged, data-plane probes are impossible.
		c = nil
	}
	probe := func(ctx context.Context) bool {
		if c == nil {
			return true
		}
		pctx, cancel := context.WithTimeout(ctx, 8*time.Second)
		defer cancel()
		_, ok := c.delay(pctx, clashProxySelector, hc.URL, 5*time.Second)
		return ok
	}
	failover := func(ctx context.Context) bool {
		if c == nil {
			return false
		}
		fctx, cancel := context.WithTimeout(ctx, 45*time.Second)
		defer cancel()
		now, all, e := c.selector(fctx)
		if e != nil {
			return false
		}
		tag, ok := c.bestCandidate(fctx, now, all, hc.URL)
		if !ok {
			return false
		}
		id, pt, ok := r.profileForTag(rev, tag)
		if !ok || r.SelectNode(fctx, id) != nil {
			return false
		}
		mu.Lock()
		s.ProfileID = id
		if pt.Name != "" {
			s.ProfileName = pt.Name
		}
		persist()
		mu.Unlock()
		return true
	}
	return runMonitor(ctx, hc, monitorDeps{
		fingerprint: networkFingerprint,
		probe:       probe,
		failover:    failover,
		record:      r.recordEvent,
		setHealth: func(health string) {
			mu.Lock()
			if s.Health != health {
				s.Health = health
				persist()
			}
			mu.Unlock()
		},
		now:         time.Now,
		tick:        5 * time.Second,
		retryDelays: []time.Duration{3 * time.Second, 5 * time.Second, 10 * time.Second},
	})
}
