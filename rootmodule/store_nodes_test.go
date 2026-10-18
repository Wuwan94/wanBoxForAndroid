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
