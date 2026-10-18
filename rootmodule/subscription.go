// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"
)

// 订阅更新下沉到模块：App 只下发「更新该订阅」指令，拉取 / 解析 / diff / 写回
// 全部在模块内完成。核心停止时同样可执行（纯文件 + 出站 HTTP，不依赖代理进程）。
//
// 支持的订阅形态（按优先级探测）：
//  1. sing-box 原生：JSON 数组，元素为完整 outbound 对象（含 tag）
//  2. 传统 base64 URI 列表（ss://、vmess://、trojan://、vless://、hysteria2:// 等）
//  3. Clash YAML（proxies 段）——由 parseClashProxies 处理，可扩展

const (
	subscriptionHTTPTimeout = 30 * time.Second
	subscriptionMaxBytes    = 16 << 20 // 订阅响应上限 16MiB
	subscriptionMaxNodes    = 2048
)

// UpdateSubscription 拉取并解析订阅，diff 后写回节点库。返回新增 / 更新 / 删除计数。
func (r *Runtime) UpdateSubscription(ctx context.Context, id int64) (added, updated, removed int, err error) {
	subs, e := r.Store().ListSubscriptions()
	if e != nil {
		return 0, 0, 0, e
	}
	var target *StoreSubscription
	for i := range subs {
		if subs[i].ID == id {
			target = &subs[i]
			break
		}
	}
	if target == nil {
		return 0, 0, 0, errors.New("subscription_not_found")
	}

	raw, e := fetchSubscription(ctx, target.URL)
	if e != nil {
		r.recordSubscriptionError(target, e)
		return 0, 0, 0, e
	}
	parsed, e := parseSubscription(raw)
	if e != nil {
		r.recordSubscriptionError(target, e)
		return 0, 0, 0, e
	}
	if len(parsed) > subscriptionMaxNodes {
		r.recordSubscriptionError(target, errors.New("subscription_too_many_nodes"))
		return 0, 0, 0, errors.New("subscription_too_many_nodes")
	}

	added, updated, removed = r.applySubscriptionDiff(target.ID, parsed)

	target.LastUpdated = time.Now().UnixMilli()
	target.NodeCount = len(parsed)
	target.Error = ""
	if _, e = r.Store().UpsertSubscription(*target); e != nil {
		return 0, 0, 0, e
	}
	r.recordEvent("subscription_updated")
	return added, updated, removed, nil
}

func (r *Runtime) recordSubscriptionError(sub *StoreSubscription, err error) {
	sub.Error = err.Error()
	// 错误码必须匹配 [a-z_]{1,80}，否则回落通用码
	if !apiCodePattern.MatchString(sub.Error) {
		sub.Error = "subscription_update_failed"
	}
	_, _ = r.Store().UpsertSubscription(*sub)
}

// fetchSubscription 出站拉取订阅文本。禁用环境代理，限流读取。
func fetchSubscription(ctx context.Context, url string) ([]byte, error) {
	if !strings.HasPrefix(url, "https://") && !strings.HasPrefix(url, "http://") {
		return nil, errors.New("subscription_url_invalid")
	}
	ctx, cancel := context.WithTimeout(ctx, subscriptionHTTPTimeout)
	defer cancel()
	req, e := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if e != nil {
		return nil, errors.New("subscription_url_invalid")
	}
	req.Header.Set("User-Agent", "wanbox-subscription/1")
	client := &http.Client{
		Transport: &http.Transport{Proxy: nil},
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 5 {
				return errors.New("too_many_redirects")
			}
			return nil
		},
	}
	resp, e := client.Do(req)
	if e != nil {
		return nil, errors.New("subscription_fetch_failed")
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, errors.New("subscription_http_error")
	}
	data, e := io.ReadAll(io.LimitReader(resp.Body, subscriptionMaxBytes+1))
	if e != nil {
		return nil, errors.New("subscription_fetch_failed")
	}
	if len(data) > subscriptionMaxBytes {
		return nil, errors.New("subscription_too_large")
	}
	return data, nil
}

// parseSubscription 探测并解析订阅，产出可直接落库的节点列表。
func parseSubscription(raw []byte) ([]StoreNode, error) {
	text := strings.TrimSpace(string(raw))
	if text == "" {
		return nil, errors.New("subscription_empty")
	}
	// 1) sing-box JSON 数组
	if strings.HasPrefix(text, "[") {
		nodes, ok := parseSingBoxArray(text)
		if ok {
			return nodes, nil
		}
		return nil, errors.New("subscription_parse_failed")
	}
	// 2) base64 URI 列表
	if nodes, ok := parseBase64URIList(text); ok {
		return nodes, nil
	}
	// 3) Clash YAML
	if nodes, ok := parseClashProxies(text); ok {
		return nodes, nil
	}
	return nil, errors.New("subscription_parse_failed")
}

// parseSingBoxArray 解析 sing-box 原生订阅：outbound 对象数组。
// 每个元素必须含 "tag"；"type" 可取任意 sing-box outbound 类型。
func parseSingBoxArray(text string) ([]StoreNode, bool) {
	var raws []json.RawMessage
	if e := json.Unmarshal([]byte(text), &raws); e != nil {
		return nil, false
	}
	nodes := make([]StoreNode, 0, len(raws))
	now := time.Now().UnixMilli()
	for i, raw := range raws {
		var probe struct {
			Type string `json:"type"`
			Tag  string `json:"tag"`
		}
		if e := json.Unmarshal(raw, &probe); e != nil || probe.Type == "" || probe.Tag == "" {
			return nil, false
		}
		nodes = append(nodes, StoreNode{
			Type:      probe.Type,
			Name:      probe.Tag,
			Outbound:  raw,
			UpdatedAt: now + int64(i),
		})
	}
	return nodes, true
}

// parseBase64URIList 解析传统订阅：单行 URI 列表，可选整体 base64 编码。
// 每种 URI scheme 转成对应的 sing-box outbound；未知 scheme 跳过。
func parseBase64URIList(text string) ([]StoreNode, bool) {
	if !strings.Contains(text, "://") {
		// 可能是整体 base64
		if decoded, e := base64.StdEncoding.DecodeString(stripBase64Whitespace(text)); e == nil {
			if strings.Contains(string(decoded), "://") {
				text = string(decoded)
			} else {
				return nil, false
			}
		} else {
			return nil, false
		}
	}
	var nodes []StoreNode
	found := false
	for _, line := range strings.Split(text, "\n") {
		uri := strings.TrimSpace(line)
		if uri == "" || strings.HasPrefix(uri, "#") || !strings.Contains(uri, "://") {
			continue
		}
		if node, ok := uriToOutbound(uri); ok {
			nodes = append(nodes, node)
			found = true
		}
	}
	return nodes, found
}

func stripBase64Whitespace(s string) string {
	return strings.Map(func(r rune) rune {
		switch r {
		case ' ', '\n', '\r', '\t':
			return -1
		}
		return r
	}, s)
}

// parseClashProxies 解析 Clash YAML 的 proxies 段。此处为占位骨架：
// 完整 YAML 解析（含 proxy-groups / rules）交由后续阶段补齐，返回 false
// 表示当前不识别该形态，避免误入库。
func parseClashProxies(text string) ([]StoreNode, bool) {
	// 粗判：Clash 订阅通常以 "proxies:" 开头（YAML）
	if !strings.Contains(text, "proxies:") {
		return nil, false
	}
	// TODO(雾晚): 引入 gopkg.in/yaml.v3 解析 proxies 段并映射为 outbound。
	return nil, false
}

// applySubscriptionDiff 把解析结果与库内同订阅节点做 diff：
// 以 tag 为键，保留用户重命名（Name 与 Tag 分离）；节点被删则移除。
func (r *Runtime) applySubscriptionDiff(subID int64, incoming []StoreNode) (added, updated, removed int) {
	existing, e := r.Store().ListNodes()
	if e != nil {
		return 0, 0, 0
	}
	byTag := map[string]StoreNode{}
	order := make([]string, 0, len(existing))
	for _, n := range existing {
		if n.SubscriptionID == subID {
			var probe struct {
				Tag string `json:"tag"`
			}
			if json.Unmarshal(n.Outbound, &probe) == nil && probe.Tag != "" {
				byTag[probe.Tag] = n
				order = append(order, probe.Tag)
			}
		}
	}
	incomingTag := map[string]bool{}
	for _, node := range incoming {
		var probe struct {
			Tag string `json:"tag"`
		}
		if json.Unmarshal(node.Outbound, &probe) != nil || probe.Tag == "" {
			continue
		}
		incomingTag[probe.Tag] = true
		node.SubscriptionID = subID
		if node.Name == "" {
			node.Name = probe.Tag
		}
		if old, ok := byTag[probe.Tag]; ok {
			// 保留用户重命名
			node.Name = old.Name
			node.ID = old.ID
			node.Group = old.Group
			node.SubscriptionID = old.SubscriptionID
			if !jsonEqual(old.Outbound, node.Outbound) {
				updated++
			}
		} else {
			added++
		}
		if _, e := r.Store().UpsertNode(node); e != nil {
			continue
		}
	}
	for _, tag := range order {
		if !incomingTag[tag] {
			if old, ok := byTag[tag]; ok {
				_ = r.Store().DeleteNode(old.ID)
				removed++
			}
		}
	}
	return added, updated, removed
}

func jsonEqual(a, b json.RawMessage) bool {
	var av, bv any
	if json.Unmarshal(a, &av) != nil || json.Unmarshal(b, &bv) != nil {
		return false
	}
	aj, _ := json.Marshal(av)
	bj, _ := json.Marshal(bv)
	return string(aj) == string(bj)
}

// uriToOutbound 把单条代理 URI 转为 sing-box outbound。此处实现 ss:// 与
// trojan:// 两个最常用 scheme 作为示例；vmess/vless/hysteria2 等按同样
// 模式补充（字段映射见 sing-box outbound schema）。
func uriToOutbound(uri string) (StoreNode, bool) {
	now := time.Now().UnixMilli()
	switch {
	case strings.HasPrefix(uri, "ss://"):
		return nodeFromSS(uri, now)
	case strings.HasPrefix(uri, "trojan://"):
		return nodeFromTrojan(uri, now)
	default:
		return StoreNode{}, false
	}
}

func nodeFromSS(uri string, now int64) (StoreNode, bool) {
	// ss://base64(method:password)@host:port#name 或 ss://method:password@host:port#name
	rest := strings.TrimPrefix(uri, "ss://")
	name := ""
	if i := strings.Index(rest, "#"); i >= 0 {
		name = rest[i+1:]
		rest = rest[:i]
	}
	var method, password, hostport string
	if i := strings.Index(rest, "@"); i >= 0 {
		userinfo := rest[:i]
		hostport = rest[i+1:]
		if decoded, e := base64.RawURLEncoding.DecodeString(userinfo); e == nil {
			userinfo = string(decoded)
		}
		parts := strings.SplitN(userinfo, ":", 2)
		if len(parts) != 2 {
			return StoreNode{}, false
		}
		method, password = parts[0], parts[1]
	} else {
		return StoreNode{}, false
	}
	host, port, ok := splitHostPort(hostport)
	if !ok {
		return StoreNode{}, false
	}
	tag := name
	if tag == "" {
		tag = host
	}
	out := map[string]any{
		"type":     "shadowsocks",
		"tag":      tag,
		"server":   host,
		"server_port": port,
		"method":   method,
		"password": password,
	}
	b, _ := json.Marshal(out)
	return StoreNode{Type: "shadowsocks", Name: tag, Outbound: b, UpdatedAt: now}, true
}

func nodeFromTrojan(uri string, now int64) (StoreNode, bool) {
	rest := strings.TrimPrefix(uri, "trojan://")
	name := ""
	if i := strings.Index(rest, "#"); i >= 0 {
		name = rest[i+1:]
		rest = rest[:i]
	}
	var password, hostport string
	if i := strings.Index(rest, "@"); i >= 0 {
		password = rest[:i]
		hostport = rest[i+1:]
	} else {
		return StoreNode{}, false
	}
	host, port, ok := splitHostPort(hostport)
	if !ok {
		return StoreNode{}, false
	}
	tag := name
	if tag == "" {
		tag = host
	}
	out := map[string]any{
		"type":        "trojan",
		"tag":         tag,
		"server":      host,
		"server_port": port,
		"password":    password,
	}
	b, _ := json.Marshal(out)
	return StoreNode{Type: "trojan", Name: tag, Outbound: b, UpdatedAt: now}, true
}

func splitHostPort(hostport string) (string, string, bool) {
	hostport = strings.TrimSuffix(hostport, "/")
	i := strings.LastIndex(hostport, ":")
	if i < 0 {
		return "", "", false
	}
	host := strings.Trim(hostport[:i], "[]")
	port := hostport[i+1:]
	if host == "" || port == "" {
		return "", "", false
	}
	return host, port, true
}
