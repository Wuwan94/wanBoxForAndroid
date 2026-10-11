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
func TestCoreReadyDuration_WithMarker(t *testing.T) {
	root := t.TempDir()
	r := &Runtime{Root: root, ModuleDir: t.TempDir()}

	// Create runtime/ready marker with known mod time (65 seconds ago)
	runtimeDir := filepath.Join(root, "runtime")
	os.MkdirAll(runtimeDir, 0755)
	readyPath := filepath.Join(runtimeDir, "ready")
	os.WriteFile(readyPath, []byte("ready"), 0644)
	past := time.Now().Add(-65 * time.Second)
	os.Chtimes(readyPath, past, past)

	// Use current process with valid identity to pass SameProcess check
	pid := os.Getpid()
	identity, err := Identity(pid)
	if err != nil {
		t.Skipf("cannot get identity for current process: %v", err)
	}
	// Write state.json with matching revision to pass revision check
	statePath := filepath.Join(root, "state.json")
	os.WriteFile(statePath, []byte(`{"revision":"rev1","runningRevision":"rev1"}`), 0644)

	dur := r.coreReadyDuration(identity, "rev1")
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
	tm := &Telemetry{
		VmRSSBytes:    1024 * 1024 * 100, // 100MB
		UptimeSeconds: 3600,
		TxBytes:       1000,
		RxBytes:       2000,
	}
	count := 5
	tm.ConnectionCount = &count

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
