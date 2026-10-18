// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"encoding/json"
	"errors"
)

// 配置应用下沉：App 不再生成完整快照强推，只下发「配置意图」。
// 模块以 store 里的节点 + 全局配置为唯一数据源，自行拼出完整 sing-box
// config（含 selector），复用资源文件后走既有 Apply 流程（validate → generation）。
//
// 资源（geoip/geosite/yacd/plugins）在首次迁移时由 App 经 intent.Files 上传一次，
// 之后从当前代次复用，App 不必重复携带。

// ConfigIntent 是 App 下发的配置意图。除 Files/Plugins 外全部为增量，
// 未提供的字段保持模块 store 现值。
type ConfigIntent struct {
	Router              json.RawMessage   `json:"router,omitempty"`
	Inbounds            json.RawMessage   `json:"inbounds,omitempty"`
	DNS                 json.RawMessage   `json:"dns,omitempty"`
	Experimental        json.RawMessage   `json:"experimental,omitempty"`
	AutoStart           *bool             `json:"autoStart,omitempty"`
	PerformancePriority *bool             `json:"performancePriority,omitempty"`
	ProfileID           int64             `json:"profileId,omitempty"`
	Files               map[string][]byte `json:"files,omitempty"`
	Plugins             []Plugin          `json:"plugins,omitempty"`
}

// selectorTag 必须与 node_select.go 的 clashProxySelector 一致，热切换才命中同一 selector。
const selectorTag = "proxy"

// ApplyConfigIntent 合并 store 数据生成完整配置并应用。
// 不传入节点详情：节点真值始终在 store/nodes.json。
// 注意：不自行 withLock——r.Apply 内部已持有 control.lock，外层再加锁会死锁。
// App 侧的并发由 ServiceCommandController 串行化；store 各方法自带互斥。
func (r *Runtime) ApplyConfigIntent(ctx context.Context, intent ConfigIntent) (State, error) {
	cfg, e := r.Store().ReadConfig()
	if e != nil {
		return State{}, e
	}
	// 覆盖全局配置字段
	if intent.Router != nil && json.Valid(intent.Router) {
		cfg.Router = intent.Router
	}
	if intent.Inbounds != nil && json.Valid(intent.Inbounds) {
		cfg.Inbounds = intent.Inbounds
	}
	if intent.DNS != nil && json.Valid(intent.DNS) {
		cfg.DNS = intent.DNS
	}
	if intent.Experimental != nil && json.Valid(intent.Experimental) {
		cfg.Experimental = intent.Experimental
	}
	if intent.AutoStart != nil {
		cfg.AutoStart = *intent.AutoStart
	}
	if intent.PerformancePriority != nil {
		cfg.PerformancePriority = *intent.PerformancePriority
	}
	if e = r.Store().WriteConfig(cfg); e != nil {
		return State{}, e
	}

	// 选定节点：intent 显式指定优先，否则沿用运行态 / 上次选择，最后取第一个节点
	profileID := intent.ProfileID
	if profileID <= 0 {
		profileID = r.Status().ProfileID
	}
	nodes, e := r.Store().ListNodes()
	if e != nil {
		return State{}, e
	}
	if len(nodes) == 0 {
		return State{}, errors.New("nodes_empty")
	}
	profile, ok := nodeByID(nodes, profileID)
	if !ok {
		// 回退到第一个节点，避免无有效选择导致 config 校验失败
		profile = nodes[0]
		profileID = profile.ID
	}

	config, e := r.buildConfig(nodes, profileID, cfg)
	if e != nil {
		return State{}, e
	}

	// 资源复用：优先 intent 显式携带（首次迁移），否则沿用当前代次
	files, plugins := intent.Files, intent.Plugins
	if len(files) == 0 {
		_, current, _ := r.Current()
		if current.Files != nil {
			files = current.Files
		}
		if current.Plugins != nil {
			plugins = current.Plugins
		}
	}

	snapshot := Snapshot{
		SchemaVersion:       Version,
		ExpectedRevision:    currentRevisionOrEmpty(r),
		Config:              config,
		Files:               files,
		Plugins:             plugins,
		PerformancePriority: cfg.PerformancePriority,
		AutoStart:           cfg.AutoStart,
		ProfileID:           profileID,
		ProfileName:         profile.Name,
	}
	return r.Apply(ctx, snapshot)
}

func currentRevisionOrEmpty(r *Runtime) string {
	rev, _, e := r.Current()
	if e != nil {
		return ""
	}
	return rev
}

func nodeByID(nodes []StoreNode, id int64) (StoreNode, bool) {
	for _, n := range nodes {
		if n.ID == id {
			return n, true
		}
	}
	return StoreNode{}, false
}

// buildConfig 用 store 节点 + 全局配置拼出完整 sing-box config。
func (r *Runtime) buildConfig(nodes []StoreNode, selectedID int64, cfg StoreConfig) (json.RawMessage, error) {
	outbounds := make([]json.RawMessage, 0, len(nodes)+1)
	tags := make([]string, 0, len(nodes))
	for _, n := range nodes {
		if !json.Valid(n.Outbound) {
			continue
		}
		var probe struct {
			Tag string `json:"tag"`
		}
		if json.Unmarshal(n.Outbound, &probe) != nil || probe.Tag == "" {
			continue
		}
		outbounds = append(outbounds, n.Outbound)
		tags = append(tags, probe.Tag)
	}
	if len(outbounds) == 0 {
		return nil, errors.New("nodes_empty")
	}

	// 默认选中的节点 tag 放首位，selector 的 outbounds 顺序即候选顺序
	sel, ok := nodeByID(nodes, selectedID)
	if !ok {
		sel = nodes[0]
	}
	var selProbe struct {
		Tag string `json:"tag"`
	}
	_ = json.Unmarshal(sel.Outbound, &selProbe)

	selector := map[string]any{
		"type":      "selector",
		"tag":       selectorTag,
		"outbounds": tags,
	}
	if selProbe.Tag != "" {
		selector["default"] = selProbe.Tag
	}
	selJSON, _ := json.Marshal(selector)
	outbounds = append(outbounds, selJSON)

	config := map[string]any{
		"outbounds": outbounds,
	}
	if len(cfg.Inbounds) > 0 {
		config["inbounds"] = cfg.Inbounds
	}
	if len(cfg.Router) > 0 {
		config["route"] = cfg.Router
	}
	if len(cfg.DNS) > 0 {
		config["dns"] = cfg.DNS
	}
	if len(cfg.Experimental) > 0 {
		config["experimental"] = cfg.Experimental
	}
	return json.Marshal(config)
}
