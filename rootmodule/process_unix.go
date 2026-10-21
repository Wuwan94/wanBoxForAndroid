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
	if sameCgroups(string(raw), string(own)) {
		return nil
	}
	mounts, e := os.ReadFile("/proc/self/mountinfo")
	if e != nil {
		return errors.New("cgroup_unavailable")
	}
	groups, e := cgroupMoveTargets(string(raw), string(own), string(mounts))
	if e != nil {
		return e
	}
	for _, group := range groups {
		// cgroup.procs is a kernel pseudo-file. Never create/truncate a path
		// from mountinfo, and never rewrite a hierarchy already joined by su.
		f, err := os.OpenFile(group, os.O_WRONLY, 0)
		if err != nil {
			return errors.New("cgroup_move_failed")
		}
		_, err = f.WriteString(strconv.Itoa(os.Getpid()))
		closeErr := f.Close()
		if err != nil || closeErr != nil {
			return errors.New("cgroup_move_failed")
		}
	}
	after, e := os.ReadFile("/proc/self/cgroup")
	if e != nil || !sameCgroups(string(raw), string(after)) {
		return errors.New("cgroup_move_failed")
	}
	return nil
}
