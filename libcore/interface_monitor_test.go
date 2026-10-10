// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package libcore


import (
	"testing"
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
