package module

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"reflect"
	"sync"
	"testing"
	"time"
)

func TestAutoStopDispatchRetriesFailedDelivery(t *testing.T) {
	r, _ := fixture(t)
	if err := r.init(); err != nil {
		t.Fatal(err)
	}
	user := 10
	payload := autoStopNotification{SmartAutoStop{AppPackage: "io.test.app", UserID: &user}, "suspended"}
	if err := jsonWrite(r.path("runtime", "notification.json"), payload); err != nil {
		t.Fatal(err)
	}
	attempts := 0
	r.autoStopCommand = func(args ...string) error {
		attempts++
		if attempts == 1 {
			return errors.New("delivery failed")
		}
		return nil
	}
	if err := r.DispatchAutoStopBroadcasts(context.Background()); err != nil {
		t.Fatal(err)
	}
	if attempts != 2 {
		t.Fatalf("attempts = %d, want 2", attempts)
	}
	pending, err := os.ReadFile(r.path("runtime", "notification.json"))
	if err != nil {
		t.Fatal(err)
	}
	delivered, err := os.ReadFile(r.path("runtime", "notification-delivered.json"))
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(pending, delivered) {
		t.Fatalf("delivered payload = %s, want %s", delivered, pending)
	}
}

func TestAutoStopDispatchRetryCoalescesNewerPhase(t *testing.T) {
	r, _ := fixture(t)
	if err := r.init(); err != nil {
		t.Fatal(err)
	}
	user := 10
	payload := autoStopNotification{SmartAutoStop{AppPackage: "io.test.app", UserID: &user}, "suspended"}
	if err := jsonWrite(r.path("runtime", "notification.json"), payload); err != nil {
		t.Fatal(err)
	}
	var phases []string
	r.autoStopCommand = func(args ...string) error {
		phases = append(phases, args[len(args)-1])
		if len(phases) == 1 {
			payload.Phase = "stopped"
			if err := jsonWrite(r.path("runtime", "notification.json"), payload); err != nil {
				t.Fatal(err)
			}
			return errors.New("delivery failed")
		}
		if _, err := os.Stat(r.path("runtime", "notification-delivered.json")); !os.IsNotExist(err) {
			t.Fatalf("failed phase was acknowledged: %v", err)
		}
		return nil
	}
	if err := r.DispatchAutoStopBroadcasts(context.Background()); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(phases, []string{"suspended", "stopped"}) {
		t.Fatalf("phases = %v", phases)
	}
	var delivered autoStopNotification
	data, err := os.ReadFile(r.path("runtime", "notification-delivered.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(data, &delivered); err != nil {
		t.Fatal(err)
	}
	if delivered.Phase != "stopped" {
		t.Fatalf("delivered = %v", delivered)
	}
}

func TestAutoStopDispatchExhaustionLeavesLatestPending(t *testing.T) {
	r, _ := fixture(t)
	if err := r.init(); err != nil {
		t.Fatal(err)
	}
	user := 10
	payload := autoStopNotification{SmartAutoStop{AppPackage: "io.test.app", UserID: &user}, "suspended"}
	if err := jsonWrite(r.path("runtime", "notification.json"), payload); err != nil {
		t.Fatal(err)
	}
	wantErr := errors.New("delivery failed")
	attempts := 0
	r.autoStopCommand = func(args ...string) error {
		attempts++
		payload.Phase = "stopped"
		if err := jsonWrite(r.path("runtime", "notification.json"), payload); err != nil {
			t.Fatal(err)
		}
		return wantErr
	}
	if err := r.DispatchAutoStopBroadcasts(context.Background()); !errors.Is(err, wantErr) {
		t.Fatalf("error = %v", err)
	}
	if attempts != 3 {
		t.Fatalf("attempts = %d, want 3", attempts)
	}
	if _, err := os.Stat(r.path("runtime", "notification-delivered.json")); !os.IsNotExist(err) {
		t.Fatalf("failure acknowledged: %v", err)
	}
	r.autoStopCommand = func(args ...string) error {
		if phase := args[len(args)-1]; phase != "stopped" {
			t.Fatalf("replayed stale phase %q", phase)
		}
		return nil
	}
	if err := r.DispatchAutoStopBroadcasts(context.Background()); err != nil {
		t.Fatal(err)
	}
	pending, err := os.ReadFile(r.path("runtime", "notification.json"))
	if err != nil {
		t.Fatal(err)
	}
	delivered, err := os.ReadFile(r.path("runtime", "notification-delivered.json"))
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(pending, delivered) {
		t.Fatalf("latest payload not delivered")
	}
}

func TestAutoStopCommandHasShortDeadline(t *testing.T) {
	sleep, err := exec.LookPath("sleep")
	if err != nil {
		t.Skip("sleep unavailable")
	}
	started := time.Now()
	if err := runAutoStopCommand(sleep, "10"); err == nil {
		t.Fatal("stuck command completed without timeout")
	}
	if elapsed := time.Since(started); elapsed > 2*time.Second {
		t.Fatalf("broadcast command blocked for %v", elapsed)
	}
}

func TestAutoStopDispatchSerializesAndCoalescesLatestPhase(t *testing.T) {
	r, _ := fixture(t)
	if err := r.init(); err != nil {
		t.Fatal(err)
	}
	user := 10
	policy := SmartAutoStop{AppPackage: "io.test.app", UserID: &user}
	previous := runAutoStopCommand
	entered, release := make(chan struct{}), make(chan struct{})
	var mu sync.Mutex
	var phases []string
	runAutoStopCommand = func(args ...string) error {
		mu.Lock()
		phases = append(phases, args[len(args)-1])
		first := len(phases) == 1
		mu.Unlock()
		if first {
			close(entered)
			<-release
		}
		return nil
	}
	defer func() { runAutoStopCommand = previous }()
	r.QueueAutoStopBroadcast(policy, "suspended")
	done := make(chan error, 2)
	go func() { done <- r.DispatchAutoStopBroadcasts(context.Background()) }()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("dispatch never started")
	}
	r.QueueAutoStopBroadcast(policy, "connected")
	r.QueueAutoStopBroadcast(policy, "stopped")
	go func() { done <- r.DispatchAutoStopBroadcasts(context.Background()) }()
	close(release)
	for i := 0; i < 2; i++ {
		select {
		case err := <-done:
			if err != nil {
				t.Fatal(err)
			}
		case <-time.After(3 * time.Second):
			t.Fatal("dispatch hung")
		}
	}
	if !reflect.DeepEqual(phases, []string{"suspended", "stopped"}) {
		t.Fatalf("stale or unordered delivery: %v", phases)
	}
	// A late worker must not replay suspended or duplicate the terminal phase.
	if err := r.DispatchAutoStopBroadcasts(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(phases) != 2 {
		t.Fatalf("late worker replayed notification: %v", phases)
	}
}
