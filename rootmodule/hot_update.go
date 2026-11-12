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

var errGuardUnsupported = errors.New("hot_update_guard_unsupported")

// routeGuard installs or removes the transient blackhole rule. Test seams
// replace it wholesale; the default shells out to iproute2 like the rest of
// the module's fixed system commands.
func (r *Runtime) routeGuard(ctx context.Context, enable bool) error {
	if r.installRouteGuard != nil {
		return r.installRouteGuard(ctx, enable)
	}
	return defaultRouteGuard(ctx, enable)
}

func defaultRouteGuard(ctx context.Context, enable bool) error {
	ip, err := exec.LookPath("ip")
	if err != nil {
		return errGuardUnsupported
	}
	var args []string
	if enable {
		args = []string{"rule", "add", "pref", guardRulePref, "blackhole"}
	} else {
		args = []string{"rule", "del", "pref", guardRulePref}
	}
	out, err := exec.CommandContext(ctx, ip, args...).CombinedOutput()
	if err == nil {
		return nil
	}
	text := string(out)
	switch {
	case strings.Contains(text, "Operation not permitted"):
		// Sandboxed/CI kernels without CAP_NET_ADMIN: no code on this host
		// could install the rule. Production root shells have the capability,
		// so any other failure stays fail-closed and aborts the switch.
		return errGuardUnsupported
	case enable && strings.Contains(text, "File exists"):
		return nil // already installed (idempotent)
	case !enable && (strings.Contains(text, "No such file") || strings.Contains(text, "No such process")):
		return nil // already gone (idempotent)
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
