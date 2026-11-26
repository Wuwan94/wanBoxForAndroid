// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestGoogleFirewallModuleActiveRequiresInstalledExecutableAndNoMarkers(t *testing.T) {
	dir := t.TempDir()
	if googleFirewallModuleActive(dir) {
		t.Fatal("empty directory considered an active module")
	}
	if err := os.WriteFile(filepath.Join(dir, "module.prop"), []byte("id=wanbox\n"), 0600); err != nil {
		t.Fatal(err)
	}
	bin := filepath.Join(dir, "bin")
	if err := os.Mkdir(bin, 0700); err != nil {
		t.Fatal(err)
	}
	cli := filepath.Join(bin, "wanboxctl")
	if err := os.WriteFile(cli, []byte("fixed cli"), 0700); err != nil {
		t.Fatal(err)
	}
	if !googleFirewallModuleActive(dir) {
		t.Fatal("complete enabled module rejected")
	}
	for _, marker := range []string{"disable", "remove"} {
		path := filepath.Join(dir, marker)
		if err := os.WriteFile(path, nil, 0600); err != nil {
			t.Fatal(err)
		}
		if googleFirewallModuleActive(dir) {
			t.Errorf("module with %s marker considered active", marker)
		}
		if err := os.Remove(path); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.Chmod(cli, 0600); err != nil {
		t.Fatal(err)
	}
	if googleFirewallModuleActive(dir) {
		t.Fatal("non-executable internal CLI accepted")
	}
}

func TestGoogleFirewallMutationGuardRechecksDisableAndRemoveMarkers(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "module.prop"), []byte("id=wanbox\n"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(dir, "bin"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "bin", "wanboxctl"), []byte("fixed cli"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := googleFirewallMutationGuard(context.Background(), dir); err != nil {
		t.Fatalf("active module rejected: %v", err)
	}
	for _, marker := range []string{"disable", "remove"} {
		path := filepath.Join(dir, marker)
		if err := os.WriteFile(path, nil, 0600); err != nil {
			t.Fatal(err)
		}
		if err := googleFirewallMutationGuard(context.Background(), dir); !errors.Is(err, errGoogleFirewallModuleInactive) {
			t.Errorf("guard with %s marker returned %v", marker, err)
		}
		if err := os.Remove(path); err != nil {
			t.Fatal(err)
		}
	}
}
