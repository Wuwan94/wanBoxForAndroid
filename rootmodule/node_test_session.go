package module

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"time"
)

// B5（N1）：Root 侧节点测试会话。App 在停止态下测节点时，
// 不经过运行态的 Clash Controller / 内嵌核心，而是把 App 生成的
// 最小测试配置（forTest=true，不含 Clash API、缓存与受管入站）
// 交给 rootbox 起一个短生命周期测试 box；会话结束后即销毁，
// 不修改正式服务状态、当前选择或 Worker。
// 插件出站（external chains）本批不支持：App 侧在调用前拒绝，
// 见 app 侧 url_test_plugin_unsupported。

const (
	nodeTestMinTimeoutMs = 1000
	nodeTestMaxTimeoutMs = 60000
	// rootbox stdout 结果上限：URL 探测一个小 JSON，速度测试一个结果 JSON。
	nodeTestMaxResultBytes = 1 << 20
)

// SpeedTestMode 与 libcore/speedtest.go 的模式常量对齐
// （rootmodule 不直接依赖 libcore，参数经 JSON 文件传给 rootbox）。
const (
	SpeedTestModeDownloadUpload = "download_upload"
	SpeedTestModeDownload       = "download"
	SpeedTestModeUpload         = "upload"
	SpeedTestModeSimpleDownload = "simple_download"
)

const SpeedTestStageComplete = "complete"

// SpeedTestParams 是 rootbox --speed-test 的参数，
// 以 JSON 文件传给 rootbox（与 libcore.SpeedTestParams 字段对齐）。
type SpeedTestParams struct {
	ProfileKey            string `json:"profileKey"`
	Mode                  string `json:"mode"`
	TimeoutMs             int32  `json:"timeoutMs"`
	ServerListURL         string `json:"serverListURL"`
	FallbackServerListURL string `json:"fallbackServerListURL"`
	SimpleDownloadURL     string `json:"simpleDownloadURL"`
}

// SpeedTestResult 是 rootbox --speed-test 的 stdout 结果，
// 字段名与 libcore.SpeedTestResult 的 Go 字段名一致（无 JSON tag）。
type SpeedTestResult struct {
	ProfileKey            string `json:"ProfileKey"`
	Mode                  string `json:"Mode"`
	Stage                 string `json:"Stage"`
	DownloadBitsPerSecond int64  `json:"DownloadBitsPerSecond"`
	UploadBitsPerSecond   int64  `json:"UploadBitsPerSecond"`
	DownloadBytes         int64  `json:"DownloadBytes"`
	UploadBytes           int64  `json:"UploadBytes"`
	LatencyMs             int64  `json:"LatencyMs"`
	ServerID              string `json:"ServerID"`
	ServerName            string `json:"ServerName"`
	ServerCountry         string `json:"ServerCountry"`
	ServerSponsor         string `json:"ServerSponsor"`
	Error                 string `json:"Error"`
	Cancelled             bool   `json:"Cancelled"`
	Done                  bool   `json:"Done"`
}

func (r *Runtime) nodeTestAssetsDir() string {
	if rev, err := r.CurrentRevision(); err == nil && rev != "" {
		if dir := r.path("generations", rev, "assets"); dirExists(dir) {
			return dir
		}
	}
	return r.ModuleDir
}

func dirExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}

// writeNodeTestTemp 把调用方提供的 JSON 写进 ModuleDir 下的临时文件
// （0600，用后删除），返回路径。
func (r *Runtime) writeNodeTestTemp(raw []byte) (string, error) {
	f, err := os.CreateTemp(r.ModuleDir, ".node-test-*.json")
	if err != nil {
		return "", err
	}
	path := f.Name()
	if _, err := f.Write(raw); err != nil {
		_ = f.Close()
		_ = os.Remove(path)
		return "", err
	}
	if err := f.Sync(); err != nil {
		_ = f.Close()
		_ = os.Remove(path)
		return "", err
	}
	if err := f.Close(); err != nil {
		_ = os.Remove(path)
		return "", err
	}
	return path, nil
}

// runNodeTestRootbox 执行 rootbox 测试模式，只取 stdout（上限 1 MiB），
// stderr 一律丢弃（核心日志可能含凭据，绝不回传 App）。
func (r *Runtime) runNodeTestRootbox(ctx context.Context, args ...string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, filepath.Join(r.ModuleDir, "bin", "rootbox"), args...)
	cmd.Stderr = io.Discard
	out, err := cmd.Output()
	if err != nil {
		return nil, errors.New("node_test_failed")
	}
	if len(out) > nodeTestMaxResultBytes {
		return nil, errors.New("node_test_invalid_result")
	}
	return out, nil
}

// NodeURLTest 用短生命周期测试 box 对指定节点做一次 URL 探测，
// 返回毫秒延迟。configJSON 必须是 App 生成的最小测试配置。
func (r *Runtime) NodeURLTest(ctx context.Context, profileID int64, configJSON, link string, timeoutMs int) (int32, error) {
	if profileID <= 0 {
		return 0, errors.New("node_test_invalid_profile")
	}
	if timeoutMs < nodeTestMinTimeoutMs || timeoutMs > nodeTestMaxTimeoutMs {
		return 0, errors.New("node_test_invalid_timeout")
	}
	if parsed, err := url.Parse(link); err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Host == "" {
		return 0, errors.New("node_test_invalid_url")
	}
	if !json.Valid([]byte(configJSON)) {
		return 0, errors.New("node_test_invalid_config")
	}
	configPath, err := r.writeNodeTestTemp([]byte(configJSON))
	if err != nil {
		return 0, err
	}
	defer os.Remove(configPath)
	ctx, cancel := context.WithTimeout(ctx, time.Duration(timeoutMs)*time.Millisecond+60*time.Second)
	defer cancel()
	out, err := r.runNodeTestRootbox(ctx, "--url-test", configPath, r.nodeTestAssetsDir(), link, strconv.Itoa(timeoutMs))
	if err != nil {
		return 0, err
	}
	var result struct {
		DelayMs int32 `json:"delayMs"`
	}
	if err := json.Unmarshal(out, &result); err != nil || result.DelayMs < 0 {
		return 0, errors.New("node_test_invalid_result")
	}
	return result.DelayMs, nil
}

// NodeSpeedTest 用短生命周期测试 box 对指定节点做一次速度测试，
// 返回最终结果（stage 为 complete 或 error 时均返回结果本身，
// 只有会话无法启动/超时才返回 error）。
func (r *Runtime) NodeSpeedTest(ctx context.Context, profileID int64, configJSON string, params SpeedTestParams) (*SpeedTestResult, error) {
	if profileID <= 0 {
		return nil, errors.New("node_test_invalid_profile")
	}
	switch params.Mode {
	case SpeedTestModeDownloadUpload, SpeedTestModeDownload, SpeedTestModeUpload, SpeedTestModeSimpleDownload:
	default:
		return nil, errors.New("node_test_invalid_mode")
	}
	if params.TimeoutMs < 0 || params.TimeoutMs > nodeTestMaxTimeoutMs {
		return nil, errors.New("node_test_invalid_timeout")
	}
	if params.SimpleDownloadURL != "" {
		if parsed, err := url.Parse(params.SimpleDownloadURL); err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Host == "" {
			return nil, errors.New("node_test_invalid_url")
		}
	}
	if !json.Valid([]byte(configJSON)) {
		return nil, errors.New("node_test_invalid_config")
	}
	params.ProfileKey = strconv.FormatInt(profileID, 10)
	paramsRaw, err := json.Marshal(params)
	if err != nil {
		return nil, errors.New("node_test_invalid_params")
	}
	configPath, err := r.writeNodeTestTemp([]byte(configJSON))
	if err != nil {
		return nil, err
	}
	defer os.Remove(configPath)
	paramsPath, err := r.writeNodeTestTemp(paramsRaw)
	if err != nil {
		return nil, err
	}
	defer os.Remove(paramsPath)
	timeout := time.Duration(params.TimeoutMs) * time.Millisecond
	if timeout <= 0 {
		timeout = 5 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, timeout+120*time.Second)
	defer cancel()
	out, err := r.runNodeTestRootbox(ctx, "--speed-test", configPath, r.nodeTestAssetsDir(), paramsPath)
	if err != nil {
		return nil, err
	}
	var result SpeedTestResult
	if err := json.Unmarshal(out, &result); err != nil {
		return nil, errors.New("node_test_invalid_result")
	}
	return &result, nil
}
