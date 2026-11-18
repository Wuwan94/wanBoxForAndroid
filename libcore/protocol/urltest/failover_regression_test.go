// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package urltest

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// P2-1：故障转移只能在本轮生效层级内取候选。高层成员即便有新鲜历史，
// 只要回切门（3 轮健康 + 60 秒无失败）没开，就不得被当作转移目标；
// 否则一次建连失败就会把 currentTier 直接改写成高层，门被永久绕过。
func TestFailoverCandidatesStayInCurrentTier(t *testing.T) {
	primary := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "b [T2]", []string{"tcp", "udp"}, nil), dead: true}
	upper := &stubDialOutbound{Adapter: outbound.NewAdapter("loopback", "a [T1]", []string{"tcp", "udp"}, nil)}
	grp := tierGroup(t, upper, primary)
	grp.interval = 3 * time.Minute
	storeTierHistory(t, grp, "a [T1]", 100)
	storeTierHistory(t, grp, "b [T2]", 90)
	// 当前生效层是 T2（currentTier=1），高层 T1 健康但门一次都没数过。
	grp.currentTier = 1
	grp.selectedOutboundTCP = primary
	close(grp.startupDone)

	u := &URLTest{group: grp, logger: log.NewNOPFactory().Logger()}
	dest := M.ParseSocksaddrHostPort("example.com", 443)
	_, err := u.DialContext(context.Background(), N.NetworkTCP, dest)
	if upper.calls.Load() != 0 {
		t.Fatalf("failover must not reach a higher tier while the switch-back gate is closed, got %d calls", upper.calls.Load())
	}
	if err == nil {
		t.Fatal("expected the connection to fail inside the exhausted current tier, got success")
	}
	if grp.currentTier != 1 {
		t.Fatalf("failover must not rewrite currentTier across tiers, got %d", grp.currentTier)
	}
	if got := grp.tierGate.consecutiveOK[0]; got != 0 {
		t.Fatalf("failover must not advance the recovery gate, got %d healthy rounds", got)
	}
}

// P2-2：过期候选按新鲜度排序时只能排到最后梯队，不能被整体剔除。
// 新鲜候选全部已挂时，过期但可用的成员仍须兜底，否则单节点抖动会把
// “换节点连上”退化成“这次连接直接失败”。
func TestFailoverKeepsStaleCandidatesAsLastResort(t *testing.T) {
	primary := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "primary-dead", []string{"tcp", "udp"}, nil), dead: true}
	freshDead := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "fresh-dead", []string{"tcp", "udp"}, nil), dead: true}
	staleAlive := &stubDialOutbound{Adapter: outbound.NewAdapter("loopback", "stale-alive", []string{"tcp", "udp"}, nil)}
	grp := startupGroupWithMembers(t, context.Background(), "http://127.0.0.1/generate_204", primary, freshDead, staleAlive)
	grp.interval = 3 * time.Minute
	now := time.Now()
	grp.history.StoreURLTestHistory("primary-dead", &adapter.URLTestHistory{Time: now, Delay: 40})
	grp.history.StoreURLTestHistory("fresh-dead", &adapter.URLTestHistory{Time: now, Delay: 60})
	grp.history.StoreURLTestHistory("stale-alive", &adapter.URLTestHistory{Time: now.Add(-4 * grp.interval), Delay: 10})
	grp.selectedOutboundTCP = primary
	close(grp.startupDone)

	u := &URLTest{group: grp, logger: log.NewNOPFactory().Logger()}
	conn, err := u.DialContext(context.Background(), N.NetworkTCP, M.ParseSocksaddrHostPort("example.com", 443))
	if err != nil {
		t.Fatalf("expected the stale but reachable member to back up the failed fresh candidates, got %v", err)
	}
	_ = conn.Close()
	if freshDead.calls.Load() != 1 {
		t.Fatalf("expected the fresh candidate to be tried first once, got %d", freshDead.calls.Load())
	}
	if staleAlive.calls.Load() != 1 {
		t.Fatalf("expected the stale member to be tried as the last resort once, got %d", staleAlive.calls.Load())
	}
}

// P2-5：整轮预算到期且一次探测都没完成时，不得仅凭“历史还新鲜”推进
// 层级恢复门——那会把完全没观测到的一轮算成健康轮，让高层提前回切。
func TestBudgetExpiryWithoutProbesDoesNotAdvanceTierGate(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-r.Context().Done()
	}))
	defer server.Close()

	grp := startupGroupWithMembers(t, context.Background(), server.URL,
		tierMember("a [T1]"),
		tierMember("b [T2]"),
	)
	storeTierHistory(t, grp, "a [T1]", 100)
	storeTierHistory(t, grp, "b [T2]", 90)
	grp.currentTier = 1
	grp.tierGate.consecutiveOK[0] = 0

	roundCtx, cancel := context.WithTimeout(context.Background(), 80*time.Millisecond)
	_, _, err := grp.urlTest(roundCtx, true)
	cancel()
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected budget expiry, got %v", err)
	}
	if got := grp.tierGate.consecutiveOK[0]; got != 0 {
		t.Fatalf("a round with zero completed probes must not advance the tier gate, got %d healthy rounds", got)
	}
}
