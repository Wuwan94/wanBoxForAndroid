// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
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
	s := State{SchemaVersion: Version, Phase: "starting", Revision: rev, RunningRevision: rev, ProfileID: snapshot.ProfileID, ProfileName: snapshot.ProfileName, Supervisor: identity}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	var mu sync.Mutex
	persist := func() {
		if jsonWrite(r.path("state.json"), s) != nil {
			cancel()
		}
	}
	if e = jsonWrite(r.path("state.json"), s); e != nil {
		return errors.New("state_write_failed")
	}
	if e = r.independentCgroup(); e != nil {
		s.Phase = "failed"
		s.Error = "cgroup_move_failed"
		s.Supervisor = ProcessIdentity{}
		persist()
		return e
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
		_ = os.Remove(r.path("runtime", "ready"))
	}()
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
	for attempt := 0; attempt <= 3; attempt++ {
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
		persist()
		mu.Unlock()
		generationStart := time.Now()
		err := r.runGeneration(ctx, rev, snapshot, &s, &mu, persist)
		mu.Lock()
		// @author 雾晚: a core that stayed connected for a while is a fresh failure,
		// not part of the startup crash loop; never let rare crashes over days
		// accumulate into a permanent retry_limit_reached.
		stableRun := s.Phase == "connected" && time.Since(generationStart) >= stableRunReset
		mu.Unlock()
		if ctx.Err() != nil {
			return nil
		}
		if _, e := os.Stat(r.path("runtime", "stop")); e == nil {
			return nil
		}
		if err == nil {
			return nil
		}
		if err.Error() == "cleanup_timeout_no_restart" {
			mu.Lock()
			s.Phase = "failed"
			s.Error = "cleanup_required"
			persist()
			mu.Unlock()
			return err
		}
		mu.Lock()
		s.Phase = "starting"
		s.Error = "core_retry"
		s.Stats = nil
		persist()
		mu.Unlock()
		if stableRun {
			attempt = -1
		}
		if attempt == 3 {
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
	return time.Duration(1<<attempt) * time.Second
}

func (r *Runtime) runGeneration(ctx context.Context, rev string, snapshot Snapshot, s *State, mu *sync.Mutex, persist func()) error {
	dir := r.path("generations", rev)
	childCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	identities := make([]ProcessIdentity, 0, len(snapshot.Plugins))
	waiters := make([]chan error, 0, len(snapshot.Plugins))
	defer func() {
		for _, identity := range identities {
			_ = terminate(identity)
		}
		var cleanup sync.WaitGroup
		for i, wait := range waiters {
			cleanup.Add(1)
			go func() {
				defer cleanup.Done()
				select {
				case <-wait:
				case <-time.After(2 * time.Second):
					_ = forceTerminate(identities[i])
					select {
					case <-wait:
					case <-time.After(2 * time.Second):
					}
				}
			}()
		}
		cleanup.Wait()
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
	cmd.Stderr = io.Discard
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
	wait := make(chan error, 1)
	go func() { wait <- cmd.Wait() }()
	probe := newStartupProbe(100*time.Millisecond, 60*time.Second)
	defer probe.stop()
	startupFailed := false
	for {
		select {
		case err := <-wait:
			<-readerDone
			if err == nil {
				err = errors.New("core_exited")
			}
			return err
		case <-childCtx.Done():
			_ = terminate(p)
			select {
			case <-wait:
				<-readerDone
				if ctx.Err() != nil {
					return nil
				}
				if startupFailed {
					return errors.New("startup_timeout")
				}
				return errors.New("plugin_exited")
			case <-time.After(20 * time.Second):
				// @author 雾晚: escalate on the identity captured at start; only a core
				// that survives SIGKILL is left for manual cleanup.
				_ = forceTerminate(p)
				select {
				case <-wait:
					<-readerDone
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
					}
					probe.stop()
				}
				mu.Unlock()
			}
		}
	}
}
