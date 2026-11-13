// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"path/filepath"
	"testing"
	module "wanbox/module"
)

func TestChannelCommandRequiresRootAndFailsClosedWhenDown(t *testing.T) {
	r := module.New(filepath.Join(t.TempDir(), "data"), t.TempDir())
	if _, err := readChannel(r, 2000); err == nil || err.Error() != "root_required" {
		t.Fatal("non-root channel accepted:", err)
	}
	state, err := readChannel(r, 0)
	if err == nil || err.Error() != "channel_unavailable" || state.Channel != nil {
		t.Fatal("down channel did not fail closed:", state, err)
	}
}
