//go:build linux

// @author 雾晚
package module

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestIsolationFailureRemainsObservableWithoutSpawningCore(t *testing.T) {
	r, s := fixture(t)
	if _, e := r.Apply(context.Background(), s); e != nil {
		t.Fatal(e)
	}
	marker := filepath.Join(t.TempDir(), "should-not-start")
	if e := os.MkdirAll(filepath.Join(r.ModuleDir, "bin"), 0700); e != nil {
		t.Fatal(e)
	}
	if e := os.WriteFile(filepath.Join(r.ModuleDir, "bin", "rootbox"), []byte("#!/bin/sh\ntouch '"+marker+"'\n"), 0700); e != nil {
		t.Fatal(e)
	}
	r.independentCgroup = func() error { return errors.New("cgroup_move_failed") }
	if e := r.Supervise(context.Background()); e == nil || e.Error() != "cgroup_move_failed" {
		t.Fatal("isolation bypassed", e)
	}
	if _, e := os.Stat(marker); !os.IsNotExist(e) {
		t.Fatal("core was started despite failed isolation", e)
	}
	state := r.Status()
	if state.Phase != "failed" || state.Error != "cgroup_move_failed" || state.Supervisor.PID != 0 || state.Core.PID != 0 {
		t.Fatal("unobservable failure", state)
	}
	if e := r.Stop(context.Background()); e != nil {
		t.Fatal("stop unavailable after isolation failure", e)
	}
	if r.Status().Revision == "" {
		t.Fatal("diagnostic/stop lost committed config")
	}
}

// Exercise Start's actual detached subprocess/state handshake. The fake
// supervisor writes a terminal failure; no network, TUN or Android device is used.
func TestStartPreservesSafeSupervisorFailure(t *testing.T) {
	for _, tc := range []struct{ name, code, want string }{
		{"isolation", "cgroup_move_failed", "cgroup_move_failed"},
		{"unsafe text redacted", "secret=https://user:pass@example.com", "core_start_failed"},
		{"empty fallback", "", "core_start_failed"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r, snapshot := fixture(t)
			committed, e := r.Apply(context.Background(), snapshot)
			if e != nil {
				t.Fatal(e)
			}
			if e = os.MkdirAll(filepath.Join(r.ModuleDir, "bin"), 0700); e != nil {
				t.Fatal(e)
			}
			failure, e := json.Marshal(State{SchemaVersion: Version, Phase: "failed", RunningRevision: committed.Revision, Error: tc.code})
			if e != nil {
				t.Fatal(e)
			}
			quote := func(s string) string { return "'" + strings.ReplaceAll(s, "'", "'\"'\"'") + "'" }
			// Atomic rename mirrors the real supervisor state writer.
			script := "#!/bin/sh\nprintf '%s' " + quote(string(failure)) + " > " + quote(r.path("failed.tmp")) + "\nmv " + quote(r.path("failed.tmp")) + " " + quote(r.path("state.json")) + "\n"
			if e = os.WriteFile(filepath.Join(r.ModuleDir, "bin", "wanboxctl"), []byte(script), 0700); e != nil {
				t.Fatal(e)
			}
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			if e = r.Start(ctx); e == nil || e.Error() != tc.want {
				t.Fatal("wrong startup diagnostic", e, "want", tc.want)
			}
		})
	}
}
