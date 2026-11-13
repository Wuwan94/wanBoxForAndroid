// @author 雾晚
package module

import (
	"testing"
	"time"
)

func TestParseMemTotal(t *testing.T) {
	text := "MemTotal:       16384000 kB\nMemFree:         1024000 kB\nMemAvailable:    8192000 kB\n"
	if got, ok := parseMemTotal(text); !ok || got != 16384000<<10 {
		t.Fatal(got, ok)
	}
	for _, bad := range []string{"", "MemFree: 1024 kB\n", "MemTotal: notanumber kB\n", "MemTotal: 0 kB\n", "MemTotal: 1024 MB\n"} {
		if _, ok := parseMemTotal(bad); ok {
			t.Fatal("accepted malformed meminfo:", bad)
		}
	}
}

func TestMemoryHighFor(t *testing.T) {
	const gib = 1 << 30
	cases := []struct{ total, want uint64 }{
		{0, 0},               // unknown total disables governance
		{512 << 20, 0},       // computed 128 MiB < floor: would squeeze
		{1 * gib, 256 << 20}, // exactly at the floor
		{2 * gib, 512 << 20}, // plain quarter
		{8 * gib, 2 * gib},   // typical phone
		{16 * gib, 4 * gib},  // at the cap
		{32 * gib, 4 * gib},  // capped
		{3 * gib, 768 << 20}, // non-power-of-two total
	}
	for _, tc := range cases {
		if got := memoryHighFor(tc.total); got != tc.want {
			t.Fatalf("memoryHighFor(%d) = %d, want %d", tc.total, got, tc.want)
		}
	}
}

func TestParseMemoryEvents(t *testing.T) {
	text := "low 0\nhigh 42\nmax 7\noom 0\noom_kill 0\nsock_throttled 3\n"
	if ev := parseMemoryEvents(text); ev.high != 42 || ev.max != 7 {
		t.Fatal(ev)
	}
	if ev := parseMemoryEvents(""); ev.high != 0 || ev.max != 0 {
		t.Fatal(ev)
	}
	if ev := parseMemoryEvents("high notanumber\nmax 5\n"); ev.high != 0 || ev.max != 5 {
		t.Fatal("malformed counter must be skipped, others kept:", ev)
	}
}

func TestOwnV2CgroupDir(t *testing.T) {
	mountsRoot := "20 1 0:1 / /sys/fs/cgroup rw - cgroup2 cgroup2 rw\n21 1 0:2 / /dev/cpuctl rw - cgroup cgroup rw,cpu\n"
	cases := []struct {
		name, self, mounts, want string
		ok                       bool
	}{
		{"dedicated subgroup", "0::/apps/bg\n", mountsRoot, "/sys/fs/cgroup/apps/bg", true},
		{"hybrid v1+v2 membership", "4:cpu,cpuacct:/\n0::/uid_10001/pid_5\n", mountsRoot, "/sys/fs/cgroup/uid_10001/pid_5", true},
		{"root group skipped", "0::/\n", mountsRoot, "", false},
		{"no v2 entry", "4:cpu,cpuacct:/apps\n", mountsRoot, "", false},
		{"no cgroup2 mount", "0::/apps\n", "21 1 0:2 / /dev/cpuctl rw - cgroup cgroup rw,cpu\n", "", false},
		{"deeper mount root", "0::/system/app\n", "20 1 0:1 /system /sys/fs/cgroup rw - cgroup2 cgroup2 rw\n", "/sys/fs/cgroup/app", true},
		{"outside mount root rejected", "0::/other/app\n", "20 1 0:1 /system /sys/fs/cgroup rw - cgroup2 cgroup2 rw\n", "", false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, ok := ownV2CgroupDir(tc.self, tc.mounts)
			if ok != tc.ok || got != tc.want {
				t.Fatalf("got (%q, %v), want (%q, %v)", got, ok, tc.want, tc.ok)
			}
		})
	}
}

func TestMemoryGuardObserve(t *testing.T) {
	g := &memoryGuard{high: 1000}
	t0 := time.Unix(10000, 0)
	steps := []struct {
		current uint64
		ev      memoryEvents
		at      time.Time
		want    bool
		why     string
	}{
		{100, memoryEvents{}, t0, false, "first sample only primes the baseline"},
		{899, memoryEvents{}, t0.Add(time.Minute), false, "just below 90% of the limit"},
		{900, memoryEvents{}, t0.Add(time.Minute), true, "at 90%: GC due"},
		{999, memoryEvents{}, t0.Add(time.Minute + 10*time.Second), false, "GC throttled"},
		{100, memoryEvents{high: 1}, t0.Add(2 * time.Minute), true, "high counter grew: kernel already reclaimed"},
		{100, memoryEvents{high: 1}, t0.Add(3 * time.Minute), false, "no new growth, low current"},
		{100, memoryEvents{high: 1, max: 2}, t0.Add(4 * time.Minute), true, "max counter grew"},
		{950, memoryEvents{high: 1, max: 2}, t0.Add(4*time.Minute + 5*time.Second), false, "pressure but still throttled"},
		{950, memoryEvents{high: 1, max: 2}, t0.Add(5 * time.Minute), true, "throttle window passed"},
	}
	for i, s := range steps {
		if got := g.observe(s.current, s.ev, s.at); got != s.want {
			t.Fatalf("step %d (%s): got %v, want %v", i, s.why, got, s.want)
		}
	}
	disabled := &memoryGuard{high: 0}
	if disabled.observe(1<<62, memoryEvents{high: 99, max: 99}, t0) {
		t.Fatal("disabled guard must never request GC")
	}
}
