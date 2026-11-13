// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// ---------------------------------------------------------------------------
// Dedicated child cgroup for memory governance.
//
// IndependentCgroup leaves the supervisor in init's v2 group — on Android
// the hierarchy root, shared with the whole system. Governance must never
// write limits there (see cgroup_memory.go), so before the guard is set
// up, the supervisor carves out a child group of its own — "wanbox" — and
// moves itself into it. setupMemoryGuard runs before the first core
// generation starts, so every process the supervisor spawns afterwards
// (the core and its plugins) is born inside the child group: the soft
// limit and the pressure watch then cover exactly the module's own
// process tree, and nothing else on the phone.
//
// Safety boundary: governance writes (memory.high and the sampled
// counters in cgroup_memory.go) go ONLY to this dedicated child group —
// never to the root group, never to any group the module does not own.
// The only writes outside the child are the one-time establishment steps
// below: +memory in the CURRENT group's subtree_control (controller
// delegation — it activates the memory controller for children without
// limiting anyone) and the migration of our own pid.
//
// Everything here is best-effort like the rest of memory governance: any
// failure degrades silently to "no child group, no governance" and never
// blocks supervisor startup.
// ---------------------------------------------------------------------------

// memoryChildGroupName is the fixed name of the supervisor's dedicated
// v2 child group.
const memoryChildGroupName = "wanbox"

// childDegradeReason is the closed set of reasons child-group planning or
// establishment gave up. It reaches the supervisor event log only as a
// stable numeric suffix ("memory_child_degraded:<n>"), so no free-form
// strings ever enter the event vocabulary.
type childDegradeReason string

const (
	reasonNoV2Hierarchy       childDegradeReason = "no_v2_hierarchy"
	reasonStateUnreadable     childDegradeReason = "state_unreadable"
	reasonControllerMissing   childDegradeReason = "memory_controller_unavailable"
	reasonSubtreeEnableFailed childDegradeReason = "subtree_enable_failed"
	reasonChildMkdirFailed    childDegradeReason = "child_mkdir_failed"
	reasonChildMoveFailed     childDegradeReason = "child_move_failed"
	reasonChildVerifyFailed   childDegradeReason = "child_verify_failed"
	reasonGroupNotDedicated   childDegradeReason = "group_not_dedicated"
)

// eventCode maps the reason to its stable numeric event suffix.
func (r childDegradeReason) eventCode() int {
	switch r {
	case reasonNoV2Hierarchy:
		return 1
	case reasonStateUnreadable:
		return 2
	case reasonControllerMissing:
		return 3
	case reasonSubtreeEnableFailed:
		return 4
	case reasonChildMkdirFailed:
		return 5
	case reasonChildMoveFailed:
		return 6
	case reasonChildVerifyFailed:
		return 7
	case reasonGroupNotDedicated:
		return 8
	}
	return 0
}

type memoryChildPlanKind int

const (
	planDegrade memoryChildPlanKind = iota
	planUseAsIs
	planCreateChild
)

type memoryChildPlan struct {
	kind memoryChildPlanKind
	// dir is the group directory to govern: the current group for
	// planUseAsIs, the child group for planCreateChild.
	dir string
	// childHier is the hierarchy path /proc/self/cgroup must report
	// after the migration (planCreateChild only).
	childHier string
	// enableMemory marks that the current group's subtree_control does
	// not yet delegate the memory controller to its children.
	enableMemory bool
	reason       childDegradeReason
}

// planMemoryChildGroup is the pure planning step. Inputs are the resolved
// layout of the group the supervisor currently sits in and the contents
// of that group's cgroup.controllers / cgroup.subtree_control files.
func planMemoryChildGroup(layout v2Layout, controllers, subtreeControl string) memoryChildPlan {
	own := layout.hierPath
	if filepath.Base(own) == memoryChildGroupName {
		// Already inside the dedicated group (a previous start created
		// it and something placed us here, or a re-exec): govern it
		// directly, never nest a second level.
		return memoryChildPlan{kind: planUseAsIs, dir: layout.dir}
	}
	child := memoryChildPlan{
		kind:      planCreateChild,
		dir:       filepath.Join(layout.dir, memoryChildGroupName),
		childHier: filepath.Join(own, memoryChildGroupName),
	}
	if own == "/" {
		// The root group is exempt from the no-internal-process rule,
		// so delegating memory to a child can always be attempted even
		// though init and the supervisor live here. Whether the kernel
		// offers the memory controller at all is decided by the write.
		child.enableMemory = !hasController(subtreeControl, "memory")
		return child
	}
	if hasController(subtreeControl, "memory") {
		// Memory is already delegated to this group's children: carve
		// out the dedicated child and move in.
		return child
	}
	if hasController(controllers, "memory") {
		// The memory controller is active for THIS group but cannot be
		// delegated further while the group holds processes (the
		// no-internal-process rule), so no dedicated child can be
		// carved out here. The group itself is foreign — we merely
		// inhabit it, and it may be shared with unrelated processes —
		// so writing memory.high into it would limit strangers too.
		// Governance only ever writes into the dedicated wanbox
		// group; degrade instead.
		return memoryChildPlan{kind: planDegrade, reason: reasonGroupNotDedicated}
	}
	return memoryChildPlan{kind: planDegrade, reason: reasonControllerMissing}
}

// hasController reports whether a cgroup.controllers or
// cgroup.subtree_control listing ("cpu memory pids") names a controller.
func hasController(listing, name string) bool {
	for _, field := range strings.Fields(listing) {
		if field == name {
			return true
		}
	}
	return false
}

// memoryChildFS injects the /proc reads and the group creation the
// migration depends on, so tests can emulate the kernel: a fake cgroupfs
// tree, control files that materialize on group creation, and a
// /proc/self/cgroup view that changes once the fake cgroup.procs accepts
// our pid.
type memoryChildFS struct {
	readSelfCgroup func() (string, error)
	readMountinfo  func() (string, error)
	createGroup    func(dir string) error
	pid            int
}

func procMemoryChildFS() memoryChildFS {
	read := func(path string) func() (string, error) {
		return func() (string, error) {
			b, err := os.ReadFile(path)
			return string(b), err
		}
	}
	return memoryChildFS{
		readSelfCgroup: read("/proc/self/cgroup"),
		readMountinfo:  read("/proc/self/mountinfo"),
		createGroup:    mkdirCgroup,
		pid:            os.Getpid(),
	}
}

// mkdirCgroup creates a cgroup directory, treating an already existing
// directory as success: the child group is deliberately reused across
// starts and never removed on exit (rmdir would race a fast restart
// that is about to reuse it, and the kernel reclaims empty groups'
// state on its own schedule anyway).
func mkdirCgroup(dir string) error {
	if err := os.Mkdir(dir, 0o755); err != nil {
		if info, statErr := os.Stat(dir); statErr != nil || !info.IsDir() {
			return err
		}
	}
	return nil
}

// ensureMemoryChildGroup establishes (or reuses) the dedicated child
// group and returns the directory the memory guard must govern. Any
// failure returns ok=false with a closed-set reason; the caller degrades
// silently and supervision proceeds without governance.
func ensureMemoryChildGroup(fs memoryChildFS) (string, childDegradeReason, bool) {
	self, err := fs.readSelfCgroup()
	if err != nil {
		return "", reasonStateUnreadable, false
	}
	mounts, err := fs.readMountinfo()
	if err != nil {
		return "", reasonStateUnreadable, false
	}
	layout, ok := resolveV2Layout(self, mounts)
	if !ok {
		return "", reasonNoV2Hierarchy, false
	}
	controllers, err := readCgroupFile(layout.dir, "cgroup.controllers")
	if err != nil {
		return "", reasonStateUnreadable, false
	}
	subtree, err := readCgroupFile(layout.dir, "cgroup.subtree_control")
	if err != nil {
		return "", reasonStateUnreadable, false
	}
	plan := planMemoryChildGroup(layout, controllers, subtree)
	switch plan.kind {
	case planUseAsIs:
		return plan.dir, "", true
	case planDegrade:
		return "", plan.reason, false
	}
	// planCreateChild: delegate the memory controller to children first
	// (skipped when the plan found it already delegated), then create
	// the group, move our whole thread group into it via cgroup.procs,
	// and verify the move against a fresh /proc/self/cgroup read — a
	// write the kernel silently ignored must not leave the guard
	// watching the wrong group.
	if plan.enableMemory {
		if err := writeCgroupFile(layout.dir, "cgroup.subtree_control", "+memory"); err != nil {
			return "", reasonSubtreeEnableFailed, false
		}
	}
	if err := fs.createGroup(plan.dir); err != nil {
		return "", reasonChildMkdirFailed, false
	}
	if err := writeCgroupFile(plan.dir, "cgroup.procs", strconv.Itoa(fs.pid)); err != nil {
		return "", reasonChildMoveFailed, false
	}
	after, err := fs.readSelfCgroup()
	if err != nil {
		return "", reasonChildVerifyFailed, false
	}
	nowPath, ok := v2PathOf(after)
	if !ok || nowPath != plan.childHier {
		return "", reasonChildVerifyFailed, false
	}
	return plan.dir, "", true
}

func readCgroupFile(dir, name string) (string, error) {
	b, err := os.ReadFile(filepath.Join(dir, name))
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// writeCgroupFile issues a command to a cgroup control file: kernel
// pseudo-files are opened write-only and written in place, never created
// or truncated (the same rule IndependentCgroup follows for
// cgroup.procs).
func writeCgroupFile(dir, name, value string) error {
	f, err := os.OpenFile(filepath.Join(dir, name), os.O_WRONLY, 0)
	if err != nil {
		return err
	}
	_, writeErr := f.WriteString(value)
	closeErr := f.Close()
	if writeErr != nil {
		return writeErr
	}
	return closeErr
}
