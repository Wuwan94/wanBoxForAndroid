// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package urltest

import (
	"context"
	"net/http"
	"net/http/httptest"
	"reflect"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter/outbound"
	N "github.com/sagernet/sing/common/network"
)

// 不拨号的层级用例用 newObservedRound 显式声明“本轮发生了什么”。
// 生产路径的观测只能由真实探测写入，测试不会伪造历史冒充新成功。
// 每个观测都带网络：一轮里 TCP 与 UDP 的结局可以完全不同。
type testObservation struct {
	tag     string
	network string
	state   urlTestObservation
}

func observedOK(tag string) testObservation      { return testObservation{tag, "tcp", urlTestSuccess} }
func observedFailed(tag string) testObservation  { return testObservation{tag, "tcp", urlTestFailure} }
func observedSkipped(tag string) testObservation { return testObservation{tag, "tcp", urlTestSkipped} }
func observedCanceled(tag string) testObservation {
	return testObservation{tag, "tcp", urlTestCanceled}
}

func observedOKUDP(tag string) testObservation { return testObservation{tag, "udp", urlTestSuccess} }
func observedFailedUDP(tag string) testObservation {
	return testObservation{tag, "udp", urlTestFailure}
}
func observedSkippedUDP(tag string) testObservation {
	return testObservation{tag, "udp", urlTestSkipped}
}

func newObservedRound(entries ...testObservation) *urlTestRound {
	round := newURLTestRound()
	for _, entry := range entries {
		round.record(entry.tag, entry.network, entry.state)
		if entry.state == urlTestSuccess || entry.state == urlTestFailure {
			round.addProbed(1)
		}
	}
	return round
}

// 同一轮里嵌套组与直接成员重复上报同一叶子 tag 时，弱观测不得覆盖强观测。
func TestRoundRecordKeepsStrongestObservation(t *testing.T) {
	round := newURLTestRound()
	round.record("x", "tcp", urlTestSuccess)
	round.record("x", "tcp", urlTestCanceled)
	round.record("x", "tcp", urlTestSkipped)
	if got := round.snapshot()["x"]; got != urlTestSuccess {
		t.Fatalf("late weak observations overwrote a success, got %v", got)
	}
	round.record("y", "tcp", urlTestCanceled)
	round.record("y", "tcp", urlTestFailure)
	if got := round.snapshot()["y"]; got != urlTestFailure {
		t.Fatalf("a new failure must replace a canceled observation, got %v", got)
	}
	round.record("z", "tcp", urlTestSkipped)
	round.record("z", "tcp", urlTestCanceled)
	if got := round.snapshot()["z"]; got != urlTestSkipped {
		t.Fatalf("skipped and canceled are equally neutral, got %v", got)
	}
	// 同一 tag 的两个网络互不覆盖：TCP 失败不得冲掉 UDP 成功。
	round.record("w", "tcp", urlTestFailure)
	round.record("w", "udp", urlTestSuccess)
	if got := round.snapshotNetwork("tcp")["w"]; got != urlTestFailure {
		t.Fatalf("tcp observation got %v, want failure", got)
	}
	if got := round.snapshotNetwork("udp")["w"]; got != urlTestSuccess {
		t.Fatalf("udp observation got %v, want success", got)
	}
}

// 恢复门的不变量：新成功 +1、新失败清零并记失败时间、跳过/取消/未观测保持原状；
// 同一轮里高层成功与低层失败互不借用。
func TestTierGateObservationInvariants(t *testing.T) {
	grp := tierGroup(t, tierMember("a [T1]"), tierMember("b [T2]"))

	grp.performRoundEndCheck(newObservedRound(observedOK("a [T1]"), observedFailed("b [T2]")))
	if grp.tierTCP.gate.consecutiveOK[0] != 1 || grp.tierTCP.gate.consecutiveOK[1] != 0 {
		t.Fatalf("mixed round: want tier1=1 tier2=0, got %d/%d", grp.tierTCP.gate.consecutiveOK[0], grp.tierTCP.gate.consecutiveOK[1])
	}
	if _, failed := grp.tierTCP.gate.lastFailure[1]; !failed {
		t.Fatal("low-tier failure must record its failure time")
	}

	grp.performRoundEndCheck(newObservedRound(observedSkipped("a [T1]"), observedSkipped("b [T2]")))
	if grp.tierTCP.gate.consecutiveOK[0] != 1 || grp.tierTCP.gate.consecutiveOK[1] != 0 {
		t.Fatalf("cache-skipped round must be neutral, got %d/%d", grp.tierTCP.gate.consecutiveOK[0], grp.tierTCP.gate.consecutiveOK[1])
	}

	grp.performRoundEndCheck(newObservedRound(observedCanceled("a [T1]"), observedOK("b [T2]")))
	if grp.tierTCP.gate.consecutiveOK[0] != 1 || grp.tierTCP.gate.consecutiveOK[1] != 1 {
		t.Fatalf("canceled high tier must not advance: got %d/%d", grp.tierTCP.gate.consecutiveOK[0], grp.tierTCP.gate.consecutiveOK[1])
	}

	// 高层完全没有条目（未观测），低层失败：高层计数既不加也不清。
	grp.performRoundEndCheck(newObservedRound(observedFailed("b [T2]")))
	if grp.tierTCP.gate.consecutiveOK[0] != 1 || grp.tierTCP.gate.consecutiveOK[1] != 0 {
		t.Fatalf("unobserved high tier must stay untouched: got %d/%d", grp.tierTCP.gate.consecutiveOK[0], grp.tierTCP.gate.consecutiveOK[1])
	}

	before := time.Now()
	grp.performRoundEndCheck(newObservedRound(observedFailed("a [T1]"), observedOK("b [T2]")))
	if grp.tierTCP.gate.consecutiveOK[0] != 0 {
		t.Fatalf("a new high-tier failure must reset consecutiveOK, got %d", grp.tierTCP.gate.consecutiveOK[0])
	}
	if last, failed := grp.tierTCP.gate.lastFailure[0]; !failed || last.Before(before) {
		t.Fatalf("a new high-tier failure must refresh lastFailure, got %v", last)
	}

	// 成员同时支持两个网络时，TCP 探测只推进 TCP 门：UDP 没有自己的新观测，
	// 两侧不再“完全一致”——那正是本版修掉的跨协议冒充。
	if got := grp.tierUDP.gate.consecutiveOK[0]; got != 0 {
		t.Fatalf("TCP-only observations must not advance the UDP gate, got %d", got)
	}
	if _, failed := grp.tierUDP.gate.lastFailure[0]; failed {
		t.Fatal("TCP failures must not stamp the UDP gate")
	}
}

// 一轮里高层成功、低层失败必须分别裁决；两者都不许借用对方的观测。
func TestMixedTierObservationsInOneRound(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	grp := startupGroupWithMembers(t, context.Background(), server.URL,
		&delayedDialOutbound{Adapter: outbound.NewAdapter("loopback", "a [T1]", []string{"tcp", "udp"}, nil), server: server},
		&startupOutbound{Adapter: outbound.NewAdapter("loopback", "b [T2]", []string{"tcp", "udp"}, nil), dead: true},
	)
	_, round, err := grp.urlTest(context.Background(), true)
	if err != nil {
		t.Fatalf("mixed round: %v", err)
	}
	observations := round.snapshot()
	if observations["a [T1]"] != urlTestSuccess || observations["b [T2]"] != urlTestFailure {
		t.Fatalf("expected high=success low=failure, got %v", observations)
	}
	if round.probed() != 4 {
		// 两个成员都是双栈：每人一次 TCP 探测 + 一次 UDP 关联，共 4 个完结观测。
		t.Fatalf("expected four completed observations (2 members × TCP/UDP), got %d", round.probed())
	}
	if grp.tierTCP.gate.consecutiveOK[0] != 1 || grp.tierTCP.gate.consecutiveOK[1] != 0 {
		t.Fatalf("mixed round gate: got %d/%d", grp.tierTCP.gate.consecutiveOK[0], grp.tierTCP.gate.consecutiveOK[1])
	}
	if _, failed := grp.tierTCP.gate.lastFailure[1]; !failed {
		t.Fatal("low-tier failure time must be recorded")
	}
}

// 预算到期后底层探测 goroutine 可能仍在上报；轮次对象必须闭合，
// 迟到结果不得写进上一轮，也不得让下一轮的高层凭空累计健康轮。
func TestRoundObservationsDoNotLeakAcrossRounds(t *testing.T) {
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
	grp.tierTCP.gate.lastFailure[0] = time.Now().Add(-61 * time.Second)

	runExpiredRound := func() *urlTestRound {
		t.Helper()
		roundCtx, cancel := context.WithTimeout(context.Background(), 120*time.Millisecond)
		defer cancel()
		_, round, err := grp.urlTest(roundCtx, true)
		if err == nil {
			t.Fatal("expected the round budget to expire")
		}
		if round == nil || round.probed() == 0 {
			t.Fatal("expected the lower-tier member to complete a probe")
		}
		return round
	}

	first := runExpiredRound()
	firstSnapshot := first.snapshot()
	if firstSnapshot["a [T1]"] != urlTestCanceled || firstSnapshot["b [T2]"] != urlTestSuccess {
		t.Fatalf("unexpected first-round observations: %v", firstSnapshot)
	}
	// 等被取消的底层探测 goroutine 全部退出，再确认没有跨轮写入。
	time.Sleep(250 * time.Millisecond)
	if !reflect.DeepEqual(firstSnapshot, first.snapshot()) {
		t.Fatalf("late result leaked into the closed round: before=%v after=%v", firstSnapshot, first.snapshot())
	}
	if grp.tierTCP.gate.consecutiveOK[0] != 0 || grp.tierTCP.gate.consecutiveOK[1] != 1 {
		t.Fatalf("first round gate: got %d/%d", grp.tierTCP.gate.consecutiveOK[0], grp.tierTCP.gate.consecutiveOK[1])
	}

	second := runExpiredRound()
	if second == first {
		t.Fatal("each probe round must own a fresh observation set")
	}
	if grp.tierTCP.gate.consecutiveOK[0] != 0 || grp.tierTCP.gate.consecutiveOK[1] != 2 {
		t.Fatalf("second round must not resurrect the unobserved high tier: %d/%d", grp.tierTCP.gate.consecutiveOK[0], grp.tierTCP.gate.consecutiveOK[1])
	}
	selected, _ := grp.Select(N.NetworkTCP)
	if selected == nil || selected.Tag() != "b [T2]" {
		t.Fatalf("unobserved high tier must not switch back, got %v", selected)
	}
}

// 并发检查合并到同一次 in-flight 轮次：leader 只收尾一次，joiner 不重复计数。
func TestConcurrentChecksShareOneRound(t *testing.T) {
	entered := make(chan struct{}, 4)
	release := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case entered <- struct{}{}:
		default:
		}
		<-release
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	grp := startupGroupWithMembers(t, context.Background(), server.URL,
		&delayedDialOutbound{Adapter: outbound.NewAdapter("loopback", "a [T1]", []string{"tcp", "udp"}, nil), server: server},
		&delayedDialOutbound{Adapter: outbound.NewAdapter("loopback", "b [T2]", []string{"tcp", "udp"}, nil), server: server},
	)

	leaderDone := make(chan *urlTestRound, 1)
	go func() {
		_, round, _ := grp.urlTest(context.Background(), true)
		leaderDone <- round
	}()
	select {
	case <-entered:
	case <-time.After(2 * time.Second):
		t.Fatal("leader probe did not start")
	}

	const joiners = 3
	joined := make([]*urlTestRound, joiners)
	var wg sync.WaitGroup
	for i := 0; i < joiners; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			_, round, _ := grp.urlTest(context.Background(), true)
			joined[i] = round
		}(i)
	}
	close(release)
	leader := <-leaderDone
	wg.Wait()
	if leader == nil || leader.probed() != 4 {
		// 两个双栈成员 × (TCP 探测 + UDP 关联)：共享轮只收尾一次，计数不重复。
		t.Fatalf("expected four completed observations in the shared round, got %v", leader)
	}
	for i, round := range joined {
		if round != leader {
			t.Fatalf("joiner %d did not share the leader round", i)
		}
	}
	if got := grp.tierTCP.gate.consecutiveOK[0]; got != 1 {
		t.Fatalf("the shared round must advance the high tier exactly once, got %d", got)
	}
}
