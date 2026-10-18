// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"os"
)

// runtimeSelectionFile 保存热切换后的运行态节点选择。
//
// supervisor 在内存里持有启动时由快照得到的 ProfileID，并按 stats 节奏
// （约 5 秒）用这份内存状态重写 state.json。若热切换只改 state.json，
// 下一次 persist 就会把选择覆盖回旧节点，App 侧的节点名与勾选先变后弹回，
// 表现为「切换节点时上方按钮重启/闪烁」。因此热切换写独立文件，
// supervisor 与 Status 都从这里合并，且用 RunningRevision 隔离代次。
const runtimeSelectionFile = "selection.json"

type runtimeSelection struct {
	Revision    string `json:"revision"`
	ProfileID   int64  `json:"profileId"`
	ProfileName string `json:"profileName"`
}

// readRuntimeSelection 只在代次一致时返回选择；跨代次、缺失或损坏一律视为无记录。
func (r *Runtime) readRuntimeSelection(rev string) *runtimeSelection {
	if rev == "" {
		return nil
	}
	var sel runtimeSelection
	if e := readJSON(r.path("runtime", runtimeSelectionFile), &sel, 1<<20); e != nil {
		return nil
	}
	if sel.Revision != rev || sel.ProfileID <= 0 {
		return nil
	}
	return &sel
}

func (r *Runtime) writeRuntimeSelection(sel runtimeSelection) error {
	if e := os.MkdirAll(r.path("runtime"), 0700); e != nil {
		return e
	}
	return jsonWrite(r.path("runtime", runtimeSelectionFile), sel)
}

// clearRuntimeSelection 在新代次启动前调用，避免上一代的节点选择盖住新配置。
func (r *Runtime) clearRuntimeSelection() {
	_ = os.Remove(r.path("runtime", runtimeSelectionFile))
}

// applyRuntimeSelection 把同代次的热切换结果合并进状态快照。
func (r *Runtime) applyRuntimeSelection(s *State) {
	sel := r.readRuntimeSelection(s.RunningRevision)
	if sel == nil {
		return
	}
	s.ProfileID = sel.ProfileID
	if sel.ProfileName != "" {
		s.ProfileName = sel.ProfileName
	}
}
