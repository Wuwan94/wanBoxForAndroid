// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"encoding/json"
	"net/http"
	"strconv"
)

// 新增管理端点：节点 / 订阅 / 配置 / 数据迁移。
// 全部沿用 api_server.go 的鉴权与回环约束（apiAuth + isLoopbackHost）。
// 数据源为 store/ 下的文件，核心停止时照常可用。

// registerExtraRoutes 由 apiHandler 在 mux 构建完成后调用一次。
// 单独成文件是为了把预览版已有端点与新增端点隔离，降低回归面。
func (r *Runtime) registerExtraRoutes(mux *http.ServeMux, cfg APIConfig) {
	// ---- 节点 ----
	mux.HandleFunc("GET /v1/nodes", r.apiAuth(cfg, r.apiListNodes))
	mux.HandleFunc("PUT /v1/nodes", r.apiAuth(cfg, r.apiReplaceNodes))
	mux.HandleFunc("POST /v1/nodes", r.apiAuth(cfg, r.apiAddNode))
	mux.HandleFunc("PUT /v1/nodes/{id}", r.apiAuth(cfg, r.apiPutNode))
	mux.HandleFunc("DELETE /v1/nodes/{id}", r.apiAuth(cfg, r.apiDeleteNode))

	// ---- 订阅 ----
	mux.HandleFunc("GET /v1/subscriptions", r.apiAuth(cfg, r.apiListSubscriptions))
	mux.HandleFunc("POST /v1/subscriptions", r.apiAuth(cfg, r.apiAddSubscription))
	mux.HandleFunc("POST /v1/subscriptions/{id}/update", r.apiAuth(cfg, r.apiUpdateSubscription))
	mux.HandleFunc("DELETE /v1/subscriptions/{id}", r.apiAuth(cfg, r.apiDeleteSubscription))

	// ---- 配置 ----
	mux.HandleFunc("GET /v1/config", r.apiAuth(cfg, r.apiGetConfig))
	mux.HandleFunc("PUT /v1/config", r.apiAuth(cfg, r.apiPutConfig))
	// 只读日志：核心停止时仍返回事件环，App 无需再读 logcat。
	mux.HandleFunc("GET /v1/logs", r.apiAuth(cfg, r.apiLogs))

	// 重应用当前配置（配置变更后的完整 reload，等价 config apply）
	mux.HandleFunc("POST /v1/service/reload", r.apiAuth(cfg, r.apiReload))
	// 自启开关：轻量，只写 boot 标记，不重启核心（原 CLI autostart on/off）
	mux.HandleFunc("POST /v1/service/autostart", r.apiAuth(cfg, r.apiAutostart))

	// ---- 数据迁移（原 CLI data prepare/finish/rollback 的 HTTP 形态）----
	mux.HandleFunc("POST /v1/data/prepare", r.apiAuth(cfg, r.apiDataPrepare))
	mux.HandleFunc("POST /v1/data/finish", r.apiAuth(cfg, r.apiDataFinish))
	mux.HandleFunc("POST /v1/data/rollback", r.apiAuth(cfg, r.apiDataRollback))
	// 安装器数据选择确认（原 CLI data selection-finish <id>）
	mux.HandleFunc("POST /v1/data/selection-finish", r.apiAuth(cfg, r.apiSelectionFinish))
}

// ---- 节点 handler ----

func (r *Runtime) apiListNodes(w http.ResponseWriter, req *http.Request) {
	nodes, e := r.Store().ListNodes()
	if e != nil {
		writeAPIError(w, http.StatusInternalServerError, e.Error())
		return
	}
	writeAPIJSON(w, http.StatusOK, map[string]any{"schema": 1, "ok": true, "nodes": nodes})
}

// apiReplaceNodes 整表替换节点：用于 App 首次从本地库迁移，或订阅全量重建。
// 只在 store 为空或调用方明确要求整体覆盖时使用，常规增删仍走单条端点。
func (r *Runtime) apiReplaceNodes(w http.ResponseWriter, req *http.Request) {
	var body struct {
		Nodes []StoreNode `json:"nodes"`
	}
	if e := json.NewDecoder(http.MaxBytesReader(w, req.Body, 16<<20)).Decode(&body); e != nil {
		writeAPIError(w, http.StatusBadRequest, "request_invalid")
		return
	}
	nodes, e := r.Store().ReplaceNodes(body.Nodes)
	if e != nil {
		writeAPIError(w, http.StatusConflict, e.Error())
		return
	}
	r.recordEvent("nodes_replaced")
	writeAPIJSON(w, http.StatusOK, map[string]any{"schema": 1, "ok": true, "nodes": nodes, "state": r.Status()})
}

// apiLogs 返回模块事件环，核心停止时同样可读，替代 App 侧读 logcat。
func (r *Runtime) apiLogs(w http.ResponseWriter, req *http.Request) {
	limit := 200
	if raw := req.URL.Query().Get("lines"); raw != "" {
		if v, e := strconv.Atoi(raw); e == nil && v > 0 {
			limit = v
		}
	}
	if limit > 1000 {
		limit = 1000
	}
	events := r.Events()
	if len(events) > limit {
		events = events[len(events)-limit:]
	}
	writeAPIJSON(w, http.StatusOK, map[string]any{"schema": 1, "ok": true, "logs": events})
}

func (r *Runtime) apiAddNode(w http.ResponseWriter, req *http.Request) {
	var in StoreNode
	if e := json.NewDecoder(http.MaxBytesReader(w, req.Body, 4<<20)).Decode(&in); e != nil {
		writeAPIError(w, http.StatusBadRequest, "request_invalid")
		return
	}
	in.ID = 0 // 强制模块分配
	node, e := r.Store().UpsertNode(in)
	if e != nil {
		writeAPIError(w, http.StatusConflict, e.Error())
		return
	}
	r.recordEvent("node_added")
	writeAPIJSON(w, http.StatusOK, map[string]any{"schema": 1, "ok": true, "node": node})
}

func (r *Runtime) apiPutNode(w http.ResponseWriter, req *http.Request) {
	id, ok := parsePathID(req)
	if !ok {
		writeAPIError(w, http.StatusBadRequest, "arguments_invalid")
		return
	}
	var in StoreNode
	if e := json.NewDecoder(http.MaxBytesReader(w, req.Body, 4<<20)).Decode(&in); e != nil {
		writeAPIError(w, http.StatusBadRequest, "request_invalid")
		return
	}
	in.ID = id
	node, e := r.Store().UpsertNode(in)
	if e != nil {
		writeAPIError(w, http.StatusConflict, e.Error())
		return
	}
	r.recordEvent("node_updated")
	writeAPIJSON(w, http.StatusOK, map[string]any{"schema": 1, "ok": true, "node": node})
}

func (r *Runtime) apiDeleteNode(w http.ResponseWriter, req *http.Request) {
	id, ok := parsePathID(req)
	if !ok {
		writeAPIError(w, http.StatusBadRequest, "arguments_invalid")
		return
	}
	// 删除运行中节点会留下悬空选择：要求先切走
	if st := r.Status(); st.ProfileID == id {
		writeAPIError(w, http.StatusConflict, "node_in_use")
		return
	}
	if e := r.Store().DeleteNode(id); e != nil {
		writeAPIError(w, http.StatusConflict, e.Error())
		return
	}
	_ = r.Store().RecomputeSubscriptionCounts()
	r.recordEvent("node_deleted")
	writeAPIJSON(w, http.StatusOK, map[string]any{"schema": 1, "ok": true, "state": r.Status()})
}

// ---- 订阅 handler ----

func (r *Runtime) apiListSubscriptions(w http.ResponseWriter, req *http.Request) {
	subs, e := r.Store().ListSubscriptions()
	if e != nil {
		writeAPIError(w, http.StatusInternalServerError, e.Error())
		return
	}
	writeAPIJSON(w, http.StatusOK, map[string]any{"schema": 1, "ok": true, "subscriptions": subs})
}

func (r *Runtime) apiAddSubscription(w http.ResponseWriter, req *http.Request) {
	var in StoreSubscription
	if e := json.NewDecoder(http.MaxBytesReader(w, req.Body, 4<<20)).Decode(&in); e != nil {
		writeAPIError(w, http.StatusBadRequest, "request_invalid")
		return
	}
	in.ID = 0
	sub, e := r.Store().UpsertSubscription(in)
	if e != nil {
		writeAPIError(w, http.StatusConflict, e.Error())
		return
	}
	writeAPIJSON(w, http.StatusOK, map[string]any{"schema": 1, "ok": true, "subscription": sub})
}

func (r *Runtime) apiUpdateSubscription(w http.ResponseWriter, req *http.Request) {
	id, ok := parsePathID(req)
	if !ok {
		writeAPIError(w, http.StatusBadRequest, "arguments_invalid")
		return
	}
	added, updated, removed, e := r.UpdateSubscription(req.Context(), id)
	if e != nil {
		writeAPIError(w, http.StatusConflict, e.Error())
		return
	}
	r.recordEvent("subscription_updated")
	writeAPIJSON(w, http.StatusOK, map[string]any{
		"schema": 1, "ok": true,
		"added": added, "updated": updated, "removed": removed,
	})
}

func (r *Runtime) apiDeleteSubscription(w http.ResponseWriter, req *http.Request) {
	id, ok := parsePathID(req)
	if !ok {
		writeAPIError(w, http.StatusBadRequest, "arguments_invalid")
		return
	}
	if e := r.Store().DeleteSubscription(id); e != nil {
		writeAPIError(w, http.StatusConflict, e.Error())
		return
	}
	r.recordEvent("subscription_deleted")
	writeAPIJSON(w, http.StatusOK, map[string]any{"schema": 1, "ok": true, "state": r.Status()})
}

// ---- 配置 handler ----

func (r *Runtime) apiGetConfig(w http.ResponseWriter, req *http.Request) {
	cfg, e := r.Store().ReadConfig()
	if e != nil {
		writeAPIError(w, http.StatusInternalServerError, e.Error())
		return
	}
	writeAPIJSON(w, http.StatusOK, map[string]any{"schema": 1, "ok": true, "config": cfg})
}

func (r *Runtime) apiPutConfig(w http.ResponseWriter, req *http.Request) {
	var intent ConfigIntent
	if e := json.NewDecoder(http.MaxBytesReader(w, req.Body, MaxConfigBytes)).Decode(&intent); e != nil {
		writeAPIError(w, http.StatusBadRequest, "request_invalid")
		return
	}
	state, e := r.ApplyConfigIntent(req.Context(), intent)
	if e != nil {
		writeAPIError(w, http.StatusConflict, e.Error())
		return
	}
	writeAPIJSON(w, http.StatusOK, map[string]any{"schema": 1, "ok": true, "state": state})
}

func (r *Runtime) apiReload(w http.ResponseWriter, req *http.Request) {
	state, e := r.ApplyConfigIntent(req.Context(), ConfigIntent{})
	if e != nil {
		writeAPIError(w, http.StatusConflict, e.Error())
		return
	}
	writeAPIJSON(w, http.StatusOK, map[string]any{"schema": 1, "ok": true, "state": state})
}

func (r *Runtime) apiAutostart(w http.ResponseWriter, req *http.Request) {
	var body struct {
		Enabled bool `json:"enabled"`
	}
	if e := json.NewDecoder(http.MaxBytesReader(w, req.Body, 4096)).Decode(&body); e != nil {
		writeAPIError(w, http.StatusBadRequest, "request_invalid")
		return
	}
	if e := r.SetAutoStart(body.Enabled); e != nil {
		writeAPIError(w, http.StatusConflict, e.Error())
		return
	}
	writeAPIJSON(w, http.StatusOK, map[string]any{"schema": 1, "ok": true, "state": r.Status()})
}

// ---- 数据迁移 handler（对应原 CLI data prepare/finish/rollback）----

func (r *Runtime) apiDataPrepare(w http.ResponseWriter, req *http.Request) {
	if e := r.PrepareDataUpdate(req.Context()); e != nil {
		writeAPIError(w, http.StatusConflict, e.Error())
		return
	}
	writeAPIJSON(w, http.StatusOK, map[string]any{"schema": 1, "ok": true, "state": r.Status()})
}

func (r *Runtime) apiDataFinish(w http.ResponseWriter, req *http.Request) {
	if e := r.FinishDataUpdate(); e != nil {
		writeAPIError(w, http.StatusConflict, e.Error())
		return
	}
	writeAPIJSON(w, http.StatusOK, map[string]any{"schema": 1, "ok": true, "state": r.Status()})
}

func (r *Runtime) apiDataRollback(w http.ResponseWriter, req *http.Request) {
	if e := r.RollbackDataUpdate(); e != nil {
		writeAPIError(w, http.StatusConflict, e.Error())
		return
	}
	writeAPIJSON(w, http.StatusOK, map[string]any{"schema": 1, "ok": true, "state": r.Status()})
}

func (r *Runtime) apiSelectionFinish(w http.ResponseWriter, req *http.Request) {
	var body struct {
		ID string `json:"id"`
	}
	if e := json.NewDecoder(http.MaxBytesReader(w, req.Body, 4096)).Decode(&body); e != nil {
		writeAPIError(w, http.StatusBadRequest, "request_invalid")
		return
	}
	if !validSelectionID(body.ID) {
		writeAPIError(w, http.StatusBadRequest, "arguments_invalid")
		return
	}
	if e := r.FinishInstallSelection(body.ID); e != nil {
		writeAPIError(w, http.StatusConflict, e.Error())
		return
	}
	writeAPIJSON(w, http.StatusOK, map[string]any{"schema": 1, "ok": true, "state": r.Status()})
}

// ---- 工具 ----

func parsePathID(req *http.Request) (int64, bool) {
	raw := req.PathValue("id")
	id, e := strconv.ParseInt(raw, 10, 64)
	if e != nil || id <= 0 {
		return 0, false
	}
	return id, true
}
