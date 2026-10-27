package libcore

import "testing"

func TestEBPFInboundOptionsAreRegistered(t *testing.T) {
	registry := nekoboxAndroidInboundRegistry()
	if _, ok := registry.CreateOptions("ebpf"); !ok {
		t.Fatal("eBPF inbound options must remain parseable in both enabled and fallback builds")
	}
}
