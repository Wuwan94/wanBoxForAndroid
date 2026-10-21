// @author 雾晚
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	module "wanbox/module"
)

// Real dispatch and JSON response used by the Android su/stdio bridge;
// not a rooted Android KSU or real-network test.
func TestRootReadOnlyBridgeAndStopDoNotRequireCgroupMigration(t *testing.T) {
	for _, command := range []string{"status", "module", "logs", "dashboard", "stop"} {
		t.Run(command, func(t *testing.T) {
			dir := t.TempDir()
			mod := filepath.Join(dir, "module")
			if e := os.Mkdir(mod, 0700); e != nil {
				t.Fatal(e)
			}
			if e := os.WriteFile(filepath.Join(mod, "module.prop"), []byte("id=wanbox\n"), 0600); e != nil {
				t.Fatal(e)
			}
			r := module.New(filepath.Join(dir, "state"), mod)
			err := runAuthorized(context.Background(), r, []string{command}, 0)
			var out bytes.Buffer
			module.Response(&out, r.Status(), err)
			var payload struct {
				SchemaVersion int          `json:"schemaVersion"`
				OK            bool         `json:"ok"`
				State         module.State `json:"state"`
			}
			if e := json.Unmarshal(out.Bytes(), &payload); e != nil {
				t.Fatal(e)
			}
			if err != nil || !payload.OK || payload.SchemaVersion != 1 || payload.State.Phase != "stopped" {
				t.Fatal("bridge failed", out.String(), err)
			}
		})
	}
}
func TestRootAuthorizationStillRequiredForEveryPublicCommand(t *testing.T) {
	r := module.New(t.TempDir(), t.TempDir())
	for _, args := range [][]string{{"status"}, {"stop"}, {"config", "apply"}, {"__internal", "supervise"}} {
		if e := runAuthorized(context.Background(), r, args, 2000); e == nil || e.Error() != "root_required" {
			t.Fatal("non-root accepted", args, e)
		}
	}
}
func TestUnknownArgumentsStillRejected(t *testing.T) {
	r := module.New(t.TempDir(), t.TempDir())
	for _, args := range [][]string{{"status;id"}, {"--module-dir", "/tmp"}, {"node", "select", "-1"}} {
		if e := runAuthorized(context.Background(), r, args, 0); e == nil || e.Error() != "arguments_invalid" {
			t.Fatal("unknown arguments accepted", args, e)
		}
	}
}
