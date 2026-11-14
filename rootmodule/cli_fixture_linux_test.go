//go:build linux

package module

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// moduleTestCLI is the executable boundary used by lifecycle tests.
func moduleTestCLI(t *testing.T, r *Runtime, beforeSupervise string) string {
	t.Helper()
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	t.Setenv("WANBOX_TEST_WORKER", "1")
	t.Setenv("WANBOX_TEST_ROOT", r.Root)
	t.Setenv("WANBOX_TEST_MODULE", r.ModuleDir)
	return `#!/bin/sh
if [ "$#" -ne 2 ] || [ "$1" != "__internal" ]; then exit 2; fi
case "$2" in
auto-stop-notify)
 printf 'notification fixture complete\n'
 exit 0
 ;;
supervise)
` + beforeSupervise + "\nexec '" + strings.ReplaceAll(exe, "'", "'\"'\"'") + `' -test.run=^TestModuleWorkerFixture$ -- "$@"
 ;;
*) exit 2 ;;
esac
`
}

func TestModuleCLINotificationExitsWithoutStartingSupervisor(t *testing.T) {
	r, snapshot := fixture(t)
	if _, err := r.Apply(context.Background(), snapshot); err != nil {
		t.Fatal(err)
	}
	bin := filepath.Join(r.ModuleDir, "bin")
	if err := os.MkdirAll(bin, 0700); err != nil {
		t.Fatal(err)
	}
	cli := filepath.Join(bin, "wanboxctl")
	if err := os.WriteFile(cli, []byte(moduleTestCLI(t, r, "")), 0700); err != nil {
		t.Fatal(err)
	}
	// Holding the supervisor lock makes accidental notification routing observable:
	// a notification must finish even while a supervisor owns its lifecycle lock.
	unlock, err := fileLock(r.path("supervisor.lock"))
	if err != nil {
		t.Fatal(err)
	}
	defer unlock()
	before, beforeErr := os.ReadFile(r.path("state.json"))
	if beforeErr != nil && !os.IsNotExist(beforeErr) {
		t.Fatal(beforeErr)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, cli, "__internal", "auto-stop-notify")
	output, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("notification worker must exit successfully without supervising: %v (%s)", err, output)
	}
	if string(output) != "notification fixture complete\n" {
		t.Fatalf("notification branch marker missing: %q", output)
	}
	after, err := os.ReadFile(r.path("state.json"))
	if (beforeErr == nil && err != nil) || (os.IsNotExist(beforeErr) && !os.IsNotExist(err)) || string(after) != string(before) {
		t.Fatalf("notification changed persisted state: %v", err)
	}
	if st := r.Status(); st.Supervisor.PID != 0 || st.Core.PID != 0 || st.Phase == "connected" {
		t.Fatalf("notification started supervisor: %+v", st)
	}
	if _, err := os.Stat(r.path("runtime", "core.pid")); !os.IsNotExist(err) {
		t.Fatalf("notification created core PID file: %v", err)
	}
}
