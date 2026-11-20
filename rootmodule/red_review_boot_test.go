// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
// 复审 P2 的 RED 探针：只用基线（da68253）已存在的 API 与错误码字面量。
// 断言的是“正确行为”：boot.json 损坏时状态不得声称模块自启已生效。
package module

import (
	"context"
	"os"
	"testing"
)

func TestRedCorruptBootSettingsAreNeverReportedAsEnabled(t *testing.T) {
	r, s := fixture(t)
	if s.AutoStart != true {
		t.Fatal("fixture must start enabled so the snapshot fallback would be visible")
	}
	if _, e := r.Apply(context.Background(), s); e != nil {
		t.Fatal(e)
	}
	if e := os.WriteFile(r.path("boot.json"), []byte("{"), 0600); e != nil {
		t.Fatal(e)
	}
	state := r.Status()
	if state.AutoStart != nil && *state.AutoStart {
		t.Fatalf("RED: a corrupt boot.json still reports autoStart=true while Boot refuses to start")
	}
	r.Validate = func(context.Context, string, string) error { t.Fatal("booted with a corrupt setting"); return nil }
	if e := r.Boot(context.Background(), func() bool { return true }); e == nil || e.Error() != "boot_settings_invalid" {
		t.Fatalf("Boot must keep refusing with boot_settings_invalid, got %v", e)
	}
}
