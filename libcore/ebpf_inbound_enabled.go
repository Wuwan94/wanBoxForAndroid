//go:build with_ebpf && (linux || android)

package libcore

import (
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/protocol/ebpf"
)

func registerEBPFInbound(registry *inbound.Registry) {
	ebpf.RegisterInbound(registry)
}
