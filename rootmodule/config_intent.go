// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"encoding/json"
	"errors"
	"strconv"
)

// 配置应用下沉：App 不再生成完整快照强推，只下发「配置意图」。
// 模块以 store 里的节点 + 全局配置为唯一数据源，自行拼出完整 sing-box
// config（含 selector），复用资源文件后走既有 Apply 流程（validate → generation）。
//
// 资源（geoip/geosite/yacd/plugins）在首次迁移时由 App 经 intent.Files 上传一次，
// 之后从当前代次复用，App 不必重复携带。
//
// 节点之外的出站依赖同样必须保留：route/dns 里引用的 direct/bypass 出站、
// 链式 detour、顶层 endpoints 与顶层 log 都不能丢，否则拼出的配置必然校验失败。
// 这些「辅助出站」由 App 按 tag 归属划分后放进 AuxiliaryOutbounds。

// ConfigIntent 是 App 下发的配置意图。除 Files/Plugins 外全部为增量，
// 未提供的字段保持模块 store 现值。
type ConfigIntent struct {
	Router       json.RawMessage `json:"router,omitempty"`
	Inbounds     json.RawMessage `json:"inbounds,omitempty"`
	DNS          json.RawMessage `json:"dns,omitempty"`
	Experimental json.RawMessage `json:"experimental,omitempty"`
	// AuxiliaryOutbounds 是非节点出站（direct/bypass/链式 detour 等），
	// 按 App 配置里的原始顺序保存，拼装时追加在节点之后、selector 之前。
	// 显式传空数组表示清空；字段缺省（nil）时保持 store 现值。
	AuxiliaryOutbounds []json.RawMessage `json:"auxiliaryOutbounds,omitempty"`
	// Endpoints 是配置顶层 endpoints 数组中「不属于任何节点」的部分，原样透传。
	Endpoints           []json.RawMessage `json:"endpoints,omitempty"`
	Log                 json.RawMessage   `json:"log,omitempty"`
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
	// 覆盖全局配置字段（只在内存里改，等 buildConfig 通过后才落盘）
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
	if intent.Log != nil && json.Valid(intent.Log) {
		cfg.Log = intent.Log
	}
	// 辅助出站是整段语义：App 显式给出（含空数组）即整体替换，缺省才保留现值。
	if intent.AuxiliaryOutbounds != nil {
		cfg.AuxiliaryOutbounds = intent.AuxiliaryOutbounds
	}
	if intent.Endpoints != nil {
		cfg.Endpoints = intent.Endpoints
	}
	if intent.AutoStart != nil {
		cfg.AutoStart = *intent.AutoStart
	}
	if intent.PerformancePriority != nil {
		cfg.PerformancePriority = *intent.PerformancePriority
	}

	// 选定节点：intent 显式指定优先，否则沿用运行态 / 上次选择。
	// 显式指定是用户意图，绝不能静默换成别的节点——「选 A 实际走 B 且无报错」
	// 比直接失败危险得多。只有 App 没指定时（<=0）才允许回退。
	explicitProfile := intent.ProfileID > 0
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
	profile, ok := selectorCandidateByID(nodes, profileID)
	if !ok {
		if _, isEndpoint := anyNodeByID(nodes, profileID); isEndpoint {
			// 指定的是 endpoints 节点：它不进 selector 候选，selector 会引用
			// 一个不存在的 tag，同样按固定错误码拒绝。
			return State{}, errors.New("selector_target_missing")
		}
		if explicitProfile {
			// 显式指定的 id 不在模块节点里（未迁移 / 已被删除）：如实报错，
			// 不回退到其它节点，让 App 有机会补迁移或提示用户。
			return State{}, errors.New("profile_missing")
		}
		// 未显式指定（沿用运行态或上次选择）且该节点已消失：回退到第一个
		// 可作为代理的节点，避免无选择导致 config 校验失败。
		profile, ok = firstSelectorCandidate(nodes)
		if !ok {
			return State{}, errors.New("nodes_empty")
		}
		profileID = profile.ID
	}

	config, e := r.buildConfig(nodes, profileID, cfg)
	if e != nil {
		// 组装失败不得提交新代次，也不把未生效的意图写进 store。
		return State{}, e
	}
	// profile_tags.json 由模块按最终节点 tag 生成（App 的旧映射不再参与），
	// 保证热切换用的 tag 与本次真正写进配置的 tag 完全一致。
	tags, e := profileTagsJSON(nodes)
	if e != nil {
		return State{}, e
	}
	if e = r.Store().WriteConfig(cfg); e != nil {
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
	// 复制一份再注入，避免就地修改上一代次的 files map。
	merged := make(map[string][]byte, len(files)+1)
	for name, data := range files {
		merged[name] = data
	}
	merged["files/"+profileTagFile] = tags
	files = merged

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

// anyNodeByID 在全部节点里查（含 endpoints 节点），用于区分
// 「节点不存在」与「节点是端点」两种失败原因。
func anyNodeByID(nodes []StoreNode, id int64) (StoreNode, bool) {
	for _, n := range nodes {
		if n.ID == id {
			return n, true
		}
	}
	return StoreNode{}, false
}

// selectorCandidateByID 只在「非 endpoints」的节点里查：端点不进入 selector，
// 选中端点会让 selector 引用一个不存在的候选，必须按错误拒绝而不是静默改写。
func selectorCandidateByID(nodes []StoreNode, id int64) (StoreNode, bool) {
	for _, n := range nodes {
		if n.ID == id && !n.Endpoint {
			return n, true
		}
	}
	return StoreNode{}, false
}

func firstSelectorCandidate(nodes []StoreNode) (StoreNode, bool) {
	for _, n := range nodes {
		if !n.Endpoint {
			return n, true
		}
	}
	return StoreNode{}, false
}

// outboundTag 取出出站对象的 tag；不是 JSON 对象或 tag 为空时返回 false。
func outboundTag(raw json.RawMessage) (string, bool) {
	if len(raw) == 0 || !json.Valid(raw) {
		return "", false
	}
	var probe struct {
		Tag string `json:"tag"`
	}
	if json.Unmarshal(raw, &probe) != nil || probe.Tag == "" {
		return "", false
	}
	return probe.Tag, true
}

// buildConfig 用 store 节点 + 全局配置拼出完整 sing-box config。
//
// 结构固定为：节点出站（endpoints 节点除外）→ 辅助出站（保持原顺序）→ selector；
// 顶层另有 endpoints（节点端点 + 透传端点）与 log。
// 任何一处非法都返回固定错误码，调用方据此拒绝本次应用（不提交新代次）。
func (r *Runtime) buildConfig(nodes []StoreNode, selectedID int64, cfg StoreConfig) (json.RawMessage, error) {
	outbounds := make([]json.RawMessage, 0, len(nodes)+len(cfg.AuxiliaryOutbounds)+1)
	endpoints := make([]json.RawMessage, 0, len(cfg.Endpoints)+2)
	tags := make([]string, 0, len(nodes))
	// selector 自己占用 selectorTag，节点/辅助/端点都不能重复占用它。
	seen := map[string]bool{selectorTag: true}

	for _, n := range nodes {
		tag, ok := outboundTag(n.Outbound)
		if !ok {
			return nil, errors.New("node_outbound_invalid")
		}
		if seen[tag] {
			return nil, errors.New("node_tag_duplicate")
		}
		seen[tag] = true
		if n.Endpoint {
			endpoints = append(endpoints, n.Outbound)
			continue
		}
		outbounds = append(outbounds, n.Outbound)
		tags = append(tags, tag)
	}
	if len(tags) == 0 {
		return nil, errors.New("nodes_empty")
	}
	// 辅助出站在节点之后、selector 之前追加，保持 App 配置里的原始顺序，
	// 这样链式 detour 的引用关系与「先声明后引用」的书写习惯都不被打乱。
	for _, aux := range cfg.AuxiliaryOutbounds {
		tag, ok := outboundTag(aux)
		if !ok {
			return nil, errors.New("auxiliary_outbound_invalid")
		}
		if seen[tag] {
			return nil, errors.New("node_tag_duplicate")
		}
		seen[tag] = true
		outbounds = append(outbounds, aux)
	}
	for _, ep := range cfg.Endpoints {
		tag, ok := outboundTag(ep)
		if !ok {
			return nil, errors.New("endpoint_invalid")
		}
		if seen[tag] {
			return nil, errors.New("node_tag_duplicate")
		}
		seen[tag] = true
		endpoints = append(endpoints, ep)
	}

	sel, ok := selectorCandidateByID(nodes, selectedID)
	if !ok {
		return nil, errors.New("selector_target_missing")
	}
	selTag, ok := outboundTag(sel.Outbound)
	if !ok {
		return nil, errors.New("node_outbound_invalid")
	}

	// selector 的 outbounds 顺序即候选顺序
	selector := map[string]any{
		"type":      "selector",
		"tag":       selectorTag,
		"outbounds": tags,
		"default":   selTag,
	}
	selJSON, e := json.Marshal(selector)
	if e != nil {
		return nil, errors.New("node_outbound_invalid")
	}
	outbounds = append(outbounds, selJSON)

	config := map[string]any{
		"outbounds": outbounds,
	}
	if len(endpoints) > 0 {
		config["endpoints"] = endpoints
	}
	if len(cfg.Log) > 0 && json.Valid(cfg.Log) {
		config["log"] = cfg.Log
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

// profileTagsJSON 按模块最终节点 tag 生成 profileId → {tag,name} 映射，
// 供热切换（SelectNode）在运行时定位目标出站。端点节点不进 selector，故不收录。
func profileTagsJSON(nodes []StoreNode) ([]byte, error) {
	tags := make(map[string]ProfileTag, len(nodes))
	for _, n := range nodes {
		if n.Endpoint {
			continue
		}
		tag, ok := outboundTag(n.Outbound)
		if !ok {
			// 与 buildConfig 一致：非法节点必须让整次应用失败，不生成半套映射。
			return nil, errors.New("node_outbound_invalid")
		}
		name := n.Name
		if name == "" {
			name = tag
		}
		tags[strconv.FormatInt(n.ID, 10)] = ProfileTag{Tag: tag, Name: name}
	}
	return json.Marshal(tags)
}
