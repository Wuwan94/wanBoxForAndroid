// @author 雾晚
package urltest

import (
	"context"
	"libcore/internal/urlprobe"
	"net"
	"net/http"
	"time"

	"github.com/sagernet/sing-box/adapter"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
)

const DefaultFallbackURL = "https://www.gstatic.com/generate_204"

// ProbeOutbound uses the same complete request timing as manual and Root probes.
//
// @author 雾晚: 这是 TCP 语义的观测——HTTP 请求经 detour.DialContext("tcp") 发出，
// 结果只能证明该成员 TCP 可用。调用方 MUST 把结果记到 TCP 一侧，不得用它
// 冒充 UDP 成功（UDP 的证据只能来自真实 UDP 操作，见 ProbeOutboundUDP）。
func ProbeOutbound(ctx context.Context, detour adapter.Outbound, link string, timeout time.Duration) (uint16, error) {
	if detour == nil {
		return 0, E.New("nil detour")
	}
	if link == "" {
		link = DefaultFallbackURL
	}
	if timeout <= 0 {
		timeout = 3 * time.Second
	}
	transport := &http.Transport{
		ForceAttemptHTTP2: true,
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return detour.DialContext(ctx, network, M.ParseSocksaddr(addr))
		},
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{
		Transport: transport,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	delay, err := urlprobe.MeasureContext(ctx, client, link, timeout)
	if err != nil {
		return 0, err
	}
	if delay > 65535 {
		delay = 65535
	}
	return uint16(delay), nil
}

// ProbeOutboundUDP 用数据面同一个原语（ListenPacket）向成员建立一次 UDP 关联，
// 成功即关闭并返回 nil。它是本版唯一“真实 UDP”证据：探测请求走 TCP，UDP 的
// 可用性不能从 TCP 成功或缓存里推断。
//
// @author 雾晚: 边界——关联成功证明成员接受 UDP 流（SOCKS5 UDP ASSOCIATE 之类
// 的握手已经完成），不证明某个目的端会回包；往回包里塞合成 DNS 查询需要额外的
// 目标配置与第三方流量，与“不引入网络泄漏”的约束冲突，故不采用，边界写进
// openspec/specs/libcore-integration。失败只作为本网络的观测，不改写任何历史。
func ProbeOutboundUDP(ctx context.Context, detour adapter.Outbound, timeout time.Duration) error {
	if detour == nil {
		return E.New("nil detour")
	}
	if timeout <= 0 {
		timeout = 3 * time.Second
	}
	probeCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	conn, err := detour.ListenPacket(probeCtx, M.Socksaddr{})
	if err != nil {
		return err
	}
	return conn.Close()
}
