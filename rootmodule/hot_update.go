// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

// v3.0.15 hot-update hardening: transient fail-closed route guard, a 5-second
// canary window before the previous module backup is dropped, update-marker
// cleanup and the committed-version broadcast event.
//
// Guard rule priority: the TUN auto-route lives at pref 9000, so the guard
// blackhole sits at pref 9001 — while a core is up, traffic matches the TUN
// rule first; during the switch gap (no TUN rule) it falls into the
// blackhole instead of leaking onto the physical default route.

import (
	"context"
	"errors"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const guardRulePref = "9001"

// routeGuard installs or removes the transient blackhole rule. Test seams
// replace it wholesale; the default shells out to iproute2 like the rest of
// the module's fixed system commands.
func (r *Runtime) routeGuard(ctx context.Context, enable bool) error {
	if r.installRouteGuard != nil {
		return r.installRouteGuard(ctx, enable)
	}
	return defaultRouteGuard(ctx, enable)
}

// guardRun executes one iproute2 invocation for the route guard. It is a
// package-level seam so tests can script ip(8) outcomes without root or
// iproute2 installed; production behavior is unchanged (LookPath +
// CombinedOutput, like the rest of the module's fixed system commands).
var guardRun = func(ctx context.Context, args ...string) (string, error) {
	ip, err := exec.LookPath("ip")
	if err != nil {
		return "", err
	}
	out, err := exec.CommandContext(ctx, ip, args...).CombinedOutput()
	return string(out), err
}

// defaultRouteGuard is strictly fail-closed (CONTRIBUTING §1.3): any
// failure to install the guard — missing binary, missing capability, kernel
// refusal — aborts the switch before the old core stops. There is no
// degraded mode; test environments seam installRouteGuard instead.
func defaultRouteGuard(ctx context.Context, enable bool) error {
	if enable {
		out, err := guardRun(ctx, "rule", "add", "pref", guardRulePref, "blackhole")
		if err == nil || strings.Contains(out, "File exists") {
			return nil // installed, or already installed (idempotent)
		}
		return errors.New("hot_update_guard_failed")
	}
	return drainGuardRules(ctx)
}

// guardDrainMaxAttempts bounds the pref-9001 drain loop below. Identical
// rules stack: every "ip rule add" appends another copy and a single
// "ip rule del" removes only one, so teardown must keep deleting until the
// kernel reports the rule absent — a leftover copy keeps blackholing all
// traffic with no core up (orphan outage). The cap only guards against a
// pathological ip(8) that reports success without deleting; hitting it is
// an error, never silent success (Fail-Closed).
const guardDrainMaxAttempts = 128

// drainGuardRules deletes pref-9001 rules until none remain. Only the
// kernel's explicit "No such file / No such process" proves the drain is
// complete; any other failure aborts (Fail-Closed).
func drainGuardRules(ctx context.Context) error {
	for attempt := 0; attempt < guardDrainMaxAttempts; attempt++ {
		out, err := guardRun(ctx, "rule", "del", "pref", guardRulePref)
		if err == nil {
			continue // one copy removed; duplicates may remain
		}
		if strings.Contains(out, "No such file") || strings.Contains(out, "No such process") {
			return nil // drained: no pref-9001 rule remains
		}
		return errors.New("hot_update_guard_failed")
	}
	return errors.New("hot_update_guard_failed")
}

// clearUpdateMarker removes the manager's pending-update flag from the
// installed module directory. After a hot switch the running code IS the
// update, so a stale flag would keep Magisk/KernelSU showing
// "reboot to apply" forever.
func clearUpdateMarker(target string) {
	_ = os.Remove(filepath.Join(target, "update"))
}

// moduleVersionCode reads versionCode from a module.prop file.
func moduleVersionCode(dir string) (int, bool) {
	b, err := os.ReadFile(filepath.Join(dir, "module.prop"))
	if err != nil {
		return 0, false
	}
	for _, line := range strings.Split(string(b), "\n") {
		if strings.HasPrefix(line, "versionCode=") {
			code, err := strconv.Atoi(strings.TrimSpace(strings.TrimPrefix(line, "versionCode=")))
			if err == nil && code > 0 {
				return code, true
			}
		}
	}
	return 0, false
}

// runCanary observes the freshly started core for the canary window. The
// previous module backup still exists while it runs, so a failure can roll
// back. A tick fails when the core process identity is gone or the phase
// reports failure; when the Clash controller is configured it must also
// answer at least once before the window closes.
func (r *Runtime) runCanary(ctx context.Context, target string) bool {
	window := r.installCanaryWindow
	if window <= 0 {
		window = 5 * time.Second
	}
	poll := r.installCanaryPoll
	if poll <= 0 {
		poll = 500 * time.Millisecond
	}
	controllerConfigured, controllerOK := false, false
	check := r.installCanaryCheck
	if check == nil {
		check = func(ctx context.Context) bool {
			st := New(r.Root, target).Status()
			if st.Phase == "failed" || !SameProcess(st.Core) {
				return false
			}
			if client, err := r.clashClientFor(st.RunningRevision); err == nil {
				controllerConfigured = true
				probe, cancel := context.WithTimeout(ctx, time.Second)
				code, _, err := client.call(probe, http.MethodGet, "/version", nil)
				cancel()
				if err == nil && code == http.StatusOK {
					controllerOK = true
				}
			}
			return true
		}
	}
	deadline := time.NewTimer(window)
	defer deadline.Stop()
	ticker := time.NewTicker(poll)
	defer ticker.Stop()
	for {
		if !check(ctx) {
			return false
		}
		select {
		case <-ctx.Done():
			return false
		case <-deadline.C:
			return !controllerConfigured || controllerOK
		case <-ticker.C:
		}
	}
}
