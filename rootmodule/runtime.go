// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

const Version = 1
const MaxSnapshotBytes = 192 << 20
const MaxConfigBytes = 8 << 20
const MaxFiles = 512

var safeName = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9._/-]{0,180}$`)
var revisionName = regexp.MustCompile(`^[a-f0-9]{64}$`)

// Snapshot is the portable App -> module contract. Files are JSON base64 bytes,
// never App paths. Only fixed plugin kinds can become executable helpers.
type Snapshot struct {
	SchemaVersion       int               `json:"schemaVersion"`
	ExpectedRevision    string            `json:"expectedRevision"`
	Config              json.RawMessage   `json:"config"`
	Files               map[string][]byte `json:"files"`
	Plugins             []Plugin          `json:"plugins"`
	PerformancePriority bool              `json:"performancePriority"`
	AutoStart           bool              `json:"autoStart"`
	ProfileID           int64             `json:"profileId"`
	ProfileName         string            `json:"profileName"`
}
type Plugin struct {
	Kind        string `json:"kind"`
	Binary      string `json:"binary"`
	Config      string `json:"config"`
	Certificate string `json:"certificate,omitempty"`
}
type ProcessIdentity struct {
	PID        int    `json:"pid"`
	Start      string `json:"start"`
	Executable string `json:"executable"`
}
type State struct {
	SchemaVersion   int                   `json:"schemaVersion"`
	Phase           string                `json:"phase"`
	Revision        string                `json:"revision"`
	RunningRevision string                `json:"runningRevision"`
	ProfileID       int64                 `json:"profileId"`
	ProfileName     string                `json:"profileName"`
	Supervisor      ProcessIdentity       `json:"supervisor"`
	Core            ProcessIdentity       `json:"core"`
	Error           string                `json:"error,omitempty"`
	Stats           json.RawMessage       `json:"stats,omitempty"`
	Telemetry       *Telemetry            `json:"telemetry,omitempty"`
	Events          []Event               `json:"events,omitempty"`
	InstallData     *InstallDataSelection `json:"installData,omitempty"`
}
type Event struct {
	Time int64  `json:"time"`
	Code string `json:"code"`
}

// @author 雾晚：模块端遥测字段，可选。
// 由 Status() 在 Core 进程身份校验通过后填充。
// 未知时为 nil/0，Kotlin 端显示"—"，不伪造数据。
type Telemetry struct {
	// VmRSSBytes: 核心进程 VmRSS（字节），从 /proc/<pid>/status 读取
	VmRSSBytes int64 `json:"vmRssBytes,omitempty"`
	// UptimeSeconds: 核心进程运行时长（秒），从 ready marker 计算
	UptimeSeconds int64 `json:"uptimeSeconds,omitempty"`
	// TxBytes/RxBytes: 累计上传/下载字节，nil 表示未知（不用 0 冒充）
	TxBytes *int64 `json:"txBytes,omitempty"`
	RxBytes *int64 `json:"rxBytes,omitempty"`
	// ConnectionCount: 活动连接数，nil 表示未知
	ConnectionCount *int `json:"connectionCount,omitempty"`
}

func (r *Runtime) Events() []Event {
	var events []Event
	_ = readJSON(r.path("events.json"), &events, 64<<10)
	return events
}
func (r *Runtime) recordEvent(code string) {
	if !regexp.MustCompile(`^[a-z_]{1,80}$`).MatchString(code) {
		return
	}
	events := r.Events()
	events = append(events, Event{time.Now().Unix(), code})
	if len(events) > 40 {
		events = events[len(events)-40:]
	}
	_ = jsonWrite(r.path("events.json"), events)
}

type Runtime struct {
	Root      string
	ModuleDir string
	// Validate must perform the pinned core's real check, without starting TUN.
	Validate func(context.Context, string, string) error
	// Unexported test seams; CLI input can never replace these lifecycle operations.
	installValidate func(context.Context, string, string) error
	installStart    func(context.Context) error
	installRunning  func() bool

	// Store 必须是同一实例：每次返回新 Store 会让并发请求各自持锁，
	// 无法串行化对 store/*.json 的读写。
	storeOnce sync.Once
	store     *Store
}

func New(root, moduleDir string) *Runtime {
	r := &Runtime{Root: root, ModuleDir: moduleDir}
	r.Validate = func(ctx context.Context, config, assets string) error {
		ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
		defer cancel()
		// Core errors may contain credentials: never forward raw stderr to the App/log.
		cmd := exec.CommandContext(ctx, filepath.Join(moduleDir, "bin", "rootbox"), "--check", config, assets)
		cmd.Stdout = io.Discard
		cmd.Stderr = io.Discard
		if err := cmd.Run(); err != nil {
			return errors.New("core_config_invalid")
		}
		return nil
	}
	return r
}
func (r *Runtime) path(parts ...string) string {
	return filepath.Join(append([]string{r.Root}, parts...)...)
}
func AtomicWrite(path string, data []byte, mode os.FileMode) error {
	f, err := os.CreateTemp(filepath.Dir(path), ".commit-")
	if err != nil {
		return err
	}
	tmp := f.Name()
	defer os.Remove(tmp)
	if err = f.Chmod(mode); err == nil {
		_, err = f.Write(data)
	}
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	if err = os.Rename(tmp, path); err != nil {
		return err
	}
	return syncDir(filepath.Dir(path))
}
func jsonWrite(path string, v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return AtomicWrite(path, b, 0600)
}
func readJSON(path string, v any, limit int64) error {
	f, e := os.Open(path)
	if e != nil {
		return e
	}
	defer f.Close()
	b, e := io.ReadAll(io.LimitReader(f, limit+1))
	if e != nil {
		return e
	}
	if int64(len(b)) > limit {
		return errors.New("input_too_large")
	}
	return json.Unmarshal(b, v)
}
func DecodeSnapshot(in io.Reader) (Snapshot, error) {
	var s Snapshot
	b, e := io.ReadAll(io.LimitReader(in, MaxSnapshotBytes+1))
	if e != nil {
		return s, e
	}
	if len(b) > MaxSnapshotBytes {
		return s, errors.New("snapshot_too_large")
	}
	d := json.NewDecoder(bytes.NewReader(b))
	d.DisallowUnknownFields()
	if e = d.Decode(&s); e != nil {
		return s, errors.New("snapshot_invalid")
	}
	var extra any
	if d.Decode(&extra) != io.EOF {
		return s, errors.New("trailing_input")
	}
	if e = CheckSnapshot(s); e != nil {
		return s, e
	}
	return s, nil
}
func CheckSnapshot(s Snapshot) error {
	if s.SchemaVersion != Version || !json.Valid(s.Config) || len(s.Config) > MaxConfigBytes || s.ProfileID <= 0 || len(s.ProfileName) > 512 {
		return errors.New("snapshot_invalid")
	}
	if s.ExpectedRevision != "" && !revisionName.MatchString(s.ExpectedRevision) {
		return errors.New("revision_invalid")
	}
	if len(s.Files) > MaxFiles || len(s.Plugins) > 32 {
		return errors.New("too_many_files")
	}
	var size int
	for name, data := range s.Files {
		if !safeRelative(name) || !(strings.HasPrefix(name, "assets/") || strings.HasPrefix(name, "files/") || strings.HasPrefix(name, "plugins/")) {
			return errors.New("unsafe_path")
		}
		if len(data) > 64<<20 {
			return errors.New("file_too_large")
		}
		size += len(data)
		if size > 128<<20 {
			return errors.New("files_too_large")
		}
	}
	for _, p := range s.Plugins {
		switch p.Kind {
		case "trojan-go", "mieru", "naive", "hysteria":
		default:
			return errors.New("plugin_unsupported")
		}
		bin, ok := s.Files[p.Binary]
		if !ok || !strings.HasPrefix(p.Binary, "plugins/") || len(bin) < 64 || !bytes.Equal(bin[:6], []byte{127, 'E', 'L', 'F', 2, 1}) || binary.LittleEndian.Uint16(bin[18:20]) != 183 {
			return errors.New("plugin_invalid")
		}
		if _, ok = s.Files[p.Config]; !ok || !strings.HasPrefix(p.Config, "files/") {
			return errors.New("plugin_config_missing")
		}
		if p.Certificate != "" {
			if _, ok = s.Files[p.Certificate]; !ok || !strings.HasPrefix(p.Certificate, "files/") {
				return errors.New("plugin_cert_missing")
			}
		}
	}
	// No App directory may remain in a runtime snapshot, including custom JSON.
	var config any
	if json.Unmarshal(s.Config, &config) != nil {
		return errors.New("config_invalid")
	}
	if _, ok := config.(map[string]any); !ok {
		return errors.New("config_invalid")
	}
	if err := checkRuntimePaths(config, s.Files); err != nil {
		return err
	}
	return nil
}
func safeRelative(s string) bool {
	return safeName.MatchString(s) && !strings.Contains(s, "\\") && !filepath.IsAbs(s) && filepath.ToSlash(filepath.Clean(s)) == s && !strings.Contains(s, "../")
}
func checkRuntimePaths(v any, files map[string][]byte) error { return checkPaths(v, files, "") }
func checkPaths(v any, files map[string][]byte, parent string) error {
	switch x := v.(type) {
	case map[string]any:
		for k, v := range x {
			if text, ok := v.(string); ok {
				if strings.Contains(text, "/data/user/") || strings.Contains(text, "/data/data/") || strings.Contains(text, "/storage/emulated/") {
					return errors.New("app_path_not_portable")
				}
				filePath := strings.HasSuffix(k, "_path") || k == "external_ui" ||
					(k == "path" && (x["type"] == "local" || parent == "cache_file")) || (k == "output" && parent == "log")
				if filePath {
					// Existing wanBox geo aliases are interpreted by libcore, not
					// filesystem traversal. Require their portable backing asset.
					if k == "path" && x["type"] == "local" {
						if geo := regexp.MustCompile(`^(geoip|geosite)[:-]([a-zA-Z0-9_@!-]+)(?:\.srs)?$`).FindStringSubmatch(text); geo != nil {
							_, db := files["assets/"+geo[1]+".db"]
							_, binary := files["assets/"+geo[1]+"-"+strings.TrimSuffix(geo[2], ".srs")+".srs"]
							if !db && !binary {
								return errors.New("config_resource_missing")
							}
							continue
						}
					}
					// Paths are relative to generation/run; writable cache has one fixed location.
					if text == "../files/yacd" {
						if _, ok := files["files/yacd/index.html"]; !ok {
							return errors.New("config_resource_missing")
						}
					}
					if text != "" && text != "cache.db" && text != "../files/yacd" && !(parent == "log" && (text == "stdout" || text == "stderr")) {
						name := strings.TrimPrefix(text, "../")
						if !strings.HasPrefix(text, "../") || !safeRelative(name) {
							return errors.New("unsafe_config_path")
						}
						if _, exists := files[name]; !exists {
							return errors.New("config_resource_missing")
						}
					}
				}
			}
			if e := checkPaths(v, files, k); e != nil {
				return e
			}
		}
	case []any:
		for _, v := range x {
			if e := checkPaths(v, files, parent); e != nil {
				return e
			}
		}
	}
	return nil
}
func (r *Runtime) init() error {
	if e := privateDirectory(r.Root); e != nil {
		return e
	}
	for _, d := range []string{"generations", "runtime"} {
		if e := privateDirectory(r.path(d)); e != nil {
			return e
		}
	}
	return nil
}
func privateDirectory(p string) error {
	if info, e := os.Lstat(p); e == nil && (!info.IsDir() || info.Mode()&os.ModeSymlink != 0) {
		return errors.New("unsafe_directory")
	}
	if e := os.MkdirAll(p, 0700); e != nil {
		return e
	}
	return os.Chmod(p, 0700)
}
func (r *Runtime) Current() (string, Snapshot, error) {
	var s Snapshot
	rev, e := r.CurrentRevision()
	if e != nil || rev == "" {
		return rev, s, e
	}
	e = readJSON(r.path("generations", rev, "snapshot.json"), &s, MaxSnapshotBytes)
	if e == nil {
		e = CheckSnapshot(s)
	}
	return rev, s, e
}
func (r *Runtime) CurrentRevision() (string, error) {
	f, e := os.Open(r.path("current"))
	if os.IsNotExist(e) {
		return "", nil
	}
	if e != nil {
		return "", e
	}
	defer f.Close()
	b, e := io.ReadAll(io.LimitReader(f, 256))
	if e != nil {
		return "", e
	}
	rev := strings.TrimSpace(string(b))
	if !revisionName.MatchString(rev) {
		return "", errors.New("current_invalid")
	}
	return rev, nil
}
func (r *Runtime) Stage(ctx context.Context, s Snapshot) (string, error) {
	if e := CheckSnapshot(s); e != nil {
		return "", e
	}
	if e := r.init(); e != nil {
		return "", e
	}
	encoded, e := json.Marshal(s)
	if e != nil {
		return "", e
	}
	hash := sha256.Sum256(encoded)
	rev := hex.EncodeToString(hash[:])
	dir, e := os.MkdirTemp(r.path("generations"), ".stage-")
	if e != nil {
		return "", e
	}
	defer os.RemoveAll(dir)
	for _, d := range []string{"run", "assets", "files", "plugins"} {
		if e = privateDirectory(filepath.Join(dir, d)); e != nil {
			return "", e
		}
	}
	for name, b := range s.Files {
		p := filepath.Join(dir, filepath.FromSlash(name))
		if e = privateDirectory(filepath.Dir(p)); e != nil {
			return "", e
		}
		mode := os.FileMode(0600)
		if strings.HasPrefix(name, "plugins/") {
			mode = 0700
		}
		if e = AtomicWrite(p, b, mode); e != nil {
			return "", e
		}
	}
	if e = AtomicWrite(filepath.Join(dir, "run", "config.json"), s.Config, 0600); e != nil {
		return "", e
	}
	if e = r.Validate(ctx, filepath.Join(dir, "run", "config.json"), filepath.Join(dir, "assets")); e != nil {
		return "", e
	}
	if e = jsonWrite(filepath.Join(dir, "snapshot.json"), s); e != nil {
		return "", e
	}
	dst := r.path("generations", rev)
	if _, e = os.Stat(dst); e == nil {
		return rev, nil
	}
	if e = os.Rename(dir, dst); e != nil {
		return "", e
	}
	if e = syncDir(r.path("generations")); e != nil {
		return "", e
	}
	return rev, nil
}

// @author 雾晚：从 /proc/<pid>/status 安全读取 VmRSS（字节）。
// PID 身份已由 SameProcess 校验，读取前后核对代次。
// 使用可注入的解析器，便于测试溢出与非法值。
var vmRSSParser = parseVmRSSFromText

func readVmRSS(pid int) int64 {
	data, err := os.ReadFile(fmt.Sprintf("/proc/%d/status", pid))
	if err != nil {
		return 0
	}
	return vmRSSParser(string(data))
}

// @author 雾晚：从 status 文本解析 VmRSS，处理溢出与非法值。
func parseVmRSSFromText(text string) int64 {
	for _, line := range strings.Split(text, "\n") {
		if strings.HasPrefix(line, "VmRSS:") {
			fields := strings.Fields(line)
			if len(fields) >= 2 {
				var kb int64
				if _, err := fmt.Sscanf(fields[1], "%d", &kb); err != nil {
					return 0
				}
				if kb < 0 {
					return 0
				}
				// 溢出检查：kb * 1024 不得超过 MaxInt64
				if kb > (1<<63-1)/1024 {
					return 0
				}
				return kb * 1024
			}
		}
	}
	return 0
}

// @author 雾晚：从 ready marker 文件时间计算核心就绪时长（秒）。
// Start 是 /proc/<pid>/stat 的启动 ticks（非 RFC3339），不硬猜 HZ。
// 使用 runtime/ready 文件的修改时间作为可说明的就绪起点，
// 读取前后验证 SameProcess 与 revision 一致，防止 PID 复用。
func (r *Runtime) coreReadyDuration(core ProcessIdentity, revision string) int64 {
	if !SameProcess(core) {
		return 0
	}
	fi, err := os.Stat(r.path("runtime", "ready"))
	if err != nil {
		return 0
	}
	// 读取后再次验证，防止竞态
	if !SameProcess(core) {
		return 0
	}
	if rev, _ := r.CurrentRevision(); rev != revision {
		return 0
	}
	d := time.Since(fi.ModTime())
	if d < 0 {
		return 0
	}
	return int64(d.Seconds())
}

// @author 雾晚：从运行中配置读取 Clash API secret，仅本地鉴权。
// 真实路径：generations/<runningRevision>/run/config.json
// 绑定 revision，有上限流式读取，禁止先全量再检查。
func (r *Runtime) clashAPISecret(revision string) string {
	if revision == "" || !revisionName.MatchString(revision) {
		return ""
	}
	path := r.path("generations", revision, "run", "config.json")
	f, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer f.Close()
	// 有上限读取：MaxConfigBytes+1，超限明确拒绝
	limited := io.LimitReader(f, MaxConfigBytes+1)
	data, err := io.ReadAll(limited)
	if err != nil || len(data) > MaxConfigBytes {
		return ""
	}
	var config struct {
		Experimental struct {
			ClashAPI struct {
				Secret string `json:"secret"`
			} `json:"clash_api"`
		} `json:"experimental"`
	}
	if err := json.Unmarshal(data, &config); err != nil {
		return ""
	}
	return config.Experimental.ClashAPI.Secret
}

// @author 雾晚：共享受限 loopback 客户端，避免每轮新建遗留 idle socket。
// 禁用环境代理，禁重定向。调用方负责 ctx 超时。
var loopbackClient = &http.Client{
	Transport: &http.Transport{
		Proxy:              nil, // 禁用环境代理，仅 loopback
		MaxIdleConns:       2,
		IdleConnTimeout:    10 * time.Second,
		DisableCompression: true,
	},
	CheckRedirect: func(req *http.Request, via []*http.Request) error {
		return errors.New("redirect_forbidden")
	},
}

// @author 雾晚：通过受限 loopback API 获取连接数与累计流量。
// 仅访问 127.0.0.1:9090，复用受限客户端，总 ctx 超时 5s，
// 响应读 max+1 明确拒绝超限。请求后由调用方验证代次。
func (r *Runtime) queryClashStats(revision string, core ProcessIdentity) (tx, rx *int64, connCount *int) {
	secret := r.clashAPISecret(revision)
	if secret == "" {
		return nil, nil, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, "GET", "http://127.0.0.1:9090/connections", nil)
	if err != nil {
		return nil, nil, nil
	}
	req.Header.Set("Authorization", "Bearer "+secret)
	// @author 雾晚：复用受限客户端，避免每轮遗留 idle socket
	resp, err := loopbackClient.Do(req)
	if err != nil {
		return nil, nil, nil
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, nil, nil
	}
	// 读 max+1 (256KB+1)，超限明确拒绝
	const maxResp = 262144
	limited := io.LimitReader(resp.Body, maxResp+1)
	data, err := io.ReadAll(limited)
	if err != nil || len(data) > maxResp {
		return nil, nil, nil
	}
	var result struct {
		UploadTotal   *int64            `json:"uploadTotal"`
		DownloadTotal *int64            `json:"downloadTotal"`
		Connections   []json.RawMessage `json:"connections"`
	}
	if err := json.Unmarshal(data, &result); err != nil {
		return nil, nil, nil
	}
	if result.UploadTotal != nil && *result.UploadTotal < 0 {
		result.UploadTotal = nil
	}
	if result.DownloadTotal != nil && *result.DownloadTotal < 0 {
		result.DownloadTotal = nil
	}
	var count *int
	if result.Connections != nil {
		c := len(result.Connections)
		count = &c
	}
	return result.UploadTotal, result.DownloadTotal, count
}

// @author 雾晚：填充本地遥测数据（无 HTTP）。
// Runtime.Status() 必须保持快速本地检查，不访问网络。
// Tx/Rx/connectionCount 由专用 dashboard 命令在 CLI 层显式获取，
// 不经过 Status()，避免守护循环/持锁逻辑被网络等待阻塞。
func (r *Runtime) fillTelemetry(s *State) {
	if !SameProcess(s.Core) {
		s.Telemetry = nil
		return
	}
	revision := s.RunningRevision
	t := &Telemetry{}
	// VmRSS：读取后再次验证身份
	t.VmRSSBytes = readVmRSS(s.Core.PID)
	if !SameProcess(s.Core) {
		s.Telemetry = nil
		return
	}
	// 运行时长：ready marker 时间，需验证 revision 一致
	t.UptimeSeconds = r.coreReadyDuration(s.Core, revision)
	// Tx/Rx/connectionCount: nil（未知），由 dashboard 命令单独采集
	// Status() 永不发起 HTTP，保持快速
	if t.VmRSSBytes > 0 || t.UptimeSeconds > 0 {
		s.Telemetry = t
	} else {
		s.Telemetry = nil
	}
}

// @author 雾晚：可注入的 Dashboard 状态采集，用于测试。
// readStatus: 读取状态；collect: 采集遥测；sameProc: 进程身份校验。
// 生产代码传入真实实现，测试传入模拟。
func dashboardStateInternal(
	readStatus func() State,
	collect func(snap State) *Telemetry,
	sameProc func(ProcessIdentity) bool,
) State {
	before := readStatus()
	if before.Phase != "connected" || !sameProc(before.Core) {
		return before
	}
	// 以 before 采集
	t := collect(before)

	// 无条件回读，严格验证
	after := readStatus()
	if after.Phase != before.Phase {
		after.Telemetry = nil
		after.Stats = nil
		return after
	}
	if after.Core.PID != before.Core.PID || after.Core.Start != before.Core.Start || after.Core.Executable != before.Core.Executable {
		after.Telemetry = nil
		after.Stats = nil
		return after
	}
	if after.RunningRevision != before.RunningRevision {
		after.Telemetry = nil
		after.Stats = nil
		return after
	}
	// ProfileID：热切换节点同 PID/revision 也必须识别
	if after.ProfileID != before.ProfileID {
		after.Telemetry = nil
		after.Stats = nil
		return after
	}
	if !sameProc(after.Core) {
		after.Telemetry = nil
		after.Stats = nil
		return after
	}
	// 一致：把采集赋给 after
	if t != nil {
		after.Telemetry = t
	}
	return after
}

// @author 雾晚：返回绑定同一快照的完整 Dashboard 状态。
// 生产代码调用可注入版本，传入真实实现。
func (r *Runtime) DashboardState() State {
	return dashboardStateInternal(
		r.Status,
		func(snap State) *Telemetry {
			t := &Telemetry{}
			t.VmRSSBytes = readVmRSS(snap.Core.PID)
			t.UptimeSeconds = r.coreReadyDuration(snap.Core, snap.RunningRevision)
			tx, rx, connCount := r.queryClashStats(snap.RunningRevision, snap.Core)
			t.TxBytes = tx
			t.RxBytes = rx
			t.ConnectionCount = connCount
			if t.VmRSSBytes <= 0 && t.UptimeSeconds <= 0 && tx == nil && rx == nil && connCount == nil {
				return nil
			}
			return t
		},
		SameProcess,
	)
}

// @author 雾晚：DashboardTelemetry 仅为兼容保留，调用完整 DashboardState。
// 不另读自己的快照，避免代次混合。
func (r *Runtime) DashboardTelemetry() *Telemetry {
	s := r.DashboardState()
	return s.Telemetry
}

func (r *Runtime) Status() State {
	s := State{SchemaVersion: Version, Phase: "stopped"}
	_ = readJSON(r.path("state.json"), &s, 1<<20)
	rev, err := r.CurrentRevision()
	if err != nil {
		s.Error = "current_invalid"
	}
	s.Revision = rev
	if !r.Enabled() {
		s.Phase = "disabled"
	}
	if !SameProcess(s.Supervisor) {
		// Do not show stale connected/failed status as this process's success.
		if SameProcess(s.Core) {
			s.Phase = "cleanup_required"
			s.Error = "supervisor_missing"
		} else if s.Phase != "disabled" && s.Phase != "failed" {
			s.Phase = "stopped"
		}
	} else if s.Phase == "connected" {
		if !SameProcess(s.Core) {
			s.Phase = "failed"
			s.Error = "core_exited"
		} else if _, e := os.Stat(r.path("runtime", "ready")); e != nil {
			s.Phase = "starting"
		}
	}
	if s.Phase != "connected" {
		s.Stats = nil
		s.Telemetry = nil
	} else {
		// @author 雾晚：已连接时填充遥测，进程身份已校验
		r.fillTelemetry(&s)
	}
	selection, selectionError := r.installSelection()
	s.InstallData = selection
	if selectionError != nil {
		s.Error = "install_data_selection_invalid"
	} else if selection != nil {
		s.Error = "install_data_update_pending"
	}
	// 热切换结果优先于 supervisor 快照，覆盖两次持久化之间的窗口。
	r.applyRuntimeSelection(&s)
	return s
}
func (r *Runtime) Enabled() bool {
	if _, e := os.Stat(filepath.Join(r.ModuleDir, "module.prop")); e != nil {
		return false
	}
	for _, flag := range []string{"disable", "remove"} {
		if _, e := os.Stat(filepath.Join(r.ModuleDir, flag)); e == nil {
			return false
		}
	}
	return true
}
func (r *Runtime) withLock(fn func() error) error {
	return r.withDataLock(func() error {
		if _, e := os.Lstat(r.path("install-data.json")); e == nil {
			return errors.New("install_data_update_pending")
		} else if !os.IsNotExist(e) {
			return e
		}
		if _, e := os.Lstat(r.path("data-reset.json")); e == nil {
			return errors.New("data_update_pending")
		} else if !os.IsNotExist(e) {
			return e
		}
		return fn()
	})
}
func (r *Runtime) withDataLock(fn func() error) error {
	if e := r.init(); e != nil {
		return e
	}
	unlock, e := fileLock(r.path("control.lock"))
	if e != nil {
		return e
	}
	defer unlock()
	return fn()
}
func (r *Runtime) Apply(ctx context.Context, s Snapshot) (State, error) {
	var out State
	e := r.withLock(func() error {
		old, current, e := r.Current()
		if e != nil {
			return e
		}
		if s.ExpectedRevision != old {
			return errors.New("revision_conflict")
		}
		if e = ctx.Err(); e != nil {
			return e
		}
		if e = CheckSnapshot(s); e != nil {
			return e
		}
		// ExpectedRevision guards concurrency; it is not configuration content.
		// Compare bytes directly rather than marshal two potentially large snapshots.
		if old != "" && identicalSnapshotContent(current, s) {
			if e = ctx.Err(); e != nil {
				return e
			}
			out = r.Status()
			return nil
		}
		rev, e := r.Stage(ctx, s)
		if e != nil {
			return e
		}
		if e = ctx.Err(); e != nil {
			return e
		}
		if old != "" {
			if e = AtomicWrite(r.path("previous"), []byte(old), 0600); e != nil {
				return e
			}
		}
		active := SameProcess(r.Status().Supervisor)
		if active {
			if e = r.stop(ctx); e != nil {
				return e
			}
		}
		if e = AtomicWrite(r.path("current"), []byte(rev), 0600); e != nil {
			if active {
				_ = r.start(context.Background())
			}
			return e
		}
		if active {
			if e = r.start(ctx); e != nil {
				// Fully tear down a rejected generation before restoring the last working one.
				if clean := r.stop(context.Background()); clean != nil {
					return errors.New("rollback_cleanup_failed")
				}
				if old != "" {
					if err := AtomicWrite(r.path("current"), []byte(old), 0600); err != nil {
						return err
					}
					if err := r.start(context.Background()); err != nil {
						return errors.New("rollback_start_failed")
					}
				}
				return errors.New("new_core_failed_rolled_back")
			}
		}
		out = r.Status()
		r.recordEvent("config_applied")
		r.pruneGenerations()
		return nil
	})
	return out, e
}
func (r *Runtime) Start(ctx context.Context) error {
	return r.withLock(func() error { return r.start(ctx) })
}
func (r *Runtime) Stop(ctx context.Context) error {
	return r.withLock(func() error { return r.stop(ctx) })
}
func (r *Runtime) start(ctx context.Context) error {
	if _, e := os.Lstat(r.path("install-data.json")); e == nil {
		return errors.New("install_data_update_pending")
	} else if !os.IsNotExist(e) {
		return e
	}
	if e := ctx.Err(); e != nil {
		return e
	}
	if !r.Enabled() {
		return errors.New("module_disabled_or_missing")
	}
	s := r.Status()
	if SameProcess(s.Supervisor) {
		if s.Phase == "connected" {
			return nil
		}
		return errors.New("module_busy")
	}
	if SameProcess(s.Core) {
		return errors.New("cleanup_required")
	}
	rev, _, e := r.Current()
	if e != nil {
		return e
	}
	if rev == "" {
		return errors.New("config_missing")
	}
	if e = r.Validate(ctx, r.path("generations", rev, "run", "config.json"), r.path("generations", rev, "assets")); e != nil {
		return e
	}
	_ = os.Remove(r.path("runtime", "stop"))
	_ = os.Remove(r.path("runtime", "ready"))
	if e = jsonWrite(r.path("state.json"), State{SchemaVersion: Version, Phase: "starting", RunningRevision: rev}); e != nil {
		return e
	}
	cmd := exec.Command(filepath.Join(r.ModuleDir, "bin", "wanboxctl"), "__internal", "supervise")
	detach(cmd)
	cmd.Stdin = nil
	cmd.Stdout = io.Discard
	cmd.Stderr = io.Discard
	if e = cmd.Start(); e != nil {
		return errors.New("supervisor_start_failed")
	}
	// Reap without tying the new session's lifetime to this short CLI invocation.
	go cmd.Wait()
	until := time.NewTimer(270 * time.Second)
	defer until.Stop()
	ticker := time.NewTicker(200 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-until.C:
			return errors.New("startup_timeout")
		case <-ticker.C:
			state := r.Status()
			if state.RunningRevision == rev && state.Phase == "connected" {
				r.recordEvent("core_started")
				return nil
			}
			if state.RunningRevision == rev && state.Phase == "failed" && !SameProcess(state.Supervisor) {
				return errors.New("core_start_failed")
			}
		}
	}
}
func (r *Runtime) stop(ctx context.Context) error {
	s := r.Status()
	if !SameProcess(s.Supervisor) && !SameProcess(s.Core) {
		return nil
	}
	if e := AtomicWrite(r.path("runtime", "stop"), []byte("stop"), 0600); e != nil {
		return e
	}
	// The identity is rechecked at signal delivery: never kill a reused PID.
	if SameProcess(s.Supervisor) {
		if e := terminate(s.Supervisor); e != nil {
			return e
		}
	}
	if !SameProcess(s.Supervisor) && SameProcess(s.Core) {
		if e := terminate(s.Core); e != nil {
			return e
		}
	}
	timer := time.NewTimer(25 * time.Second)
	defer timer.Stop()
	tick := time.NewTicker(100 * time.Millisecond)
	defer tick.Stop()
	for {
		if !SameProcess(s.Supervisor) && !SameProcess(s.Core) {
			_ = os.Remove(r.path("runtime", "ready"))
			r.recordEvent("core_stopped")
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-timer.C:
			return errors.New("cleanup_timeout_no_restart")
		case <-tick.C:
		}
	}
}
func (r *Runtime) Restart(ctx context.Context) error {
	return r.withLock(func() error {
		if e := r.stop(ctx); e != nil {
			return e
		}
		return r.start(ctx)
	})
}

// Rollback restores the separately retained validated generation, including
// recovery from a corrupt current pointer; it never deletes App/database data.
// @author 雾晚
func (r *Runtime) Rollback(ctx context.Context) error {
	return r.withLock(func() error {
		raw, e := os.ReadFile(r.path("previous"))
		if e != nil {
			return errors.New("rollback_missing")
		}
		previous := strings.TrimSpace(string(raw))
		if !revisionName.MatchString(previous) {
			return errors.New("rollback_invalid")
		}
		var snapshot Snapshot
		if e = readJSON(r.path("generations", previous, "snapshot.json"), &snapshot, MaxSnapshotBytes); e != nil {
			return errors.New("rollback_invalid")
		}
		if e = CheckSnapshot(snapshot); e != nil {
			return e
		}
		if e = r.Validate(ctx, r.path("generations", previous, "run", "config.json"), r.path("generations", previous, "assets")); e != nil {
			return e
		}
		active := SameProcess(r.Status().Supervisor) || SameProcess(r.Status().Core)
		if active {
			if e = r.stop(ctx); e != nil {
				return e
			}
		}
		if e = AtomicWrite(r.path("current"), []byte(previous), 0600); e != nil {
			return e
		}
		if active {
			if e = r.start(ctx); e != nil {
				return e
			}
		}
		r.recordEvent("config_rolled_back")
		return nil
	})
}
func (r *Runtime) SetAutoStart(enabled bool) error {
	return r.withLock(func() error {
		return jsonWrite(r.path("boot.json"), struct {
			AutoStart bool `json:"autoStart"`
		}{enabled})
	})
}
func (r *Runtime) Boot(ctx context.Context, bootReady func() bool) error {
	timer := time.NewTimer(3 * time.Minute)
	defer timer.Stop()
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for !bootReady() {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-timer.C:
			return errors.New("boot_timeout")
		case <-ticker.C:
		}
	}
	if e := r.consumeBootInstallChoice(); e != nil {
		return e
	}
	return r.withLock(func() error {
		if !r.Enabled() {
			return nil
		}
		_, snapshot, e := r.Current()
		if e != nil {
			return e
		}
		if snapshot.SchemaVersion == 0 {
			return nil
		}
		settings := struct {
			AutoStart bool `json:"autoStart"`
		}{snapshot.AutoStart}
		if e = readJSON(r.path("boot.json"), &settings, 4096); e != nil && !os.IsNotExist(e) {
			return errors.New("boot_settings_invalid")
		}
		if !settings.AutoStart {
			return nil
		}
		return r.start(ctx)
	})
}

// Errors are stable codes; never serialize raw core/plugin stderr or configurations.
func Response(w io.Writer, state State, err error) {
	payload := struct {
		SchemaVersion int    `json:"schemaVersion"`
		OK            bool   `json:"ok"`
		State         State  `json:"state"`
		Error         string `json:"error,omitempty"`
	}{Version, err == nil, state, ""}
	if err != nil {
		payload.Error = err.Error()
		if !regexp.MustCompile(`^[a-z_]{1,80}$`).MatchString(payload.Error) {
			payload.Error = "module_operation_failed"
		}
	}
	_ = json.NewEncoder(w).Encode(payload)
}
func (r *Runtime) ValidateSnapshot(ctx context.Context, s Snapshot) error {
	return r.withLock(func() error {
		_, e := r.Stage(ctx, s)
		if e == nil {
			r.pruneGenerations()
		}
		return e
	})
}

// @author 雾晚: retain current/previous/running snapshots; never delete active resources.
func (r *Runtime) pruneGenerations() {
	current, e := r.CurrentRevision()
	if e != nil {
		return
	}
	state := r.Status()
	keep := map[string]bool{current: true, state.RunningRevision: true}
	previous, e := os.ReadFile(r.path("previous"))
	if e == nil {
		keep[strings.TrimSpace(string(previous))] = true
	}
	entries, e := os.ReadDir(r.path("generations"))
	if e != nil {
		return
	}
	for _, entry := range entries {
		if entry.IsDir() && revisionName.MatchString(entry.Name()) && !keep[entry.Name()] {
			_ = os.RemoveAll(r.path("generations", entry.Name()))
		}
	}
}
func (r *Runtime) String() string { return fmt.Sprintf("wanbox module protocol %d", Version) }
