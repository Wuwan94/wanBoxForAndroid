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
	"time"
)

// hotFixture builds a running-module hot-update scenario: staging package in
// r.ModuleDir, an old installed module at target, lifecycle fully seamed.
func hotFixture(t *testing.T, canary bool) (*Runtime, string, *int) {
	t.Helper()
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
	r.installRunning = func() bool { return true }
	r.installRouteGuard = func(context.Context, bool) error { return nil }
	starts := new(int)
	r.installStart = func(context.Context) error { *starts++; return nil }
	r.installCanaryWindow = 30 * time.Millisecond
	r.installCanaryPoll = 5 * time.Millisecond
	r.installCanaryCheck = func(context.Context) bool { return canary }
	return r, target, starts
}

func eventCodes(r *Runtime) map[string]bool {
	codes := map[string]bool{}
	for _, ev := range r.Events() {
		codes[ev.Code] = true
	}
	return codes
}

func TestHotInstallCanaryFailureRollsBack(t *testing.T) {
	r, target, starts := hotFixture(t, false)
	if e := r.Activate(context.Background(), target); e == nil || e.Error() != "module_update_failed_rolled_back" {
		t.Fatal(e)
	}
	if *starts != 2 { // new core start + rollback restart of the old core
		t.Fatal("starts =", *starts)
	}
	if _, e := os.Stat(filepath.Join(target, "old")); e != nil {
		t.Fatal("old code not restored:", e)
	}
	if !eventCodes(r)["module_update_canary_failed_rolled_back"] {
		t.Fatal("canary rollback event missing")
	}
	if left, _ := filepath.Glob(filepath.Join(filepath.Dir(target), ".wanbox-previous-*")); len(left) != 0 {
		t.Fatal("backup left behind:", left)
	}
}

func TestHotInstallCanaryPassCommitsBroadcastsAndClearsMarker(t *testing.T) {
	r, target, _ := hotFixture(t, true)
	// Give the staged package a versionCode so the commit broadcast fires.
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
	// Simulate the manager writing its update flag late, during the canary.
	r.installCanaryCheck = func(context.Context) bool {
		_ = os.WriteFile(filepath.Join(target, "update"), nil, 0600)
		return true
	}
	var guardSeq []bool
	r.installRouteGuard = func(_ context.Context, enable bool) error {
		guardSeq = append(guardSeq, enable)
		return nil
	}
	if e := r.Activate(context.Background(), target); e != nil {
		t.Fatal(e)
	}
	if _, e := os.Stat(filepath.Join(target, "update")); !os.IsNotExist(e) {
		t.Fatal("update marker survived the hot update")
	}
	if b, e := os.ReadFile(filepath.Join(target, "module.prop")); e != nil || string(b) != string(prop) {
		t.Fatal("new module.prop not active", e)
	}
	if !eventCodes(r)["module_hot_update_committed:2023"] {
		t.Fatal("commit broadcast event missing")
	}
	if len(guardSeq) != 2 || !guardSeq[0] || guardSeq[1] {
		t.Fatal("guard sequence =", guardSeq) // on before stop, off after commit
	}
}

func TestHotInstallGuardFailureAbortsBeforeStop(t *testing.T) {
	r, target, starts := hotFixture(t, true)
	r.installRouteGuard = func(context.Context, bool) error { return errors.New("boom") }
	if e := r.Activate(context.Background(), target); e == nil || e.Error() != "hot_update_guard_failed" {
		t.Fatal(e)
	}
	if *starts != 0 {
		t.Fatal("core restarted despite guard failure")
	}
	if _, e := os.Stat(filepath.Join(target, "old")); e != nil {
		t.Fatal("old code touched despite guard failure")
	}
}

func TestModuleVersionCodeAndMarkerHelpers(t *testing.T) {
	dir := t.TempDir()
	if e := os.WriteFile(filepath.Join(dir, "module.prop"), []byte("id=wanbox\nversionCode= 77 \n"), 0600); e != nil {
		t.Fatal(e)
	}
	if code, ok := moduleVersionCode(dir); !ok || code != 77 {
		t.Fatal(code, ok)
	}
	if e := os.WriteFile(filepath.Join(dir, "update"), nil, 0600); e != nil {
		t.Fatal(e)
	}
	clearUpdateMarker(dir)
	if _, e := os.Stat(filepath.Join(dir, "update")); !os.IsNotExist(e) {
		t.Fatal("marker not cleared")
	}
	clearUpdateMarker(dir) // idempotent
}
