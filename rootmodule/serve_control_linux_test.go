//go:build linux

// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"os"
	"os/exec"
	"testing"
	"time"
)

// 真实进程验证：serve-stop 只对「身份一致」的记录发信号，并清理自己的记录。
// 设备端（Android）语义相同，但测试只在 Linux 主机上跑，避免伪造 Root 环境。
func TestServeStopTerminatesRecordedProcess(t *testing.T) {
	r := serveFixture(t)
	cmd := exec.Command("/bin/sh", "-c", "while true; do sleep 1; done")
	if e := cmd.Start(); e != nil {
		t.Skip("无法启动测试进程", e)
	}
	defer func() {
		_ = cmd.Process.Kill()
		_, _ = cmd.Process.Wait()
	}()
	id, e := Identity(cmd.Process.Pid)
	if e != nil {
		t.Skip("当前内核不支持进程身份读取", e)
	}
	if e = jsonWrite(r.serveRecordPath(), apiServeRecord{
		PID:        id.PID,
		Start:      id.Start,
		Executable: id.Executable,
		StartedAt:  time.Now().Unix(),
	}); e != nil {
		t.Fatal(e)
	}
	// 记录的身份可校验时，serving 必须报告在线。
	if _, ok := r.serving(); !ok {
		t.Fatal("身份一致的记录应报告在线")
	}
	if e = r.ServeStop(context.Background()); e != nil {
		t.Fatal(e)
	}
	if SameProcess(id) {
		t.Fatal("进程未被停止")
	}
	if _, e = os.Stat(r.serveRecordPath()); !os.IsNotExist(e) {
		t.Fatal("停止成功后必须删除自己的 pid 记录")
	}
	// 幂等：再停一次仍然成功。
	if e = r.ServeStop(context.Background()); e != nil {
		t.Fatal("重复停止必须成功", e)
	}
}

// pid 被复用（身份里的启动刻度对不上）时不得发出任何信号。
func TestServeStopNeverSignalsReusedPID(t *testing.T) {
	r := serveFixture(t)
	cmd := exec.Command("/bin/sh", "-c", "while true; do sleep 1; done")
	if e := cmd.Start(); e != nil {
		t.Skip("无法启动测试进程", e)
	}
	defer func() {
		_ = cmd.Process.Kill()
		_, _ = cmd.Process.Wait()
	}()
	id, e := Identity(cmd.Process.Pid)
	if e != nil {
		t.Skip("当前内核不支持进程身份读取", e)
	}
	// 启动刻度被篡改：等价于 pid 被另一个进程复用。
	if e = jsonWrite(r.serveRecordPath(), apiServeRecord{
		PID:        id.PID,
		Start:      "1",
		Executable: id.Executable,
		StartedAt:  time.Now().Unix(),
	}); e != nil {
		t.Fatal(e)
	}
	if e = r.ServeStop(context.Background()); e != nil {
		t.Fatal(e)
	}
	if !SameProcess(id) {
		t.Fatal("身份不匹配时误杀了进程")
	}
	if _, e = os.Stat(r.serveRecordPath()); e != nil {
		t.Fatal("身份不匹配时不得删除 pid 记录", e)
	}
}
