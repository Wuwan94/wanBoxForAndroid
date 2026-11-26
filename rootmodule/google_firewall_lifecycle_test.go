// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestBootSchedulesGoogleRepairIndependentOfCoreAutostart(t *testing.T) {
	r := &Runtime{Root: t.TempDir(), ModuleDir: t.TempDir()}
	var calls int
	var gotDir string
	var gotDelay time.Duration
	r.launchGoogleFirewallRepair = func(moduleDir string, delay time.Duration) error {
		calls++
		gotDir, gotDelay = moduleDir, delay
		return nil
	}
	if err := r.Boot(context.Background(), func() bool { return true }); err != nil {
		t.Fatal(err)
	}
	if calls != 1 || gotDir != r.ModuleDir || gotDelay != time.Minute {
		t.Fatalf("boot repair schedule = calls:%d dir:%q delay:%s, want module dir once after 1m", calls, gotDir, gotDelay)
	}
}

func TestSuccessfulHotActivationSchedulesGoogleRepairAfterInstallLockRelease(t *testing.T) {
	r, target, _ := hotFixture(t, true)
	var calls int
	var lockReacquired bool
	var gotDir string
	r.launchGoogleFirewallRepair = func(moduleDir string, delay time.Duration) error {
		calls++
		gotDir = moduleDir
		if delay != 0 {
			t.Errorf("hot activation repair delay = %s, want immediate", delay)
		}
		return r.withInstallLock("preserve", func(*InstallDataSelection) error {
			lockReacquired = true
			return nil
		})
	}
	if err := r.Activate(context.Background(), target); err != nil {
		t.Fatal(err)
	}
	if calls != 1 || !lockReacquired || gotDir != target {
		t.Fatalf("repair launch calls=%d lock-reacquired=%t dir=%q; want one launch from active target after lock release", calls, lockReacquired, gotDir)
	}
}

func TestRolledBackHotActivationDoesNotScheduleGoogleRepair(t *testing.T) {
	r, target, _ := hotFixture(t, false)
	var calls int
	r.launchGoogleFirewallRepair = func(string, time.Duration) error {
		calls++
		return nil
	}
	if err := r.Activate(context.Background(), target); err == nil || err.Error() != "module_update_failed_rolled_back" {
		t.Fatalf("activation error = %v, want rollback", err)
	}
	if calls != 0 {
		t.Fatalf("rolled-back activation scheduled Google repair %d times", calls)
	}
}

func TestGoogleRepairSchedulerFailureDoesNotBlockBootOrCommittedUpdate(t *testing.T) {
	boot := &Runtime{Root: t.TempDir(), ModuleDir: t.TempDir()}
	boot.launchGoogleFirewallRepair = func(string, time.Duration) error {
		return errors.New("simulated child process start failure")
	}
	if err := boot.Boot(context.Background(), func() bool { return true }); err != nil {
		t.Fatalf("optional boot repair failure blocked boot: %v", err)
	}

	updated, target, _ := hotFixture(t, true)
	updated.launchGoogleFirewallRepair = func(string, time.Duration) error {
		return errors.New("simulated child process start failure")
	}
	if err := updated.Activate(context.Background(), target); err != nil {
		t.Fatalf("optional repair failure changed committed update result: %v", err)
	}
}
