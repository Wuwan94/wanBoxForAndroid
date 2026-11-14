//go:build linux

// @author 雾晚
package module

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"
)

// A real child process fixture, without VPN/Root routing. Device tests remain separate.
func TestSupervisorCancelReapsCoreAndAppPIDIsNotOwner(t *testing.T) {
	r, s := fixture(t)
	if _, e := r.Apply(context.Background(), s); e != nil {
		t.Fatal(e)
	}
	if e := os.MkdirAll(filepath.Join(r.ModuleDir, "bin"), 0700); e != nil {
		t.Fatal(e)
	}
	script := `#!/bin/sh
trap 'rm -f "$4" "$3"; exit 0' TERM INT
echo $$ > "$3"
echo ready > "$4"
while [ ! -e "$5" ]; do
  echo 'WANBOX_STATS:{"tag":"proxy","tx":0,"rx":0,"directTx":0,"directRx":0}'
  sleep 0.1
done
rm -f "$4" "$3"
`
	if e := os.WriteFile(filepath.Join(r.ModuleDir, "bin", "rootbox"), []byte(script), 0700); e != nil {
		t.Fatal(e)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- r.Supervise(ctx) }()
	deadline := time.Now().Add(5 * time.Second)
	var identity ProcessIdentity
	for time.Now().Before(deadline) {
		state := r.Status()
		if state.Phase == "connected" && len(state.Stats) > 0 {
			identity = state.Core
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if identity.PID == 0 {
		t.Fatal("never became ready", r.Status())
	}
	if !SameProcess(identity) {
		t.Fatal("unverified core")
	}
	// Pass the five-second statistics throttle: identical idle samples must not
	// rewrite the persisted state, while the child remains connected and alive.
	before, e := os.Stat(r.path("state.json"))
	if e != nil {
		t.Fatal(e)
	}
	time.Sleep(5500 * time.Millisecond)
	after, e := os.Stat(r.path("state.json"))
	if e != nil || !after.ModTime().Equal(before.ModTime()) || !SameProcess(identity) {
		t.Fatal("idle samples rewrote state or stopped the child", e)
	}
	cancel()
	select {
	case e := <-done:
		if e != nil {
			t.Fatal(e)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("cancellation hung")
	}
	if SameProcess(identity) {
		t.Fatal("child leaked")
	}
	if r.Status().Phase == "connected" {
		t.Fatal("stale connected")
	}
}
func TestPIDIdentityRejectsReusedPID(t *testing.T) {
	p, e := Identity(os.Getpid())
	if e != nil {
		t.Fatal(e)
	}
	if !SameProcess(p) {
		t.Fatal("own identity missing")
	}
	p.Start = "old-start-token"
	if SameProcess(p) {
		t.Fatal("reused PID accepted")
	}
	if e = terminate(p); e != nil {
		t.Fatal(e)
	}
	if e = forceTerminate(p); e != nil {
		t.Fatal(e)
	} // Must not signal this test process.
}

// This subprocess is the real supervisor fixture, not an Android/root-network test.
func TestModuleWorkerFixture(t *testing.T) {
	if os.Getenv("WANBOX_TEST_WORKER") != "1" {
		return
	}
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGTERM)
	defer cancel()
	r := New(os.Getenv("WANBOX_TEST_ROOT"), os.Getenv("WANBOX_TEST_MODULE"))
	if path := os.Getenv("WANBOX_TEST_NETWORK"); path != "" {
		r.observeNetwork = func() NetworkObservation {
			b, _ := os.ReadFile(path)
			return NetworkObservation{Interfaces: map[string][]net.IP{"wlan0": {net.ParseIP(strings.TrimSpace(string(b)))}}}
		}
	}
	err := r.Supervise(ctx)
	if err != nil {
		os.Exit(1)
	}
	os.Exit(0)
}
func TestActiveFailedApplyRestoresRunningRevision(t *testing.T) {
	r, s := fixture(t)
	initial, e := r.Apply(context.Background(), s)
	if e != nil {
		t.Fatal(e)
	}
	bin := filepath.Join(r.ModuleDir, "bin")
	if e = os.MkdirAll(bin, 0700); e != nil {
		t.Fatal(e)
	}
	cli := moduleTestCLI(t, r, "")
	core := `#!/bin/sh
if grep -q 'fail_start' "$1"; then exit 1; fi
trap 'rm -f "$4" "$3"; exit 0' TERM INT
echo $$ > "$3"
echo ready > "$4"
while [ ! -e "$5" ]; do sleep 0.1; done
rm -f "$4" "$3"
`
	for name, body := range map[string]string{"wanboxctl": cli, "rootbox": core} {
		if e = os.WriteFile(filepath.Join(bin, name), []byte(body), 0700); e != nil {
			t.Fatal(e)
		}
	}
	if e = r.Start(context.Background()); e != nil {
		t.Fatal(e)
	}
	defer r.Stop(context.Background())
	// Re-applying an unchanged connected snapshot must preserve the real child PID.
	before := r.Status()
	s.ExpectedRevision = initial.Revision
	unchanged, e := r.Apply(context.Background(), s)
	if e != nil || unchanged.Core != before.Core || unchanged.Supervisor != before.Supervisor ||
		unchanged.Phase != "connected" || unchanged.RunningRevision != initial.Revision {
		t.Fatal("unchanged apply interrupted running core", e)
	}
	if _, e = os.Stat(r.path("previous")); !os.IsNotExist(e) {
		t.Fatal("unchanged apply replaced rollback pointer", e)
	}
	s.ExpectedRevision = initial.Revision
	s.Config = json.RawMessage(`{"fail_start":true}`)
	if _, e = r.Apply(context.Background(), s); e == nil || e.Error() != "new_core_failed_rolled_back" {
		t.Fatal("missing rollback", e)
	}
	state := r.Status()
	if state.Phase != "connected" || state.RunningRevision != initial.Revision || state.Revision != initial.Revision {
		t.Fatal("wrong live revision", state)
	}
	if e = r.Stop(context.Background()); e != nil {
		t.Fatal(e)
	}
	if e = r.Stop(context.Background()); e != nil {
		t.Fatal("second stop", e)
	}
}

func TestSupervisorManualResumeAndNetworkExitStartOneCore(t *testing.T) {

	previous := runAutoStopCommand
	var phaseMu sync.Mutex
	var phases []string
	runAutoStopCommand = func(args ...string) error {
		phaseMu.Lock()
		defer phaseMu.Unlock()
		phases = append(phases, args[len(args)-1])
		return nil
	}
	defer func() {
		runAutoStopCommand = previous
		phaseMu.Lock()
		defer phaseMu.Unlock()
		if strings.Join(phases, ",") != "suspended,connected,stopped,connected,suspended,connected,stopped" {
			t.Errorf("lifecycle broadcasts: %v", phases)
		}
	}()
	r, snapshot := fixture(t)
	snapshot.Files["files/"+autoStopFile] = []byte(`{"enabled":true,"rules":["192.0.2.0/24"],"appPackage":"io.test.app","userId":10}`)
	var observationMu sync.Mutex
	address := "192.0.2.9"
	r.observeNetwork = func() NetworkObservation {
		observationMu.Lock()
		defer observationMu.Unlock()
		return NetworkObservation{Interfaces: map[string][]net.IP{"wlan0": {net.ParseIP(address)}}}
	}
	if _, err := r.Apply(context.Background(), snapshot); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(r.ModuleDir, "bin"), 0700); err != nil {
		t.Fatal(err)
	}
	countPath := filepath.Join(r.ModuleDir, "starts")
	script := `#!/bin/sh
trap 'rm -f "$4" "$3"; exit 0' TERM INT
echo start >> "` + countPath + `"
echo $$ > "$3"
echo ready > "$4"
while [ ! -e "$5" ]; do sleep 0.05; done
rm -f "$4" "$3"
`
	if err := os.WriteFile(filepath.Join(r.ModuleDir, "bin", "rootbox"), []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Supervise(ctx) }()
	defer func() {
		cancel()
		select {
		case err := <-done:
			if err != nil {
				t.Error(err)
			}
		case <-time.After(5 * time.Second):
			t.Error("supervisor failed to exit")
		}
		if err := r.DispatchAutoStopBroadcasts(context.Background()); err != nil {
			t.Error(err)
		}
	}()
	await := func(phase string, starts int) {
		t.Helper()
		deadline := time.Now().Add(12 * time.Second)
		for time.Now().Before(deadline) {
			b, _ := os.ReadFile(countPath)
			if r.Status().Phase == phase && strings.Count(string(b), "start\n") == starts {
				data, err := os.ReadFile(r.path("runtime", "notification.json"))
				var notification autoStopNotification
				if err == nil && json.Unmarshal(data, &notification) == nil && notification.Phase == phase {
					if err := r.DispatchAutoStopBroadcasts(context.Background()); err != nil {
						t.Fatal(err)
					}
					return
				}
			}
			time.Sleep(20 * time.Millisecond)
		}
		t.Fatalf("wanted %s with %d starts; got %+v", phase, starts, r.Status())
	}
	await("suspended", 1)
	if err := r.Resume(context.Background()); err != nil {
		t.Fatal(err)
	}
	await("connected", 2)
	time.Sleep(5500 * time.Millisecond)
	await("connected", 2)
	// Simulate the supervisor replacement performed by config Apply/reload.
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if err := r.DispatchAutoStopBroadcasts(context.Background()); err != nil {
		t.Fatal(err)
	}
	snapshot.ExpectedRevision = r.Status().Revision
	snapshot.Config = json.RawMessage(`{"reload":true}`)
	if _, err := r.Apply(context.Background(), snapshot); err != nil {
		t.Fatal(err)
	}
	ctx2, cancel2 := context.WithCancel(context.Background())
	defer cancel2()
	done2 := make(chan error, 1)
	go func() { done2 <- r.Supervise(ctx2) }()
	done = done2
	await("connected", 3)
	time.Sleep(5500 * time.Millisecond)
	await("connected", 3)
	observationMu.Lock()
	address = "192.0.2.10"
	observationMu.Unlock()
	await("suspended", 3)
	observationMu.Lock()
	address = "198.51.100.9"
	observationMu.Unlock()
	await("connected", 4)
	// Repeated resume on an already connected core is an idempotent operation.
	for i := 0; i < 3; i++ {
		if err := r.Resume(context.Background()); err != nil {
			t.Fatal(err)
		}
	}
	await("connected", 4)
}

func TestSupervisorAutoStopGracefulTimeoutFailsClosed(t *testing.T) {
	r, snapshot := fixture(t)
	snapshot.Files["files/"+autoStopFile] = []byte(`{"enabled":true,"rules":["192.0.2.9"],"appPackage":"io.test.app","userId":10}`)
	r.observeNetwork = func() NetworkObservation {
		return NetworkObservation{Interfaces: map[string][]net.IP{"wlan0": {net.ParseIP("192.0.2.9")}}}
	}
	if _, err := r.Apply(context.Background(), snapshot); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(r.ModuleDir, "bin"), 0700); err != nil {
		t.Fatal(err)
	}
	script := `#!/bin/sh
trap '' TERM INT
echo $$ > "$3"
echo ready > "$4"
while :; do sleep 0.05; done
`
	if err := os.WriteFile(filepath.Join(r.ModuleDir, "bin", "rootbox"), []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 24*time.Second)
	defer cancel()
	err := r.Supervise(ctx)
	if err == nil || err.Error() != "cleanup_timeout_no_restart" {
		t.Fatalf("graceful timeout must prohibit automatic resume/retry: %v %+v", err, r.Status())
	}
	if st := r.Status(); st.Phase != "failed" || st.Error != "cleanup_required" {
		t.Fatalf("timeout misreported: %+v", st)
	}
}

func TestSupervisorAutoStopAbnormalExitFailsClosed(t *testing.T) {
	r, snapshot := fixture(t)
	snapshot.Files["files/"+autoStopFile] = []byte(`{"enabled":true,"rules":["192.0.2.9"],"appPackage":"io.test.app","userId":10}`)
	r.observeNetwork = func() NetworkObservation {
		return NetworkObservation{Interfaces: map[string][]net.IP{"wlan0": {net.ParseIP("192.0.2.9")}}}
	}
	if _, err := r.Apply(context.Background(), snapshot); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(r.ModuleDir, "bin"), 0700); err != nil {
		t.Fatal(err)
	}
	script := `#!/bin/sh
trap 'exit 7' TERM INT
echo $$ > "$3"
echo ready > "$4"
while :; do sleep 0.05; done
`
	if err := os.WriteFile(filepath.Join(r.ModuleDir, "bin", "rootbox"), []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 9*time.Second)
	defer cancel()
	err := r.Supervise(ctx)
	if err == nil || err.Error() != "cleanup_timeout_no_restart" {
		t.Fatalf("abnormal exit must prohibit automatic resume/retry: %v %+v", err, r.Status())
	}
	if st := r.Status(); st.Phase != "failed" || st.Error != "cleanup_required" {
		t.Fatalf("abnormal exit misreported: %+v", st)
	}
	if err := r.Start(context.Background()); err == nil || err.Error() != "cleanup_required" {
		t.Fatalf("cleanup failure allowed restart: %v", err)
	}
	for i := 0; i < 2; i++ {
		if err := r.Stop(context.Background()); err != nil {
			t.Fatalf("stop retry: %v", err)
		}
	}

}

func TestAutoStopExitRequiresSuccess(t *testing.T) {
	for _, err := range []error{errors.New("exit status 7"), errors.New("core_cleanup_failed")} {
		if got := autoStopExitResult(err); got == nil || got.Error() != "cleanup_timeout_no_restart" {
			t.Fatalf("unsuccessful cleanup permitted suspension: %v", got)
		}
	}
	if !errors.Is(autoStopExitResult(nil), errAutoStop) {
		t.Fatal("successful cleanup did not suspend")
	}
}

func TestActiveApplyPreservesManualResume(t *testing.T) {
	r, snapshot := fixture(t)
	snapshot.Files["files/"+autoStopFile] = []byte(`{"enabled":true,"rules":["192.0.2.0/24"],"appPackage":"io.test.app","userId":10}`)
	if _, err := r.Apply(context.Background(), snapshot); err != nil {
		t.Fatal(err)
	}
	bin := filepath.Join(r.ModuleDir, "bin")
	if err := os.MkdirAll(bin, 0700); err != nil {
		t.Fatal(err)
	}
	network := filepath.Join(r.ModuleDir, "network")
	if err := os.WriteFile(network, []byte("192.0.2.9"), 0600); err != nil {
		t.Fatal(err)
	}
	r.observeNetwork = func() NetworkObservation {
		b, _ := os.ReadFile(network)
		return NetworkObservation{Interfaces: map[string][]net.IP{"wlan0": {net.ParseIP(string(b))}}}
	}
	t.Setenv("WANBOX_TEST_NETWORK", network)
	cli := moduleTestCLI(t, r, "")
	core := `#!/bin/sh
trap 'rm -f "$4" "$3"; exit 0' TERM INT
echo $$ > "$3"
echo ready > "$4"
while [ ! -e "$5" ]; do sleep 0.05; done
rm -f "$4" "$3"
`
	for name, body := range map[string]string{"wanboxctl": cli, "rootbox": core} {
		if err := os.WriteFile(filepath.Join(bin, name), []byte(body), 0700); err != nil {
			t.Fatal(err)
		}
	}
	defer r.Stop(context.Background())
	await := func(phase string) {
		t.Helper()
		deadline := time.Now().Add(10 * time.Second)
		for time.Now().Before(deadline) {
			if r.Status().Phase == phase {
				return
			}
			time.Sleep(20 * time.Millisecond)
		}
		t.Fatalf("wanted %s, got %+v", phase, r.Status())
	}
	if err := r.Start(context.Background()); err != nil {
		t.Fatal(err)
	}
	await("suspended")
	if err := r.Resume(context.Background()); err != nil {
		t.Fatal(err)
	}
	await("connected")
	snapshot.ExpectedRevision = r.Status().Revision
	snapshot.Config = json.RawMessage(`{"reload":true}`)
	if _, err := r.Apply(context.Background(), snapshot); err != nil {
		t.Fatal(err)
	}
	time.Sleep(5500 * time.Millisecond)
	if st := r.Status(); st.Phase != "connected" {
		t.Fatalf("Apply lost manual suppression: %+v", st)
	}
	if err := os.WriteFile(network, []byte("192.0.2.10"), 0600); err != nil {
		t.Fatal(err)
	}
	await("suspended")
	if err := r.Stop(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(network, []byte("198.51.100.9"), 0600); err != nil {
		t.Fatal(err)
	}
	time.Sleep(2200 * time.Millisecond)
	if st := r.Status(); st.Phase != "stopped" || SameProcess(st.Supervisor) || SameProcess(st.Core) {
		t.Fatalf("manual stop undone: %+v", st)
	}
}
