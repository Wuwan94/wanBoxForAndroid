// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"syscall"
	"time"
	module "wanbox/module"
)

func main() {
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer cancel()
	args := os.Args[1:]
	if len(args) == 3 && args[0] == "__internal" && args[1] == "volume-key" {
		seconds, e := strconv.Atoi(args[2])
		if os.Geteuid() != 0 || e != nil || strconv.Itoa(seconds) != args[2] {
			os.Exit(1)
		}
		key, e := module.ReadVolumeKey(ctx, seconds)
		if e != nil {
			os.Exit(1)
		}
		fmt.Fprintln(os.Stdout, key)
		return
	}
	exe, e := os.Executable()
	if e != nil {
		os.Exit(1)
	}
	dir := filepath.Dir(filepath.Dir(exe))
	// Installed modules and manager staging paths are root-owned. No App-supplied
	// --module-dir/data-root/command arguments, env overrides or shell execution.
	r := module.New("/data/adb/wanbox", dir)
	if len(args) == 2 && args[0] == "node" && args[1] == "tags" {
		state, err := readProfileTags(r, os.Geteuid())
		module.Response(os.Stdout, state, err)
		if err != nil {
			os.Exit(1)
		}
		return
	}
	if len(args) == 2 && args[0] == "channel" {
		state, err := readChannel(r, os.Geteuid())
		module.Response(os.Stdout, state, err)
		if err != nil {
			os.Exit(1)
		}
		return
	}
	// B5（N1/N2）：结果返回型命令，不走 Status 信封。
	// 成功时 stdout 为结果 JSON，失败时 stdout 为 {"error":"<stable_token>"}。
	if len(args) == 3 && args[0] == "node" && (args[1] == "urltest" || args[1] == "speedtest") {
		os.Exit(runNodeTestCommand(ctx, r, args, os.Geteuid(), os.Stdin, os.Stdout))
	}
	if len(args) == 2 && args[0] == "diagnostics" && args[1] == "export" {
		os.Exit(runDiagnosticsExportCommand(r, os.Geteuid(), os.Stdin, os.Stdout))
	}
	// B6（N4）：结果返回型命令，不走 Status 信封。成功时 stdout 为结果 JSON，
	// 失败时 stdout 为 {"error":"<stable_token>"}。
	if len(args) == 2 && args[0] == "__internal" && args[1] == "shared-interfaces" {
		os.Exit(runSharedInterfacesCommand(os.Geteuid(), os.Stdout))
	}
	if len(args) == 3 && args[0] == "__internal" && args[1] == "shared-preflight" {
		os.Exit(runSharedPreflightCommand(os.Geteuid(), args[2], os.Stdout))
	}
	err := runAuthorized(ctx, r, args, os.Geteuid())
	// @author 雾晚：dashboard 返回绑定同一快照的完整状态，不拼接两代次。
	// 渲染统一走 module 包的 CommandResponse，与回环长轮询通道共用同一
	// 路径，保证 CLI 输出与 HTTP 响应逐字节一致。
	command := ""
	if len(args) == 1 {
		command = args[0]
	}
	_, _ = os.Stdout.Write(r.CommandResponse(command, err))
	if err != nil {
		os.Exit(1)
	}
}

func readChannel(r *module.Runtime, euid int) (module.State, error) {
	if euid != 0 {
		return r.Status(), errors.New("root_required")
	}
	return r.ChannelEndpoint()
}

func readProfileTags(r *module.Runtime, euid int) (module.State, error) {
	state := r.Status()
	if euid != 0 {
		return state, errors.New("root_required")
	}
	tags, err := r.ProfileTags()
	state.ProfileTags = tags
	return state, err
}

// printResultError 把稳定错误 token 写成 {"error":"..."}。
// 非 snake_case 的错误一律折叠为 module_operation_failed，
// 与 module.Response 的做法一致，避免原始错误（含凭据可能）外泄。
func printResultError(w io.Writer, err error) {
	token := err.Error()
	if !regexp.MustCompile(`^[a-z_]{1,80}$`).MatchString(token) {
		token = "module_operation_failed"
	}
	_ = json.NewEncoder(w).Encode(struct {
		Error string `json:"error"`
	}{token})
}

// runNodeTestCommand 处理 `node urltest <id>` / `node speedtest <id>`。
// stdin 为 JSON 请求（urltest：{config,url,timeoutMs}；
// speedtest：{config,mode,timeoutMs,serverListURL,
// fallbackServerListURL,simpleDownloadURL}）。
// 成功返回 0 且 stdout 为结果 JSON；失败返回 1 且 stdout 为错误 JSON。
func runNodeTestCommand(ctx context.Context, r *module.Runtime, args []string, euid int, stdin io.Reader, stdout io.Writer) int {
	if euid != 0 {
		printResultError(stdout, errors.New("root_required"))
		return 1
	}
	id, err := strconv.ParseInt(args[2], 10, 64)
	if err != nil || id <= 0 || strconv.FormatInt(id, 10) != args[2] {
		printResultError(stdout, errors.New("arguments_invalid"))
		return 1
	}
	// 上限 5 分钟：覆盖 URL 探测（60 秒 + 60 秒收尾）
	// 与速度测试（60 秒 + 120 秒收尾）的 Root 侧时限。
	ctx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()
	decoder := json.NewDecoder(io.LimitReader(stdin, 1<<20))
	if args[1] == "urltest" {
		var req struct {
			Config    string `json:"config"`
			URL       string `json:"url"`
			TimeoutMs int    `json:"timeoutMs"`
		}
		if err := decoder.Decode(&req); err != nil {
			printResultError(stdout, errors.New("arguments_invalid"))
			return 1
		}
		delay, err := r.NodeURLTest(ctx, id, req.Config, req.URL, req.TimeoutMs)
		if err != nil {
			printResultError(stdout, err)
			return 1
		}
		_ = json.NewEncoder(stdout).Encode(struct {
			DelayMs int32 `json:"delayMs"`
		}{delay})
		return 0
	}
	var req struct {
		Config                string `json:"config"`
		Mode                  string `json:"mode"`
		TimeoutMs             int32  `json:"timeoutMs"`
		ServerListURL         string `json:"serverListURL"`
		FallbackServerListURL string `json:"fallbackServerListURL"`
		SimpleDownloadURL     string `json:"simpleDownloadURL"`
	}
	if err := decoder.Decode(&req); err != nil {
		printResultError(stdout, errors.New("arguments_invalid"))
		return 1
	}
	result, err := r.NodeSpeedTest(ctx, id, req.Config, module.SpeedTestParams{
		Mode:                  req.Mode,
		TimeoutMs:             req.TimeoutMs,
		ServerListURL:         req.ServerListURL,
		FallbackServerListURL: req.FallbackServerListURL,
		SimpleDownloadURL:     req.SimpleDownloadURL,
	})
	if err != nil {
		printResultError(stdout, err)
		return 1
	}
	_ = json.NewEncoder(stdout).Encode(result)
	return 0
}

// validateDiagnosticsDestination 只允许导出到系统 Download 目录：
// /sdcard/Download 或 /storage/emulated/<user>/Download。
// 解析符号链接后再判定，避免经链接绕到别处。
func validateDiagnosticsDestination(destination string) error {
	dir := filepath.Dir(strings.TrimSpace(destination))
	resolved, err := filepath.EvalSymlinks(dir)
	if err != nil {
		return errors.New("diagnostics_destination_unavailable")
	}
	if resolved == filepath.Join("/sdcard", "Download") {
		return nil
	}
	if filepath.Base(resolved) == "Download" && filepath.Dir(filepath.Dir(resolved)) == filepath.Join("/storage", "emulated") {
		return nil
	}
	return errors.New("diagnostics_destination_unavailable")
}

// runDiagnosticsExportCommand 处理 `diagnostics export`。
// stdin 为 module.DiagnosticsRequest JSON；成功返回 0 且 stdout 为
// {"missing":[...]}（无缺失时为空数组），失败返回 1 且 stdout 为错误 JSON。
func runDiagnosticsExportCommand(r *module.Runtime, euid int, stdin io.Reader, stdout io.Writer) int {
	if euid != 0 {
		printResultError(stdout, errors.New("root_required"))
		return 1
	}
	var req module.DiagnosticsRequest
	if err := json.NewDecoder(io.LimitReader(stdin, 1<<20)).Decode(&req); err != nil {
		printResultError(stdout, errors.New("arguments_invalid"))
		return 1
	}
	if err := validateDiagnosticsDestination(req.Destination); err != nil {
		printResultError(stdout, err)
		return 1
	}
	missing, err := r.ExportDiagnostics(req)
	if err != nil {
		printResultError(stdout, err)
		return 1
	}
	if missing == nil {
		missing = []string{}
	}
	_ = json.NewEncoder(stdout).Encode(struct {
		Missing []string `json:"missing"`
	}{missing})
	return 0
}

// runSharedInterfacesCommand 处理 `__internal shared-interfaces`。
// 成功返回 0 且 stdout 为 {"interfaces":[...]}（无候选时为空数组），
// loopback 永不入选；失败返回 1 且 stdout 为错误 JSON。
func runSharedInterfacesCommand(euid int, stdout io.Writer) int {
	if euid != 0 {
		printResultError(stdout, errors.New("root_required"))
		return 1
	}
	names, err := module.ListDownstreamInterfaces()
	if err != nil {
		printResultError(stdout, err)
		return 1
	}
	if names == nil {
		names = []string{}
	}
	_ = json.NewEncoder(stdout).Encode(struct {
		Interfaces []string `json:"interfaces"`
	}{names})
	return 0
}

// runSharedPreflightCommand 处理 `__internal shared-preflight <data-plane>`。
// 成功返回 0 且 stdout 为 eBPF 能力预检报告 JSON（ok=false 也是正常结果，
// 表示能力缺失，不得自动切换数据平面）；非法参数返回 1。
func runSharedPreflightCommand(euid int, dataPlane string, stdout io.Writer) int {
	if euid != 0 {
		printResultError(stdout, errors.New("root_required"))
		return 1
	}
	if dataPlane != "socket_assign" && dataPlane != "packet_rewrite" {
		printResultError(stdout, errors.New("arguments_invalid"))
		return 1
	}
	_ = json.NewEncoder(stdout).Encode(module.EbpfPreflight(dataPlane, nil))
	return 0
}

// Only the long-lived supervisor must escape the caller's cgroup. A Root
// status/stop/config command must still work when that isolation is unavailable,
// so the manager can read the actual failure and stop/repair an existing core.
// Supervise performs its own fail-closed check before spawning any core/helper.
func runAuthorized(ctx context.Context, r *module.Runtime, args []string, euid int) error {
	if euid != 0 {
		return errors.New("root_required")
	}
	return run(ctx, r, args)
}

func run(ctx context.Context, r *module.Runtime, args []string) error {
	if len(args) == 3 && args[0] == "data" && args[1] == "selection-finish" {
		return r.FinishInstallSelection(args[2])
	}
	if len(args) == 4 && args[0] == "__internal" && args[1] == "schedule-install" {
		pid, e := strconv.Atoi(args[2])
		if e != nil || pid <= 1 || strconv.Itoa(pid) != args[2] {
			return errors.New("arguments_invalid")
		}
		return r.ScheduleInstallMode(ctx, pid, args[3])
	}
	if len(args) == 6 && args[0] == "__internal" && args[1] == "finish-install" {
		pid, e := strconv.Atoi(args[2])
		if e != nil || pid <= 1 || strconv.Itoa(pid) != args[2] || len(args[4]) != 64 || strings.Trim(args[4], "0123456789abcdef") != "" {
			return errors.New("arguments_invalid")
		}
		if _, e = strconv.ParseUint(args[3], 10, 64); e != nil {
			return errors.New("arguments_invalid")
		}
		return r.FinishInstallMode(ctx, pid, args[3], args[4], args[5])
	}
	if (len(args) == 3 || len(args) == 5) && args[0] == "__internal" {
		pid, e := strconv.Atoi(args[2])
		if e != nil || pid <= 1 || strconv.Itoa(pid) != args[2] {
			return errors.New("arguments_invalid")
		}
		if len(args) == 3 && args[1] == "schedule-install" {
			return r.ScheduleInstall(ctx, pid)
		}
		if len(args) == 5 && args[1] == "finish-install" {
			if _, e = strconv.ParseUint(args[3], 10, 64); e != nil || len(args[4]) != 64 || strings.Trim(args[4], "0123456789abcdef") != "" {
				return errors.New("arguments_invalid")
			}
			return r.FinishInstall(ctx, pid, args[3], args[4])
		}
	}
	if len(args) == 2 && args[0] == "data" {
		switch args[1] {
		case "prepare":
			return r.PrepareDataUpdate(ctx)
		case "finish":
			return r.FinishDataUpdate()
		case "rollback":
			return r.RollbackDataUpdate()
		}
	}
	if len(args) == 2 && args[0] == "config" && args[1] == "rollback" {
		return r.Rollback(ctx)
	}
	if len(args) == 3 && args[0] == "node" && args[1] == "select" {
		id, e := strconv.ParseInt(args[2], 10, 64)
		if e != nil || id <= 0 || strconv.FormatInt(id, 10) != args[2] {
			return errors.New("arguments_invalid")
		}
		return r.SelectNode(ctx, id)
	}
	if len(args) == 2 && args[0] == "node" && args[1] == "failover" {
		return r.Failover(ctx)
	}
	if len(args) == 1 {
		switch args[0] {
		case "status", "module", "logs", "dashboard":
			return nil
		case "start":
			return r.Start(ctx)
		case "stop":
			return r.Stop(ctx)
		case "resume":
			return r.Resume(ctx)
		case "restart", "reload":
			return r.Restart(ctx)
		}
	}
	if len(args) == 2 && args[0] == "config" && (args[1] == "apply" || args[1] == "validate") {
		snapshot, e := module.DecodeSnapshot(os.Stdin)
		if e != nil {
			return e
		}
		if args[1] == "validate" {
			return r.ValidateSnapshot(ctx, snapshot)
		}
		_, e = r.Apply(ctx, snapshot)
		return e
	}
	if len(args) == 2 && args[0] == "autostart" && (args[1] == "on" || args[1] == "off") {
		return r.SetAutoStart(args[1] == "on")
	}
	if len(args) == 2 && args[0] == "__internal" {
		switch args[1] {
		case "auto-stop-notify":
			return r.DispatchAutoStopBroadcasts(ctx)
		case "supervise":
			return r.Supervise(ctx)
		case "activate":
			return r.Activate(ctx, "/data/adb/modules/wanbox")
		case "install-manager":
			return r.InstallManager(ctx)
		case "boot":
			return r.Boot(ctx, func() bool {
				t, c := context.WithTimeout(ctx, 2*time.Second)
				defer c()
				b, e := exec.CommandContext(t, "/system/bin/getprop", "sys.boot_completed").Output()
				return e == nil && strings.TrimSpace(string(b)) == "1"
			})
		}
	}
	return errors.New("arguments_invalid")
}
