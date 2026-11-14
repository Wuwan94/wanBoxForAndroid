//go:build linux

package module

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestApplyStartupCleanupFailureRequiresExplicitRecovery(t *testing.T) {
	r, snapshot := fixture(t)
	initial, err := r.Apply(context.Background(), snapshot)
	if err != nil {
		t.Fatal(err)
	}
	bin := filepath.Join(r.ModuleDir, "bin")
	if err = os.MkdirAll(bin, 0700); err != nil {
		t.Fatal(err)
	}
	// Simulate the supervisor's public cleanup failure before startup confirmation.
	cli := moduleTestCLI(t, r, `rev=$(cat "$WANBOX_TEST_ROOT/current")
if grep -q fail_cleanup "$WANBOX_TEST_ROOT/generations/$rev/run/config.json"; then
 printf '{"schemaVersion":1,"phase":"failed","runningRevision":"%s","error":"cleanup_required"}' "$rev" > "$WANBOX_TEST_ROOT/state.json"
 exit 1
fi
`)
	core := `#!/bin/sh
trap 'rm -f "$4" "$3"; exit 0' TERM INT
echo $$ > "$3"
echo ready > "$4"
while [ ! -e "$5" ]; do sleep 0.1; done
rm -f "$4" "$3"
`
	for name, body := range map[string]string{"wanboxctl": cli, "rootbox": core} {
		if err = os.WriteFile(filepath.Join(bin, name), []byte(body), 0700); err != nil {
			t.Fatal(err)
		}
	}
	if err = r.Start(context.Background()); err != nil {
		t.Fatal(err)
	}
	defer r.Stop(context.Background())
	snapshot.ExpectedRevision = initial.Revision
	snapshot.Config = json.RawMessage(`{"fail_cleanup":true}`)
	if _, err = r.Apply(context.Background(), snapshot); err == nil || err.Error() != "cleanup_required" {
		t.Fatalf("cleanup failure must survive Apply: %v", err)
	}
	state := r.Status()
	if state.Phase != "failed" || state.Error != "cleanup_required" || state.RunningRevision == initial.Revision || SameProcess(state.Supervisor) || SameProcess(state.Core) {
		t.Fatalf("restarted or hid failure: %+v", state)
	}
	if err = r.Start(context.Background()); err == nil || err.Error() != "cleanup_required" {
		t.Fatalf("start bypassed recovery: %v", err)
	}
	if err = r.Stop(context.Background()); err != nil {
		t.Fatal(err)
	}
	if r.Status().Error != "" {
		t.Fatal("explicit recovery did not clear failure")
	}
}
