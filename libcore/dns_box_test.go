// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package libcore

import (
	"testing"
	"time"
)

// TestExchangeContextSuccessSignalsDone 是 B1/O2a 的回归测试：Success() 写入地址后
// 必须调用 done()，否则等待该次解析的调用方会挂起直至上下文超时。
func TestExchangeContextSuccessSignalsDone(t *testing.T) {
	done := make(chan struct{})
	c := &ExchangeContext{
		done: func() { close(done) },
	}
	c.Success("1.1.1.1\n8.8.8.8")
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("Success() did not call done(), DNS exchange would hang until timeout")
	}
	if len(c.addresses) != 2 {
		t.Fatalf("expected 2 parsed addresses, got %d", len(c.addresses))
	}
	if c.addresses[0].String() != "1.1.1.1" || c.addresses[1].String() != "8.8.8.8" {
		t.Fatalf("unexpected parsed addresses: %v", c.addresses)
	}
}

// 其余三个回调本来就有 done()，此处锁定该行为不被后续重构丢掉。
func TestExchangeContextOtherCallbacksSignalDone(t *testing.T) {
	for _, call := range []struct {
		name string
		fn   func(c *ExchangeContext)
	}{
		{"RawSuccess", func(c *ExchangeContext) { c.RawSuccess([]byte{}) }},
		{"ErrorCode", func(c *ExchangeContext) { c.ErrorCode(3) }},
		{"ErrnoCode", func(c *ExchangeContext) { c.ErrnoCode(111) }},
	} {
		t.Run(call.name, func(t *testing.T) {
			done := make(chan struct{})
			c := &ExchangeContext{
				done: func() { close(done) },
			}
			call.fn(c)
			select {
			case <-done:
			case <-time.After(time.Second):
				t.Fatalf("%s did not call done()", call.name)
			}
		})
	}
}
