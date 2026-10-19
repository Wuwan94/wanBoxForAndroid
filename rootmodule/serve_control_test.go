// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// 管理服务生命周期测试：serve-stop 必须幂等，且只在身份一致时才动进程/删记录。

// serveFixture 预建数据目录：api.pid 走 AtomicWrite，目录必须存在。
func serveFixture(t *testing.T) *Runtime {
	t.Helper()
	r, _ := fixture(t)
	if e := privateDirectory(r.Root); e != nil {
		t.Fatal(e)
	}
	return r
}

// 没有 api.pid 时必须直接成功（卸载流程可重复执行）。
func TestServeStopIsIdempotentWithoutRecord(t *testing.T) {
	r := serveFixture(t)
	for i := 0; i < 2; i++ {
		if e := r.ServeStop(context.Background()); e != nil {
			t.Fatal("无 pid 文件必须成功", e)
		}
	}
	if _, e := os.Stat(r.serveRecordPath()); !os.IsNotExist(e) {
		t.Fatal("不应凭空产生 pid 文件")
	}
}

// 身份不匹配（pid 被复用 / 启动刻度不符）时绝不发信号，也不删除别人的 pid 文件。
func TestServeStopKeepsRecordWhenIdentityMismatch(t *testing.T) {
	r := serveFixture(t)
	rec := apiServeRecord{
		PID:        os.Getpid(),
		Start:      "0",
		Executable: filepath.Join(r.ModuleDir, "bin", "wanboxctl"),
		StartedAt:  time.Now().Unix(),
	}
	if e := jsonWrite(r.serveRecordPath(), rec); e != nil {
		t.Fatal(e)
	}
	if e := r.ServeStop(context.Background()); e != nil {
		t.Fatal("身份不匹配应静默成功", e)
	}
	if _, e := os.Stat(r.serveRecordPath()); e != nil {
		t.Fatal("身份不匹配时不得删除 pid 文件", e)
	}
	// 测试进程仍然活着（否则这条断言根本执行不到），说明没有误杀。
	if !t.Failed() && os.Getpid() <= 1 {
		t.Fatal("进程身份异常")
	}
}

// 记录非法时按固定错误码拒绝，并保留现场（不猜、不删）。
func TestServeStopRejectsInvalidRecord(t *testing.T) {
	r := serveFixture(t)
	if e := jsonWrite(r.serveRecordPath(), apiServeRecord{PID: 0}); e != nil {
		t.Fatal(e)
	}
	if e := r.ServeStop(context.Background()); e == nil || e.Error() != "api_pid_invalid" {
		t.Fatal("非法记录必须拒绝", e)
	}
	if _, e := os.Stat(r.serveRecordPath()); e != nil {
		t.Fatal("非法记录也应保留现场", e)
	}
	// 相对路径同样非法：身份校验必须有绝对可执行路径可比。
	if e := jsonWrite(r.serveRecordPath(), apiServeRecord{PID: os.Getpid(), Start: "1", Executable: "bin/wanboxctl"}); e != nil {
		t.Fatal(e)
	}
	if e := r.ServeStop(context.Background()); e == nil || e.Error() != "api_pid_invalid" {
		t.Fatal("相对路径必须拒绝", e)
	}
}

// serving 只在身份一致时报告管理面在线。
func TestServingReportsOnlyVerifiedRecord(t *testing.T) {
	r := serveFixture(t)
	if _, ok := r.serving(); ok {
		t.Fatal("没有记录时不得报告在线")
	}
	rec := apiServeRecord{
		PID:        os.Getpid(),
		Start:      "0",
		Executable: filepath.Join(r.ModuleDir, "bin", "wanboxctl"),
		StartedAt:  time.Now().Unix(),
	}
	if e := jsonWrite(r.serveRecordPath(), rec); e != nil {
		t.Fatal(e)
	}
	if _, ok := r.serving(); ok {
		t.Fatal("启动刻度不符时必须报告离线")
	}
}

// clearServeRecord 不得删除不代表自己的记录。
func TestClearServeRecordKeepsForeignRecord(t *testing.T) {
	r := serveFixture(t)
	rec := apiServeRecord{
		PID:        os.Getpid(),
		Start:      "0",
		Executable: filepath.Join(r.ModuleDir, "bin", "wanboxctl"),
		StartedAt:  time.Now().Unix(),
	}
	if e := jsonWrite(r.serveRecordPath(), rec); e != nil {
		t.Fatal(e)
	}
	r.clearServeRecord()
	if _, e := os.Stat(r.serveRecordPath()); e != nil {
		t.Fatal("不是自己的记录不得删除", e)
	}
}

// writeServeRecord 必须写出「自己」的完整身份：pid + 启动刻度 + 绝对可执行路径。
// 写出来的记录要能被 serving() 认可、被 clearServeRecord 删除；否则 serve 会留下
// 一个谁都删不掉的 api.pid，后续每次启动都误判为「管理面已在线」而拒绝拉起。
func TestWriteServeRecordDescribesOwnProcess(t *testing.T) {
	r := serveFixture(t)
	if _, e := Identity(os.Getpid()); e != nil {
		t.Skip("当前平台不支持进程身份校验，跳过", e)
	}
	if e := r.writeServeRecord(); e != nil {
		t.Fatal(e)
	}
	rec, e := r.readServeRecord()
	if e != nil {
		t.Fatal("写出的记录必须能被读回", e)
	}
	if rec.PID != os.Getpid() || rec.Start == "" || !filepath.IsAbs(rec.Executable) {
		t.Fatal("记录身份不完整", rec)
	}
	if rec.StartedAt <= 0 {
		t.Fatal("启动时间缺失", rec)
	}
	// 身份一致：serving 报告在线，clearServeRecord 才会认为这是自己的记录并清除。
	if _, ok := r.serving(); !ok {
		t.Fatal("自己的记录必须被判定为在线")
	}
	r.clearServeRecord()
	if _, e = os.Stat(r.serveRecordPath()); !os.IsNotExist(e) {
		t.Fatal("自己的记录必须能被清除", e)
	}
}

// 卸载脚本必须按 serve-stop → stop 的顺序停进程；只 stop 会把 serve 留在后台。
func TestUninstallScriptStopsManagementPlaneFirst(t *testing.T) {
	data, e := os.ReadFile(filepath.Join("package", "uninstall.sh"))
	if e != nil {
		t.Fatal(e)
	}
	text := string(data)
	stopIndex := strings.Index(text, "__internal serve-stop")
	coreIndex := strings.Index(text, "wanboxctl\" stop")
	if stopIndex < 0 || coreIndex < 0 {
		t.Fatal("卸载脚本缺少 serve-stop 或 stop", text)
	}
	if stopIndex > coreIndex {
		t.Fatal("必须先 serve-stop 再 stop", text)
	}
	for _, forbidden := range []string{"pkill", "killall"} {
		if strings.Contains(text, forbidden) {
			t.Fatal("不允许按名字批量杀进程", forbidden)
		}
	}
}
