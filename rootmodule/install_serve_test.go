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

// 停旧管理面失败：不做替换（目录还没动，无需回滚），只把核心恢复。
func TestCodeUpdateAbortsWhenManagementPlaneCannotStop(t *testing.T) {
	r, target := serveActivateFixture(t)
	coreRestarted := false
	r.installRunning = func() bool { return true }
	r.installStart = func(context.Context) error { coreRestarted = true; return nil }
	r.installServing = func() bool { return true }
	r.installLegacyServe = func() []ProcessIdentity { return nil }
	r.installServeStop = func(context.Context) error { return os.ErrPermission }
	r.installServeStart = func(context.Context) error {
		t.Fatal("停不下来时不得启动新管理面")
		return nil
	}
	if e := r.Activate(context.Background(), target); e == nil || e.Error() != "serve_stop_failed" {
		t.Fatal("期望固定错误码 serve_stop_failed，实际", e)
	}
	if !coreRestarted {
		t.Fatal("停服失败必须把已停的核心恢复")
	}
	if _, e := os.Stat(filepath.Join(target, "old")); e != nil {
		t.Fatal("停服失败时不得动模块目录", e)
	}
}

// 停服务必须在重命名模块目录**之前**发生：重命名后 /proc/<pid>/exe 会指向
// backup 路径，与 api.pid 里记录的原路径对不上，身份校验必然失败，旧 serve
// 停不掉、新 serve 又抢不到 api.lock。这里用「旧代码文件还在不在」钉住顺序。
func TestCodeUpdateStopsManagementPlaneBeforeReplacingDirectory(t *testing.T) {
	r, target := serveActivateFixture(t)
	marker := filepath.Join(target, "old")
	var stopBeforeReplace, startAfterReplace bool
	r.installRunning = func() bool { return false }
	r.installServing = func() bool { return true }
	r.installLegacyServe = func() []ProcessIdentity { return nil }
	r.installServeStop = func(context.Context) error {
		if _, e := os.Stat(marker); e == nil {
			stopBeforeReplace = true
		}
		return nil
	}
	r.installServeStart = func(context.Context) error {
		if _, e := os.Stat(marker); e != nil {
			startAfterReplace = true
		}
		return nil
	}
	if e := r.Activate(context.Background(), target); e != nil {
		t.Fatal(e)
	}
	if !stopBeforeReplace {
		t.Fatal("停旧管理面必须发生在替换模块目录之前，否则身份校验对不上")
	}
	if !startAfterReplace {
		t.Fatal("新管理面必须在替换模块目录之后才启动")
	}
}

// 旧版（preview.11 及更早）不写 api.pid：必须走反查兼容路径，
// 否则热更新后旧 serve 还在跑、新的起不来，App 直接失联。
func TestCodeUpdateStopsLegacyServeWithoutPidFile(t *testing.T) {
	r, target := serveActivateFixture(t)
	marker := filepath.Join(target, "old")
	legacy := []ProcessIdentity{{PID: 4242, Start: "1", Executable: "/data/adb/modules/wanbox/bin/wanboxctl"}}
	var stopped []ProcessIdentity
	var stopBeforeReplace, started bool
	r.installRunning = func() bool { return false }
	r.installServing = func() bool { return false } // 没有 api.pid
	r.installLegacyServe = func() []ProcessIdentity { return legacy }
	r.installServeStop = func(context.Context) error {
		t.Fatal("没有 api.pid 时不应走精确停止路径")
		return nil
	}
	r.installLegacyServeStop = func(_ context.Context, ids []ProcessIdentity) error {
		if _, e := os.Stat(marker); e == nil {
			stopBeforeReplace = true
		}
		stopped = append(stopped, ids...)
		return nil
	}
	r.installServeStart = func(context.Context) error {
		started = true
		return nil
	}
	if e := r.Activate(context.Background(), target); e != nil {
		t.Fatal(e)
	}
	if !stopBeforeReplace {
		t.Fatal("兼容路径同样必须在替换模块目录之前停掉旧 serve")
	}
	if len(stopped) != 1 || stopped[0] != legacy[0] {
		t.Fatal("反查到的旧 serve 必须被完整传递并停止", stopped)
	}
	if !started {
		t.Fatal("旧 serve 停掉后必须拉起新的，否则管理面失联")
	}
}

// 管理面原本没起：不无故拉起，避免在没有 App 连接的场景多开一个常驻进程。
func TestCodeUpdateKeepsManagementPlaneAbsentWhenItWasNeverUp(t *testing.T) {
	r, target := serveActivateFixture(t)
	started := false
	r.installRunning = func() bool { return false }
	r.installServing = func() bool { return false }
	r.installLegacyServe = func() []ProcessIdentity { return nil }
	r.installLegacyServeStop = func(context.Context, []ProcessIdentity) error {
		t.Fatal("管理面没起时不应停止任何进程")
		return nil
	}
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

func TestCodeUpdateRenameFailureRestoresStoppedServices(t *testing.T) {
	for _, point := range []string{"retire_old", "activate_new"} {
		for _, running := range []bool{false, true} {
			t.Run(point+map[bool]string{false: "_core_stopped", true: "_core_running"}[running], func(t *testing.T) {
				r, target := serveActivateFixture(t)
				var order []string
				r.installRunning = func() bool { return running }
				r.installServing = func() bool { return true }
				r.installLegacyServe = func() []ProcessIdentity { return nil }
				r.installServeStop = func(context.Context) error { order = append(order, "stop_api"); return nil }
				r.installServeStart = func(ctx context.Context) error {
					if ctx.Err() != nil {
						t.Fatal("recovery reused cancelled request", ctx.Err())
					}
					if _, err := os.Stat(filepath.Join(target, "old")); err != nil {
						t.Fatal("restarted before restoring old code", err)
					}
					order = append(order, "start_api")
					return nil
				}
				r.installStart = func(context.Context) error { order = append(order, "start_core"); return nil }
				r.installRename = func(from, to string) error {
					if (point == "retire_old" && from == target) ||
						(point == "activate_new" && to == target && strings.HasPrefix(filepath.Base(from), ".wanbox-install-")) {
						return os.ErrPermission
					}
					return os.Rename(from, to)
				}
				err := r.Activate(context.Background(), target)
				if err == nil || err.Error() != "module_replace_failed" {
					t.Fatal("lost rename error", err)
				}
				want := "stop_api,start_api"
				if running {
					want += ",start_core"
				}
				if strings.Join(order, ",") != want {
					t.Fatal("stopped services not restored in order", order)
				}
			})
		}
	}
}

func TestCodeUpdateReportsManagementRecoveryFailure(t *testing.T) {
	r, target := serveActivateFixture(t)
	r.installRunning = func() bool { return true }
	coreRestored := false
	r.installStart = func(context.Context) error { coreRestored = true; return nil }
	r.installServing = func() bool { return true }
	r.installLegacyServe = func() []ProcessIdentity { return nil }
	r.installServeStop = func(context.Context) error { return nil }
	r.installServeStart = func(context.Context) error { return os.ErrPermission }
	r.installRename = func(string, string) error { return os.ErrPermission }
	if err := r.Activate(context.Background(), target); err == nil || err.Error() != "install_rollback_serve_failed" {
		t.Fatal("management recovery failure must not be reported as success", err)
	}
	if _, err := os.Stat(filepath.Join(target, "old")); err != nil {
		t.Fatal("recovery failure discarded old code", err)
	}
	if !coreRestored {
		t.Fatal("API recovery failure must not prevent restoring the core")
	}
}

func TestCodeUpdateRecoveryIgnoresCancelledInstallerContext(t *testing.T) {
	r, target := serveActivateFixture(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	r.installRunning = func() bool { return false }
	r.installServing = func() bool { return true }
	r.installLegacyServe = func() []ProcessIdentity { return nil }
	r.installServeStop = func(context.Context) error { return nil }
	started := false
	r.installServeStart = func(recovery context.Context) error {
		started = true
		if recovery.Err() != nil {
			t.Fatal("recovery inherited installer cancellation")
		}
		if _, bounded := recovery.Deadline(); !bounded {
			t.Fatal("unbounded recovery")
		}
		return nil
	}
	r.installRename = func(string, string) error { cancel(); return context.Canceled }
	if err := r.Activate(ctx, target); err == nil || err.Error() != "module_replace_failed" || !started {
		t.Fatal("cancelled installer left API stopped", err)
	}
}
