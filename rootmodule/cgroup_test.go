// @author 雾晚
package module

import (
	"reflect"
	"testing"
)

func TestCgroupMoveTargets(t *testing.T) {
	cases := []struct {
		name, init, own, mounts string
		want                    []string
		fail                    bool
	}{
		{"KSU already escaped unchanged readonly v1", "0::/\n4:cpuset:/\n", "0::/uid_10001/pid_5\n4:cpuset:/\n", "20 1 0:1 / /sys/fs/cgroup rw - cgroup2 cgroup2 rw\n21 1 0:2 / /dev/cpuset ro - cgroup cpuset ro,cpuset", []string{"/sys/fs/cgroup/cgroup.procs"}, false},
		{"v2 already joined", "0::/", "0::/", "", nil, false},
		{"reordered membership", "0::/\n4:cpu,cpuacct:/", "4:cpuacct,cpu:/\n0::/\n", "", nil, false},
		{"v1 combined controllers", "4:cpu,cpuacct:/foreground", "4:cpuacct,cpu:/apps", "21 1 0:2 / /dev/cpuctl rw - cgroup cgroup rw,cpu,cpuacct", []string{"/dev/cpuctl/foreground/cgroup.procs"}, false},
		{"wrong controller mount rejected", "4:cpu,cpuacct:/", "4:cpu,cpuacct:/apps", "21 1 0:2 / /dev/cpu rw - cgroup cgroup rw,cpu", nil, true},
		{"subtree mount", "0::/system/init", "0::/system/app", "20 1 0:1 /system /sys/fs/cgroup rw - cgroup2 cgroup2 rw", []string{"/sys/fs/cgroup/init/cgroup.procs"}, false},
		{"prefix is not a directory", "0::/apps-other/init", "0::/apps/app", "20 1 0:1 /apps /sys/fs/cgroup rw - cgroup2 cgroup2 rw", nil, true},
		{"escaped mountinfo", "0::/", "0::/apps", `20 1 0:1 / /sys/cgroup\040space rw - cgroup2 cgroup2 rw`, []string{"/sys/cgroup space/cgroup.procs"}, false},
		{"missing mount fails closed", "0::/", "0::/apps", "", nil, true},
		{"missing hierarchy fails closed", "0::/\n1:cpu:/", "0::/", "", nil, true},
		{"malformed membership fails closed", "not-a-membership", "0::/", "", nil, true},
		{"traversal membership rejected", "0::/../system", "0::/apps", "", nil, true},
	}
	for _, tt := range cases {
		t.Run(tt.name, func(t *testing.T) {
			got, e := cgroupMoveTargets(tt.init, tt.own, tt.mounts)
			if (e != nil) != tt.fail || !reflect.DeepEqual(got, tt.want) {
				t.Fatalf("targets=%v err=%v want=%v fail=%v", got, e, tt.want, tt.fail)
			}
		})
	}
}

func TestSameCgroupsStillRequiresEveryHierarchy(t *testing.T) {
	if !sameCgroups("2:cpu,cpuacct:/\n0::/", "0::/\n2:cpuacct,cpu:/\n") {
		t.Fatal("semantic equality rejected")
	}
	for _, other := range []string{"", "0::/", "0::/\n2:cpu,cpuacct:/apps", "0::/\n0::/\n2:cpu,cpuacct:/"} {
		if sameCgroups("2:cpu,cpuacct:/\n0::/", other) {
			t.Fatal("unsafe/partial equality accepted", other)
		}
	}
}
