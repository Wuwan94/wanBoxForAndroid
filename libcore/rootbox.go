// @author 雾晚
package libcore

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
)

// RunRootBox is owned by the module supervisor, never by an Android App PID.
// @author 雾晚
func RunRootBox(configPath, assetsPath, pidPath, readyPath, stopPath string, supervisorPID int) (result error) {
	if os.Geteuid() != 0 {
		return fmt.Errorf("Root TUN requires UID 0")
	}
	if err := os.Chdir(filepath.Dir(configPath)); err != nil {
		return err
	}
	SetMemoryProfile(os.Getenv("WANBOX_PERFORMANCE") == "true")
	if supervisorPID <= 1 {
		return fmt.Errorf("invalid supervisor PID")
	}
	if err := os.WriteFile(pidPath, []byte(fmt.Sprint(os.Getpid())), 0600); err != nil {
		return err
	}
	defer os.Remove(pidPath)
	externalAssetsPath = filepath.Clean(assetsPath) + string(os.PathSeparator)
	resourcePaths = append(resourcePaths, externalAssetsPath)
	if pem, err := os.ReadFile(filepath.Join(assetsPath, "ca.pem")); err == nil {
		updateRootCACerts(pem)
	}

	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer cancel()
	// @author 雾晚: reuse the existing watchdog; no new polling thread or API port.
	type rootSampleSource struct {
		box           *BoxInstance
		proxy, direct []string
	}
	var telemetry atomic.Pointer[rootSampleSource]
	watchdogDone := make(chan struct{})
	defer func() { cancel(); <-watchdogDone }()
	go func() {
		defer close(watchdogDone)
		lastSample := time.Now()
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if _, err := os.Stat(stopPath); err == nil {
					cancel()
					return
				}
				if core := telemetry.Load(); core != nil {
					elapsed := time.Since(lastSample).Seconds()
					lastSample = time.Now()
					if elapsed > 0 {
						payload, _ := json.Marshal(struct {
							Tag      string `json:"tag"`
							Tx       int64  `json:"tx"`
							Rx       int64  `json:"rx"`
							DirectTx int64  `json:"directTx"`
							DirectRx int64  `json:"directRx"`
						}{
							core.box.CurrentOutboundTag(), rootTrafficRate(core.proxy, "uplink", elapsed, core.box.QueryStats), rootTrafficRate(core.proxy, "downlink", elapsed, core.box.QueryStats), rootTrafficRate(core.direct, "uplink", elapsed, core.box.QueryStats), rootTrafficRate(core.direct, "downlink", elapsed, core.box.QueryStats),
						})
						fmt.Println("WANBOX_STATS:" + string(payload))
					}
				}
				if telemetry.Load() == nil {
					lastSample = time.Now()
				}
				if syscall.Kill(supervisorPID, 0) == syscall.ESRCH {
					cancel()
					return
				}
			}
		}
	}()
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidProviderRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(),
		nekoboxAndroidCertificateProviderRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	config, err := os.ReadFile(configPath)
	if err != nil {
		return err
	}
	var options option.Options
	if err = options.UnmarshalJSONContext(ctx, config); err != nil {
		return fmt.Errorf("decode root configuration: %w", err)
	}
	if options.Route != nil {
		if err = validateRootGeoResources(options.Route.RuleSet, assetsPath); err != nil {
			return err
		}
		if err = prepareLocalGeoRuleSets(options.Route.RuleSet); err != nil {
			return err
		}
		if err = prepareRemoteRuleSets(options.Route.RuleSet); err != nil {
			return err
		}
	}
	instance, err := box.New(box.Options{Options: options, Context: ctx})
	if err != nil {
		return err
	}
	// @author 雾晚: join the reader before closing stats/router resources.
	defer func() { cancel(); <-watchdogDone; result = rootCloseResult(result, instance.Close) }()
	stats := &BoxInstance{Box: instance}
	proxyTags, directTags := rootTrafficTags(options.Outbounds, options.Endpoints)
	stats.SetV2rayStats(strings.Join(append(append([]string{}, proxyTags...), directTags...), "\n"))
	if err = instance.Start(); err != nil {
		return err
	}
	if err = os.WriteFile(readyPath, []byte("ready"), 0600); err != nil {
		return err
	}
	defer os.Remove(readyPath)
	telemetry.Store(&rootSampleSource{stats, proxyTags, directTags})
	<-ctx.Done()
	return nil
}

// CheckRootConfig constructs and closes the real pinned core without Start:
// validate schema, outbounds, rules and resources without establishing TUN.
// @author 雾晚
func CheckRootConfig(configPath, assetsPath string) error {
	if os.Geteuid() != 0 {
		return fmt.Errorf("root required")
	}
	if err := os.Chdir(filepath.Dir(configPath)); err != nil {
		return err
	}
	externalAssetsPath = filepath.Clean(assetsPath) + string(os.PathSeparator)
	resourcePaths = append(resourcePaths, externalAssetsPath)
	ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), nekoboxAndroidProviderRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateProviderRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	raw, err := os.ReadFile(configPath)
	if err != nil {
		return err
	}
	var options option.Options
	if err = options.UnmarshalJSONContext(ctx, raw); err != nil {
		return err
	}
	if options.Route != nil {
		if err = validateRootGeoResources(options.Route.RuleSet, assetsPath); err != nil {
			return err
		}
		if err = prepareLocalGeoRuleSets(options.Route.RuleSet); err != nil {
			return err
		}
		if err = prepareRemoteRuleSets(options.Route.RuleSet); err != nil {
			return err
		}
	}
	instance, err := box.New(box.Options{Options: options, Context: ctx})
	if err != nil {
		return err
	}
	return instance.Close()
}

// @author 雾晚: strict resource checks only for standalone module snapshots.
func validateRootGeoResources(rules []option.RuleSet, assetsPath string) error {
	// Module snapshots must not silently turn missing/corrupt geo assets
	// into empty rules. Keep the legacy converter for other App cores.
	for _, rule := range rules {
		if rule.Type != "local" {
			continue
		}
		code, isIP, legacy, ok := parseGeoRuleSetPath(rule.Path)
		if !ok {
			continue
		}
		name := geositeDat
		if isIP {
			name = geoipDat
		}
		if !legacy {
			// B3/O6：官方 .srs 只有真实填充过才算有效；0 规则占位落到下面的 db 加载校验，
			// 不得只凭文件存在就放行。
			if isPopulatedSRSFile(filepath.Join(assetsPath, name[:len(name)-3]+"-"+code+".srs")) {
				continue
			}
		}
		var assetErr error
		if isIP {
			_, assetErr = loadGeoIPRules(filepath.Join(assetsPath, name), code)
		} else {
			_, assetErr = loadGeoSiteRules(filepath.Join(assetsPath, name), code)
		}
		if assetErr != nil {
			return fmt.Errorf("invalid module geo resource")
		}
	}

	return nil
}

// SpeedTestParams 是 rootbox --speed-test 的参数（JSON 文件传入）。
type SpeedTestParams struct {
	ProfileKey            string `json:"profileKey"`
	Mode                  string `json:"mode"`
	TimeoutMs             int32  `json:"timeoutMs"`
	ServerListURL         string `json:"serverListURL"`
	FallbackServerListURL string `json:"fallbackServerListURL"`
	SimpleDownloadURL     string `json:"simpleDownloadURL"`
}

// RunRootURLTest 只起一个短生命周期测试 box（不注册 PlatformLogWriter，
// 不建 cache.db 与 Clash API，不经过受管入站），用配置的默认出站做一次
// URL 探测。测试会话不修改正式服务状态、当前选择或 Worker。
// @author 雾晚
func RunRootURLTest(configPath, assetsPath, link string, timeoutMs int) (int32, error) {
	if os.Geteuid() != 0 {
		return 0, fmt.Errorf("root required")
	}
	return runRootURLTest(configPath, assetsPath, link, timeoutMs)
}

func runRootURLTest(configPath, assetsPath, link string, timeoutMs int) (int32, error) {
	if timeoutMs < 1000 || timeoutMs > 60000 {
		return 0, fmt.Errorf("invalid url-test timeout")
	}
	if link == "" {
		return 0, fmt.Errorf("empty url-test link")
	}
	if parsed, err := url.Parse(link); err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Host == "" {
		return 0, fmt.Errorf("invalid url-test link")
	}
	// 测试 box 要求工作目录在配置所在目录；退出时恢复，避免调用方
	// （尤其宿主测试）留在随后被清理的临时目录里。
	if wd, err := os.Getwd(); err == nil {
		defer os.Chdir(wd)
	}
	if err := os.Chdir(filepath.Dir(configPath)); err != nil {
		return 0, err
	}
	externalAssetsPath = filepath.Clean(assetsPath) + string(os.PathSeparator)
	resourcePaths = append(resourcePaths, externalAssetsPath)
	config, err := os.ReadFile(configPath)
	if err != nil {
		return 0, err
	}
	instance, err := NewTestSingBoxInstance(string(config), nil)
	if err != nil {
		return 0, err
	}
	defer instance.Close()
	if err := instance.Start(); err != nil {
		return 0, err
	}
	session := NewURLTestSession()
	defer session.Cancel()
	return session.Test(instance, link, int32(timeoutMs))
}

// RunRootSpeedTest 与 RunRootURLTest 同为短生命周期测试会话，
// 区别是跑一次完整速度测试并返回最终结果（不流式输出进度）。
// @author 雾晚
func RunRootSpeedTest(configPath, assetsPath string, params SpeedTestParams) (*SpeedTestResult, error) {
	if os.Geteuid() != 0 {
		return nil, fmt.Errorf("root required")
	}
	return runRootSpeedTest(configPath, assetsPath, params)
}

func runRootSpeedTest(configPath, assetsPath string, params SpeedTestParams) (*SpeedTestResult, error) {
	// 同 runRootURLTest：工作目录进配置目录，退出时恢复。
	if wd, err := os.Getwd(); err == nil {
		defer os.Chdir(wd)
	}
	if err := os.Chdir(filepath.Dir(configPath)); err != nil {
		return nil, err
	}
	externalAssetsPath = filepath.Clean(assetsPath) + string(os.PathSeparator)
	resourcePaths = append(resourcePaths, externalAssetsPath)
	config, err := os.ReadFile(configPath)
	if err != nil {
		return nil, err
	}
	session, err := NewSpeedTestSession(params.ProfileKey, string(config), nil,
		params.Mode, params.TimeoutMs, params.ServerListURL, params.FallbackServerListURL, params.SimpleDownloadURL)
	if err != nil {
		return nil, err
	}
	defer session.Close()
	if err := session.Start(); err != nil {
		return nil, err
	}
	// 会话自身超时界定测试时长；这里只加 120 秒收尾余量，避免无限等待。
	timeout := time.Duration(params.TimeoutMs) * time.Millisecond
	if timeout <= 0 {
		timeout = time.Duration(DefaultSpeedTestTimeoutMs) * time.Millisecond
	}
	deadline := time.Now().Add(timeout + 120*time.Second)
	for {
		result := session.GetResult()
		if result.Done {
			return result, nil
		}
		if time.Now().After(deadline) {
			session.Cancel()
			return nil, fmt.Errorf("speed-test timeout")
		}
		time.Sleep(200 * time.Millisecond)
	}
}
