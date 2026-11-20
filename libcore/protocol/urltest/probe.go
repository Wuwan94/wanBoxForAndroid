// @author 雾晚
package urltest

import (
	"bytes"
	"context"
	"errors"
	"libcore/internal/urlprobe"
	"net"
	"net/http"
	"os"
	"time"

	"github.com/sagernet/sing-box/adapter"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
)

const DefaultFallbackURL = "https://www.gstatic.com/generate_204"

// DefaultUDPProbePayload 是未配置 udp_probe.payload 时使用的探测数据报。
// 探测端点 MUST 原样回显它：回包必须与本数据报逐字节相同才算往返成功。
const DefaultUDPProbePayload = "wanbox-udp-probe-v1"

// ProbeOutbound uses the same complete request timing as manual and Root probes.
//
// @author 雾晚: 这是 TCP 语义的观测——HTTP 请求经 detour.DialContext("tcp") 发出，
// 结果只能证明该成员 TCP 可用。调用方 MUST 把结果记到 TCP 一侧，不得用它
// 冒充 UDP 成功（UDP 的证据只能来自真实 UDP 往返，见 ProbeOutboundUDP）。
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

// UDPProbeOutcome 是一次 UDP 往返探测的结论。只有 UDPProbeVerified 能当作
// “数据报可往返”的健康证据；其余结论都不许被上层当成成功。
type UDPProbeOutcome uint8

const (
	// UDPProbeUnobserved 表示没有观测到结论（内部零值，不应上报）。
	UDPProbeUnobserved UDPProbeOutcome = iota
	// UDPProbeVerified 表示探测数据报经该成员到达目标、并带着原样回显返回：
	// 这是“关联可用 + 会话建立 + 目标可达”三者同时成立的唯一证据。
	UDPProbeVerified
	// UDPProbeRejected 表示成员或链路明确不给 UDP：关联失败、首次发送失败、
	// 连接被对端关闭，或回包不是本次探测的回显。
	UDPProbeRejected
	// UDPProbeUnverified 表示关联已建立、数据报已发出，但没有拿到有效响应
	// （静默丢弃 / 读取超时 / 空包）。这是“未知”，既不是成功也不是失败。
	UDPProbeUnverified
	// UDPProbeSkipped 表示没有可验证的探测端点、或成员不支持 UDP：
	// 本轮不做任何 UDP 操作，也不产生任何额外流量。
	UDPProbeSkipped
	// UDPProbeCanceled 表示父上下文或组生命周期已取消，本轮没有结论。
	UDPProbeCanceled
)

func (o UDPProbeOutcome) String() string {
	switch o {
	case UDPProbeVerified:
		return "verified"
	case UDPProbeRejected:
		return "rejected"
	case UDPProbeUnverified:
		return "unverified"
	case UDPProbeSkipped:
		return "skipped"
	case UDPProbeCanceled:
		return "canceled"
	default:
		return "unobserved"
	}
}

// ProbeOutboundUDP 用数据面原语（ListenPacket）向成员建立一次 UDP 关联，
// 发出一条探测数据报，并要求目标原样回显：只有收到同一份数据报才算
// UDPProbeVerified。
//
// @author 雾晚: 证据边界（写进 openspec/specs/libcore-integration）——
//   - 只创建/关闭 PacketConn 不产生任何证据：VMess/VLESS/Trojan/XUDP 这类
//     “early packet conn” 在首次写入时才完成会话建立，创建成功只说明成员愿意
//     接这条流，服务端是否接受会话、数据报是否可往返都未被证明；
//   - 写入成功也不构成证据：那是“发出去了”，不是“对方收到并回了话”；
//   - 目标 MUST 由用户配置（URLTestUDPProbeOptions），本函数不选目标、不依赖
//     任何第三方探测服务、不加合成 DNS 查询；未配置时调用方直接跳过；
//   - 有界：整个过程受 ctx 与 timeout 双重约束，只发一条数据报、只读一次、
//     不重试；取消时关闭连接把阻塞的读叫醒，连接随调用结束释放。
func ProbeOutboundUDP(ctx context.Context, detour adapter.Outbound, target M.Socksaddr, payload []byte, timeout time.Duration) UDPProbeOutcome {
	if detour == nil || !target.IsValid() || target.Port == 0 || len(payload) == 0 {
		return UDPProbeSkipped
	}
	if timeout <= 0 {
		timeout = 3 * time.Second
	}
	probeCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	if probeCtx.Err() != nil {
		return UDPProbeCanceled
	}
	conn, err := detour.ListenPacket(probeCtx, target)
	if err != nil {
		// 关联失败：成员明确不给 UDP（或成员本身不可达），是失败证据。
		return udpProbeErrorOutcome(ctx, err)
	}
	// 取消/超时必须能把阻塞在 ReadFrom 上的探测叫醒：关闭连接是最可靠的方式。
	stop := context.AfterFunc(probeCtx, func() { _ = conn.Close() })
	defer func() {
		stop()
		_ = conn.Close()
	}()
	if deadline, ok := probeCtx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	if _, err = conn.WriteTo(payload, target.UDPAddr()); err != nil {
		// 首次发送失败：会话没有建立起来（early packet conn 的握手就在这一步）。
		return udpProbeErrorOutcome(ctx, err)
	}
	reply := make([]byte, len(payload)+64)
	n, _, err := conn.ReadFrom(reply)
	if err != nil {
		if ctx.Err() != nil {
			return UDPProbeCanceled
		}
		// probeCtx 到期时，取消回调会同时关闭连接：这条“连接已关闭”的错误
		// 同样是“没拿到回包”，不是成员失败。
		if probeCtx.Err() != nil || isUDPProbeSilence(err) {
			// 数据报发出去了，但没有有效响应：未知，不当成功也不当失败。
			return UDPProbeUnverified
		}
		return UDPProbeRejected
	}
	if n <= 0 {
		return UDPProbeUnverified
	}
	if n != len(payload) || !bytes.Equal(reply[:n], payload) {
		return UDPProbeRejected
	}
	return UDPProbeVerified
}

// udpProbeErrorOutcome 把一次 UDP 操作错误归类：父上下文取消是 Canceled，
// 其余（含成员自己的超时）都是成员/链路的明确失败。
func udpProbeErrorOutcome(parent context.Context, err error) UDPProbeOutcome {
	if parent.Err() != nil {
		return UDPProbeCanceled
	}
	if errors.Is(err, context.Canceled) {
		return UDPProbeCanceled
	}
	return UDPProbeRejected
}

// isUDPProbeSilence 报告一次读取是否只是“没有回包”（超时/截止时间）：
// 这类结果 MUST 归为未知，不能当成失败。
func isUDPProbeSilence(err error) bool {
	if errors.Is(err, os.ErrDeadlineExceeded) || errors.Is(err, context.DeadlineExceeded) {
		return true
	}
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return true
	}
	return false
}
