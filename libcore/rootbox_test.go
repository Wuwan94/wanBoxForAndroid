// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package libcore

import (
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"golang.org/x/sys/unix"
)

// hostBoxPlatform 是宿主测试桩：模拟 Android JNI 侧的 BoxPlatformInterface，
// 只为让测试 box 在宿主上能启动并拨号到 127.0.0.1。生产路径仍走 Kotlin 实现。
type hostStringIterator struct {
	values []string
	index  int
}

func (it *hostStringIterator) Next() string {
	if it.index >= len(it.values) {
		return ""
	}
	value := it.values[it.index]
	it.index++
	return value
}

func (it *hostStringIterator) HasNext() bool { return it.index < len(it.values) }
func (it *hostStringIterator) Length() int32 { return int32(len(it.values)) }

type hostNetworkInterfaceIterator struct {
	values []*NetworkInterface
	index  int
}

func (it *hostNetworkInterfaceIterator) Next() *NetworkInterface {
	if it.index >= len(it.values) {
		return nil
	}
	value := it.values[it.index]
	it.index++
	return value
}

func (it *hostNetworkInterfaceIterator) HasNext() bool { return it.index < len(it.values) }
func (it *hostNetworkInterfaceIterator) Length() int32 { return int32(len(it.values)) }

type hostBoxPlatform struct{}

func (hostBoxPlatform) AutoDetectInterfaceControl(fd int32) error { return nil }
func (hostBoxPlatform) OpenTun(_, _ string) (int, error) {
	return 0, errors.New("tun unsupported on host")
}
func (hostBoxPlatform) UseProcFS() bool { return false }
func (hostBoxPlatform) FindConnectionOwner(int32, string, int32, string, int32) (int32, error) {
	return -1, errors.New("unsupported on host")
}
func (hostBoxPlatform) PackageNameByUid(int32) (string, error) {
	return "", errors.New("unsupported on host")
}
func (hostBoxPlatform) UIDByPackageName(string) (int32, error) {
	return -1, errors.New("unsupported on host")
}

// WIFIState 必须返回两个逗号分隔字段，platform_box.go 的 ReadWIFIState
// 会按索引取 SSID 与 BSSID，空串会越界。
func (hostBoxPlatform) WIFIState() string { return "," }

func (hostBoxPlatform) StartDefaultInterfaceMonitor(listener InterfaceUpdateListener) error {
	if ifaces, err := net.Interfaces(); err == nil {
		for _, iface := range ifaces {
			if iface.Flags&net.FlagLoopback != 0 {
				listener.UpdateDefaultInterface(iface.Name, int32(iface.Index))
				break
			}
		}
	}
	return nil
}

func (hostBoxPlatform) CloseDefaultInterfaceMonitor(InterfaceUpdateListener) error { return nil }

func (hostBoxPlatform) GetInterfaces() (NetworkInterfaceIterator, error) {
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil, err
	}
	values := make([]*NetworkInterface, 0, len(ifaces))
	for _, iface := range ifaces {
		addrs, _ := iface.Addrs()
		prefixes := make([]string, 0, len(addrs))
		for _, addr := range addrs {
			prefixes = append(prefixes, addr.String())
		}
		var flags uint32
		if iface.Flags&net.FlagUp != 0 {
			flags |= unix.IFF_UP | unix.IFF_RUNNING
		}
		if iface.Flags&net.FlagBroadcast != 0 {
			flags |= unix.IFF_BROADCAST
		}
		if iface.Flags&net.FlagLoopback != 0 {
			flags |= unix.IFF_LOOPBACK
		}
		if iface.Flags&net.FlagPointToPoint != 0 {
			flags |= unix.IFF_POINTOPOINT
		}
		if iface.Flags&net.FlagMulticast != 0 {
			flags |= unix.IFF_MULTICAST
		}
		ifaceType := InterfaceTypeOther
		if iface.Flags&net.FlagLoopback == 0 {
			ifaceType = InterfaceTypeEthernet
		}
		values = append(values, &NetworkInterface{
			Index:     int32(iface.Index),
			MTU:       int32(iface.MTU),
			Name:      iface.Name,
			Addresses: &hostStringIterator{values: prefixes},
			Flags:     int32(flags),
			Type:      ifaceType,
			DNSServer: &hostStringIterator{},
		})
	}
	return &hostNetworkInterfaceIterator{values: values}, nil
}

// installHostBoxPlatform 在测试期间把 intfBox 换成宿主桩，测试后还原。
func installHostBoxPlatform(t *testing.T) {
	t.Helper()
	previous := intfBox
	intfBox = hostBoxPlatform{}
	t.Cleanup(func() { intfBox = previous })
}

// B5（N1）：Root 侧短生命周期测试会话。测试配置只含 direct 出站，
// 目标为本地 httptest 服务器，不触网、不含真实节点。

const rootTestDirectConfig = `{"log":{"level":"warn"},` +
	`"outbounds":[{"type":"direct","tag":"direct"}],` +
	`"route":{"final":"direct"}}`

func writeRootTestConfig(t *testing.T, config string) (string, string) {
	t.Helper()
	dir := t.TempDir()
	configPath := filepath.Join(dir, "config.json")
	if err := os.WriteFile(configPath, []byte(config), 0600); err != nil {
		t.Fatal(err)
	}
	assets := filepath.Join(dir, "assets")
	if err := os.MkdirAll(assets, 0700); err != nil {
		t.Fatal(err)
	}
	return configPath, assets
}

func TestRunRootURLTestDirectOutbound(t *testing.T) {
	installHostBoxPlatform(t)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	configPath, assets := writeRootTestConfig(t, rootTestDirectConfig)
	wd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	defer os.Chdir(wd)
	delay, err := runRootURLTest(configPath, assets, server.URL+"/generate_204", 5000)
	if err != nil {
		t.Fatalf("runRootURLTest failed: %v", err)
	}
	if delay < 1 || delay > 5000 {
		t.Errorf("unexpected delay %d ms", delay)
	}
}

func TestRunRootURLTestInvalidConfig(t *testing.T) {
	configPath, assets := writeRootTestConfig(t, `{"outbounds": [broken`)
	if _, err := runRootURLTest(configPath, assets, "http://127.0.0.1/generate_204", 5000); err == nil {
		t.Errorf("invalid config accepted")
	}
}

func TestRunRootURLTestInvalidURL(t *testing.T) {
	configPath, assets := writeRootTestConfig(t, rootTestDirectConfig)
	for _, link := range []string{"://bad-url", "ftp://example.com/x"} {
		if _, err := runRootURLTest(configPath, assets, link, 5000); err == nil {
			t.Errorf("invalid URL accepted: %q", link)
		}
	}
}

func TestRunRootURLTestTimeoutBounds(t *testing.T) {
	configPath, assets := writeRootTestConfig(t, rootTestDirectConfig)
	for _, timeout := range []int{0, 999, 60001, -5} {
		if _, err := runRootURLTest(configPath, assets, "http://127.0.0.1/generate_204", timeout); err == nil {
			t.Errorf("out-of-range timeout accepted: %d", timeout)
		}
	}
}

func TestRunRootSpeedTestSimpleDownload(t *testing.T) {
	installHostBoxPlatform(t)
	payload := make([]byte, 1<<20)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", fmt.Sprint(len(payload)))
		_, _ = w.Write(payload)
	}))
	defer server.Close()
	configPath, assets := writeRootTestConfig(t, rootTestDirectConfig)
	wd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	defer os.Chdir(wd)
	result, err := runRootSpeedTest(configPath, assets, SpeedTestParams{
		ProfileKey:        "7",
		Mode:              SpeedTestModeSimpleDownload,
		TimeoutMs:         10000,
		SimpleDownloadURL: server.URL + "/download",
	})
	if err != nil {
		t.Fatalf("runRootSpeedTest failed: %v", err)
	}
	if result == nil || !result.Done || result.Stage != SpeedTestStageComplete {
		t.Fatalf("unexpected speed-test result: %+v", result)
	}
	if result.DownloadBytes <= 0 || result.DownloadBitsPerSecond <= 0 {
		t.Errorf("empty speed-test outcome: %+v", result)
	}
}

func TestRunRootSpeedTestInvalidConfig(t *testing.T) {
	configPath, assets := writeRootTestConfig(t, `{"outbounds": [broken`)
	if _, err := runRootSpeedTest(configPath, assets, SpeedTestParams{
		ProfileKey: "7", Mode: SpeedTestModeSimpleDownload, TimeoutMs: 5000,
		SimpleDownloadURL: "http://127.0.0.1/download",
	}); err == nil {
		t.Errorf("invalid config accepted")
	}
}

func TestRunRootSpeedTestInvalidMode(t *testing.T) {
	configPath, assets := writeRootTestConfig(t, rootTestDirectConfig)
	if _, err := runRootSpeedTest(configPath, assets, SpeedTestParams{
		ProfileKey: "7", Mode: "bogus", TimeoutMs: 5000,
		SimpleDownloadURL: "http://127.0.0.1/download",
	}); err == nil {
		t.Errorf("invalid mode accepted")
	}
}

func TestRunRootURLTestRequiresRoot(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("running as root; euid gate not observable")
	}
	configPath, assets := writeRootTestConfig(t, rootTestDirectConfig)
	if _, err := RunRootURLTest(configPath, assets, "http://127.0.0.1/generate_204", 5000); err == nil {
		t.Errorf("non-root URL test accepted")
	}
}
