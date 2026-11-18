// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

// B8（N7 热更新契约测试补强）：只补测试，不动实现。fixture 内容全部虚构。
// 覆盖面说明见提交说明里的 4.1 缺口清单；测试暴露的实现缺陷见同提交说明，
// 本文件不改生产代码。

// snapshotState reads the currently committed snapshot for exact-content
// assertions: a hot update must never rewrite user data by itself.
func snapshotState(t *testing.T, r *Runtime) (string, Snapshot) {
	t.Helper()
	rev, snap, e := r.Current()
	if e != nil {
		t.Fatal(e)
	}
	return rev, snap
}

// assertNoHotLeftovers fails when a failed or committed switch leaves
// installer temp directories behind.
func assertNoHotLeftovers(t *testing.T, dir string) {
	t.Helper()
	for _, pattern := range []string{".wanbox-install-*", ".wanbox-previous-*"} {
		left, _ := filepath.Glob(filepath.Join(dir, pattern))
		if len(left) != 0 {
			t.Fatal("installer temp left behind:", left)
		}
	}
}

// assertStagingKeepsPackage asserts the staging package still holds the new
// package content after a failed switch (upstream: stage keeps package,
// live keeps current).
func assertStagingKeepsPackage(t *testing.T, r *Runtime) {
	t.Helper()
	b, e := os.ReadFile(filepath.Join(r.ModuleDir, "module.prop"))
	if e != nil || string(b) != "id=wanbox\nversion=new\n" {
		t.Fatal("staging package lost:", e)
	}
}

// installModeContract runs one install mode end to end and asserts the exact
// post-commit contents: which files came from the package, which journal the
// mode, and whether the core was (re)started.
func TestHotInstallModesJournalExactSelectionAndKeepSnapshot(t *testing.T) {
	for _, mode := range []string{"preserve", "nodes", "fresh"} {
		t.Run(mode, func(t *testing.T) {
			r, target, starts := hotFixture(t, true)
			revBefore, snapBefore := snapshotState(t, r)
			if e := os.WriteFile(filepath.Join(target, "update"), nil, 0600); e != nil {
				t.Fatal(e)
			}
			var guardSeq []bool
			r.installRouteGuard = func(_ context.Context, enable bool) error {
				guardSeq = append(guardSeq, enable)
				return nil
			}
			if e := r.activateMode(context.Background(), target, false, mode); e != nil {
				t.Fatal(e)
			}
			if b, e := os.ReadFile(filepath.Join(target, "module.prop")); e != nil || string(b) != "id=wanbox\nversion=new\n" {
				t.Fatal("new code not active", e)
			}
			if _, e := os.Stat(filepath.Join(target, "old")); !os.IsNotExist(e) {
				t.Fatal("old code not replaced")
			}
			if _, e := os.Stat(filepath.Join(target, "update")); !os.IsNotExist(e) {
				t.Fatal("update marker survived the switch")
			}
			assertNoHotLeftovers(t, filepath.Dir(target))
			revAfter, snapAfter := snapshotState(t, r)
			if revAfter != revBefore || snapAfter.ProfileID != snapBefore.ProfileID ||
				string(snapAfter.Config) != string(snapBefore.Config) {
				t.Fatal("hot update rewrote the user snapshot")
			}
			if !eventCodes(r)["module_updated"] {
				t.Fatal("module_updated event missing")
			}
			if mode == "preserve" {
				if *starts != 1 {
					t.Fatal("running core not restarted, starts =", *starts)
				}
				if pending, e := r.installSelection(); e != nil || pending != nil {
					t.Fatal("preserve queued a data selection:", pending, e)
				}
				if len(guardSeq) != 2 || !guardSeq[0] || guardSeq[1] {
					t.Fatal("guard sequence =", guardSeq)
				}
				if eventCodes(r)["module_hot_update_guard_release_failed"] {
					t.Fatal("unexpected guard release failure")
				}
			} else {
				// nodes/fresh only journal intent for the App to apply on
				// next open; the core stays stopped until then ("完成前暂停连接").
				if *starts != 0 {
					t.Fatal("core restarted before the App applied", mode, "starts =", *starts)
				}
				pending, e := r.installSelection()
				if e != nil || pending == nil || pending.Mode != mode || !validSelectionID(pending.ID) {
					t.Fatal("bad data selection journal:", pending, e)
				}
				if len(guardSeq) != 0 {
					t.Fatal("non-preserve switch installed a guard:", guardSeq)
				}
				for _, ev := range r.Events() {
					if len(ev.Code) > len("module_hot_update_committed:") &&
						ev.Code[:len("module_hot_update_committed:")] == "module_hot_update_committed:" {
						t.Fatal("non-preserve switch broadcast a commit:", ev.Code)
					}
				}
			}
		})
	}
}

func TestHotInstallSelectionLifecycleBlocksApplyUntilAcknowledged(t *testing.T) {
	r, target, starts := hotFixture(t, true)
	if e := r.activateMode(context.Background(), target, false, "nodes"); e != nil {
		t.Fatal(e)
	}
	pending, e := r.installSelection()
	if e != nil || pending == nil || pending.Mode != "nodes" {
		t.Fatal("nodes selection not journaled:", pending, e)
	}
	id := pending.ID
	// A different non-preserve mode cannot replace the pending intent.
	if e := r.activateMode(context.Background(), target, false, "fresh"); e == nil || e.Error() != "install_data_selection_changed" {
		t.Fatal("replaced pending selection:", e)
	}
	if *starts != 0 {
		t.Fatal("rejected switch restarted the core")
	}
	// App/config writes stay blocked while the selection is pending.
	rev, cur, e := r.Current()
	if e != nil {
		t.Fatal(e)
	}
	cur.ExpectedRevision = rev
	cur.ProfileName = "changed"
	if e := func() error { _, e := r.Apply(context.Background(), cur); return e }(); e == nil || e.Error() != "install_data_update_pending" {
		t.Fatal("apply not blocked by pending selection:", e)
	}
	// A forged acknowledgement cannot clear the journal.
	if e := r.FinishInstallSelection("00000000000000000000000000000000"); e == nil || e.Error() != "install_data_selection_changed" {
		t.Fatal("forged acknowledgement accepted:", e)
	}
	if pending, e := r.installSelection(); e != nil || pending == nil || pending.ID != id {
		t.Fatal("journal changed by forged acknowledgement:", pending, e)
	}
	if e := r.FinishInstallSelection(id); e != nil {
		t.Fatal(e)
	}
	if pending, e := r.installSelection(); e != nil || pending != nil {
		t.Fatal("selection not consumed:", pending, e)
	}
	var completed InstallDataSelection
	if e := readJSON(r.path("install-data-completed.json"), &completed, 4096); e != nil || completed.Mode != "nodes" || completed.ID != id {
		t.Fatal("completed journal wrong:", completed, e)
	}
	if e := r.FinishInstallSelection(id); e != nil {
		t.Fatal("acknowledgement not idempotent:", e)
	}
	rev, cur, e = r.Current()
	if e != nil {
		t.Fatal(e)
	}
	cur.ExpectedRevision = rev
	cur.ProfileName = "changed"
	if _, e := r.Apply(context.Background(), cur); e != nil {
		t.Fatal("apply still blocked after acknowledgement:", e)
	}
}

func TestHotInstallDataResetBlocksActivation(t *testing.T) {
	r, s := fixture(t)
	if _, e := r.Apply(context.Background(), s); e != nil {
		t.Fatal(e)
	}
	packageFixture(t, r)
	target := filepath.Join(filepath.Dir(r.ModuleDir), "active")
	if e := os.Mkdir(target, 0700); e != nil {
		t.Fatal(e)
	}
	if e := os.WriteFile(filepath.Join(target, "old"), []byte("old"), 0600); e != nil {
		t.Fatal(e)
	}
	if e := os.WriteFile(r.path("data-reset.json"), nil, 0600); e != nil {
		t.Fatal(e)
	}
	if e := r.Activate(context.Background(), target); e == nil || e.Error() != "data_update_pending" {
		t.Fatal("activated during a data update:", e)
	}
	if _, e := os.Stat(filepath.Join(target, "old")); e != nil {
		t.Fatal("old code touched:", e)
	}
	assertStagingKeepsPackage(t, r)
}

func TestHotInstallCorruptSnapshotFailsBeforeStopKeepsStaging(t *testing.T) {
	r, target, starts := hotFixture(t, true)
	revBefore, _ := snapshotState(t, r)
	path := r.path("generations", revBefore, "snapshot.json")
	if e := os.WriteFile(path, []byte("{corrupt"), 0600); e != nil {
		t.Fatal(e)
	}
	e := r.Activate(context.Background(), target)
	if e == nil || e.Error() == "module_update_failed_rolled_back" {
		t.Fatal("corrupt snapshot accepted or misreported as rollback:", e)
	}
	if *starts != 0 {
		t.Fatal("core stopped despite corrupt snapshot")
	}
	if _, e := os.Stat(filepath.Join(target, "old")); e != nil {
		t.Fatal("old code touched:", e)
	}
	if rev, _, e := r.Current(); e == nil && rev == revBefore {
		t.Fatal("corrupt snapshot still reports as current")
	}
	assertStagingKeepsPackage(t, r)
	assertNoHotLeftovers(t, filepath.Dir(target))
	if eventCodes(r)["module_updated"] {
		t.Fatal("failed switch reported module_updated")
	}
}

// Preserve with no snapshot at all is a fresh module install, not a failure:
// wanBox deliberately differs from upstream here (upstream fails preserve
// when live config is missing). The App submits configuration afterwards.
func TestHotInstallPreserveWithNoSnapshotInstallsCode(t *testing.T) {
	r, _ := fixture(t)
	packageFixture(t, r)
	target := filepath.Join(filepath.Dir(r.ModuleDir), "active")
	if e := os.Mkdir(target, 0700); e != nil {
		t.Fatal(e)
	}
	if e := os.WriteFile(filepath.Join(target, "old"), []byte("old"), 0600); e != nil {
		t.Fatal(e)
	}
	if e := r.Activate(context.Background(), target); e != nil {
		t.Fatal(e)
	}
	if b, e := os.ReadFile(filepath.Join(target, "module.prop")); e != nil || string(b) != "id=wanbox\nversion=new\n" {
		t.Fatal("new code not active", e)
	}
	if rev, e := r.CurrentRevision(); e != nil || rev != "" {
		t.Fatal("phantom revision after install:", rev, e)
	}
	if pending, e := r.installSelection(); e != nil || pending != nil {
		t.Fatal("preserve queued a data selection:", pending, e)
	}
}

func TestHotInstallInstalledFileModes(t *testing.T) {
	r, s := fixture(t)
	if _, e := r.Apply(context.Background(), s); e != nil {
		t.Fatal(e)
	}
	packageFixture(t, r)
	target := filepath.Join(filepath.Dir(r.ModuleDir), "active")
	if e := r.Activate(context.Background(), target); e != nil {
		t.Fatal(e)
	}
	checks := map[string]os.FileMode{
		".":                    0700,
		"module.prop":          0600,
		"LICENSE":              0600,
		"bin/rootbox":          0700,
		"bin/wanboxctl":        0700,
		"service.sh":           0700,
		"customize.sh":         0700,
		"uninstall.sh":         0700,
		"installer-options.sh": 0700,
	}
	// installer-options.sh is not in packageFixture; skip it if absent.
	for name, want := range checks {
		info, e := os.Stat(filepath.Join(target, name))
		if os.IsNotExist(e) {
			continue
		}
		if e != nil || info.Mode().Perm() != want {
			t.Fatalf("%s mode = %v, want %v (%v)", name, info.Mode().Perm(), want, e)
		}
	}
}

func TestHotInstallGuardReleaseFailureStillCommits(t *testing.T) {
	r, target, _ := hotFixture(t, true)
	prop := []byte("id=wanbox\nversion=new\nversionCode=2023\n")
	if e := os.WriteFile(filepath.Join(r.ModuleDir, "module.prop"), prop, 0600); e != nil {
		t.Fatal(e)
	}
	var manifest PackageManifest
	if e := readJSON(filepath.Join(r.ModuleDir, "package-manifest.json"), &manifest, 64<<10); e != nil {
		t.Fatal(e)
	}
	sum := sha256.Sum256(prop)
	manifest.Files["module.prop"] = PackageFile{int64(len(prop)), hex.EncodeToString(sum[:])}
	if e := jsonWrite(filepath.Join(r.ModuleDir, "package-manifest.json"), manifest); e != nil {
		t.Fatal(e)
	}
	var guardSeq []bool
	r.installRouteGuard = func(_ context.Context, enable bool) error {
		guardSeq = append(guardSeq, enable)
		if !enable {
			return errors.New("ip missing")
		}
		return nil
	}
	if e := r.Activate(context.Background(), target); e != nil {
		t.Fatal(e)
	}
	if len(guardSeq) != 2 || !guardSeq[0] || guardSeq[1] {
		t.Fatal("guard sequence =", guardSeq)
	}
	if !eventCodes(r)["module_hot_update_guard_release_failed"] {
		t.Fatal("guard release failure not recorded")
	}
	if !eventCodes(r)["module_hot_update_committed:2023"] {
		t.Fatal("commit broadcast missing despite release failure")
	}
	if !eventCodes(r)["module_updated"] {
		t.Fatal("module_updated missing")
	}
}

func TestHotInstallRollbackRestartFailureRetainsGuard(t *testing.T) {
	r, target, starts := hotFixture(t, true)
	var guardSeq []bool
	r.installRouteGuard = func(_ context.Context, enable bool) error {
		guardSeq = append(guardSeq, enable)
		return nil
	}
	r.installStart = func(context.Context) error {
		*starts++
		return errors.New("start_failed")
	}
	e := r.Activate(context.Background(), target)
	if e == nil || e.Error() != "install_rollback_start_failed" {
		t.Fatal("wrong rollback error:", e)
	}
	if *starts != 2 {
		t.Fatal("starts =", *starts)
	}
	if _, e := os.Stat(filepath.Join(target, "old")); e != nil {
		t.Fatal("old code not restored:", e)
	}
	if !eventCodes(r)["module_hot_update_guard_retained"] {
		t.Fatal("guard retention not recorded")
	}
	if len(guardSeq) != 1 || !guardSeq[0] {
		t.Fatal("guard must stay on after failed rollback restart, sequence =", guardSeq)
	}
	assertNoHotLeftovers(t, filepath.Dir(target))
	if eventCodes(r)["module_updated"] {
		t.Fatal("failed switch reported module_updated")
	}
}

// TestHotInstallBackupCreationFailureReleasesGuard pins the desired
// fail-closed behaviour when the previous-module backup cannot be created
// after the guard went on: the guard must come off again because the old
// core keeps running and no switch is happening.
//
// RED TEST — exposes an implementation defect, kept red on purpose (B8 only
// adds tests, never changes the implementation; see the commit message):
// activateMode returns the MkdirTemp error without releasing the guard, so
// the pref-9001 blackhole stays installed until uninstall or the next hot
// update, even though the old core is still up.
func TestHotInstallBackupCreationFailureReleasesGuard(t *testing.T) {
	r, target, starts := hotFixture(t, true)
	var guardSeq []bool
	r.installRouteGuard = func(_ context.Context, enable bool) error {
		guardSeq = append(guardSeq, enable)
		return nil
	}
	parent := filepath.Dir(target)
	moved := parent + ".moved-b8"
	swapped := false
	restore := func() {
		if !swapped {
			return
		}
		swapped = false
		_ = os.Remove(parent) // placeholder file put in place of the directory
		_ = os.Rename(moved, parent)
		// The deferred stage cleanup ran against the moved path and could
		// not remove anything; drop the leftover here.
		left, _ := filepath.Glob(filepath.Join(parent, ".wanbox-install-*"))
		for _, d := range left {
			_ = os.RemoveAll(d)
		}
	}
	t.Cleanup(restore)
	// installRunning is the last seam before the guard goes on and the
	// backup is created: swap the module parent away so the backup
	// MkdirTemp fails deterministically. (The installValidate seam fires
	// inside Stage, too early: swapping there breaks Stage itself.)
	origRunning := r.installRunning
	r.installRunning = func() bool {
		if swapped {
			return true
		}
		if e := os.Rename(parent, moved); e != nil {
			return true
		}
		if e := os.WriteFile(parent, []byte("not a directory"), 0600); e != nil {
			return true
		}
		swapped = true
		if origRunning != nil {
			return origRunning()
		}
		return true
	}
	e := r.Activate(context.Background(), target)
	restore()
	if e == nil {
		t.Fatal("backup creation failure accepted")
	}
	if *starts != 0 {
		t.Fatal("core stopped despite backup failure")
	}
	if _, e := os.Stat(filepath.Join(target, "old")); e != nil {
		t.Fatal("old code touched:", e)
	}
	assertNoHotLeftovers(t, parent)
	if len(guardSeq) != 2 || !guardSeq[0] || guardSeq[1] {
		t.Fatalf("guard leaked after backup failure, sequence = %v (want [true false])", guardSeq)
	}
	if eventCodes(r)["module_updated"] {
		t.Fatal("failed switch reported module_updated")
	}
}

func TestCompleteInstallWaitFailureRecordsDeferredEvent(t *testing.T) {
	r, s := fixture(t)
	if _, e := r.Apply(context.Background(), s); e != nil {
		t.Fatal(e)
	}
	packageFixture(t, r)
	digest, e := manifestDigest(r.ModuleDir)
	if e != nil {
		t.Fatal(e)
	}
	target := filepath.Join(filepath.Dir(r.ModuleDir), "active")
	if e := os.Mkdir(target, 0700); e != nil {
		t.Fatal(e)
	}
	if e := os.WriteFile(filepath.Join(target, "old"), []byte("old"), 0600); e != nil {
		t.Fatal(e)
	}
	e = r.completeInstall(context.Background(), func(context.Context) error {
		return errors.New("installer gone")
	}, target, digest)
	if e == nil || e.Error() != "installer_handoff_incomplete" {
		t.Fatal("wait failure accepted:", e)
	}
	if !eventCodes(r)["module_hot_update_deferred"] {
		t.Fatal("deferred event not recorded")
	}
	if _, e := os.Stat(filepath.Join(target, "old")); e != nil {
		t.Fatal("old code touched:", e)
	}
	if _, e := os.Stat(r.ModuleDir); e != nil {
		t.Fatal("staging lost:", e)
	}
}

func TestHotInstallValidationFailureLeavesNoStagingLeftovers(t *testing.T) {
	r, s := fixture(t)
	if _, e := r.Apply(context.Background(), s); e != nil {
		t.Fatal(e)
	}
	packageFixture(t, r)
	r.installRunning = func() bool { return true }
	starts := 0
	r.installStart = func(context.Context) error { starts++; return nil }
	r.installValidate = func(context.Context, string, string) error { return errors.New("incompatible") }
	target := filepath.Join(filepath.Dir(r.ModuleDir), "active")
	if e := os.Mkdir(target, 0700); e != nil {
		t.Fatal(e)
	}
	if e := os.WriteFile(filepath.Join(target, "old"), []byte("old"), 0600); e != nil {
		t.Fatal(e)
	}
	if e := r.Activate(context.Background(), target); e == nil {
		t.Fatal("accepted incompatible config")
	}
	if starts != 0 {
		t.Fatal("core stopped despite validation failure")
	}
	if _, e := os.Stat(filepath.Join(target, "old")); e != nil {
		t.Fatal("old code touched:", e)
	}
	assertStagingKeepsPackage(t, r)
	assertNoHotLeftovers(t, filepath.Dir(target))
}

// TestHotInstallLockInodePreservedAcrossSwitch covers the upstream lock
// contract that a directory switch must not disturb the data lock: the
// control.lock inode is identical before and after activation.
func TestHotInstallLockInodePreservedAcrossSwitch(t *testing.T) {
	r, s := fixture(t)
	if _, e := r.Apply(context.Background(), s); e != nil {
		t.Fatal(e)
	}
	packageFixture(t, r)
	before, e := os.Stat(r.path("control.lock"))
	if e != nil {
		t.Fatal(e)
	}
	target := filepath.Join(filepath.Dir(r.ModuleDir), "active")
	if e := r.Activate(context.Background(), target); e != nil {
		t.Fatal(e)
	}
	after, e := os.Stat(r.path("control.lock"))
	if e != nil {
		t.Fatal(e)
	}
	if !os.SameFile(before, after) {
		t.Fatal("data lock inode changed across the switch")
	}
	if after.Mode().Perm() != 0600 {
		t.Fatal("data lock mode =", after.Mode().Perm())
	}
}

func addManagerAPK(t *testing.T, r *Runtime, digestMismatch bool) {
	t.Helper()
	data := []byte("fixture manager apk\n")
	if e := os.WriteFile(filepath.Join(r.ModuleDir, "manager.apk"), data, 0600); e != nil {
		t.Fatal(e)
	}
	var manifest PackageManifest
	if e := readJSON(filepath.Join(r.ModuleDir, "package-manifest.json"), &manifest, 64<<10); e != nil {
		t.Fatal(e)
	}
	sum := sha256.Sum256(data)
	if digestMismatch {
		sum = sha256.Sum256([]byte("different bytes\n"))
	}
	manifest.Files["manager.apk"] = PackageFile{int64(len(data)), hex.EncodeToString(sum[:])}
	manifest.WithManager = true
	if e := jsonWrite(filepath.Join(r.ModuleDir, "package-manifest.json"), manifest); e != nil {
		t.Fatal(e)
	}
}

func TestInstallManagerMissingAndCorruptApk(t *testing.T) {
	t.Run("missing", func(t *testing.T) {
		r, _ := fixture(t)
		packageFixture(t, r)
		if e := r.InstallManager(context.Background()); e == nil || e.Error() != "manager_apk_missing" {
			t.Fatal("accepted package without manager APK:", e)
		}
	})
	t.Run("checksum", func(t *testing.T) {
		r, _ := fixture(t)
		packageFixture(t, r)
		addManagerAPK(t, r, true)
		if e := r.InstallManager(context.Background()); e == nil || e.Error() != "package_checksum_failed" {
			t.Fatal("accepted corrupt manager APK:", e)
		}
	})
	t.Run("platform", func(t *testing.T) {
		r, _ := fixture(t)
		packageFixture(t, r)
		addManagerAPK(t, r, false)
		// No /system/bin/cmd on a Linux host: the platform installer is
		// unreachable and the module must be left untouched.
		if e := r.InstallManager(context.Background()); e == nil || e.Error() != "manager_apk_install_failed" {
			t.Fatal("unexpected manager install result:", e)
		}
		if _, e := os.Stat(filepath.Join(r.ModuleDir, "manager.apk")); e != nil {
			t.Fatal("manager APK touched:", e)
		}
	})
}
