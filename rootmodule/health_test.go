// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"
)

func TestClassifyExitReasons(t *testing.T) {
	cases := map[string]string{
		"core_exited":               ReasonCoreExit,
		"core_killed_after_timeout": ReasonCoreExit,
		"plugin_exited":             ReasonCoreExit,
		"startup_timeout":           ReasonTunFail,
		"core_start_failed":         ReasonTunFail,
		"network_rebuild":           ReasonNetworkChange,
		"something_else":            ReasonUnknown,
	}
	for code, want := range cases {
		if got := classifyExit(errors.New(code)); got != want {
			t.Fatalf("%s => %s, want %s", code, got, want)
		}
	}
	if classifyExit(nil) != "" {
		t.Fatal("nil error must not be classified")
	}
	if exitCode(errors.New("password=leak")) != "unknown" || exitCode(errors.New("core_exited")) != "core_exited" {
		t.Fatal("exit code must be a fixed safe code")
	}
}

func TestRetryBackoffIsCappedExponential(t *testing.T) {
	want := []time.Duration{time.Second, 2 * time.Second, 4 * time.Second, 8 * time.Second, 16 * time.Second, 30 * time.Second, 30 * time.Second}
	for i, w := range want {
		if got := retryBackoff(i); got != w {
			t.Fatalf("attempt %d backoff %v want %v", i, got, w)
		}
	}
	if retryBackoff(99) != maxRetryBackoff {
		t.Fatal("backoff not capped")
	}
	if maxCoreAttempts < 2 || maxCoreAttempts > 8 {
		t.Fatal("watchdog attempts must stay bounded")
	}
}

func TestEventCodeAcceptsNumericSuffixOnly(t *testing.T) {
	r, _ := fixture(t)
	if e := r.init(); e != nil {
		t.Fatal(e)
	}
	r.recordEvent("node_hot_switch_ms:420")
	r.recordEvent("node_hot_switch_ms:abc")
	r.recordEvent("password=1:2")
	r.recordEvent("x:1:2")
	events := r.Events()
	if len(events) != 1 || events[0].Code != "node_hot_switch_ms:420" {
		t.Fatal("unexpected events", events)
	}
}

func TestHealthCheckNormalize(t *testing.T) {
	h := HealthCheck{Mode: "rm -rf", IntervalSeconds: 1, Threshold: 99, URL: "file:///etc/passwd"}.normalize()
	d := defaultHealthCheck()
	if h != d {
		t.Fatal("unsafe health config not normalized", h)
	}
	if d.Mode != HealthModeNotify {
		t.Fatal("default must be notify-only")
	}
	ok := HealthCheck{Mode: HealthModeAuto, IntervalSeconds: 120, Threshold: 4, URL: "https://cp.cloudflare.com/"}.normalize()
	if ok.Mode != HealthModeAuto || ok.IntervalSeconds != 120 || ok.Threshold != 4 {
		t.Fatal("valid config altered", ok)
	}
	r, _ := fixture(t)
	if got := r.loadHealthCheck("missing"); got != d {
		t.Fatal("missing file must use defaults")
	}
}

type fakeMonitor struct {
	mu        sync.Mutex
	fp        string
	probeOK   bool
	failoverO bool
	events    []string
	health    string
	failovers int
	clock     time.Time
}

func (f *fakeMonitor) deps() monitorDeps {
	return monitorDeps{
		fingerprint: func() string { f.mu.Lock(); defer f.mu.Unlock(); return f.fp },
		probe:       func(context.Context) bool { f.mu.Lock(); defer f.mu.Unlock(); return f.probeOK },
		failover: func(context.Context) bool {
			f.mu.Lock()
			defer f.mu.Unlock()
			f.failovers++
			return f.failoverO
		},
		record:    func(c string) { f.mu.Lock(); f.events = append(f.events, c); f.mu.Unlock() },
		setHealth: func(h string) { f.mu.Lock(); f.health = h; f.mu.Unlock() },
		now: func() time.Time {
			f.mu.Lock()
			defer f.mu.Unlock()
			f.clock = f.clock.Add(time.Hour) // every check is "due"
			return f.clock
		},
		tick:        time.Millisecond,
		retryDelays: []time.Duration{time.Millisecond, time.Millisecond},
	}
}

func (f *fakeMonitor) has(code string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, e := range f.events {
		if e == code {
			return true
		}
	}
	return false
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(2 * time.Millisecond)
	}
	t.Fatal("condition not reached")
}

func TestMonitorNotifyModeNeverSwitches(t *testing.T) {
	f := &fakeMonitor{fp: "a", probeOK: false, clock: time.Unix(0, 0)}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- runMonitor(ctx, HealthCheck{Mode: HealthModeNotify, IntervalSeconds: 60, Threshold: 3}, f.deps()) }()
	waitFor(t, func() bool { return f.has("node_unhealthy") })
	cancel()
	<-done
	if f.failovers != 0 || f.health != "degraded" || !f.has(ReasonNodeFail) {
		t.Fatal("notify mode must only report", f.events, f.failovers, f.health)
	}
}

func TestMonitorAutoModeFailsOverWithinBudget(t *testing.T) {
	failoverBudget = &budget{window: time.Hour, max: 3}
	f := &fakeMonitor{fp: "a", probeOK: false, failoverO: true, clock: time.Unix(0, 0)}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- runMonitor(ctx, HealthCheck{Mode: HealthModeAuto, IntervalSeconds: 60, Threshold: 2}, f.deps()) }()
	waitFor(t, func() bool { return f.has("node_failover") })
	cancel()
	<-done
	if f.failovers == 0 {
		t.Fatal("auto mode did not fail over")
	}
}

func TestMonitorOffModeSkipsHealthProbe(t *testing.T) {
	f := &fakeMonitor{fp: "a", probeOK: false, clock: time.Unix(0, 0)}
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	_ = runMonitor(ctx, HealthCheck{Mode: HealthModeOff, IntervalSeconds: 60, Threshold: 2}, f.deps())
	if f.has("node_unhealthy") {
		t.Fatal("off mode must not judge node health")
	}
}

func TestMonitorNetworkChangeValidatedOrRebuilt(t *testing.T) {
	// Validated: probe OK after the change.
	f := &fakeMonitor{fp: "a", probeOK: true, clock: time.Unix(0, 0)}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- runMonitor(ctx, HealthCheck{Mode: HealthModeOff, IntervalSeconds: 60, Threshold: 3}, f.deps()) }()
	time.Sleep(5 * time.Millisecond)
	f.mu.Lock()
	f.fp = "b"
	f.mu.Unlock()
	waitFor(t, func() bool { return f.has("network_validated") })
	cancel()
	<-done

	// Not validated: bounded retries, then a rebuild request (budgeted).
	rebuildBudget = &budget{window: 10 * time.Minute, max: 2}
	g := &fakeMonitor{fp: "a", probeOK: false, clock: time.Unix(0, 0)}
	ctx2, cancel2 := context.WithCancel(context.Background())
	defer cancel2()
	done2 := make(chan error, 1)
	go func() { done2 <- runMonitor(ctx2, HealthCheck{Mode: HealthModeOff, IntervalSeconds: 60, Threshold: 3}, g.deps()) }()
	time.Sleep(5 * time.Millisecond)
	g.mu.Lock()
	g.fp = "c"
	g.mu.Unlock()
	select {
	case err := <-done2:
		if !errors.Is(err, errNetworkRebuild) || !g.has(ReasonNetworkChange) || !g.has("tun_rebuild") {
			t.Fatal("expected rebuild request", err, g.events)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("monitor did not request rebuild")
	}
}

func TestBudgetLimitsRebuilds(t *testing.T) {
	b := &budget{window: 10 * time.Minute, max: 2}
	now := time.Unix(1000, 0)
	if !b.allow(now) || !b.allow(now.Add(time.Minute)) || b.allow(now.Add(2*time.Minute)) {
		t.Fatal("budget exceeded")
	}
	if !b.allow(now.Add(11 * time.Minute)) {
		t.Fatal("budget window does not slide")
	}
}

func TestFailoverRequiresRunningCore(t *testing.T) {
	r, _ := fixture(t)
	if e := r.init(); e != nil {
		t.Fatal(e)
	}
	if err := r.Failover(context.Background()); err == nil || err.Error() != "module_not_running" {
		t.Fatal("failover without a running core", err)
	}
}

func TestIgnoredInterfaces(t *testing.T) {
	for _, name := range []string{"tun0", "wanbox0", "lo", "dummy0"} {
		if !ignoredInterface(name) {
			t.Fatal(name)
		}
	}
	for _, name := range []string{"wlan0", "rmnet_data0", "eth0"} {
		if ignoredInterface(name) {
			t.Fatal(name)
		}
	}
	_ = networkFingerprint()
}
