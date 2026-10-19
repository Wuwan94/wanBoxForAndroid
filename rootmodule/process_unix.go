//go:build linux || android

// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"
)

func syncDir(dir string) error {
	f, e := os.Open(dir)
	if e != nil {
		return e
	}
	defer f.Close()
	return f.Sync()
}
func fileLock(path string) (func(), error) {
	f, e := os.OpenFile(path, os.O_CREATE|os.O_RDWR|syscall.O_NOFOLLOW, 0600)
	if e != nil {
		return nil, e
	}
	until := time.Now().Add(65 * time.Second)
	for {
		e = syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB)
		if e == nil {
			break
		}
		if time.Now().After(until) {
			f.Close()
			return nil, errors.New("module_busy")
		}
		time.Sleep(100 * time.Millisecond)
	}
	return func() { syscall.Flock(int(f.Fd()), syscall.LOCK_UN); f.Close() }, nil
}
func Identity(pid int) (ProcessIdentity, error) {
	var out ProcessIdentity
	if pid <= 1 {
		return out, errors.New("pid_invalid")
	}
	base := filepath.Join("/proc", strconv.Itoa(pid))
	exe, e := os.Readlink(filepath.Join(base, "exe"))
	if e != nil {
		return out, e
	}
	raw, e := os.ReadFile(filepath.Join(base, "stat"))
	if e != nil {
		return out, e
	}
	// comm can contain spaces and ')': only the final ')' delimits the stat fields.
	end := strings.LastIndex(string(raw), ")")
	if end < 0 {
		return out, errors.New("stat_invalid")
	}
	fields := strings.Fields(string(raw)[end+1:])
	if len(fields) < 20 || fields[0] == "Z" {
		return out, errors.New("process_dead")
	}
	return ProcessIdentity{pid, fields[19], strings.TrimSuffix(exe, " (deleted)")}, nil
}
// findServeProcesses 反查「正在以 exePath 运行且命令行是 __internal serve」的进程。
//
// 兼容路径：preview.11 及更早没有 api.pid，热更新时无从知道旧管理面的 pid。
// 只按进程名/裸 pid 杀进程是危险的，这里用两条硬条件收窄：
// /proc/<pid>/exe 必须指向本模块的 wanboxctl，且命令行必须确实是 serve。
// 两条都满足才认定是我们要替换的管理面。
func findServeProcesses(exePath string) []ProcessIdentity {
	if exePath == "" {
		return nil
	}
	entries, e := os.ReadDir("/proc")
	if e != nil {
		return nil
	}
	out := make([]ProcessIdentity, 0, 2)
	for _, entry := range entries {
		pid, e := strconv.Atoi(entry.Name())
		if e != nil || pid <= 1 {
			continue
		}
		id, e := Identity(pid)
		if e != nil || id.Executable != exePath {
			continue
		}
		raw, e := os.ReadFile(filepath.Join("/proc", entry.Name(), "cmdline"))
		if e != nil {
			continue
		}
		args := strings.Split(strings.TrimRight(string(raw), "\x00"), "\x00")
		for i := 1; i < len(args); i++ {
			if args[i] == "serve" && args[i-1] == "__internal" {
				out = append(out, id)
				break
			}
		}
	}
	return out
}

func SameProcess(want ProcessIdentity) bool {
	got, e := Identity(want.PID)
	return e == nil && want.Start != "" && got == want
}
func terminate(p ProcessIdentity) error {
	if !SameProcess(p) {
		return nil
	}
	return syscall.Kill(p.PID, syscall.SIGTERM)
}
func detach(cmd *exec.Cmd) { cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true} }
func parentDeathSignal(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{Pdeathsig: syscall.SIGTERM}
}

// Helpers do not establish TUN routes; bounded escalation is safe only after
// checking the identity captured at creation, never one read from a reused PID.
func forceTerminate(p ProcessIdentity) error {
	if !SameProcess(p) {
		return nil
	}
	return syscall.Kill(p.PID, syscall.SIGKILL)
}

// Android's force-stop can kill descendants in an App-owned cgroup even with
// setsid. Move only our own PID to init's existing cgroup, or reject startup.
// Do not invent cgroup paths or alter system-wide freezer/security settings.
func IndependentCgroup() error {
	if runtime.GOOS != "android" {
		return nil
	} // Linux test host.
	raw, e := os.ReadFile("/proc/1/cgroup")
	if e != nil {
		return errors.New("cgroup_unavailable")
	}
	own, e := os.ReadFile("/proc/self/cgroup")
	if e != nil {
		return errors.New("cgroup_unavailable")
	}
	if string(raw) == string(own) {
		return nil
	}
	mounts, e := os.ReadFile("/proc/self/mountinfo")
	if e != nil {
		return errors.New("cgroup_unavailable")
	}
	joined := 0
	for _, line := range strings.Split(string(raw), "\n") {
		parts := strings.SplitN(line, ":", 3)
		if len(parts) != 3 {
			continue
		}
		for _, m := range strings.Split(string(mounts), "\n") {
			halves := strings.SplitN(m, " - ", 2)
			if len(halves) != 2 {
				continue
			}
			front := strings.Fields(halves[0])
			back := strings.Fields(halves[1])
			if len(front) < 5 || len(back) < 3 {
				continue
			}
			match := parts[1] == "" && back[0] == "cgroup2"
			if back[0] == "cgroup" {
				for _, controller := range strings.Split(parts[1], ",") {
					if strings.Contains(","+back[2]+",", ","+controller+",") {
						match = true
					}
				}
			}
			if !match {
				continue
			}
			if !strings.HasPrefix(parts[2], front[3]) {
				continue
			}
			group := filepath.Join(front[4], strings.TrimPrefix(parts[2], front[3]), "cgroup.procs")
			if e = os.WriteFile(group, []byte(strconv.Itoa(os.Getpid())), 0600); e != nil {
				return errors.New("cgroup_move_failed")
			}
			joined++
		}
	}
	if joined == 0 {
		return errors.New("cgroup_move_failed")
	}
	after, e := os.ReadFile("/proc/self/cgroup")
	if e != nil || string(after) != string(raw) {
		return errors.New("cgroup_move_failed")
	}
	return nil
}
