// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"
)

// ---------------------------------------------------------------------------
// cgroup v2 memory governance: memory.high soft limit + pressure-driven GC.
//
// Why best-effort here, when routing is strictly Fail-Closed: a failed
// route guard means traffic could leak or die, so hot_update.go aborts.
// A failed memory limit only loses an optimization — the kernel and the
// Android LMK keep doing their jobs without it. The module must therefore
// never fail to start, and never disturb its main loop, because cgroup
// files were missing, read-only, or shaped unexpectedly: every error in
// this section degrades silently to "no memory governance".
//
// Scope guard: the limit is written only to the module's own dedicated
// child group, which cgroup_child.go establishes before this setup runs.
// After IndependentCgroup the process sits in init's group, which on
// Android is the hierarchy root shared with the whole system — a module
// must never throttle the entire phone, so limits are never written to
// the root group or to any group the module does not own. If the child
// group cannot be established, governance stays off entirely (monitor
// and GC included: a foreign group's memory.current says nothing about
// this module's own pressure).
// ---------------------------------------------------------------------------

const (
	// memoryHighDivisor sizes the soft limit as MemTotal/divisor. The
	// group holds the supervisor plus the core and plugin processes it
	// spawns; their steady-state footprint is far below a quarter of
	// RAM, while a quarter still engages kernel reclaim (memory.high
	// throttles and reclaims — it never OOM-kills like memory.max) long
	// before LMK territory on high-throughput bursts.
	memoryHighDivisor = 4
	// memoryHighMin floors the divisor result to the specified safe
	// lower bound, including on devices with less than 1 GiB of RAM.
	memoryHighMin = 256 << 20
	// memoryHighMax caps the limit on very large devices.
	memoryHighMax = 4 << 30
	// memoryCheckInterval is how often memory.current/events are read.
	memoryCheckInterval = 20 * time.Second
	// memoryPressurePercent is the fraction of the soft limit at which
	// current usage alone counts as pressure.
	memoryPressurePercent = 90
	// memoryGCMinInterval throttles proactive GC under sustained
	// pressure so the cure never becomes a CPU problem of its own.
	memoryGCMinInterval = 30 * time.Second
)

// parseMemTotal extracts MemTotal (bytes) from /proc/meminfo content.
func parseMemTotal(text string) (uint64, bool) {
	for _, line := range strings.Split(text, "\n") {
		fields := strings.Fields(line)
		if len(fields) != 3 || fields[0] != "MemTotal:" || fields[2] != "kB" {
			continue
		}
		kb, err := strconv.ParseUint(fields[1], 10, 64)
		if err != nil || kb == 0 {
			return 0, false
		}
		return kb << 10, true
	}
	return 0, false
}

// memoryHighFor computes the soft limit for a MemTotal; 0 disables
// governance only when the total is unknown.
func memoryHighFor(memTotal uint64) uint64 {
	if memTotal == 0 {
		return 0
	}
	high := memTotal / memoryHighDivisor
	if high < memoryHighMin {
		high = memoryHighMin
	}
	if high > memoryHighMax {
		return memoryHighMax
	}
	return high
}

// memoryEvents is the pair of cgroup v2 memory.events counters the guard
// watches: growth of either means the kernel already had to reclaim
// (high) or hard-fail allocations (max) inside this group.
type memoryEvents struct{ high, max uint64 }

// parseMemoryEvents parses memory.events content ("key value" lines);
// unknown keys are ignored, missing counters stay zero.
func parseMemoryEvents(text string) memoryEvents {
	var ev memoryEvents
	for _, line := range strings.Split(text, "\n") {
		fields := strings.Fields(line)
		if len(fields) != 2 {
			continue
		}
		v, err := strconv.ParseUint(fields[1], 10, 64)
		if err != nil {
			continue
		}
		switch fields[0] {
		case "high":
			ev.high = v
		case "max":
			ev.max = v
		}
	}
	return ev
}

// v2Layout locates this process in the unified (v2) hierarchy: the
// hierarchy path reported by /proc/self/cgroup and the filesystem
// directory it maps to under the cgroup2 mount.
type v2Layout struct {
	hierPath string // own path within the hierarchy ("/", "/apps", ...)
	dir      string // filesystem directory of own group
}

// v2PathOf extracts this process's unified-hierarchy (v2) path from
// /proc/self/cgroup content.
func v2PathOf(selfCgroup string) (string, bool) {
	for _, line := range strings.Split(strings.TrimSpace(selfCgroup), "\n") {
		parts := strings.SplitN(line, ":", 3)
		if len(parts) == 3 && parts[1] == "" && strings.HasPrefix(parts[2], "/") {
			return parts[2], true
		}
	}
	return "", false
}

// resolveV2Layout maps the process's v2 membership to a filesystem
// directory using the first visible cgroup2 mount. ok is false when
// there is no v2 entry, no cgroup2 mount, or the group lies outside the
// mounted subtree.
func resolveV2Layout(selfCgroup, mountinfo string) (v2Layout, bool) {
	ownPath, ok := v2PathOf(selfCgroup)
	if !ok {
		return v2Layout{}, false
	}
	for _, line := range strings.Split(mountinfo, "\n") {
		halves := strings.SplitN(line, " - ", 2)
		if len(halves) != 2 {
			continue
		}
		front, back := strings.Fields(halves[0]), strings.Fields(halves[1])
		if len(front) < 6 || len(back) < 1 || back[0] != "cgroup2" {
			continue
		}
		root, mount := mountPath(front[3]), mountPath(front[4])
		if !filepath.IsAbs(root) || !filepath.IsAbs(mount) {
			continue
		}
		relative, err := filepath.Rel(root, ownPath)
		if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
			continue
		}
		return v2Layout{hierPath: ownPath, dir: filepath.Join(mount, relative)}, true
	}
	return v2Layout{}, false
}

// ownV2CgroupDir resolves the filesystem directory of the unified (v2)
// cgroup this process belongs to. ok is false when the layout cannot be
// resolved, or the group is the hierarchy root (see scope guard above):
// callers that only resolve — and never migrate — must not govern root.
func ownV2CgroupDir(selfCgroup, mountinfo string) (string, bool) {
	layout, ok := resolveV2Layout(selfCgroup, mountinfo)
	if !ok || layout.hierPath == "/" {
		return "", false
	}
	return layout.dir, true
}

// memoryGuard is the pressure state machine. observe is pure (all inputs
// injected) so the trigger and throttle logic is unit-testable; the file
// IO lives in check/run and fails silently.
type memoryGuard struct {
	dir    string
	high   uint64
	primed bool
	lastEv memoryEvents
	lastGC time.Time
}

// observe records one sample and reports whether a proactive GC is due:
// memory.current at or above 90% of the soft limit, or growth of the
// memory.events high/max counters since the previous sample. GCs are
// throttled to memoryGCMinInterval.
func (g *memoryGuard) observe(current uint64, ev memoryEvents, now time.Time) bool {
	if g.high == 0 {
		return false
	}
	pressure := current >= g.high*memoryPressurePercent/100
	if g.primed && (ev.high > g.lastEv.high || ev.max > g.lastEv.max) {
		pressure = true
	}
	g.lastEv = ev
	g.primed = true
	if !pressure || now.Sub(g.lastGC) < memoryGCMinInterval {
		return false
	}
	g.lastGC = now
	return true
}

// check samples the group once and GCs when observe says so. Any read or
// parse problem simply skips the round.
func (g *memoryGuard) check(now time.Time) {
	rawCurrent, err := os.ReadFile(filepath.Join(g.dir, "memory.current"))
	if err != nil {
		return
	}
	current, err := strconv.ParseUint(strings.TrimSpace(string(rawCurrent)), 10, 64)
	if err != nil {
		return
	}
	rawEvents, _ := os.ReadFile(filepath.Join(g.dir, "memory.events"))
	if g.observe(current, parseMemoryEvents(string(rawEvents)), now) {
		runtime.GC()
	}
}

// run is the watchdog loop; it stops with supervision and never errors.
func (g *memoryGuard) run(ctx context.Context, tick time.Duration) {
	t := time.NewTicker(tick)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case now := <-t.C:
			g.check(now)
		}
	}
}

// memoryGovernanceDir resolves the one v2 group governance may touch:
// on Android, the dedicated child group established (and migrated into)
// by ensureMemoryChildGroup; a child-group failure is reported as a
// memory_child_degraded:<n> event so field diagnosis can tell "device
// refused the child group" apart from a healthy setup. On non-Android
// hosts (development machines, CI) the supervisor is never migrated —
// the same stance IndependentCgroup takes — and only an already
// dedicated non-root group is governed, as before.
func (r *Runtime) memoryGovernanceDir() string {
	if runtime.GOOS != "android" {
		self, err := os.ReadFile("/proc/self/cgroup")
		if err != nil {
			return ""
		}
		mounts, err := os.ReadFile("/proc/self/mountinfo")
		if err != nil {
			return ""
		}
		dir, ok := ownV2CgroupDir(string(self), string(mounts))
		if !ok {
			return ""
		}
		return dir
	}
	dir, reason, ok := ensureMemoryChildGroup(procMemoryChildFS())
	if !ok {
		if r != nil {
			r.recordEvent("memory_child_degraded:" + strconv.Itoa(reason.eventCode()))
		}
		return ""
	}
	return dir
}

// setupMemoryGuard resolves the group to govern, computes the soft limit
// from /proc/meminfo and writes memory.high — re-asserted on every
// start, because the child group directory is reused across restarts
// and its previous contents cannot be trusted. It returns nil when
// governance is unavailable for any reason (see the section comment):
// setup failure must never fail supervision.
func (r *Runtime) setupMemoryGuard() *memoryGuard {
	dir := r.memoryGovernanceDir()
	if dir == "" {
		return nil
	}
	meminfo, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return nil
	}
	total, ok := parseMemTotal(string(meminfo))
	if !ok {
		return nil
	}
	high := memoryHighFor(total)
	if high == 0 {
		return nil
	}
	// cgroup control files are kernel pseudo-files: open write-only and
	// write in place, never create/truncate (same rule as cgroup.procs).
	f, err := os.OpenFile(filepath.Join(dir, "memory.high"), os.O_WRONLY, 0)
	if err != nil {
		return nil
	}
	_, writeErr := f.WriteString(strconv.FormatUint(high, 10))
	closeErr := f.Close()
	if writeErr != nil || closeErr != nil {
		return nil
	}
	return &memoryGuard{dir: dir, high: high}
}
