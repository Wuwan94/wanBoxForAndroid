// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package libcore

import (
	"reflect"
	"sync/atomic"
	"testing"

	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
)

// testLogger 是一个空的 logger 实现，用于测试。
type testLogger struct{}

func (testLogger) Trace(args ...any)    {}
func (testLogger) Debug(args ...any)    {}
func (testLogger) Info(args ...any)     {}
func (testLogger) Warn(args ...any)     {}
func (testLogger) Error(args ...any)    {}
func (testLogger) Fatal(args ...any)    {}
func (testLogger) Panic(args ...any)    {}
func (testLogger) SetLevel(level int32) {}

// TestInterfaceIPFingerprint 验证 IP 指纹函数不 panic，
// 在无效 index 时返回空字符串。
func TestInterfaceIPFingerprint(t *testing.T) {
	if fp := interfaceIPFingerprint(-1); fp != "" {
		t.Errorf("expected empty fingerprint for invalid index, got %q", fp)
	}
	if fp := interfaceIPFingerprint(0); fp != "" {
		t.Errorf("expected empty fingerprint for index 0, got %q", fp)
	}
	// 不验证具体值（环境相关），只确保不 panic
	_ = interfaceIPFingerprint(1)
}

// TestUpdateSSIDTracking 验证 SSID 跟踪的状态机逻辑：
// 相同 SSID 不重复记录，空 SSID 不触发重置。
func TestUpdateSSIDTracking(t *testing.T) {
	m := &interfaceMonitor{logger: testLogger{}}
	// 首次设置
	m.UpdateSSID("HomeWiFi")
	if m.lastSSID != "HomeWiFi" {
		t.Errorf("expected lastSSID=HomeWiFi, got %q", m.lastSSID)
	}
	// 相同 SSID：无变化
	m.UpdateSSID("HomeWiFi")
	if m.lastSSID != "HomeWiFi" {
		t.Errorf("expected lastSSID unchanged")
	}
	// 切换到空：记录但不触发（无 defaultInterface 时无回调）
	m.UpdateSSID("")
	if m.lastSSID != "" {
		t.Errorf("expected lastSSID cleared")
	}
}

// TestInterfaceMonitorPreservesConnectionsWhileResettingRouterNetwork 是 B1/O3
// （上游 8.13）的移植测试：networkChangeResetConnections=false 时，切网必须保留
// 现有代理长连接（不通知回调拆出站），但仍重置路由器 DNS/HTTP 连接池。
func TestInterfaceMonitorPreservesConnectionsWhileResettingRouterNetwork(t *testing.T) {
	prev := networkChangeResetConnections.Load()
	defer networkChangeResetConnections.Store(prev)
	networkChangeResetConnections.Store(false)

	wrapper := &boxPlatformInterfaceWrapper{}
	var routerResetCalls atomic.Int32
	wrapper.setRouterReset(func() {
		routerResetCalls.Add(1)
	})

	mon := newInterfaceMonitor(wrapper, testLogger{})
	var events []string
	elem := mon.RegisterCallback(func(defaultInterface *control.Interface, flags int) {
		if defaultInterface == nil {
			events = append(events, "nil")
		} else {
			events = append(events, defaultInterface.Name)
		}
	})
	defer mon.UnregisterCallback(elem)

	// 1. 首次接口出现（oldInterface == nil）必须通知回调。
	mon.UpdateDefaultInterface("wlan0", 99991)
	if !reflect.DeepEqual(events, []string{"wlan0"}) {
		t.Fatalf("expected initial interface wake callback [wlan0], got %v", events)
	}
	if routerResetCalls.Load() != 0 {
		t.Fatalf("expected 0 router resets on initial wake callback, got %d", routerResetCalls.Load())
	}

	// 2. 无缝切网且重置关闭时：不拆连接（无回调），但重置一次路由器网络。
	mon.UpdateDefaultInterface("rmnet0", 99992)
	if !reflect.DeepEqual(events, []string{"wlan0"}) {
		t.Fatalf("expected callbacks skipped on seamless switch when reset disabled, got %v", events)
	}
	if routerResetCalls.Load() != 1 {
		t.Fatalf("expected 1 router network reset on seamless switch, got %d", routerResetCalls.Load())
	}

	// 3. 重复的接口事件必须被完全忽略。
	mon.UpdateDefaultInterface("rmnet0", 99992)
	if !reflect.DeepEqual(events, []string{"wlan0"}) || routerResetCalls.Load() != 1 {
		t.Fatalf("expected duplicate update to be ignored, events=%v resets=%d", events, routerResetCalls.Load())
	}

	// 4. 清除路由器重置钩子（Close）后，后续切网仍须安全无操作。
	wrapper.setRouterReset(nil)
	mon.UpdateDefaultInterface("wlan1", 99993)
	if !reflect.DeepEqual(events, []string{"wlan0"}) || routerResetCalls.Load() != 1 {
		t.Fatalf("expected safe no-op after setRouterReset(nil), events=%v resets=%d", events, routerResetCalls.Load())
	}

	// 5. NetworkPause（index == -1）与随后的 NetworkWake（oldInterface == nil）仍通知回调。
	mon.UpdateDefaultInterface("", -1)
	mon.UpdateDefaultInterface("wlan0", 99994)
	if !reflect.DeepEqual(events, []string{"wlan0", "nil", "wlan0"}) {
		t.Fatalf("expected pause and wake callbacks [wlan0 nil wlan0], got %v", events)
	}
}

func TestInterfaceMonitorNotifiesCallbacksWhenResetEnabled(t *testing.T) {
	prev := networkChangeResetConnections.Load()
	defer networkChangeResetConnections.Store(prev)
	networkChangeResetConnections.Store(true)

	wrapper := &boxPlatformInterfaceWrapper{}
	var routerResetCalls atomic.Int32
	wrapper.setRouterReset(func() {
		routerResetCalls.Add(1)
	})

	mon := newInterfaceMonitor(wrapper, testLogger{})
	var events []string
	var cb tun.DefaultInterfaceUpdateCallback = func(defaultInterface *control.Interface, flags int) {
		if defaultInterface == nil {
			events = append(events, "nil")
		} else {
			events = append(events, defaultInterface.Name)
		}
	}
	elem := mon.RegisterCallback(cb)
	defer mon.UnregisterCallback(elem)

	mon.UpdateDefaultInterface("wlan0", 99995)
	mon.UpdateDefaultInterface("rmnet0", 99996)

	if !reflect.DeepEqual(events, []string{"wlan0", "rmnet0"}) {
		t.Fatalf("expected both callbacks when networkChangeResetConnections=true, got %v", events)
	}
	if routerResetCalls.Load() != 0 {
		t.Fatalf("expected wrapper router reset not to duplicate callback ResetNetwork, got %d", routerResetCalls.Load())
	}
}
