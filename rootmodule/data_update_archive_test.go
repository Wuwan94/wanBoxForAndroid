// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"testing"
	"time"
)

// B8 / B9：归档上限的行为测试。此前 data_update.go 明确写着 "Archives are
// retained, not automatically pruned or deleted"，每次 PrepareDataUpdate 都
// MkdirTemp 一份且从无清理 —— 目录随更新次数单调增长，属已复现缺陷。

func archiveNames(t *testing.T, r *Runtime) []string {
	t.Helper()
	entries, e := os.ReadDir(r.path("archives"))
	if e != nil {
		t.Fatal(e)
	}
	var names []string
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), "data-reset-") {
			names = append(names, entry.Name())
		}
	}
	sort.Strings(names)
	return names
}

func writeArchive(t *testing.T, r *Runtime, name string, age time.Duration) string {
	t.Helper()
	p := filepath.Join(r.path("archives"), name)
	if e := os.MkdirAll(p, 0700); e != nil {
		t.Fatal(e)
	}
	if e := os.WriteFile(filepath.Join(p, "current"), []byte(name), 0600); e != nil {
		t.Fatal(e)
	}
	stamp := time.Now().Add(-age)
	if e := os.Chtimes(p, stamp, stamp); e != nil {
		t.Fatal(e)
	}
	return p
}

func TestPruneKeepsOnlyNewestArchivesAndRestorableData(t *testing.T) {
	r := &Runtime{Root: t.TempDir()}
	if e := os.MkdirAll(r.path("archives"), 0700); e != nil {
		t.Fatal(e)
	}
	for i := 0; i < 9; i++ {
		writeArchive(t, r, fmt.Sprintf("data-reset-%02d", i), time.Duration(9-i)*time.Hour)
	}
	// 非归档目录绝不能被清理
	if e := os.Mkdir(filepath.Join(r.path("archives"), "unrelated"), 0700); e != nil {
		t.Fatal(e)
	}

	if e := r.pruneDataResetArchives(""); e != nil {
		t.Fatal(e)
	}

	names := archiveNames(t, r)
	if len(names) != maxDataResetArchives {
		t.Fatalf("archive count = %d, want %d: %v", len(names), maxDataResetArchives, names)
	}
	want := []string{
		"data-reset-04", "data-reset-05", "data-reset-06", "data-reset-07", "data-reset-08",
	}
	for i := range want {
		if names[i] != want[i] {
			t.Fatalf("kept %v, want %v", names, want)
		}
		// 保留下来的归档内容必须仍可读、可恢复
		data, e := os.ReadFile(filepath.Join(r.path("archives"), names[i], "current"))
		if e != nil || string(data) != names[i] {
			t.Fatalf("archive %s not restorable: %v", names[i], e)
		}
	}
	if _, e := os.Stat(filepath.Join(r.path("archives"), "unrelated")); e != nil {
		t.Fatal("unrelated directory was removed", e)
	}
}

func TestPruneNeverRemovesInFlightArchive(t *testing.T) {
	r := &Runtime{Root: t.TempDir()}
	if e := os.MkdirAll(r.path("archives"), 0700); e != nil {
		t.Fatal(e)
	}
	for i := 0; i < 8; i++ {
		writeArchive(t, r, fmt.Sprintf("data-reset-%02d", i), time.Duration(8-i)*time.Hour)
	}
	active := "data-reset-00" // 最旧，但 journal 正在用，必须保留
	if e := r.pruneDataResetArchives(active); e != nil {
		t.Fatal(e)
	}
	names := archiveNames(t, r)
	if len(names) != maxDataResetArchives {
		t.Fatalf("archive count = %d, want %d: %v", len(names), maxDataResetArchives, names)
	}
	found := false
	for _, n := range names {
		if n == active {
			found = true
		}
	}
	if !found {
		t.Fatalf("active archive %s was pruned: %v", active, names)
	}
}

func TestArchiveCountIsBoundedAcrossRepeatedUpdates(t *testing.T) {
	r := &Runtime{Root: t.TempDir()}
	if e := os.MkdirAll(r.path("archives"), 0700); e != nil {
		t.Fatal(e)
	}
	rounds := maxDataResetArchives + 8
	for i := 0; i < rounds; i++ {
		// 与 PrepareDataUpdate 相同的顺序：先裁剪历史，再创建新归档。
		if e := r.pruneDataResetArchives(""); e != nil {
			t.Fatal(e)
		}
		p, e := os.MkdirTemp(r.path("archives"), "data-reset-")
		if e != nil {
			t.Fatal(e)
		}
		stamp := time.Now().Add(time.Duration(i) * time.Minute)
		if e := os.Chtimes(p, stamp, stamp); e != nil {
			t.Fatal(e)
		}
	}
	names := archiveNames(t, r)
	if len(names) > maxDataResetArchives {
		t.Fatalf("after %d updates: %d archives, want <= %d", rounds, len(names), maxDataResetArchives)
	}
}

func TestPruneRejectsSymlinkInsteadOfFollowingIt(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("symlink creation needs elevated privileges on Windows")
	}
	r := &Runtime{Root: t.TempDir()}
	if e := os.MkdirAll(r.path("archives"), 0700); e != nil {
		t.Fatal(e)
	}
	outside := t.TempDir()
	if e := os.Symlink(outside, filepath.Join(r.path("archives"), "data-reset-link")); e != nil {
		t.Fatal(e)
	}
	for i := 0; i < 7; i++ {
		writeArchive(t, r, fmt.Sprintf("data-reset-%02d", i), time.Duration(7-i)*time.Hour)
	}
	if e := r.pruneDataResetArchives(""); e == nil || e.Error() != "unsafe_data_path" {
		t.Fatalf("expected unsafe_data_path, got %v", e)
	}
	if _, e := os.Stat(outside); e != nil {
		t.Fatal("prune followed a symlink", e)
	}
}
