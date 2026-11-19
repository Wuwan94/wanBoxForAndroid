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

// preview.4 把候选限定在当前生效层，导致高层历史仍新鲜但实际建连已失败时，
// 健康低层完全不会被尝试。向下容灾是数据面连通性恢复，不等于向上回切。
func TestFailoverUsesLowerTierAfterFreshPrimaryDialFailure(t *testing.T) {
	primary := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "a [T1]", []string{"tcp", "udp"}, nil), dead: true}
	lower := &stubDialOutbound{Adapter: outbound.NewAdapter("loopback", "b [T2]", []string{"tcp", "udp"}, nil)}
	grp := tierGroup(t, primary, lower)
	storeTierHistory(t, grp, primary.Tag(), 100)
	storeTierHistory(t, grp, lower.Tag(), 90)
	grp.currentTier = 0
	grp.selectedOutboundTCP = primary
	close(grp.startupDone)

	u := &URLTest{group: grp, logger: log.NewNOPFactory().Logger()}
	conn, err := u.DialContext(context.Background(), N.NetworkTCP, M.ParseSocksaddrHostPort("example.com", 443))
	if err != nil {
		t.Fatalf("expected actual dial failure to fall down to healthy tier 2, got %v", err)
	}
	_ = conn.Close()
	if lower.calls.Load() != 1 {
		t.Fatalf("expected one lower-tier attempt, got %d", lower.calls.Load())
	}
	if grp.selectedOutboundTCP != lower {
		t.Fatalf("expected the reachable lower-tier member to be promoted, got %v", grp.selectedOutboundTCP)
	}
}

// 向下容灾与向上回切必须分开裁决：当前层实际建连失败后切到低层，
// 高层历史仍新鲜也不得让下一次择优立刻跳回去；回切只能靠连续新观测开门。
func TestFailoverDowngradeKeepsSelectionUntilGateOpens(t *testing.T) {
	primary := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "a [T1]", []string{"tcp", "udp"}, nil), dead: true}
	lower := &stubDialOutbound{Adapter: outbound.NewAdapter("loopback", "b [T2]", []string{"tcp", "udp"}, nil)}
	grp := tierGroup(t, primary, lower)
	storeTierHistory(t, grp, primary.Tag(), 100)
	storeTierHistory(t, grp, lower.Tag(), 90)
	grp.selectedOutboundTCP = primary
	close(grp.startupDone)

	u := &URLTest{group: grp, logger: log.NewNOPFactory().Logger()}
	conn, err := u.DialContext(context.Background(), N.NetworkTCP, M.ParseSocksaddrHostPort("example.com", 443))
	if err != nil {
		t.Fatalf("expected lower-tier failover, got %v", err)
	}
	_ = conn.Close()
	if grp.currentTier != 1 {
		t.Fatalf("downward failover must move currentTier to the working lower tier, got %d", grp.currentTier)
	}

	// 一轮只有低层的新观测：高层计数不涨，选择也不得回到高层。
	grp.tierGate.lastFailure[0] = time.Now().Add(-61 * time.Second)
	grp.performRoundEndCheck(newObservedRound(observedOK("b [T2]")))
	if selected, _ := grp.Select(N.NetworkTCP); selected == nil || selected.Tag() != "b [T2]" {
		t.Fatalf("fresh high-tier history must not undo a downward failover, got %v", selected)
	}

	// 高层连续 3 轮真实新成功 + 60 秒无失败：此时才允许回切。
	for i := 0; i < tierSwitchBackMinChecks; i++ {
		grp.performRoundEndCheck(newObservedRound(observedOK("a [T1]"), observedOK("b [T2]")))
	}
	if got := grp.tierGate.consecutiveOK[0]; got != tierSwitchBackMinChecks {
		t.Fatalf("expected %d observed high-tier rounds, got %d", tierSwitchBackMinChecks, got)
	}
	selected, healthy := grp.Select(N.NetworkTCP)
	if selected == nil || selected.Tag() != "a [T1]" || !healthy {
		t.Fatalf("expected gated switch-back after observed recovery, got %v healthy=%v", selected, healthy)
	}
}

func TestFailoverTriesSameTierBeforeMultipleLowerTiers(t *testing.T) {
	t.Run("same tier first", func(t *testing.T) {
		primary := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "a [T1]", []string{"tcp", "udp"}, nil), dead: true}
		sameTier := &stubDialOutbound{Adapter: outbound.NewAdapter("loopback", "b [T1]", []string{"tcp", "udp"}, nil)}
		lower := &stubDialOutbound{Adapter: outbound.NewAdapter("loopback", "c [T2]", []string{"tcp", "udp"}, nil)}
		grp := tierGroup(t, primary, sameTier, lower)
		for _, member := range []adapter.Outbound{primary, sameTier, lower} {
			storeTierHistory(t, grp, member.Tag(), 100)
		}
		grp.selectedOutboundTCP = primary
		close(grp.startupDone)
		u := &URLTest{group: grp, logger: log.NewNOPFactory().Logger()}
		conn, err := u.DialContext(context.Background(), N.NetworkTCP, M.ParseSocksaddrHostPort("example.com", 443))
		if err != nil {
			t.Fatal(err)
		}
		_ = conn.Close()
		if sameTier.calls.Load() != 1 || lower.calls.Load() != 0 {
			t.Fatalf("same-tier candidate must win before lower tiers, same=%d lower=%d", sameTier.calls.Load(), lower.calls.Load())
		}
	})

	t.Run("multiple lower tiers", func(t *testing.T) {
		primary := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "a [T1]", []string{"tcp", "udp"}, nil), dead: true}
		lowerDead := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "b [T2]", []string{"tcp", "udp"}, nil), dead: true}
		lowestAlive := &stubDialOutbound{Adapter: outbound.NewAdapter("loopback", "c [T3]", []string{"tcp", "udp"}, nil)}
		grp := tierGroup(t, primary, lowerDead, lowestAlive)
		for _, member := range []adapter.Outbound{primary, lowerDead, lowestAlive} {
			storeTierHistory(t, grp, member.Tag(), 100)
		}
		grp.selectedOutboundTCP = primary
		close(grp.startupDone)
		u := &URLTest{group: grp, logger: log.NewNOPFactory().Logger()}
		conn, err := u.DialContext(context.Background(), N.NetworkTCP, M.ParseSocksaddrHostPort("example.com", 443))
		if err != nil {
			t.Fatalf("expected failover to walk through tier 2 to tier 3, got %v", err)
		}
		_ = conn.Close()
		if lowerDead.calls.Load() != 1 || lowestAlive.calls.Load() != 1 {
			t.Fatalf("expected each lower tier to be tried once in order, tier2=%d tier3=%d", lowerDead.calls.Load(), lowestAlive.calls.Load())
		}
	})
}

func TestFailoverAllTiersFailureReturnsPrimaryError(t *testing.T) {
	primary := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "a [T1]", []string{"tcp", "udp"}, nil), dead: true}
	tier2 := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "b [T2]", []string{"tcp", "udp"}, nil), dead: true}
	tier3 := &startupOutbound{Adapter: outbound.NewAdapter("loopback", "c [T3]", []string{"tcp", "udp"}, nil), dead: true}
	grp := tierGroup(t, primary, tier2, tier3)
	for _, member := range []adapter.Outbound{primary, tier2, tier3} {
		storeTierHistory(t, grp, member.Tag(), 100)
	}
	grp.selectedOutboundTCP = primary
	close(grp.startupDone)
	u := &URLTest{group: grp, logger: log.NewNOPFactory().Logger()}
	conn, err := u.DialContext(context.Background(), N.NetworkTCP, M.ParseSocksaddrHostPort("example.com", 443))
	if err == nil || conn != nil {
		t.Fatalf("expected all failed candidates to return the primary error, conn=%v err=%v", conn, err)
	}
	if primary.calls.Load() != 1 || tier2.calls.Load() != 1 || tier3.calls.Load() != 1 {
		t.Fatalf("expected one bounded attempt per member, got %d/%d/%d", primary.calls.Load(), tier2.calls.Load(), tier3.calls.Load())
	}
	if grp.selectedOutboundTCP != primary {
		t.Fatalf("all-failed failover must not change selection, got %v", grp.selectedOutboundTCP)
	}
}

func TestTieredBlackholeFailsDownWithinSingleStepTimeout(t *testing.T) {
	for _, network := range []string{N.NetworkTCP, N.NetworkUDP} {
		t.Run(network, func(t *testing.T) {
			primary := &blackholeOutbound{Adapter: outbound.NewAdapter("loopback", "a [T1]", []string{"tcp", "udp"}, nil)}
			lower := &stubDialOutbound{Adapter: outbound.NewAdapter("loopback", "b [T2]", []string{"tcp", "udp"}, nil)}
			grp := tierGroup(t, primary, lower)
			storeTierHistory(t, grp, primary.Tag(), 100)
			storeTierHistory(t, grp, lower.Tag(), 90)
			grp.selectedOutboundTCP = primary
			grp.selectedOutboundUDP = primary
			close(grp.startupDone)
			u := &URLTest{group: grp, logger: log.NewNOPFactory().Logger()}
			ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
			defer cancel()
			start := time.Now()
			var err error
			if network == N.NetworkTCP {
				var conn interface{ Close() error }
				conn, err = u.DialContext(ctx, network, M.ParseSocksaddrHostPort("example.com", 443))
				if conn != nil {
					_ = conn.Close()
				}
			} else {
				conn, listenErr := u.ListenPacket(ctx, M.ParseSocksaddrHostPort("example.com", 443))
				err = listenErr
				if conn != nil {
					_ = conn.Close()
				}
			}
			if err != nil {
				t.Fatalf("expected lower-tier failover after single-step timeout, got %v", err)
			}
			if elapsed := time.Since(start); elapsed < urlTestDialAttemptTimeout || elapsed > 6*time.Second {
				t.Fatalf("expected one 3s attempt before lower-tier failover, elapsed=%s", elapsed)
			}
			if primary.timeouts.Load() != 1 || lower.calls.Load() != 1 {
				t.Fatalf("expected one primary timeout and one lower-tier attempt, got %d/%d", primary.timeouts.Load(), lower.calls.Load())
			}
			if ctx.Err() != nil {
				t.Fatalf("single-step timeout must not exhaust the caller context: %v", ctx.Err())
			}
		})
	}
}

func TestTieredFailoverStopsOnCallerCancellation(t *testing.T) {
	for _, network := range []string{N.NetworkTCP, N.NetworkUDP} {
		t.Run(network, func(t *testing.T) {
			primary := &blackholeOutbound{Adapter: outbound.NewAdapter("loopback", "a [T1]", []string{"tcp", "udp"}, nil)}
			lower := &stubDialOutbound{Adapter: outbound.NewAdapter("loopback", "b [T2]", []string{"tcp", "udp"}, nil)}
			grp := tierGroup(t, primary, lower)
			storeTierHistory(t, grp, primary.Tag(), 100)
			storeTierHistory(t, grp, lower.Tag(), 90)
			grp.selectedOutboundTCP = primary
			grp.selectedOutboundUDP = primary
			close(grp.startupDone)
			u := &URLTest{group: grp, logger: log.NewNOPFactory().Logger()}
			ctx, cancel := context.WithCancel(context.Background())
			time.AfterFunc(50*time.Millisecond, cancel)
			defer cancel()
			start := time.Now()
			var err error
			if network == N.NetworkTCP {
				_, err = u.DialContext(ctx, network, M.ParseSocksaddrHostPort("example.com", 443))
			} else {
				_, err = u.ListenPacket(ctx, M.ParseSocksaddrHostPort("example.com", 443))
			}
			if !errors.Is(err, context.Canceled) {
				t.Fatalf("expected caller cancellation, got %v", err)
			}
			if time.Since(start) > time.Second {
				t.Fatalf("cancellation did not stop failover promptly: %s", time.Since(start))
			}
			if lower.calls.Load() != 0 {
				t.Fatalf("no lower-tier attempt may start after caller cancellation, got %d", lower.calls.Load())
			}
		})
	}
}

func TestCachedOnlyRoundsDoNotAdvanceHigherTier(t *testing.T) {
	grp := tierGroup(t, tierMember("a [T1]"), tierMember("b [T2]"))
	storeTierHistory(t, grp, "a [T1]", 100)
	storeTierHistory(t, grp, "b [T2]", 90)
	grp.currentTier = 1
	grp.selectedOutboundTCP = grp.outbounds[1]
	grp.selectedOutboundUDP = grp.outbounds[1]
	grp.tierGate.lastFailure[0] = time.Now().Add(-61 * time.Second)
	for i := 0; i < tierSwitchBackMinChecks; i++ {
		_, round, err := grp.urlTest(context.Background(), false)
		if err != nil {
			t.Fatalf("cached round %d: %v", i+1, err)
		}
		if round == nil || round.probed() != 0 {
			t.Fatalf("cached round %d unexpectedly completed probes", i+1)
		}
		observations := round.snapshot()
		if observations["a [T1]"] != urlTestSkipped || observations["b [T2]"] != urlTestSkipped {
			t.Fatalf("cached round %d must record both members as skipped, got %v", i+1, observations)
		}
	}
	if got := grp.tierGate.consecutiveOK[0]; got != 0 {
		t.Fatalf("three cache-only rounds must not count as new high-tier successes, got %d", got)
	}
	selected, _ := grp.Select(N.NetworkTCP)
	if selected == nil || selected.Tag() != "b [T2]" {
		t.Fatalf("cache-only rounds must not switch back early, got %v", selected)
	}
}

func TestBudgetExpiryWithOnlyLowerTierProgressDoesNotSwitchBack(t *testing.T) {
	blockedServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-r.Context().Done()
	}))
	defer blockedServer.Close()
	fastServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer fastServer.Close()
	grp := startupGroupWithMembers(t, context.Background(), blockedServer.URL,
		&blackholeOutbound{Adapter: outbound.NewAdapter("loopback", "a [T1]", []string{"tcp", "udp"}, nil)},
		&delayedDialOutbound{Adapter: outbound.NewAdapter("loopback", "b [T2]", []string{"tcp", "udp"}, nil), server: fastServer},
	)
	storeTierHistory(t, grp, "b [T2]", 90)
	grp.performRoundEndCheck(newObservedRound(observedFailed("a [T1]"), observedOK("b [T2]")))
	storeTierHistory(t, grp, "a [T1]", 100)
	grp.tierGate.lastFailure[0] = time.Now().Add(-61 * time.Second)
	for i := 0; i < tierSwitchBackMinChecks; i++ {
		roundCtx, cancel := context.WithTimeout(context.Background(), 120*time.Millisecond)
		_, round, err := grp.urlTest(roundCtx, true)
		cancel()
		if !errors.Is(err, context.DeadlineExceeded) {
			t.Fatalf("round %d: expected budget expiry, got %v", i+1, err)
		}
		if round == nil || round.probed() == 0 {
			t.Fatalf("round %d: expected lower-tier progress", i+1)
		}
		if got := round.snapshot()["a [T1]"]; got != urlTestCanceled {
			t.Fatalf("round %d: the high tier must stay unobserved, got %v", i+1, got)
		}
	}
	if got := grp.tierGate.consecutiveOK[0]; got != 0 {
		t.Fatalf("lower-tier probes must not advance the unobserved higher tier, got %d", got)
	}
	selected, _ := grp.Select(N.NetworkTCP)
	if selected == nil || selected.Tag() != "b [T2]" {
		t.Fatalf("unobserved higher tier must not switch back, got %v", selected)
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
