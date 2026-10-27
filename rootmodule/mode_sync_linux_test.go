//go:build linux

// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestCheckGenerationMatchLinux(t *testing.T) {
	selfIdentity, err := Identity(os.Getpid())
	if err != nil {
		t.Fatalf("failed to read self identity: %v", err)
	}
	if selfIdentity.PID != os.Getpid() || selfIdentity.Start == "" || selfIdentity.Executable == "" {
		t.Fatalf("incomplete identity fields: PID=%d, Start=%s, Executable=%s",
			selfIdentity.PID, selfIdentity.Start, selfIdentity.Executable)
	}

	validRev := strings.Repeat("d", 64)
	r := setupTestRuntime(t, validRev, `{}`)

	// 1. 完全对齐匹配通过
	if err := r.checkGenerationMatch(validRev, selfIdentity); err != nil {
		t.Fatalf("expected match, got: %v", err)
	}

	// 2. 代次错配拒绝
	otherRev := strings.Repeat("c", 64)
	if err := r.checkGenerationMatch(otherRev, selfIdentity); err == nil || err.Error() != "revision_mismatch" {
		t.Fatalf("expected revision_mismatch, got: %v", err)
	}

	// 3. current 文件被改动拒绝
	if err := os.WriteFile(filepath.Join(r.Root, "current"), []byte(otherRev), 0600); err != nil {
		t.Fatal(err)
	}
	if err := r.checkGenerationMatch(validRev, selfIdentity); err == nil || err.Error() != "revision_mismatch" {
		t.Fatalf("expected revision_mismatch after file alteration, got: %v", err)
	}

	// 4. 核心进程身份错配拒绝
	bogusIdentity := ProcessIdentity{
		PID:        selfIdentity.PID + 999999,
		Start:      selfIdentity.Start,
		Executable: selfIdentity.Executable,
	}
	if err := r.checkGenerationMatch(validRev, bogusIdentity); err == nil || err.Error() != "core_identity_mismatch" {
		t.Fatalf("expected core_identity_mismatch, got: %v", err)
	}
}
