// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"encoding/json"
	"os"
	"testing"
)

// 配置意图测试：节点之外的依赖（辅助出站 / 顶层 endpoints / 顶层 log）
// 必须完整保真，且非法依赖必须被固定错误码拒绝、不得提交新代次。

func intentFixture(t *testing.T) *Runtime {
	t.Helper()
	r, s := fixture(t)
	if _, e := r.Apply(context.Background(), s); e != nil {
		t.Fatal(e)
	}
	nodes := []StoreNode{
		{
			ID:       1,
			Type:     "shadowsocks",
			Name:     "节点一",
			Outbound: json.RawMessage(`{"type":"shadowsocks","tag":"node-1","server":"1.1.1.1","server_port":443}`),
		},
		{
			ID:       2,
			Type:     "wireguard",
			Name:     "端点",
			Endpoint: true,
			Outbound: json.RawMessage(`{"type":"wireguard","tag":"endpoint-1","peers":[]}`),
		},
	}
	if _, e := r.Store().ReplaceNodes(nodes); e != nil {
		t.Fatal(e)
	}
	return r
}

func readGenerationJSON(t *testing.T, r *Runtime, rev string, parts ...string) map[string]any {
	t.Helper()
	path := r.path(append([]string{"generations", rev}, parts...)...)
	var out map[string]any
	if e := readJSON(path, &out, MaxConfigBytes); e != nil {
		t.Fatal("读取生成文件失败", path, e)
	}
	return out
}

func outboundTags(t *testing.T, value any) []string {
	t.Helper()
	list, ok := value.([]any)
	if !ok {
		t.Fatalf("不是数组: %v", value)
	}
	tags := make([]string, 0, len(list))
	for _, item := range list {
		obj, ok := item.(map[string]any)
		if !ok {
			t.Fatalf("不是对象: %v", item)
		}
		tag, _ := obj["tag"].(string)
		tags = append(tags, tag)
	}
	return tags
}

func equalTags(got, want []string) bool {
	if len(got) != len(want) {
		return false
	}
	for i := range got {
		if got[i] != want[i] {
			return false
		}
	}
	return true
}

// 辅助出站 / 顶层 endpoints / 顶层 log 必须原样出现在最终配置里，
// endpoint 节点只进顶层 endpoints，不进入 selector 候选。
func TestConfigIntentMergesAuxiliaryOutboundsEndpointsAndLog(t *testing.T) {
	r := intentFixture(t)
	intent := ConfigIntent{
		Router: json.RawMessage(`{"rules":[{"outbound":"bypass"}]}`),
		AuxiliaryOutbounds: []json.RawMessage{
			json.RawMessage(`{"type":"direct","tag":"direct"}`),
			json.RawMessage(`{"type":"direct","tag":"bypass","detour":"direct"}`),
		},
		Endpoints: []json.RawMessage{json.RawMessage(`{"type":"wireguard","tag":"endpoint-extra","peers":[]}`)},
		Log:       json.RawMessage(`{"level":"warn","timestamp":true}`),
		ProfileID: 1,
	}
	state, e := r.ApplyConfigIntent(context.Background(), intent)
	if e != nil {
		t.Fatal(e)
	}
	cfg := readGenerationJSON(t, r, state.Revision, "run", "config.json")
	// 顺序固定：节点 → 辅助出站 → selector
	if tags := outboundTags(t, cfg["outbounds"]); !equalTags(tags, []string{"node-1", "direct", "bypass", selectorTag}) {
		t.Fatal("出站顺序错误", tags)
	}
	if tags := outboundTags(t, cfg["endpoints"]); !equalTags(tags, []string{"endpoint-1", "endpoint-extra"}) {
		t.Fatal("顶层 endpoints 缺失", tags)
	}
	selector, ok := cfg["outbounds"].([]any)[3].(map[string]any)
	if !ok || selector["type"] != "selector" || selector["default"] != "node-1" {
		t.Fatal("selector 定义错误", selector)
	}
	if tags, ok := selector["outbounds"].([]any); !ok || len(tags) != 1 || tags[0] != "node-1" {
		t.Fatal("endpoint 节点不得进入 selector 候选", selector["outbounds"])
	}
	log, ok := cfg["log"].(map[string]any)
	if !ok || log["level"] != "warn" {
		t.Fatal("顶层 log 丢失", cfg["log"])
	}
	route, ok := cfg["route"].(map[string]any)
	if !ok || route["rules"] == nil {
		t.Fatal("route 丢失", cfg["route"])
	}
	// profile_tags.json 按模块最终节点 tag 生成，端点不收录。
	var tags map[string]ProfileTag
	if e = readJSON(r.path("generations", state.Revision, "files", profileTagFile), &tags, 1<<20); e != nil {
		t.Fatal("profile_tags.json 缺失", e)
	}
	if len(tags) != 1 || tags["1"].Tag != "node-1" || tags["1"].Name != "节点一" {
		t.Fatal("profile_tags 与最终节点不一致", tags)
	}
	// store 必须持久化这些依赖，否则下次 reload 会丢。
	stored, e := r.Store().ReadConfig()
	if e != nil {
		t.Fatal(e)
	}
	if len(stored.AuxiliaryOutbounds) != 2 || len(stored.Endpoints) != 1 || len(stored.Log) == 0 {
		t.Fatal("store 未持久化辅助依赖", stored)
	}
}

// 非法依赖必须按固定错误码拒绝：重复 tag、非法 JSON、selector 指向端点。
// 失败时不得提交新代次，也不得把未生效的意图写进 store。
func TestConfigIntentRejectsInvalidDependencyAndKeepsGeneration(t *testing.T) {
	r := intentFixture(t)
	ok := ConfigIntent{
		AuxiliaryOutbounds: []json.RawMessage{json.RawMessage(`{"type":"direct","tag":"direct"}`)},
		ProfileID:          1,
	}
	state, e := r.ApplyConfigIntent(context.Background(), ok)
	if e != nil {
		t.Fatal(e)
	}
	// 失败前的 store 原样快照。断言必须与旧值逐字节比对：只比条数的话，
	// 「同样 1 条但内容被改写」的失败意图也会通过，断言等于没有。
	before, e := os.ReadFile(r.path(storeConfigFile))
	if e != nil {
		t.Fatal(e)
	}
	cases := []struct {
		name   string
		intent ConfigIntent
		code   string
	}{
		{
			// 带上 router/log：全局字段同样不得在失败时被写进 store。
			name: "重复 tag",
			intent: ConfigIntent{
				Router:             json.RawMessage(`{"rules":[{"outbound":"hijack"}]}`),
				Log:                json.RawMessage(`{"level":"trace"}`),
				AuxiliaryOutbounds: []json.RawMessage{json.RawMessage(`{"type":"direct","tag":"node-1"}`)},
				ProfileID:          1,
			},
			code: "node_tag_duplicate",
		},
		{
			name: "非法 JSON",
			intent: ConfigIntent{
				AuxiliaryOutbounds: []json.RawMessage{json.RawMessage(`{"type":`)},
				ProfileID:          1,
			},
			code: "auxiliary_outbound_invalid",
		},
		{
			name: "selector 指向端点",
			intent: ConfigIntent{
				AuxiliaryOutbounds: []json.RawMessage{json.RawMessage(`{"type":"direct","tag":"direct"}`)},
				ProfileID:          2,
			},
			code: "selector_target_missing",
		},
	}
	for _, c := range cases {
		if _, e = r.ApplyConfigIntent(context.Background(), c.intent); e == nil || e.Error() != c.code {
			t.Fatalf("%s: 期望 %s，实际 %v", c.name, c.code, e)
		}
		if rev, _, e := r.Current(); e != nil || rev != state.Revision {
			t.Fatalf("%s: 失败不得提交新代次", c.name)
		}
	}
	after, e := os.ReadFile(r.path(storeConfigFile))
	if e != nil {
		t.Fatal(e)
	}
	if string(after) != string(before) {
		t.Fatalf("失败的应用不得改写 store\n旧: %s\n新: %s", before, after)
	}
	stored, e := r.Store().ReadConfig()
	if e != nil {
		t.Fatal(e)
	}
	if len(stored.AuxiliaryOutbounds) != 1 || string(stored.AuxiliaryOutbounds[0]) != `{"type":"direct","tag":"direct"}` {
		t.Fatal("store 内容被改写", stored.AuxiliaryOutbounds)
	}
	if len(stored.Router) != 0 || len(stored.Log) != 0 {
		t.Fatal("失败意图的全局字段不得落盘", stored)
	}
	var tags map[string]ProfileTag
	if e = readJSON(r.path("generations", state.Revision, "files", profileTagFile), &tags, 1<<20); e != nil {
		t.Fatal(e)
	}
	if _, exists := tags["2"]; exists {
		t.Fatal("端点不应出现在 profile_tags", tags)
	}
}

// 显式指定的 profile 不在模块节点集合里时必须硬失败。
// 「选 A 实际走 B 且无任何提示」比直接报错危险得多：用户以为连的是自己选的
// 节点，实际流量走了别的出口，且界面上完全看不出来。
func TestConfigIntentRejectsUnknownProfileWithoutFallback(t *testing.T) {
	r := intentFixture(t)
	// 先成功应用一次，让 store/config.json 存在，才有可比对的成功态快照。
	state, e := r.ApplyConfigIntent(context.Background(), ConfigIntent{ProfileID: 1})
	if e != nil {
		t.Fatal(e)
	}
	before, e := os.ReadFile(r.path(storeConfigFile))
	if e != nil {
		t.Fatal(e)
	}
	// 999 既不是普通节点也不是端点：如实报 profile_missing，不回退。
	_, e = r.ApplyConfigIntent(context.Background(), ConfigIntent{ProfileID: 999})
	if e == nil || e.Error() != "profile_missing" {
		t.Fatal("未迁移/已删除的 profile 必须报 profile_missing，不得静默换节点", e)
	}
	if got, _, e := r.Current(); e != nil || got != state.Revision {
		t.Fatal("失败不得提交新代次")
	}
	if after, e := os.ReadFile(r.path(storeConfigFile)); e != nil || string(after) != string(before) {
		t.Fatal("失败不得改写 store")
	}
	// App 未显式指定（<=0）时才允许回退到第一个可选节点，避免无选择导致校验失败。
	// 运行态 profile 是 fixture 的 7，不在节点表里，正好覆盖这条回退路径。
	fallback, e := r.ApplyConfigIntent(context.Background(), ConfigIntent{ProfileID: 0})
	if e != nil {
		t.Fatal("未显式指定时应回退", e)
	}
	// 断言落在真正落盘的快照上：返回的 State 取自 state.json，核心未真启动时
	// 那里的 profileId 仍是 0，用它断言会假通过。
	var applied Snapshot
	if e = readJSON(r.path("generations", fallback.Revision, "snapshot.json"), &applied, MaxSnapshotBytes); e != nil {
		t.Fatal(e)
	}
	if applied.ProfileID != 1 {
		t.Fatal("应回退到第一个可选节点", applied.ProfileID)
	}
	cfg := readGenerationJSON(t, r, fallback.Revision, "run", "config.json")
	list, _ := cfg["outbounds"].([]any)
	selector, _ := list[len(list)-1].(map[string]any)
	if selector["default"] != "node-1" {
		t.Fatal("回退后 selector 默认必须指向第一个可选节点", selector["default"])
	}
}

// 旧版 store/config.json 没有 auxiliaryOutbounds/endpoints/log 字段，
// 读取必须保持兼容（空值），不得报错。
func TestStoreConfigReadsLegacyFileWithoutOptionalFields(t *testing.T) {
	r, _ := fixture(t)
	if e := privateDirectory(r.path(storeDir)); e != nil {
		t.Fatal(e)
	}
	legacy := []byte(`{"schemaVersion":1,"router":{"rules":[]},"autoStart":true,"performancePriority":false}`)
	if e := os.WriteFile(r.path(storeConfigFile), legacy, 0600); e != nil {
		t.Fatal(e)
	}
	cfg, e := r.Store().ReadConfig()
	if e != nil {
		t.Fatal(e)
	}
	if len(cfg.AuxiliaryOutbounds) != 0 || len(cfg.Endpoints) != 0 || len(cfg.Log) != 0 {
		t.Fatal("旧文件的缺省字段必须为空", cfg)
	}
	if !cfg.AutoStart || len(cfg.Router) == 0 {
		t.Fatal("旧字段读取错误", cfg)
	}
	// 写回后仍能无损读回（forward/backward 兼容）
	cfg.AuxiliaryOutbounds = []json.RawMessage{json.RawMessage(`{"type":"direct","tag":"direct"}`)}
	if e = r.Store().WriteConfig(cfg); e != nil {
		t.Fatal(e)
	}
	again, e := r.Store().ReadConfig()
	if e != nil || len(again.AuxiliaryOutbounds) != 1 {
		t.Fatal("辅助出站未持久化", e)
	}
}

// 节点与端点混排时，全部非法节点错误都必须拒绝，不能静默跳过。
func TestConfigIntentRejectsBrokenNodeOutbound(t *testing.T) {
	r := intentFixture(t)
	nodes, e := r.Store().ListNodes()
	if e != nil {
		t.Fatal(e)
	}
	nodes[0].Outbound = json.RawMessage(`not-json`)
	if _, e = r.Store().ReplaceNodes(nodes); e == nil {
		t.Fatal("store 应拒绝非法出站")
	}
	// 直接构造内存态，确认 buildConfig 自身也会拒绝
	if _, e = r.buildConfig(nodes, 1, StoreConfig{}); e == nil || e.Error() != "node_outbound_invalid" {
		t.Fatal("buildConfig 未拒绝非法出站", e)
	}
	if _, e = r.buildConfig(nil, 1, StoreConfig{}); e == nil || e.Error() != "nodes_empty" {
		t.Fatal("空节点未拒绝", e)
	}
}
