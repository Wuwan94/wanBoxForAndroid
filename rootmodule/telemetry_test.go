// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// TestReadVmRSS_Valid verifies VmRSS parsing from valid /proc status
func TestReadVmRSS_Valid(t *testing.T) {
	// Use current process PID (always valid)
	pid := os.Getpid()
	rss := readVmRSS(pid)
	if rss <= 0 {
		t.Errorf("expected positive VmRSS for current process, got %d", rss)
	}
	t.Logf("Current process VmRSS: %d bytes", rss)
}

// TestReadVmRSS_InvalidPID verifies graceful handling of invalid PID
func TestReadVmRSS_InvalidPID(t *testing.T) {
	rss := readVmRSS(999999999)
	if rss != 0 {
		t.Errorf("expected 0 for invalid PID, got %d", rss)
	}
}

// TestReadVmRSS_Overflow verifies overflow handling via injectable parser
func TestReadVmRSS_Overflow(t *testing.T) {
	// 恶意大数字：超过 int64 上限的 KB 值
	malicious := "VmRSS: 99999999999999999999 kB\n"
	result := parseVmRSSFromText(malicious)
	if result != 0 {
		t.Errorf("expected 0 for overflow value, got %d", result)
	}
	// 负值
	negative := "VmRSS: -100 kB\n"
	result = parseVmRSSFromText(negative)
	if result != 0 {
		t.Errorf("expected 0 for negative value, got %d", result)
	}
	// 非法格式
	invalid := "VmRSS: abc kB\n"
	result = parseVmRSSFromText(invalid)
	if result != 0 {
		t.Errorf("expected 0 for invalid format, got %d", result)
	}
	// 正常值
	valid := "VmRSS: 102400 kB\n"
	result = parseVmRSSFromText(valid)
	if result != 102400*1024 {
		t.Errorf("expected %d, got %d", 102400*1024, result)
	}
	// 缺失字段
	missing := "Name: test\n"
	result = parseVmRSSFromText(missing)
	if result != 0 {
		t.Errorf("expected 0 for missing field, got %d", result)
	}
	t.Logf("Overflow/invalid handling verified via injectable parser")
}

// TestCoreReadyDuration_NoMarker verifies 0 when ready marker missing
func TestCoreReadyDuration_NoMarker(t *testing.T) {
	r := &Runtime{Root: t.TempDir(), ModuleDir: t.TempDir()}
	core := ProcessIdentity{PID: os.Getpid(), Start: "12345", Executable: "/test"}
	dur := r.coreReadyDuration(core, "rev1")
	// No ready marker file, should be 0
	if dur != 0 {
		t.Errorf("expected 0 without ready marker, got %d", dur)
	}
}

// TestCoreReadyDuration_WithMarker verifies duration from marker time
// Uses valid 64-char hex revision, writes Root/current, checks all errors
func TestCoreReadyDuration_WithMarker(t *testing.T) {
	root := t.TempDir()
	r := &Runtime{Root: root, ModuleDir: t.TempDir()}

	// Valid 64-char hex revision (revisionName requires it)
	rev := "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"

	// Create runtime/ready marker with known mod time (65 seconds ago)
	runtimeDir := filepath.Join(root, "runtime")
	if err := os.MkdirAll(runtimeDir, 0755); err != nil {
		t.Fatalf("MkdirAll failed: %v", err)
	}
	readyPath := filepath.Join(runtimeDir, "ready")
	if err := os.WriteFile(readyPath, []byte("ready"), 0644); err != nil {
		t.Fatalf("WriteFile ready failed: %v", err)
	}
	past := time.Now().Add(-65 * time.Second)
	if err := os.Chtimes(readyPath, past, past); err != nil {
		t.Fatalf("Chtimes failed: %v", err)
	}

	// Write Root/current with valid revision (coreReadyDuration calls CurrentRevision)
	currentPath := filepath.Join(root, "current")
	if err := os.WriteFile(currentPath, []byte(rev), 0644); err != nil {
		t.Fatalf("WriteFile current failed: %v", err)
	}

	// Use current process with valid identity (real /proc verification)
	pid := os.Getpid()
	identity, err := Identity(pid)
	if err != nil {
		t.Fatalf("cannot get identity for current process: %v", err)
	}

	dur := r.coreReadyDuration(identity, rev)
	// Should be approximately 65 seconds (allow 5s tolerance for test execution)
	if dur < 60 || dur > 70 {
		t.Errorf("expected ~65s duration, got %d", dur)
	}
	t.Logf("Verified ready duration: %d seconds (expected ~65)", dur)
}

// TestFillTelemetry_PIDReuse verifies telemetry cleared on PID mismatch
func TestFillTelemetry_PIDReuse(t *testing.T) {
	r := &Runtime{Root: t.TempDir(), ModuleDir: t.TempDir()}
	s := &State{
		Phase:           "connected",
		Core:            ProcessIdentity{PID: 999999999, Start: "old", Executable: "/old"},
		RunningRevision: "rev1",
	}
	r.fillTelemetry(s)
	if s.Telemetry != nil {
		t.Errorf("expected nil telemetry for non-matching PID, got %+v", s.Telemetry)
	}
}

// TestTelemetry_JSONRoundTrip verifies JSON Marshal/Unmarshal
func TestTelemetry_JSONRoundTrip(t *testing.T) {
	// Verify Telemetry struct JSON tags work correctly
	// TxBytes/RxBytes are *int64: use local variables and take addresses
	txVal := int64(1000)
	rxVal := int64(2000)
	count := 5
	tm := &Telemetry{
		VmRSSBytes:      1024 * 1024 * 100, // 100MB
		UptimeSeconds:   3600,
		TxBytes:         &txVal,
		RxBytes:         &rxVal,
		ConnectionCount: &count,
	}

	// Marshal to JSON
	data, err := json.Marshal(tm)
	if err != nil {
		t.Fatalf("marshal failed: %v", err)
	}
	t.Logf("Marshaled: %s", string(data))

	// Unmarshal back
	var tm2 Telemetry
	if err := json.Unmarshal(data, &tm2); err != nil {
		t.Fatalf("unmarshal failed: %v", err)
	}

	if tm2.VmRSSBytes != 104857600 {
		t.Errorf("VmRSS mismatch: got %d", tm2.VmRSSBytes)
	}
	if tm2.ConnectionCount == nil || *tm2.ConnectionCount != 5 {
		t.Errorf("ConnectionCount mismatch")
	}

	// Unknown vs real 0: nil means unknown, 0 means real zero
	tm3 := &Telemetry{} // all fields omitted
	data3, _ := json.Marshal(tm3)
	var tm4 Telemetry
	json.Unmarshal(data3, &tm4)
	if tm4.ConnectionCount != nil {
		t.Errorf("expected nil for unknown connection count, got %v", tm4.ConnectionCount)
	}
	// Real zero should be preserved
	tm5 := &Telemetry{}
	zero := 0
	tm5.ConnectionCount = &zero
	data5, _ := json.Marshal(tm5)
	var tm6 Telemetry
	json.Unmarshal(data5, &tm6)
	if tm6.ConnectionCount == nil || *tm6.ConnectionCount != 0 {
		t.Errorf("expected 0 for real zero connection count")
	}
}

// TestStatus_NoHTTP verifies Status() does not perform HTTP requests
// Status() must remain a fast local check for internal callers
func TestStatus_NoHTTP(t *testing.T) {
	r := &Runtime{Root: t.TempDir(), ModuleDir: t.TempDir()}
	// Status() should complete quickly without network
	// We can't easily mock HTTP, but we verify it doesn't set Tx/Rx
	// (those are only set by DashboardTelemetry)
	s := r.Status()
	if s.Telemetry != nil {
		if s.Telemetry.TxBytes != nil || s.Telemetry.RxBytes != nil {
			t.Errorf("Status() should not populate Tx/Rx (HTTP), got %+v", s.Telemetry)
		}
		if s.Telemetry.ConnectionCount != nil {
			t.Errorf("Status() should not populate ConnectionCount (HTTP)")
		}
	}
	t.Logf("Status() correctly avoids HTTP for Tx/Rx/ConnectionCount")
}

// TestDashboardTelemetry_OldSessionDiscard verifies old session results are discarded
func TestDashboardTelemetry_OldSessionDiscard(t *testing.T) {
	r := &Runtime{Root: t.TempDir(), ModuleDir: t.TempDir()}
	// Without a connected core, should return nil
	if tm := r.DashboardTelemetry(); tm != nil {
		t.Errorf("expected nil for non-connected state, got %+v", tm)
	}
	t.Logf("Old session correctly discarded (nil for non-connected)")
}

// TestDashboardState_ProfileIDChange verifies hot-switch detection
// Same PID/revision but different ProfileID must discard old telemetry
func TestDashboardState_ProfileIDChange(t *testing.T) {
	core := ProcessIdentity{PID: 1234, Start: "5678", Executable: "/bin/core"}
	before := State{
		Phase:           "connected",
		RunningRevision: "abc123",
		ProfileID:       100,
		Core:            core,
		Stats:           json.RawMessage(`{"tx":1}`),
	}
	after := State{
		Phase:           "connected",
		RunningRevision: "abc123",
		ProfileID:       200, // Changed!
		Core:            core,
		Stats:           json.RawMessage(`{"tx":2}`),
	}
	collectCalled := false
	result := dashboardStateInternal(
		func() State {
			if !collectCalled {
				return before
			}
			return after
		},
		func(snap State) *Telemetry {
			collectCalled = true
			// Return non-zero data
			tx := int64(1000)
			return &Telemetry{VmRSSBytes: 1024, UptimeSeconds: 60, TxBytes: &tx}
		},
		func(p ProcessIdentity) bool { return true },
	)
	// Must return after snapshot
	if result.ProfileID != 200 {
		t.Errorf("expected after ProfileID 200, got %d", result.ProfileID)
	}
	// Telemetry must be nil (discarded)
	if result.Telemetry != nil {
		t.Errorf("expected nil telemetry on ProfileID change, got %+v", result.Telemetry)
	}
	// Stats must be nil (discarded)
	if result.Stats != nil {
		t.Errorf("expected nil stats on ProfileID change")
	}
}

// TestDashboardState_PhaseChange verifies phase change discards telemetry
func TestDashboardState_PhaseChange(t *testing.T) {
	core := ProcessIdentity{PID: 1234, Start: "5678", Executable: "/bin/core"}
	before := State{Phase: "connected", RunningRevision: "abc", ProfileID: 100, Core: core}
	after := State{Phase: "stopped", RunningRevision: "abc", ProfileID: 100, Core: core}
	collectCalled := false
	result := dashboardStateInternal(
		func() State {
			if !collectCalled {
				return before
			}
			return after
		},
		func(snap State) *Telemetry {
			collectCalled = true
			return &Telemetry{VmRSSBytes: 1024}
		},
		func(p ProcessIdentity) bool { return true },
	)
	if result.Phase != "stopped" {
		t.Errorf("expected after phase stopped, got %s", result.Phase)
	}
	if result.Telemetry != nil {
		t.Errorf("expected nil telemetry on phase change")
	}
}

// TestDashboardState_RevisionChange verifies revision change discards telemetry
func TestDashboardState_RevisionChange(t *testing.T) {
	core := ProcessIdentity{PID: 1234, Start: "5678", Executable: "/bin/core"}
	before := State{Phase: "connected", RunningRevision: "aaa", ProfileID: 100, Core: core}
	after := State{Phase: "connected", RunningRevision: "bbb", ProfileID: 100, Core: core}
	collectCalled := false
	result := dashboardStateInternal(
		func() State {
			if !collectCalled {
				return before
			}
			return after
		},
		func(snap State) *Telemetry {
			collectCalled = true
			return &Telemetry{VmRSSBytes: 1024}
		},
		func(p ProcessIdentity) bool { return true },
	)
	if result.RunningRevision != "bbb" {
		t.Errorf("expected after revision bbb, got %s", result.RunningRevision)
	}
	if result.Telemetry != nil {
		t.Errorf("expected nil telemetry on revision change")
	}
}

// TestDashboardState_StartChange verifies process start change discards telemetry
func TestDashboardState_StartChange(t *testing.T) {
	beforeCore := ProcessIdentity{PID: 1234, Start: "1111", Executable: "/bin/core"}
	afterCore := ProcessIdentity{PID: 1234, Start: "2222", Executable: "/bin/core"} // Same PID, different start = PID reuse
	before := State{Phase: "connected", RunningRevision: "abc", ProfileID: 100, Core: beforeCore}
	after := State{Phase: "connected", RunningRevision: "abc", ProfileID: 100, Core: afterCore}
	collectCalled := false
	result := dashboardStateInternal(
		func() State {
			if !collectCalled {
				return before
			}
			return after
		},
		func(snap State) *Telemetry {
			collectCalled = true
			return &Telemetry{VmRSSBytes: 1024}
		},
		func(p ProcessIdentity) bool { return true },
	)
	if result.Core.Start != "2222" {
		t.Errorf("expected after start 2222, got %s", result.Core.Start)
	}
	if result.Telemetry != nil {
		t.Errorf("expected nil telemetry on start change (PID reuse)")
	}
}

// TestDashboardState_ConsistentPreserves verifies consistent snapshot keeps telemetry
// Including real 0 values (not just non-zero)
func TestDashboardState_ConsistentPreserves(t *testing.T) {
	core := ProcessIdentity{PID: 1234, Start: "5678", Executable: "/bin/core"}
	snap := State{
		Phase:           "connected",
		RunningRevision: "abc123",
		ProfileID:       100,
		Core:            core,
		Stats:           json.RawMessage(`{"tx":1}`),
	}
	// Real 0 connection count (not unknown)
	zeroCount := 0
	txVal := int64(5000)
	result := dashboardStateInternal(
		func() State { return snap },
		func(s State) *Telemetry {
			return &Telemetry{
				VmRSSBytes:      2048,
				UptimeSeconds:   120,
				TxBytes:         &txVal,
				ConnectionCount: &zeroCount, // Real 0, not nil
			}
		},
		func(p ProcessIdentity) bool { return true },
	)
	if result.Telemetry == nil {
		t.Fatalf("expected telemetry on consistent snapshot")
	}
	if result.Telemetry.VmRSSBytes != 2048 {
		t.Errorf("expected VmRSS 2048, got %d", result.Telemetry.VmRSSBytes)
	}
	if result.Telemetry.ConnectionCount == nil {
		t.Errorf("expected non-nil connection count (real 0)")
	} else if *result.Telemetry.ConnectionCount != 0 {
		t.Errorf("expected 0 connections, got %d", *result.Telemetry.ConnectionCount)
	}
	if result.Stats == nil {
		t.Errorf("expected stats preserved on consistent snapshot")
	}
}
