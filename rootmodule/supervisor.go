// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

func (r *Runtime) Supervise(ctx context.Context) error {
	if e := r.init(); e != nil {
		return e
	}
	// B5（N2）：supervisor 自身日志落盘供诊断包使用；尽力而为，失败不阻断启动。
	if serviceLog, err := openCappedLogFile(r.path("logs", "service.log")); err == nil {
		log.SetOutput(io.MultiWriter(serviceLog, os.Stderr))
		defer func() {
			serviceLog.Close()
			log.SetOutput(os.Stderr)
		}()
	}
	unlock, e := fileLock(r.path("supervisor.lock"))
	if e != nil {
		return e
	}
	defer unlock()
	identity, e := Identity(os.Getpid())
	if e != nil {
		return e
	}
	rev, snapshot, e := r.Current()
	if e != nil || rev == "" {
		return errors.New("config_missing")
	}
	// Resource bytes have already been validated and committed to private files.
	// Do not keep up to 128 MiB of decoded Base64 resources alive in the supervisor.
	snapshot.Files = nil
	snapshot.Config = nil
	// B6（N3）：统一按网络规则表；旧 F1 文件不存在新表时回退迁移。
	// 规则文件非法时不能静默变空表：与 monitorGeneration 同口径记录限频事件，
	// 而不是丢掉错误（否则停止时不会给目标应用发自动暂停广播，且完全无迹可循）。
	policy, policyErr := readUnifiedPolicy(r.path("generations", rev, "files"))
	if policyErr != nil {
		policy = NetworkPolicy{}
		r.recordEventLimited("network_policy_invalid", 5*time.Minute)
	}
	s := State{SchemaVersion: Version, Phase: "starting", Revision: rev, RunningRevision: rev, ProfileID: snapshot.ProfileID, ProfileName: snapshot.ProfileName, Supervisor: identity}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	var mu sync.Mutex
	persist := func() {
		if jsonWrite(r.path("state.json"), s) != nil {
			cancel()
		} else {
			// Wake loopback long polls (channel.go) so they can re-render
			// and return early if the state revision advanced.
			r.notifyStateChanged()
		}
	}
	if e = jsonWrite(r.path("state.json"), s); e != nil {
		return errors.New("state_write_failed")
	}
	defer func() {
		mu.Lock()
		defer mu.Unlock()
		if !SameProcess(s.Core) {
			s.Core = ProcessIdentity{}
		}
		s.Supervisor = ProcessIdentity{}
		s.Stats = nil
		if s.Phase != "failed" {
			s.Phase = "stopped"
		}
		persist()
		if policy.AppPackage != "" {
			phase := s.Phase
			if !r.Enabled() {
				phase = "disabled"
			}
			r.QueueAutoStopBroadcast(policy.broadcastIdentity(), phase)
		}
		_ = os.Remove(r.path("runtime", "ready"))
	}()
	if e = r.independentCgroup(); e != nil {
		s.Phase = "failed"
		s.Error = "cgroup_move_failed"
		s.Supervisor = ProcessIdentity{}
		persist()
		return e
	}
	// B6（N4）：共享接管开启门禁。启用时 eBPF 能力预检 MUST 通过，否则
	// Fail-Closed：拒绝启动并明确报错，不得静默降级为无共享启动，
	// 不得自动切换数据平面。
	if sharedCfg, e := readSharedConfig(r.path("generations", rev, "files", sharedInterceptionFile)); e == nil {
		if e := CheckSharedPreflight(sharedCfg, nil); e != nil {
			s.Phase = "failed"
			s.Error = "shared_preflight_failed"
			s.Supervisor = ProcessIdentity{}
			persist()
			r.recordEvent("shared_preflight_failed")
			return e
		}
	}
	// Best-effort cgroup v2 memory governance (cgroup_memory.go): first
	// carve out the module's dedicated child group and move into it
	// (cgroup_child.go) — this still runs before the first generation
	// below, so the core and plugins are born inside the governed
	// group — then soft-limit that group and GC under memory pressure.
	// Unlike the cgroup move above, unavailability is never an error —
	// supervision runs the same with or without it.
	if guard := r.setupMemoryGuard(); guard != nil {
		go guard.run(ctx, memoryCheckInterval)
	}
	// Best-effort loopback long-poll channel (channel.go): lets the manager
	// subscribe to status/dashboard over 127.0.0.1 HTTP instead of spawning
	// a `su` bridge per poll. Like the memory guard, unavailability is never
	// an error — a failed channel only loses an optimization and the
	// manager keeps using the su/stdio bridge — so startChannelServer
	// reports failure by returning nil, never by erroring Supervise.
	if loopback := r.startChannelServer(ctx); loopback != nil {
		defer loopback.stop()
	}
	// Stop on module disable/remove even when the management APK has been removed.
	done := make(chan struct{})
	defer func() { cancel(); <-done }()
	go func() {
		defer close(done)
		// @author 雾晚: module disable is a rare manual action; 30s granularity
		// is plenty and reduces idle wakeups.
		t := time.NewTicker(30 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				if !r.Enabled() {
					cancel()
					return
				}
			}
		}
	}()
	// @author 雾晚 (phase 2): bounded watchdog. At most maxCoreAttempts starts per
	// crash window with capped exponential backoff (1s,2s,4s,8s,16s ≤30s); every
	// exit reason is persisted (state.lastExit/lastReason + events) so the manager
	// can explain the drop. A generation that stayed connected ≥5 min resets the
	// budget; a validated-network rebuild never consumes it. No infinite restarts.
	recovering := false
	suppressed := r.readSuppression()
	for attempt := 0; attempt < maxCoreAttempts; attempt++ {
		if ctx.Err() != nil {
			return nil
		}
		if _, err := os.Stat(r.path("runtime", "stop")); err == nil {
			return nil
		}
		_ = os.Remove(r.path("runtime", "ready"))
		mu.Lock()
		s.Phase = "starting"
		s.Error = ""
		s.Health = ""
		s.RetryCount = attempt
		persist()
		mu.Unlock()
		generationStart := time.Now()
		err := r.runGeneration(ctx, rev, snapshot, &s, &mu, persist, &suppressed, func() {
			if policy.AppPackage != "" {
				r.QueueAutoStopBroadcast(policy.broadcastIdentity(), "connected")
			}
			if recovering {
				recovering = false
				r.recordEvent("reconnect_ok")
			}
		})
		mu.Lock()
		// @author 雾晚: a core that stayed connected for a while is a fresh failure,
		// not part of the startup crash loop; never let rare crashes over days
		// accumulate into a permanent retry_limit_reached.
		stableRun := s.Phase == "connected" && time.Since(generationStart) >= stableRunReset
		mu.Unlock()
		if err != nil && err.Error() == "cleanup_timeout_no_restart" {
			mu.Lock()
			s.Phase = "failed"
			s.Error = "cleanup_required"
			persist()
			mu.Unlock()
			return err
		}
		if ctx.Err() != nil {
			return nil
		}
		if _, e := os.Stat(r.path("runtime", "stop")); e == nil {
			return nil
		}
		if err == nil {
			return nil
		}
		if errors.Is(err, errAutoStop) {
			mu.Lock()
			s.Phase = "suspended"
			s.Error = "auto_stop"
			s.Core = ProcessIdentity{}
			s.Stats = nil
			s.Telemetry = nil
			persist()
			mu.Unlock()
			r.recordEvent("auto_stop")
			if policy.AppPackage != "" {
				r.QueueAutoStopBroadcast(policy.broadcastIdentity(), "suspended")
			}
			for {
				select {
				case <-ctx.Done():
					return nil
				case <-time.After(2 * time.Second):
				}
				if _, e := os.Stat(r.path("runtime", "stop")); e == nil {
					return nil
				}
				if b, e := os.ReadFile(r.path("runtime", "resume")); e == nil {
					if len(b) <= 128 {
						suppressed = strings.TrimSpace(string(b))
						_ = os.Remove(r.path("runtime", "resume"))
						break
					}
				}
				if !r.policyNetworkDecision(policy, &suppressed).Suspend {
					break
				}
			}
			attempt-- // Intentional suspension/resume does not consume the crash budget.
			continue
		}
		reason := classifyExit(err)
		mu.Lock()
		s.LastExit = exitCode(err)
		s.LastReason = reason
		s.LastExitAt = time.Now().Unix()
		mu.Unlock()
		r.recordEvent("core_exit_" + exitCode(err))
		if reason != ReasonNetworkChange {
			// network_change was already recorded by the monitor with its context.
			r.recordEvent(reason)
		}
		recovering = true

		mu.Lock()
		s.Phase = "starting"
		s.Error = "core_retry"
		s.Stats = nil
		persist()
		mu.Unlock()
		if stableRun || errors.Is(err, errNetworkRebuild) {
			attempt = -1
		}
		if attempt == maxCoreAttempts-1 {
			mu.Lock()
			s.Phase = "failed"
			s.Error = "retry_limit_reached"
			persist()
			mu.Unlock()
			r.recordEvent("retry_limit_reached")
			return errors.New("retry_limit_reached")
		}
		select {
		case <-ctx.Done():
			return nil
		case <-time.After(retryBackoff(attempt)):
		}
	}
	return nil
}

// stableRunReset is how long a generation must stay connected before its exit
// resets the retry budget.
const stableRunReset = 5 * time.Minute

func retryBackoff(attempt int) time.Duration {
	if attempt < 0 {
		return time.Second
	}
	if attempt > 5 {
		return maxRetryBackoff
	}
	d := time.Duration(1<<attempt) * time.Second
	if d > maxRetryBackoff {
		return maxRetryBackoff
	}
	return d
}

func (r *Runtime) runGeneration(ctx context.Context, rev string, snapshot Snapshot, s *State, mu *sync.Mutex, persist func(), suppressed *string, onConnected func()) (result error) {
	dir := r.path("generations", rev)
	childCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	identities := make([]ProcessIdentity, 0, len(snapshot.Plugins))
	waiters := make([]chan error, 0, len(snapshot.Plugins))

	defer func() {
		result = finishGeneration(result, identities, waiters, terminate, forceTerminate, 2*time.Second)
		if result != nil && result.Error() == "cleanup_timeout_no_restart" {
			// Keep identities private so explicit stop can retry and start cannot
			// duplicate helpers after this supervisor exits.
			_ = jsonWrite(r.path("runtime", "cleanup.json"), identities)
			mu.Lock()
			s.Phase, s.Error = "failed", "cleanup_required"
			persist()
			mu.Unlock()
		}
	}()

	for _, plugin := range snapshot.Plugins {
		binary := filepath.Join(dir, plugin.Binary)
		config := filepath.Join(dir, plugin.Config)
		var args []string
		env := append(os.Environ(), "LD_LIBRARY_PATH="+filepath.Dir(binary))
		switch plugin.Kind {
		case "trojan-go":
			args = []string{"-config", config}
		case "mieru":
			args = []string{"run"}
			env = append(env, "MIERU_CONFIG_JSON_FILE="+config)
		case "naive":
			args = []string{config}
			if plugin.Certificate != "" {
				env = append(env, "SSL_CERT_FILE="+filepath.Join(dir, plugin.Certificate))
			}
		case "hysteria":
			// @author 雾晚: --no-check disables Hysteria v1's update check, not TLS
			// certificate verification ("disable update check"). Certificate policy
			// lives in the generated config (insecure), which defaults to false.
			args = []string{"--no-check", "--config", config, "--log-level", "warn", "client"}
		default:
			return errors.New("plugin_unsupported")
		}
		cmd := exec.Command(binary, args...)
		parentDeathSignal(cmd)
		cmd.Dir = filepath.Join(dir, "run")
		cmd.Env = env
		cmd.Stdout = io.Discard
		cmd.Stderr = io.Discard
		if e := cmd.Start(); e != nil {
			return errors.New("plugin_start_failed")
		}
		identity, e := Identity(cmd.Process.Pid)
		if e != nil {
			cmd.Process.Kill()
			cmd.Wait()
			return errors.New("plugin_identity_failed")
		}
		identities = append(identities, identity)
		wait := make(chan error, 1)
		waiters = append(waiters, wait)
		go func() { err := cmd.Wait(); wait <- err; cancel() }()
	}
	cmd := exec.Command(filepath.Join(r.ModuleDir, "bin", "rootbox"), filepath.Join(dir, "run", "config.json"), filepath.Join(dir, "assets"), r.path("runtime", "core.pid"), r.path("runtime", "ready"), r.path("runtime", "stop"), strconv.Itoa(os.Getpid()))
	parentDeathSignal(cmd)
	cmd.Dir = filepath.Join(dir, "run")
	cmd.Env = append(os.Environ(), "WANBOX_MODULE=1", "WANBOX_PERFORMANCE="+strconv.FormatBool(snapshot.PerformancePriority))
	output, e := cmd.StdoutPipe()
	if e != nil {
		return e
	}
	// B5（N2）：核心 stderr 落盘供诊断包使用（logs/sing-box.log）；尽力而为，
	// 打不开日志文件时仍启动核心，只是本次运行无核心日志可导出。
	coreLog, _ := newCappedLogWriter(r.path("logs", "sing-box.log"))
	if coreLog != nil {
		defer coreLog.Close()
	}
	stderr, e := cmd.StderrPipe()
	if e != nil {
		return e
	}
	if e = cmd.Start(); e != nil {
		return errors.New("core_start_failed")
	}
	p, e := Identity(cmd.Process.Pid)
	if e != nil {
		cmd.Process.Kill()
		cmd.Wait()
		return errors.New("core_identity_failed")
	}
	mu.Lock()
	s.Core = p
	persist()
	mu.Unlock()
	readerDone := make(chan struct{})
	go func() {
		defer close(readerDone)
		reader := bufio.NewReaderSize(output, 8192)
		last := time.Time{}
		for {
			chunk, readErr := reader.ReadSlice('\n')
			if readErr == bufio.ErrBufferFull {
				// Oversized raw log line: discard the rest of it and keep reading.
				for readErr == bufio.ErrBufferFull {
					_, readErr = reader.ReadSlice('\n')
				}
				if readErr != nil {
					break
				}
				continue
			}
			line := strings.TrimRight(string(chunk), "\r\n")
			if strings.HasPrefix(line, "WANBOX_STATS:") && time.Since(last) >= 5*time.Second {
				raw := strings.TrimPrefix(line, "WANBOX_STATS:")
				var stats struct {
					Tag      string `json:"tag"`
					Tx       int64  `json:"tx"`
					Rx       int64  `json:"rx"`
					DirectTx int64  `json:"directTx"`
					DirectRx int64  `json:"directRx"`
				}
				if json.Unmarshal([]byte(raw), &stats) == nil && len(stats.Tag) <= 512 && stats.Tx >= 0 && stats.Rx >= 0 && stats.DirectTx >= 0 && stats.DirectRx >= 0 {
					last = time.Now()
					b, _ := json.Marshal(stats)
					mu.Lock()
					if replaceStats(s, b) {
						persist()
					}
					mu.Unlock()
				}
			}
			if readErr != nil {
				break
			}
		}
		_, _ = io.Copy(io.Discard, output) // Never block the core on a full pipe.
	}()
	stderrDone := make(chan struct{})
	go func() {
		defer close(stderrDone)
		reader := bufio.NewReaderSize(stderr, 8192)
		for {
			chunk, readErr := reader.ReadSlice('\n')
			if readErr == bufio.ErrBufferFull {
				// Oversized raw log line: discard the rest of it and keep reading.
				for readErr == bufio.ErrBufferFull {
					_, readErr = reader.ReadSlice('\n')
				}
				if readErr != nil {
					break
				}
				continue
			}
			line := strings.TrimRight(string(chunk), "\r\n")
			if line != "" {
				coreLog.WriteLine(line)
			}
			if readErr != nil {
				break
			}
		}
		_, _ = io.Copy(io.Discard, stderr) // Never block the core on a full pipe.
	}()
	wait := make(chan error, 1)
	go func() { wait <- cmd.Wait() }()
	probe := newStartupProbe(100*time.Millisecond, 60*time.Second)
	defer probe.stop()
	startupFailed := false
	monitorDone := make(chan error, 1)
	var monitorAutoStop bool
	monitorStarted := false
	monitorExited := make(chan struct{})
	defer func() {
		cancel()
		if monitorStarted {
			<-monitorExited
		}
	}()
	networkRebuild := false
	for {
		select {
		case me := <-monitorDone:
			if errors.Is(me, errNetworkRebuild) {
				networkRebuild = true
				cancel()
			} else if errors.Is(me, errAutoStop) {
				monitorAutoStop = true
				cancel()
			}
		case err := <-wait:
			<-readerDone
			<-stderrDone
			if monitorAutoStop {
				return autoStopExitResult(err)
			}
			if ctx.Err() != nil {
				return nil
			}
			if networkRebuild {
				return errNetworkRebuild
			}
			if err == nil {
				err = errors.New("core_exited")
			}
			return err
		case <-childCtx.Done():
			_ = terminate(p)
			select {
			case err := <-wait:
				<-readerDone
				if monitorAutoStop {
					return autoStopExitResult(err)
				}
				if ctx.Err() != nil {
					return nil
				}
				if startupFailed {
					return errors.New("startup_timeout")
				}
				if networkRebuild {
					return errNetworkRebuild
				}
				return errors.New("plugin_exited")
			case <-time.After(20 * time.Second):
				// @author 雾晚: escalate on the identity captured at start; only a core
				// that survives SIGKILL is left for manual cleanup.
				_ = forceTerminate(p)
				select {
				case <-wait:
					<-readerDone
					if monitorAutoStop {
						return errors.New("cleanup_timeout_no_restart")
					}
					if ctx.Err() != nil {
						return nil
					}
					return errors.New("core_killed_after_timeout")
				case <-time.After(5 * time.Second):
					return errors.New("cleanup_timeout_no_restart")
				}
			}
		case <-probe.deadline:
			startupFailed = true
			cancel()
		case <-probe.tick:
			if _, e = os.Stat(r.path("runtime", "ready")); e == nil {
				if alignErr := r.AlignClashMode(childCtx, rev, p); alignErr != nil {
					startupFailed = true
					cancel()
					break
				}
				mu.Lock()
				if s.Phase != "connected" {
					s.Phase = "connected"
					if jsonWrite(r.path("state.json"), s) != nil {
						s.Phase = "failed"
						startupFailed = true
						cancel()
					} else {
						r.notifyStateChanged()
					}
					probe.stop()
				}
				mu.Unlock()
				if !monitorStarted && !startupFailed {
					monitorStarted = true
					if onConnected != nil {
						onConnected()
					}
					go func() {
						defer close(monitorExited)
						monitorDone <- r.monitorGeneration(childCtx, rev, s, mu, persist, suppressed)
					}()
				}
			}
		}
	}
}

var errAutoStop = errors.New("auto_stop")

// A waiter receiving any result confirms exit; a signal alone does not.
func finishGeneration(result error, identities []ProcessIdentity, waiters []chan error, stop, kill func(ProcessIdentity) error, timeout time.Duration) error {
	for _, identity := range identities {
		_ = stop(identity)
	}
	failed := make(chan bool, len(waiters))
	var cleanup sync.WaitGroup
	for i, wait := range waiters {
		cleanup.Add(1)
		go func(i int, wait chan error) {
			defer cleanup.Done()
			select {
			case <-wait:
				return
			case <-time.After(timeout):
			}
			_ = kill(identities[i])
			select {
			case <-wait:
			case <-time.After(timeout):
				failed <- true
			}
		}(i, wait)
	}
	cleanup.Wait()
	if len(failed) != 0 {
		return errors.New("cleanup_timeout_no_restart")
	}
	return result
}

// Successful process exit is the core's acknowledgement that Close succeeded.
func autoStopExitResult(err error) error {
	if err != nil {
		return errors.New("cleanup_timeout_no_restart")
	}
	return errAutoStop
}
