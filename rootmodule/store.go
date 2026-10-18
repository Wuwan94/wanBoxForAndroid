// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"errors"
	"os"
	"sync"
	"time"
)

// 模块侧节点/订阅/全局配置的持久层。节点与订阅的真实数据源下沉到模块，
// App 只做展示与下发指令，不再经 Room 生成完整快照后强推。
//
// 全部为普通文件，独立于核心进程存活：核心停止时仍可正常读写，
// 因此 /v1/nodes、/v1/subscriptions、/v1/config 不受代理运行态影响。
//
// 锁约定：公开方法加 mu 锁后调用内部 *Locked 辅助函数；*Locked 一律不加锁，
// 避免写方法内部再调公开读方法导致的死锁。
const (
	storeDir               = "store"
	storeNodesFile         = "store/nodes.json"
	storeSubscriptionsFile = "store/subscriptions.json"
	storeConfigFile        = "store/config.json"
	storeMetaFile          = "store/meta.json"
)

// StoreNode 是一个可选择节点的持久化形态。Outbound 是完整的 sing-box
// outbound 对象（含 tag），由模块在生成配置时原样拼入 outbounds 数组。
type StoreNode struct {
	ID             int64           `json:"id"`
	Type           string          `json:"type"`
	Name           string          `json:"name"`
	Group          string          `json:"group,omitempty"`
	SubscriptionID int64           `json:"subscriptionId,omitempty"`
	Outbound       json.RawMessage `json:"outbound"`
	UpdatedAt      int64           `json:"updatedAt"`
}

// StoreSubscription 是订阅源。URL 仅在模块侧保存与使用，App 只看到元数据。
type StoreSubscription struct {
	ID          int64           `json:"id"`
	Name        string          `json:"name"`
	URL         string          `json:"url"`
	Filter      json.RawMessage `json:"filter,omitempty"`
	LastUpdated int64           `json:"lastUpdated,omitempty"`
	NodeCount   int             `json:"nodeCount"`
	Error       string          `json:"error,omitempty"`
}

// StoreConfig 是除节点以外的全局配置（路由 / 入站 / DNS / 实验项 / 自启 / 性能）。
type StoreConfig struct {
	SchemaVersion       int             `json:"schemaVersion"`
	Router              json.RawMessage `json:"router,omitempty"`
	Inbounds            json.RawMessage `json:"inbounds,omitempty"`
	DNS                 json.RawMessage `json:"dns,omitempty"`
	Experimental        json.RawMessage `json:"experimental,omitempty"`
	AutoStart           bool            `json:"autoStart"`
	PerformancePriority bool            `json:"performancePriority"`
}

type storeMeta struct {
	NextNodeID         int64 `json:"nextNodeId"`
	NextSubscriptionID int64 `json:"nextSubscriptionId"`
}

// Store 由 Runtime 持有，用自身互斥锁串行化 CRUD，避免与核心 Apply 争抢 control.lock。
type Store struct {
	r  *Runtime
	mu sync.Mutex
}

// Store 返回 Runtime 绑定的持久层。
func (r *Runtime) Store() *Store { return &Store{r: r} }

// ---- 内部辅助（已持锁，不加锁）----

func (s *Store) listNodesLocked() ([]StoreNode, error) {
	var nodes []StoreNode
	if e := readJSON(s.r.path(storeNodesFile), &nodes, 16<<20); e != nil {
		if errors.Is(e, os.ErrNotExist) {
			return []StoreNode{}, nil
		}
		return nil, errors.New("store_unavailable")
	}
	return nodes, nil
}

func (s *Store) listSubscriptionsLocked() ([]StoreSubscription, error) {
	var subs []StoreSubscription
	if e := readJSON(s.r.path(storeSubscriptionsFile), &subs, 8<<20); e != nil {
		if errors.Is(e, os.ErrNotExist) {
			return []StoreSubscription{}, nil
		}
		return nil, errors.New("store_unavailable")
	}
	return subs, nil
}

func (s *Store) readConfigLocked() (StoreConfig, error) {
	var c StoreConfig
	if e := readJSON(s.r.path(storeConfigFile), &c, 8<<20); e != nil {
		if errors.Is(e, os.ErrNotExist) {
			return StoreConfig{SchemaVersion: 1}, nil
		}
		return c, errors.New("store_unavailable")
	}
	if c.SchemaVersion == 0 {
		c.SchemaVersion = 1
	}
	return c, nil
}

func (s *Store) writeNodesLocked(nodes []StoreNode) error {
	if e := privateDirectory(s.r.path(storeDir)); e != nil {
		return e
	}
	if nodes == nil {
		nodes = []StoreNode{}
	}
	return jsonWrite(s.r.path(storeNodesFile), nodes)
}

func (s *Store) writeSubscriptionsLocked(subs []StoreSubscription) error {
	if e := privateDirectory(s.r.path(storeDir)); e != nil {
		return e
	}
	if subs == nil {
		subs = []StoreSubscription{}
	}
	return jsonWrite(s.r.path(storeSubscriptionsFile), subs)
}

func (s *Store) writeMetaLocked(m storeMeta) error {
	if e := privateDirectory(s.r.path(storeDir)); e != nil {
		return e
	}
	return jsonWrite(s.r.path(storeMetaFile), m)
}

func (s *Store) readMetaLocked() (storeMeta, error) {
	var m storeMeta
	if e := readJSON(s.r.path(storeMetaFile), &m, 4096); e != nil {
		if errors.Is(e, os.ErrNotExist) {
			return storeMeta{NextNodeID: 1, NextSubscriptionID: 1}, nil
		}
		return m, e
	}
	if m.NextNodeID <= 0 {
		m.NextNodeID = 1
	}
	if m.NextSubscriptionID <= 0 {
		m.NextSubscriptionID = 1
	}
	return m, nil
}

func (s *Store) nextNodeIDLocked() (int64, error) {
	m, e := s.readMetaLocked()
	if e != nil {
		return 0, e
	}
	id := m.NextNodeID
	m.NextNodeID++
	if e = s.writeMetaLocked(m); e != nil {
		return 0, e
	}
	return id, nil
}

func (s *Store) nextSubscriptionIDLocked() (int64, error) {
	m, e := s.readMetaLocked()
	if e != nil {
		return 0, e
	}
	id := m.NextSubscriptionID
	m.NextSubscriptionID++
	if e = s.writeMetaLocked(m); e != nil {
		return 0, e
	}
	return id, nil
}

// ---- 节点（公开，加锁）----

func (s *Store) ListNodes() ([]StoreNode, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.listNodesLocked()
}

// UpsertNode 新增（id<=0）或覆盖（id>0）一个节点，返回持久化后的完整节点。
func (s *Store) UpsertNode(in StoreNode) (StoreNode, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if in.Type == "" || in.Name == "" || !json.Valid(in.Outbound) {
		return StoreNode{}, errors.New("node_invalid")
	}
	nodes, e := s.listNodesLocked()
	if e != nil {
		return StoreNode{}, e
	}
	if in.ID <= 0 {
		in.ID, e = s.nextNodeIDLocked()
		if e != nil {
			return StoreNode{}, e
		}
	}
	in.UpdatedAt = time.Now().UnixMilli()
	replaced := false
	for i, n := range nodes {
		if n.ID == in.ID {
			if in.SubscriptionID == 0 {
				in.SubscriptionID = n.SubscriptionID // 覆盖时保留原归属
			}
			nodes[i] = in
			replaced = true
			break
		}
	}
	if !replaced {
		nodes = append(nodes, in)
	}
	if e = s.writeNodesLocked(nodes); e != nil {
		return StoreNode{}, e
	}
	return in, nil
}

// DeleteNode 删除节点。不校验运行态（由 API 层负责）。
func (s *Store) DeleteNode(id int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	nodes, e := s.listNodesLocked()
	if e != nil {
		return e
	}
	filtered := nodes[:0]
	for _, n := range nodes {
		if n.ID != id {
			filtered = append(filtered, n)
		}
	}
	if len(filtered) == len(nodes) {
		return errors.New("node_not_found")
	}
	return s.writeNodesLocked(filtered)
}

// ---- 订阅（公开，加锁）----

func (s *Store) ListSubscriptions() ([]StoreSubscription, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.listSubscriptionsLocked()
}

func (s *Store) UpsertSubscription(in StoreSubscription) (StoreSubscription, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if in.Name == "" || in.URL == "" {
		return StoreSubscription{}, errors.New("subscription_invalid")
	}
	subs, e := s.listSubscriptionsLocked()
	if e != nil {
		return StoreSubscription{}, e
	}
	if in.ID <= 0 {
		in.ID, e = s.nextSubscriptionIDLocked()
		if e != nil {
			return StoreSubscription{}, e
		}
	}
	replaced := false
	for i, sub := range subs {
		if sub.ID == in.ID {
			in.NodeCount = sub.NodeCount // 覆盖时不抹掉已有节点计数
			subs[i] = in
			replaced = true
			break
		}
	}
	if !replaced {
		subs = append(subs, in)
	}
	if e = s.writeSubscriptionsLocked(subs); e != nil {
		return StoreSubscription{}, e
	}
	return in, nil
}

// DeleteSubscription 删除订阅及其全部节点（原子：先写订阅再写节点）。
func (s *Store) DeleteSubscription(id int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	subs, e := s.listSubscriptionsLocked()
	if e != nil {
		return e
	}
	filtered := subs[:0]
	for _, sub := range subs {
		if sub.ID != id {
			filtered = append(filtered, sub)
		}
	}
	if len(filtered) == len(subs) {
		return errors.New("subscription_not_found")
	}
	if e = s.writeSubscriptionsLocked(filtered); e != nil {
		return e
	}
	nodes, e := s.listNodesLocked()
	if e != nil {
		return e
	}
	kept := nodes[:0]
	for _, n := range nodes {
		if n.SubscriptionID != id {
			kept = append(kept, n)
		}
	}
	return s.writeNodesLocked(kept)
}

// ---- 全局配置（公开，加锁）----

func (s *Store) ReadConfig() (StoreConfig, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.readConfigLocked()
}

func (s *Store) WriteConfig(c StoreConfig) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if c.SchemaVersion == 0 {
		c.SchemaVersion = 1
	}
	if e := privateDirectory(s.r.path(storeDir)); e != nil {
		return e
	}
	return jsonWrite(s.r.path(storeConfigFile), c)
}

// RecomputeSubscriptionCounts 重算每个订阅的节点数（订阅更新 / 删除节点后调用）。
func (s *Store) RecomputeSubscriptionCounts() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	subs, e := s.listSubscriptionsLocked()
	if e != nil {
		return e
	}
	nodes, e := s.listNodesLocked()
	if e != nil {
		return e
	}
	counts := map[int64]int{}
	for _, n := range nodes {
		if n.SubscriptionID > 0 {
			counts[n.SubscriptionID]++
		}
	}
	for i := range subs {
		subs[i].NodeCount = counts[subs[i].ID]
	}
	return s.writeSubscriptionsLocked(subs)
}
