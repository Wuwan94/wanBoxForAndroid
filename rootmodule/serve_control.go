// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"time"
)

// 管理服务（serve）是模块唯一的长期宿主，独立于核心进程。
// 热更新只重写模块文件不会替换它，因此必须能按「身份」精确停掉旧 serve 再拉起新的：
//   - api.pid 记录 pid + 启动刻度 + 可执行文件绝对路径；
//   - 任何停止动作都先校验身份，绝不按裸 pid 杀进程；
//   - 没有 pid 文件视为已停止（幂等），卸载流程可以重复执行。
const apiPidFile = "api.pid"

// apiServeRecord 是 api.pid 的内容。Start 是 /proc/<pid>/stat 的启动刻度，
// 用来识别 pid 复用；StartedAt 只用于日志与排查，不参与身份判断。
type apiServeRecord struct {
	PID        int    `json:"pid"`
	Start      string `json:"start"`
	Executable string `json:"executable"`
	StartedAt  int64  `json:"startedAt"`
}

// serving 报告当前是否已有可用的管理面进程。
func (r *Runtime) serving() (ProcessIdentity, bool) {
	rec, e := r.readServeRecord()
	if e != nil {
		return ProcessIdentity{}, false
	}
	id := rec.identity()
	return id, SameProcess(id)
}

func (rec apiServeRecord) identity() ProcessIdentity {
	return ProcessIdentity{PID: rec.PID, Start: rec.Start, Executable: rec.Executable}
}

func (r *Runtime) serveRecordPath() string { return r.path(apiPidFile) }

// serveExePath 是管理面进程的可执行文件绝对路径，用于反查没有 api.pid 的旧进程。
func (r *Runtime) serveExePath() string { return filepath.Join(r.ModuleDir, "bin", "wanboxctl") }

// legacyServe 找出「没有 api.pid 可依」的旧管理面进程。
//
// preview.11 及更早的 serve 不写 api.pid，热更新时无从知道它的 pid；只按进程名
// 或裸 pid 杀进程是危险的，因此用两条硬条件收窄：可执行文件必须是本模块的
// wanboxctl，且命令行确实是 __internal serve。api.pid 有效时返回空——那时走
// 精确停止路径，不需要反查。
func (r *Runtime) legacyServe() []ProcessIdentity {
	if r.installLegacyServe != nil {
		return r.installLegacyServe()
	}
	if _, ok := r.serving(); ok {
		return nil
	}
	return findServeProcesses(r.serveExePath())
}

// stopLegacyServe 按身份停掉反查到的旧管理面：先 TERM，超时才 KILL。
// 每个进程都重新校验身份，pid 被复用时不会误杀无关进程。
func (r *Runtime) stopLegacyServe(ctx context.Context, ids []ProcessIdentity) error {
	if r.installLegacyServeStop != nil {
		return r.installLegacyServeStop(ctx, ids)
	}
	var first error
	for _, id := range ids {
		if !SameProcess(id) {
			continue
		}
		if e := terminate(id); e != nil {
			first = e
			continue
		}
		if waitProcessGone(ctx, id, 10*time.Second) {
			continue
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if e := forceTerminate(id); e != nil {
			first = e
			continue
		}
		if !waitProcessGone(ctx, id, 5*time.Second) {
			first = errors.New("api_stop_timeout")
		}
	}
	return first
}

// readServeRecord 读取 api.pid 并做最小合法性校验。
// 文件不存在时返回 os.ErrNotExist（调用方据此判断「没有管理面」）；
// 其它读取/解析失败统一收敛到固定错误码 api_pid_invalid，不把原始报错透给 App。
func (r *Runtime) readServeRecord() (apiServeRecord, error) {
	var rec apiServeRecord
	if e := readJSON(r.serveRecordPath(), &rec, 4096); e != nil {
		if errors.Is(e, os.ErrNotExist) {
			return apiServeRecord{}, e
		}
		return apiServeRecord{}, errors.New("api_pid_invalid")
	}
	if rec.PID <= 1 || rec.Start == "" || !filepath.IsAbs(rec.Executable) {
		return apiServeRecord{}, errors.New("api_pid_invalid")
	}
	return rec, nil
}

// writeServeRecord 在 listener 绑定且 api.json 端口回写完成后落盘。
// 记录自己的启动刻度，退出时据此判断「这条记录还是不是我的」。
func (r *Runtime) writeServeRecord() error {
	id, e := Identity(os.Getpid())
	if e != nil {
		return e
	}
	return jsonWrite(r.serveRecordPath(), apiServeRecord{
		PID:        id.PID,
		Start:      id.Start,
		Executable: id.Executable,
		StartedAt:  time.Now().Unix(),
	})
}

// clearServeRecord 只删除属于本进程的记录，避免误删后来者的 api.pid。
func (r *Runtime) clearServeRecord() {
	rec, e := r.readServeRecord()
	if e != nil {
		return
	}
	own, e := Identity(os.Getpid())
	if e != nil {
		return
	}
	if rec.PID == own.PID && rec.Start == own.Start {
		_ = os.Remove(r.serveRecordPath())
	}
}

// ServeStop 按 api.pid 精确停止管理服务：先 TERM，超时再 KILL。
// 幂等：没有 pid 文件直接成功；身份不匹配（pid 复用 / 进程已退出 / 路径不符）
// 时不做任何动作，也不删除不属于自己的 pid 文件。
func (r *Runtime) ServeStop(ctx context.Context) error {
	rec, e := r.readServeRecord()
	if e != nil {
		if errors.Is(e, os.ErrNotExist) {
			return nil
		}
		return e
	}
	id := rec.identity()
	if !SameProcess(id) {
		// 绝不按裸 pid 杀进程：记录已过期，保留现场等下次 serve 覆盖。
		r.recordEvent("api_serve_record_stale")
		return nil
	}
	if e = terminate(id); e != nil {
		return e
	}
	if waitProcessGone(ctx, id, 10*time.Second) {
		_ = os.Remove(r.serveRecordPath())
		r.recordEvent("api_serve_stopped")
		return nil
	}
	if e = ctx.Err(); e != nil {
		return e
	}
	// TERM 无效才升级到 KILL，仍然只在身份一致时才发信号。
	if e = forceTerminate(id); e != nil {
		return e
	}
	if !waitProcessGone(ctx, id, 5*time.Second) {
		return errors.New("api_stop_timeout")
	}
	_ = os.Remove(r.serveRecordPath())
	r.recordEvent("api_serve_stopped")
	return nil
}

// startServe 以 detached 方式拉起新的管理服务，并等 api.pid 确认子进程真的接管了管理面。
// 子进程必须脱离当前会话，否则安装器/CLI 退出会把管理面带下线。
func (r *Runtime) startServe(ctx context.Context) error {
	cmd := exec.Command(filepath.Join(r.ModuleDir, "bin", "wanboxctl"), "__internal", "serve")
	detach(cmd)
	cmd.Stdin = nil
	cmd.Stdout = nil
	cmd.Stderr = nil
	if e := cmd.Start(); e != nil {
		return errors.New("serve_start_failed")
	}
	pid := cmd.Process.Pid
	go cmd.Wait()
	until := time.NewTimer(20 * time.Second)
	defer until.Stop()
	tick := time.NewTicker(100 * time.Millisecond)
	defer tick.Stop()
	for {
		if rec, e := r.readServeRecord(); e == nil && rec.PID == pid && SameProcess(rec.identity()) {
			r.recordEvent("api_serve_started")
			return nil
		}
		select {
		case <-ctx.Done():
			_ = cmd.Process.Kill()
			return ctx.Err()
		case <-until.C:
			// 起不来就把自己的子进程收掉，避免留下半死状态。
			_ = cmd.Process.Kill()
			return errors.New("serve_start_failed")
		case <-tick.C:
		}
	}
}

// stopServe / startServeDetached 是安装流程的两个测试缝：
// 单元测试不启动真实进程，仍可验证「先停旧管理面、再起新管理面、失败回滚」的顺序。
func (r *Runtime) stopServe(ctx context.Context) error {
	if r.installServeStop != nil {
		return r.installServeStop(ctx)
	}
	return r.ServeStop(ctx)
}

func (r *Runtime) startServeDetached(ctx context.Context) error {
	if r.installServeStart != nil {
		return r.installServeStart(ctx)
	}
	return r.startServe(ctx)
}

// waitProcessGone 在超时内轮询身份：身份变了就说明进程已经退出（或 pid 被复用）。
func waitProcessGone(ctx context.Context, id ProcessIdentity, timeout time.Duration) bool {
	until := time.Now().Add(timeout)
	for {
		if !SameProcess(id) {
			return true
		}
		if ctx.Err() != nil {
			return false
		}
		if time.Now().After(until) {
			return false
		}
		time.Sleep(50 * time.Millisecond)
	}
}
