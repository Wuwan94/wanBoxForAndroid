// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package libcore

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/ulikunitz/xz"
)

// TestUnxzRoundTrip 是 B1/O2 的回归测试：Unxz 必须正确关闭输出文件句柄、
// 传播关闭错误；损坏的压缩包必须返回错误，不得静默成功。
func TestUnxzRoundTrip(t *testing.T) {
	dir := t.TempDir()
	src := filepath.Join(dir, "a.xz")
	content := []byte("wanbox-b1-unxz-roundtrip")
	f, err := os.Create(src)
	if err != nil {
		t.Fatal(err)
	}
	w, err := xz.NewWriter(f)
	if err != nil {
		f.Close()
		t.Fatal(err)
	}
	if _, err := w.Write(content); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
	dst := filepath.Join(dir, "a.txt")
	if err := Unxz(src, dst); err != nil {
		t.Fatalf("Unxz failed: %v", err)
	}
	got, err := os.ReadFile(dst)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(content) {
		t.Fatalf("Unxz content mismatch: got %q want %q", got, content)
	}
}

func TestUnxzCorruptArchiveErrors(t *testing.T) {
	dir := t.TempDir()
	bad := filepath.Join(dir, "bad.xz")
	if err := os.WriteFile(bad, []byte("not an xz stream"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := Unxz(bad, filepath.Join(dir, "bad.txt")); err == nil {
		t.Fatal("expected Unxz to fail on corrupt archive, got nil")
	}
}
