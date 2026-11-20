// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
// 复审 P1 的 RED 探针：只用基线（da68253）已存在的 API。
// 断言的是“正确行为”：成员只建立并持有一个可用的 UDP 套接字（毫无回包证据）
// 时，本轮 UDP 不得算成功，也不得推进 UDP 回切计数。
// 在基线上这条用例应当是红的——它正是“关联成功即 UDP 健康”的复现。
package urltest

import (
	"context"
	"testing"

	"github.com/sagernet/sing-box/adapter/outbound"
	N "github.com/sagernet/sing/common/network"
)

func TestRedUDPAssociationAloneIsNotEvidence(t *testing.T) {
	member := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "a", []string{"tcp", "udp"}, nil)}
	grp := startupGroupWithMembers(t, context.Background(), "http://example.invalid/204", member)
	// 没有配置任何可验证的 UDP 端点；成员 ListenPacket 立刻成功、没有任何回包。
	if _, round, err := grp.urlTest(context.Background(), true); err != nil {
		t.Fatalf("round: %v", err)
	} else if got := round.snapshotNetwork(N.NetworkUDP)["a"]; got == urlTestSuccess {
		t.Fatalf("RED: an association without any reply was recorded as UDP success (%v)", got)
	}
	if got := grp.tierUDP.gate.consecutiveOK[0]; got != 0 {
		t.Fatalf("RED: an association without any reply advanced the UDP recovery gate to %d", got)
	}
}
