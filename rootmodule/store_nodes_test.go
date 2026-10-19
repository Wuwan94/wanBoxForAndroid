// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"testing"
)

func newTestStore(t *testing.T) *Store {
	t.Helper()
	dir := t.TempDir()
	return New(dir, dir).Store()
}

func outbound(tag, kind string) json.RawMessage {
	b, _ := json.Marshal(map[string]any{"type": kind, "tag": tag})
	return b
}

// preview.11 及更早的迁移只按 outbounds 数组建节点，wireguard 形态的端点被当成
// 普通出站存了下来（endpoint=false）。sing-box >= 1.11 不允许 wireguard 出现在
// outbounds，重新组装配置必然校验失败、核心起不来。读取时必须一次性回填标记。
func TestStoreMigratesWireguardNodesToEndpointsOnce(t *testing.T) {
	s := newTestStore(t)
	if _, e := s.ReplaceNodes([]StoreNode{
		{ID: 1, Type: "shadowsocks", Name: "节点", Outbound: outbound("node-1", "shadowsocks")},
		{ID: 2, Type: "wireguard", Name: "端点", Outbound: outbound("endpoint-1", "wireguard")},
		{ID: 3, Type: "trojan", Name: "普通", Outbound: outbound("node-3", "trojan")},
	}); e != nil {
		t.Fatal(e)
	}
	nodes, e := s.ListNodes()
	if e != nil {
		t.Fatal(e)
	}
	if len(nodes) != 3 {
		t.Fatal("节点数量错误", len(nodes))
	}
	if !nodes[1].Endpoint {
		t.Fatal("wireguard 节点必须回填端点标记，否则会写进 outbounds 导致核心起不来")
	}
	if nodes[0].Endpoint || nodes[2].Endpoint {
		t.Fatal("非 wireguard 节点不得被误标记", nodes)
	}
	// 回填必须落盘，否则下次进程启动又要重新迁移。
	persisted, e := s.ListNodes()
	if e != nil {
		t.Fatal(e)
	}
	if !persisted[1].Endpoint {
		t.Fatal("端点标记未持久化")
	}
	// 迁移标记写入后不再重复跑：把标记人为改回 false，再读应保持 false。
	// （用户手工把端点改成普通节点是合法操作，不能被迁移逻辑反复覆盖。）
	reverted := persisted
	reverted[1].Endpoint = false
	if _, e = s.ReplaceNodes(reverted); e != nil {
		t.Fatal(e)
	}
	again, e := s.ListNodes()
	if e != nil {
		t.Fatal(e)
	}
	if again[1].Endpoint {
		t.Fatal("迁移只应跑一次，不得反复覆盖用户改动")
	}
	var meta storeMeta
	if e = readJSON(s.r.path(storeMetaFile), &meta, 4096); e != nil {
		t.Fatal(e)
	}
	if meta.SchemaVersion != storeSchemaV2 {
		t.Fatal("迁移版本未记录", meta.SchemaVersion)
	}
}

func TestStoreIsSharedPerRuntime(t *testing.T) {
	dir := t.TempDir()
	r := New(dir, dir)
	if r.Store() != r.Store() {
		t.Fatal("Store() must return the same instance so writes share one mutex")
	}
}

func TestReplaceNodesAssignsUniqueIDsAndPersists(t *testing.T) {
	s := newTestStore(t)
	got, e := s.ReplaceNodes([]StoreNode{
		{Name: "a", Outbound: outbound("a", "shadowsocks")},
		{ID: 5, Name: "b", Outbound: outbound("b", "vmess")},
		{Name: "c", Outbound: outbound("c", "trojan")},
	})
	if e != nil {
		t.Fatalf("replace: %v", e)
	}
	if len(got) != 3 {
		t.Fatalf("want 3 nodes, got %d", len(got))
	}
	seen := map[int64]bool{}
	for _, n := range got {
		if n.ID <= 0 {
			t.Fatalf("node %q got invalid id %d", n.Name, n.ID)
		}
		if seen[n.ID] {
			t.Fatalf("duplicate id %d", n.ID)
		}
		seen[n.ID] = true
		if n.Type == "" || n.UpdatedAt == 0 {
			t.Fatalf("node %q missing derived fields: %+v", n.Name, n)
		}
	}
	if !seen[5] {
		t.Fatalf("provided id 5 was not preserved: %+v", got)
	}
	persisted, e := s.ListNodes()
	if e != nil {
		t.Fatalf("list: %v", e)
	}
	if len(persisted) != 3 {
		t.Fatalf("want 3 persisted nodes, got %d", len(persisted))
	}
	// 下一次分配必须从已有最大 id 之后开始，避免覆盖已迁移节点。
	added, e := s.UpsertNode(StoreNode{Name: "d", Type: "direct", Outbound: outbound("d", "direct")})
	if e != nil {
		t.Fatalf("upsert: %v", e)
	}
	if seen[added.ID] {
		t.Fatalf("new node reused id %d", added.ID)
	}
}

func TestReplaceNodesRejectsInvalidOutbound(t *testing.T) {
	s := newTestStore(t)
	if _, e := s.ReplaceNodes([]StoreNode{{Name: "bad", Outbound: json.RawMessage("{")}}); e == nil {
		t.Fatal("accepted invalid outbound")
	}
	nodes, e := s.ListNodes()
	if e != nil {
		t.Fatalf("list: %v", e)
	}
	if len(nodes) != 0 {
		t.Fatalf("invalid batch must not be persisted, got %d", len(nodes))
	}
}

func TestReplaceNodesEmptyClearsStore(t *testing.T) {
	s := newTestStore(t)
	if _, e := s.ReplaceNodes([]StoreNode{{Name: "a", Outbound: outbound("a", "direct")}}); e != nil {
		t.Fatalf("seed: %v", e)
	}
	if _, e := s.ReplaceNodes(nil); e != nil {
		t.Fatalf("clear: %v", e)
	}
	nodes, e := s.ListNodes()
	if e != nil {
		t.Fatalf("list: %v", e)
	}
	if len(nodes) != 0 {
		t.Fatalf("want empty store, got %d", len(nodes))
	}
}
