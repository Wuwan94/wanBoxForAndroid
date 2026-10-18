// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"testing"
)

// supervisor 用启动时的内存快照周期性重写 state.json。热切换必须能在这之后
// 仍然被读到，否则 App 会看到节点先切换、随后又弹回旧节点。
func TestRuntimeSelectionSurvivesSupervisorPersist(t *testing.T) {
	r, _ := fixture(t)
	fake := &fakeClash{selector: "proxy", now: "a", secret: "s3cret"}
	tags := map[string]ProfileTag{"1": {Tag: "a", Name: "node-a"}, "2": {Tag: "b", Name: "node-b"}}
	rev := stageSelectNode(t, r, fake, tags, true)

	if e := r.SelectNode(context.Background(), 2); e != nil {
		t.Fatal("select:", e)
	}

	// 模拟 supervisor 的 persist：用启动时的快照覆盖 state.json。
	snapshot := State{SchemaVersion: Version, Phase: "connected", Revision: rev, RunningRevision: rev,
		ProfileID: 1, ProfileName: "node-a"}
	if e := jsonWrite(r.path("state.json"), snapshot); e != nil {
		t.Fatal(e)
	}

	// 合并后内存状态必须回到热切换目标，而不是启动快照。
	r.applyRuntimeSelection(&snapshot)
	if snapshot.ProfileID != 2 || snapshot.ProfileName != "node-b" {
		t.Fatal("runtime selection not merged", snapshot.ProfileID, snapshot.ProfileName)
	}

	// 读取路径同理：Status 不依赖 supervisor 是否已经持久化。
	if got := r.Status().ProfileID; got != 2 {
		t.Fatal("status not following runtime selection", got)
	}
}

// 上一代的热切换记录不得污染新代次的状态。
func TestRuntimeSelectionIgnoresOtherRevision(t *testing.T) {
	r, _ := fixture(t)
	fake := &fakeClash{selector: "proxy", now: "a", secret: "s3cret"}
	tags := map[string]ProfileTag{"1": {Tag: "a", Name: "node-a"}}
	rev := stageSelectNode(t, r, fake, tags, true)

	if e := r.writeRuntimeSelection(runtimeSelection{Revision: "rev-old", ProfileID: 99, ProfileName: "stale"}); e != nil {
		t.Fatal(e)
	}
	if got := r.Status().ProfileID; got != 1 {
		t.Fatal("stale selection leaked into current revision", got)
	}

	if e := r.writeRuntimeSelection(runtimeSelection{Revision: rev, ProfileID: 2, ProfileName: "node-b"}); e != nil {
		t.Fatal(e)
	}
	if got := r.Status().ProfileID; got != 2 {
		t.Fatal("same-revision selection not applied", got)
	}
}
