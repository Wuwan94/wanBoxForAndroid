// @author 雾晚
package module

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

func TestPlanMemoryChildGroup(t *testing.T) {
	const allControllers = "cpuset cpu io memory pids"
	cases := []struct {
		name        string
		hier        string
		controllers string
		subtree     string
		wantKind    memoryChildPlanKind
		wantDir     string
		wantHier    string
		wantEnable  bool
		wantReason  childDegradeReason
	}{
		{"root, memory not yet delegated", "/", allControllers, "cpu", planCreateChild, "/cg/wanbox", "/wanbox", true, ""},
		{"root, memory already delegated", "/", allControllers, "cpu memory", planCreateChild, "/cg/wanbox", "/wanbox", false, ""},
		{"already in dedicated child", "/wanbox", allControllers, "", planUseAsIs, "/cg/wanbox", "", false, ""},
		{"nested dedicated child reused", "/system/wanbox", allControllers, "", planUseAsIs, "/cg/system/wanbox", "", false, ""},
		{"subgroup, memory delegated to children", "/apps", allControllers, "memory", planCreateChild, "/cg/apps/wanbox", "/apps/wanbox", false, ""},
		{"subgroup, memory active but not delegable", "/apps", allControllers, "cpu", planDegrade, "", "", false, reasonGroupNotDedicated},
		{"subgroup, no memory controller at all", "/apps", "cpu pids", "", planDegrade, "", "", false, reasonControllerMissing},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			layout := v2Layout{hierPath: tc.hier, dir: filepath.Join("/cg", strings.TrimPrefix(tc.hier, "/"))}
			plan := planMemoryChildGroup(layout, tc.controllers, tc.subtree)
			if plan.kind != tc.wantKind {
				t.Fatalf("kind=%v, want %v (plan %+v)", plan.kind, tc.wantKind, plan)
			}
			switch tc.wantKind {
			case planUseAsIs, planCreateChild:
				if plan.dir != tc.wantDir || plan.enableMemory != tc.wantEnable {
					t.Fatalf("plan=%+v, want dir=%q enable=%v", plan, tc.wantDir, tc.wantEnable)
				}
				if tc.wantKind == planCreateChild && plan.childHier != tc.wantHier {
					t.Fatalf("childHier=%q, want %q", plan.childHier, tc.wantHier)
				}
			case planDegrade:
				if plan.reason != tc.wantReason {
					t.Fatalf("reason=%q, want %q", plan.reason, tc.wantReason)
				}
			}
		})
	}
}

func TestChildDegradeReasonEventCodes(t *testing.T) {
	reasons := []childDegradeReason{
		reasonNoV2Hierarchy, reasonStateUnreadable, reasonControllerMissing,
		reasonSubtreeEnableFailed, reasonChildMkdirFailed, reasonChildMoveFailed,
		reasonChildVerifyFailed,
	}
	seen := map[int]bool{}
	for _, reason := range reasons {
		code := reason.eventCode()
		if code <= 0 || seen[code] {
			t.Fatalf("reason %q: event code %d not positive/unique", reason, code)
		}
		seen[code] = true
		event := "memory_child_degraded:" + strconv.Itoa(code)
		if !eventCode.MatchString(event) {
			t.Fatalf("event %q violates the event-code contract", event)
		}
	}
}

// fakeV2 emulates a cgroup2 hierarchy in a TempDir: control files are
// plain files, group creation materializes the standard files, and the
// /proc/self/cgroup view flips to the child path once the fake
// cgroup.procs accepts our pid — the same contract the kernel offers.
type fakeV2 struct {
	root      string // fake cgroup2 mount point
	pid       int
	ownHier   string
	childHier string
	selfFile  string
	mountFile string
	neverMove bool
}

func writeFakeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func newFakeV2(t *testing.T, ownHier, controllers, subtree string) *fakeV2 {
	t.Helper()
	base := t.TempDir()
	f := &fakeV2{
		root:      filepath.Join(base, "cgroup"),
		pid:       4242,
		ownHier:   ownHier,
		childHier: filepath.Join(ownHier, memoryChildGroupName),
		selfFile:  filepath.Join(base, "self.cgroup"),
		mountFile: filepath.Join(base, "mountinfo"),
	}
	writeFakeFile(t, f.mountFile, "20 1 0:1 / "+f.root+" rw - cgroup2 cgroup2 rw\n")
	writeFakeFile(t, f.groupFile(ownHier, "cgroup.controllers"), controllers)
	writeFakeFile(t, f.groupFile(ownHier, "cgroup.subtree_control"), subtree)
	return f
}

func (f *fakeV2) groupDir(hier string) string {
	return filepath.Join(f.root, strings.TrimPrefix(hier, "/"))
}

func (f *fakeV2) groupFile(hier, name string) string {
	return filepath.Join(f.groupDir(hier), name)
}

func (f *fakeV2) fs() memoryChildFS {
	return memoryChildFS{
		pid: f.pid,
		readMountinfo: func() (string, error) {
			b, err := os.ReadFile(f.mountFile)
			return string(b), err
		},
		readSelfCgroup: func() (string, error) {
			if !f.neverMove {
				procs, _ := os.ReadFile(f.groupFile(f.childHier, "cgroup.procs"))
				if strings.Contains(string(procs), strconv.Itoa(f.pid)) {
					return "0::" + f.childHier + "\n", nil
				}
			}
			return "0::" + f.ownHier + "\n", nil
		},
		createGroup: func(dir string) error {
			if err := mkdirCgroup(dir); err != nil {
				return err
			}
			// Emulate the kernel materializing a fresh group's files.
			for name, content := range map[string]string{
				"cgroup.procs":           "",
				"cgroup.controllers":     "memory",
				"cgroup.subtree_control": "",
				"memory.current":         "0",
				"memory.events":          "high 0\nmax 0\n",
				"memory.high":            "max",
			} {
				p := filepath.Join(dir, name)
				if _, err := os.Stat(p); err != nil {
					if err := os.WriteFile(p, []byte(content), 0o644); err != nil {
						return err
					}
				}
			}
			return nil
		},
	}
}

func readFakeFile(t *testing.T, path string) string {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func TestEnsureMemoryChildGroupRootCreatesChild(t *testing.T) {
	f := newFakeV2(t, "/", "cpuset cpu io memory pids", "")
	dir, reason, ok := ensureMemoryChildGroup(f.fs())
	if !ok || reason != "" {
		t.Fatalf("got (%q, %q, %v)", dir, reason, ok)
	}
	want := f.groupDir("/wanbox")
	if dir != want {
		t.Fatalf("dir=%q, want %q", dir, want)
	}
	if got := readFakeFile(t, f.groupFile("/", "cgroup.subtree_control")); got != "+memory" {
		t.Fatalf("subtree_control=%q, want the +memory delegation write", got)
	}
	if got := readFakeFile(t, f.groupFile("/wanbox", "cgroup.procs")); !strings.Contains(got, "4242") {
		t.Fatalf("child cgroup.procs=%q, want our pid migrated in", got)
	}
	// Establishment must not do governance writes: memory.high stays at
	// the kernel default until setupMemoryGuard asserts the limit.
	if got := readFakeFile(t, f.groupFile("/wanbox", "memory.high")); got != "max" {
		t.Fatalf("memory.high=%q, want untouched default", got)
	}
	// A second start finds itself already in the child group and reuses
	// it as-is.
	dir2, _, ok2 := ensureMemoryChildGroup(f.fs())
	if !ok2 || dir2 != want {
		t.Fatalf("second start: got (%q, %v), want (%q, true)", dir2, ok2, want)
	}
}

func TestEnsureMemoryChildGroupRootSubtreeAlreadyEnabled(t *testing.T) {
	f := newFakeV2(t, "/", "cpuset cpu io memory pids", "cpu memory")
	if _, _, ok := ensureMemoryChildGroup(f.fs()); !ok {
		t.Fatal("establishment failed")
	}
	if got := readFakeFile(t, f.groupFile("/", "cgroup.subtree_control")); got != "cpu memory" {
		t.Fatalf("subtree_control=%q, want untouched (no redundant +memory write)", got)
	}
}

func TestEnsureMemoryChildGroupAlreadyInChild(t *testing.T) {
	f := newFakeV2(t, "/wanbox", "memory", "")
	writeFakeFile(t, f.groupFile("/wanbox", "cgroup.procs"), "999")
	dir, _, ok := ensureMemoryChildGroup(f.fs())
	if !ok || dir != f.groupDir("/wanbox") {
		t.Fatalf("got (%q, %v)", dir, ok)
	}
	if got := readFakeFile(t, f.groupFile("/wanbox", "cgroup.procs")); got != "999" {
		t.Fatalf("cgroup.procs=%q, want untouched (no migration when already inside)", got)
	}
}

func TestEnsureMemoryChildGroupSubgroupNotDedicatedDegrades(t *testing.T) {
	f := newFakeV2(t, "/apps", "cpuset cpu memory", "cpu")
	_, _, ok := ensureMemoryChildGroup(f.fs())
	if ok {
		t.Fatal("a foreign group that cannot yield a dedicated child must degrade, never be governed in place")
	}
	if _, err := os.Stat(f.groupDir("/apps/wanbox")); !os.IsNotExist(err) {
		t.Fatal("no child group must be created when delegation is impossible")
	}
}

func TestEnsureMemoryChildGroupSubgroupCreatesChild(t *testing.T) {
	f := newFakeV2(t, "/apps", "cpuset cpu memory", "memory")
	dir, _, ok := ensureMemoryChildGroup(f.fs())
	if !ok || dir != f.groupDir("/apps/wanbox") {
		t.Fatalf("got (%q, %v)", dir, ok)
	}
	if got := readFakeFile(t, f.groupFile("/apps/wanbox", "cgroup.procs")); !strings.Contains(got, "4242") {
		t.Fatalf("child cgroup.procs=%q, want our pid migrated in", got)
	}
}

func TestEnsureMemoryChildGroupControllerMissingDegrades(t *testing.T) {
	f := newFakeV2(t, "/apps", "cpu pids", "")
	_, reason, ok := ensureMemoryChildGroup(f.fs())
	if ok || reason != reasonControllerMissing {
		t.Fatalf("got reason=%q ok=%v, want %q degrade", reason, ok, reasonControllerMissing)
	}
	if _, err := os.Stat(f.groupDir("/apps/wanbox")); !os.IsNotExist(err) {
		t.Fatal("degraded planning must not create the child group")
	}
}

func TestEnsureMemoryChildGroupNoV2Degrades(t *testing.T) {
	f := newFakeV2(t, "/", "memory", "")
	fs := f.fs()
	fs.readSelfCgroup = func() (string, error) { return "2:cpu,cpuacct:/x\n4:cpuset:/\n", nil }
	_, reason, ok := ensureMemoryChildGroup(fs)
	if ok || reason != reasonNoV2Hierarchy {
		t.Fatalf("got reason=%q ok=%v, want %q degrade", reason, ok, reasonNoV2Hierarchy)
	}
}

func TestEnsureMemoryChildGroupUnreadableStateDegrades(t *testing.T) {
	f := newFakeV2(t, "/", "memory", "")
	subtree := f.groupFile("/", "cgroup.subtree_control")
	if err := os.Remove(subtree); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(subtree, 0o755); err != nil {
		t.Fatal(err)
	}
	_, reason, ok := ensureMemoryChildGroup(f.fs())
	if ok || reason != reasonStateUnreadable {
		t.Fatalf("got reason=%q ok=%v, want %q degrade", reason, ok, reasonStateUnreadable)
	}
}

func TestEnsureMemoryChildGroupSubtreeWriteFails(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root bypasses permission bits; subtree write failure needs a non-root runner (CI)")
	}
	f := newFakeV2(t, "/", "cpuset cpu io memory pids", "")
	if err := os.Chmod(f.groupFile("/", "cgroup.subtree_control"), 0o444); err != nil {
		t.Fatal(err)
	}
	_, reason, ok := ensureMemoryChildGroup(f.fs())
	if ok || reason != reasonSubtreeEnableFailed {
		t.Fatalf("got reason=%q ok=%v, want %q degrade", reason, ok, reasonSubtreeEnableFailed)
	}
}

func TestEnsureMemoryChildGroupChildBlockedByFile(t *testing.T) {
	f := newFakeV2(t, "/", "cpuset cpu io memory pids", "memory")
	writeFakeFile(t, f.groupDir("/wanbox"), "not a directory")
	_, reason, ok := ensureMemoryChildGroup(f.fs())
	if ok || reason != reasonChildMkdirFailed {
		t.Fatalf("got reason=%q ok=%v, want %q degrade", reason, ok, reasonChildMkdirFailed)
	}
}

func TestEnsureMemoryChildGroupMoveFails(t *testing.T) {
	f := newFakeV2(t, "/", "cpuset cpu io memory pids", "memory")
	// A cgroup.procs that is a directory cannot accept the migration
	// write (and, unlike permission bits, this also fails for root).
	if err := os.MkdirAll(f.groupFile("/wanbox", "cgroup.procs"), 0o755); err != nil {
		t.Fatal(err)
	}
	_, reason, ok := ensureMemoryChildGroup(f.fs())
	if ok || reason != reasonChildMoveFailed {
		t.Fatalf("got reason=%q ok=%v, want %q degrade", reason, ok, reasonChildMoveFailed)
	}
}

func TestEnsureMemoryChildGroupVerifyFails(t *testing.T) {
	f := newFakeV2(t, "/", "cpuset cpu io memory pids", "")
	f.neverMove = true // kernel "accepted" the write but membership never changes
	_, reason, ok := ensureMemoryChildGroup(f.fs())
	if ok || reason != reasonChildVerifyFailed {
		t.Fatalf("got reason=%q ok=%v, want %q degrade", reason, ok, reasonChildVerifyFailed)
	}
}
