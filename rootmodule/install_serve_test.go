// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// 热更新必须同时替换管理面：旧 serve 按 api.pid 身份停掉，新 serve 从新目录 detached 起；
// 任何一步失败都要回滚代码并恢复旧管理面（核心原本停着也一样）。

func serveActivateFixture(t *testing.T) (*Runtime, string) {
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
	return r, target
}

// 核心原本停止：管理面仍必须被替换（stop → start），且不得顺手拉起核心。
func TestCodeUpdateReplacesManagementPlaneWhenCoreStopped(t *testing.T) {
	r, target := serveActivateFixture(t)
	var order []string
	r.installRunning = func() bool { return false }
	r.installServing = func() bool { return true }
	r.installServeStop = func(context.Context) error {
		order = append(order, "stop")
		return nil
	}
	r.installServeStart = func(context.Context) error {
		order = append(order, "start")
		return nil
	}
	if e := r.Activate(context.Background(), target); e != nil {
		t.Fatal(e)
	}
	if strings.Join(order, ",") != "stop,start" {
		t.Fatal("管理面必须先停旧再起新", order)
	}
	if SameProcess(r.Status().Core) {
		t.Fatal("核心原本停止时不得被拉起")
	}
}

// 新管理面起不来：必须回滚代码并恢复旧管理面，返回固定错误码。
func TestCodeUpdateRestoresManagementPlaneAfterServeStartFailure(t *testing.T) {
	r, target := serveActivateFixture(t)
	var order []string
	attempts := 0
	r.installRunning = func() bool { return false }
	r.installServing = func() bool { return true }
	r.installServeStop = func(context.Context) error {
		order = append(order, "stop")
		return nil
	}
	r.installServeStart = func(context.Context) error {
		attempts++
		order = append(order, "start")
		if attempts == 1 {
			return os.ErrDeadlineExceeded
		}
		return nil
	}
	e := r.Activate(context.Background(), target)
	if e == nil || e.Error() != "module_update_failed_rolled_back" {
		t.Fatal("期望回滚错误码，实际", e)
	}
	if strings.Join(order, ",") != "stop,start,stop,start" {
		t.Fatal("回滚必须先停新管理面再恢复旧管理面", order)
	}
	if attempts != 2 {
		t.Fatal("旧管理面未恢复", attempts)
	}
	if _, e := os.Stat(filepath.Join(target, "old")); e != nil {
		t.Fatal("回滚丢失旧代码")
	}
}

// 停旧管理面失败：不做替换，核心恢复，代码保持原样。
func TestCodeUpdateAbortsWhenManagementPlaneCannotStop(t *testing.T) {
	r, target := serveActivateFixture(t)
	r.installRunning = func() bool { return false }
	r.installServing = func() bool { return true }
	r.installServeStop = func(context.Context) error { return os.ErrPermission }
	r.installServeStart = func(context.Context) error {
		t.Fatal("停不下来时不得启动新管理面")
		return nil
	}
	if e := r.Activate(context.Background(), target); e == nil || e.Error() != "module_update_failed_rolled_back" {
		t.Fatal("期望回滚，实际", e)
	}
	if _, e := os.Stat(filepath.Join(target, "old")); e != nil {
		t.Fatal("回滚丢失旧代码")
	}
}

// 管理面原本没起：不无故拉起，避免在没有 App 连接的场景多开一个常驻进程。
func TestCodeUpdateKeepsManagementPlaneAbsentWhenItWasNeverUp(t *testing.T) {
	r, target := serveActivateFixture(t)
	started := false
	r.installRunning = func() bool { return false }
	r.installServing = func() bool { return false }
	r.installServeStop = func(context.Context) error {
		t.Fatal("管理面没起时不应调用 serve-stop")
		return nil
	}
	r.installServeStart = func(context.Context) error {
		started = true
		return nil
	}
	if e := r.Activate(context.Background(), target); e != nil {
		t.Fatal(e)
	}
	if started {
		t.Fatal("管理面原本离线时不应被拉起")
	}
}
