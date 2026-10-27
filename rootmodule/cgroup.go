// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"path/filepath"
	"sort"
	"strings"
)

type cgroupMembership struct{ controllers, path string }

func parseCgroups(text string) (map[string]cgroupMembership, error) {
	result := make(map[string]cgroupMembership)
	for _, line := range strings.Split(strings.TrimSpace(text), "\n") {
		parts := strings.SplitN(line, ":", 3)
		if len(parts) != 3 || parts[0] == "" || !strings.HasPrefix(parts[2], "/") || filepath.Clean(parts[2]) != parts[2] {
			return nil, errors.New("cgroup_unavailable")
		}
		controllers := strings.Split(parts[1], ",")
		sort.Strings(controllers)
		names := strings.Join(controllers, ",")
		key := parts[0] + ":" + names
		if _, exists := result[key]; exists {
			return nil, errors.New("cgroup_unavailable")
		}
		result[key] = cgroupMembership{names, parts[2]}
	}
	return result, nil
}

func sameCgroups(a, b string) bool {
	left, e := parseCgroups(a)
	if e != nil {
		return false
	}
	right, e := parseCgroups(b)
	if e != nil || len(left) != len(right) {
		return false
	}
	for k, v := range left {
		if w, ok := right[k]; !ok || w != v {
			return false
		}
	}
	return true
}

func mountPath(text string) string {
	return strings.NewReplacer(`\040`, " ", `\011`, "\t", `\012`, "\n", `\134`, `\`).Replace(text)
}

// Build one destination per differing hierarchy. KSU may already have moved
// some (not all) cgroups; rewriting unchanged/read-only hierarchies is neither
// necessary nor a reason to reject a safely isolated process.
func cgroupMoveTargets(initText, ownText, mountText string) ([]string, error) {
	target, e := parseCgroups(initText)
	if e != nil {
		return nil, e
	}
	own, e := parseCgroups(ownText)
	if e != nil || len(target) != len(own) {
		return nil, errors.New("cgroup_unavailable")
	}
	var keys []string
	for k := range target {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var paths []string
	for _, key := range keys {
		want := target[key]
		current, ok := own[key]
		if !ok {
			return nil, errors.New("cgroup_unavailable")
		}
		if want == current {
			continue
		}
		found := ""
		for _, line := range strings.Split(mountText, "\n") {
			halves := strings.SplitN(line, " - ", 2)
			if len(halves) != 2 {
				continue
			}
			front, back := strings.Fields(halves[0]), strings.Fields(halves[1])
			if len(front) < 6 || len(back) < 3 {
				continue
			}
			match := want.controllers == "" && back[0] == "cgroup2"
			if want.controllers != "" && back[0] == "cgroup" {
				match = true
				options := "," + back[2] + ","
				for _, controller := range strings.Split(want.controllers, ",") {
					if !strings.Contains(options, ","+controller+",") {
						match = false
					}
				}
			}
			if !match {
				continue
			}
			root, mount := mountPath(front[3]), mountPath(front[4])
			if !filepath.IsAbs(root) || !filepath.IsAbs(mount) {
				continue
			}
			relative, err := filepath.Rel(root, want.path)
			if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
				continue
			}
			found = filepath.Join(mount, relative, "cgroup.procs")
			break
		}
		if found == "" {
			return nil, errors.New("cgroup_move_failed")
		}
		paths = append(paths, found)
	}
	return paths, nil
}
