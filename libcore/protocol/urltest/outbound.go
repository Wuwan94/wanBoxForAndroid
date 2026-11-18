// @author 雾晚
package urltest

import (
	"context"
	"errors"
	"io"
	"maps"
	"net"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/interrupt"
	"github.com/sagernet/sing-box/common/urltest"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/protocol/group"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/batch"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/json/badoption"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/x/list"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

// @author 雾晚: 每轮测速的总预算硬上限；启动、手动与定时轮次共用，成员多或全部超时也不让一轮探测无限拖长。
const probeBudget = 5 * time.Second

// B1/O3: 单步建连超时取 3 秒——用户已拍板（与 wanBox 现有探针超时对齐，
// 不用上游 8.13 的动态 2–4.5 秒）。仅用于“还有后备候选可试”的尝试；
// 最后一个候选不限时，避免单节点组里一次慢握手被直接掐断。
const urlTestDialAttemptTimeout = 3 * time.Second

// B1/O3: 单次探测惩罚增量（沿用 wanBox 既有 +300，不照抄上游的 5000 下限）。
const urlTestProbePenalty = uint16(300)

// B7c/O7: 层级 tag 后缀约定（与 Kotlin 侧 ConfigBuilder.tierGroupTag 同一格式）。
// 层级组 tag 以 " [T<n>]"（n 从 1 计）或 " [FB]"（保底层级）结尾；
// Go 侧据此解析层级。保底层级序号取很大的哨兵值，永远排在最后。
const tierFallbackIndex = 1 << 30

// B7c/O7 回切双重门槛：与 Kotlin 侧 TierRecoveryGate 同一取值
// （连续 3 轮健康检查 + 距上次失败满 60 秒）。
const tierSwitchBackMinChecks = 3
const tierSwitchBackMinFailureFree = 60 * time.Second

// parseTierTagIndex 解析层级 tag 后缀。返回 (层级序号, 是否分层 tag)。
// "Balancer 01 [T1]" → (0, true)；"Balancer 01 [T2]" → (1, true)；
// "Balancer 01 [FB]" → (tierFallbackIndex, true)。
// 无后缀、序号非法（0 或非数字）→ (0, false)，按未分层处理。
func parseTierTagIndex(tag string) (int, bool) {
	if strings.HasSuffix(tag, " [FB]") {
		return tierFallbackIndex, true
	}
	if !strings.HasSuffix(tag, "]") {
		return 0, false
	}
	open := strings.LastIndex(tag, " [T")
	if open < 0 {
		return 0, false
	}
	numStr := tag[open+3 : len(tag)-1]
	n, err := strconv.Atoi(numStr)
	if err != nil || n < 1 {
		return 0, false
	}
	return n - 1, true
}

// B1/O3: 惩罚延迟的饱和封顶值。
const urlTestMaxPenalizedDelay = uint16(65000)

// penalizeURLTestDelay 用 uint32 求和后饱和封顶，MUST NOT 发生 uint16 回绕。
func penalizeURLTestDelay(base, delta uint16) uint16 {
	sum := uint32(base) + uint32(delta)
	if sum > uint32(urlTestMaxPenalizedDelay) {
		return urlTestMaxPenalizedDelay
	}
	return uint16(sum)
}

// penalizeOrDeleteHistory 是单次探测失败的写回策略：历史仍在 3*interval 新鲜
// 窗口内时只记惩罚（延迟饱和封顶、Time 按既有策略续期为当前时间），过期历史直接删除。
// 注意：续期 Time 是 wanBox 既有测试 TestMemberTimeoutStillPenalizesRecentHistory
// 明确要求的失败策略（got.Time.After(before.Time)），与上游 8.13“保持旧 Time”
// 不同；按“自家约束优先”保留续期，B1 只修真正缺陷——Delay+300 的 uint16 回绕。
func penalizeOrDeleteHistory(history *urltest.HistoryStorage, tag string, interval time.Duration) {
	oldHist := history.LoadURLTestHistory(tag)
	if oldHist != nil && time.Since(oldHist.Time) < 3*interval {
		history.StoreURLTestHistory(tag, &adapter.URLTestHistory{
			Time:  time.Now(),
			Delay: penalizeURLTestDelay(oldHist.Delay, urlTestProbePenalty),
		})
	} else {
		history.DeleteURLTestHistory(tag)
	}
}

type URLTestOptions struct {
	Outbounds                 []string           `json:"outbounds" reference:"outbound"`
	URL                       string             `json:"url,omitempty"`
	Interval                  badoption.Duration `json:"interval,omitempty"`
	Tolerance                 *uint16            `json:"tolerance,omitempty"`
	IdleTimeout               badoption.Duration `json:"idle_timeout,omitempty"`
	InterruptExistConnections bool               `json:"interrupt_exist_connections,omitempty"`
}

func RegisterURLTest(registry *outbound.Registry) {
	outbound.Register[URLTestOptions](registry, C.TypeURLTest, NewURLTest)
}

var (
	_ adapter.Lifecycle               = (*URLTest)(nil)
	_ adapter.OutboundGroup           = (*URLTest)(nil)
	_ adapter.InterfaceUpdateListener = (*URLTest)(nil)
	_ adapter.Referrer                = (*URLTest)(nil)
)

type URLTest struct {
	outbound.Adapter
	ctx                          context.Context
	outbound                     adapter.OutboundManager
	connection                   adapter.ConnectionManager
	logger                       log.ContextLogger
	tags                         []string
	link                         string
	interval                     time.Duration
	tolerance                    uint16
	idleTimeout                  time.Duration
	closed                       atomic.Bool
	group                        *URLTestGroup
	checkAccess                  sync.Mutex
	lastInterfaceCheck           atomic.Int64
	interruptExternalConnections bool
}

func NewURLTest(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options URLTestOptions) (adapter.Outbound, error) {
	tolerance := uint16(50)
	if options.Tolerance != nil {
		tolerance = *options.Tolerance
	}
	interval := time.Duration(options.Interval)
	if interval == 0 {
		interval = C.DefaultURLTestInterval
	}
	idleTimeout := time.Duration(options.IdleTimeout)
	if idleTimeout == 0 {
		idleTimeout = C.DefaultURLTestIdleTimeout
	}
	outbound := &URLTest{
		Adapter:                      outbound.NewAdapter(C.TypeURLTest, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.Outbounds),
		ctx:                          ctx,
		outbound:                     service.FromContext[adapter.OutboundManager](ctx),
		connection:                   service.FromContext[adapter.ConnectionManager](ctx),
		logger:                       logger,
		tags:                         options.Outbounds,
		link:                         options.URL,
		interval:                     interval,
		tolerance:                    tolerance,
		idleTimeout:                  idleTimeout,
		interruptExternalConnections: options.InterruptExistConnections,
	}
	if len(outbound.tags) == 0 {
		return nil, E.New("missing tags")
	}
	return outbound, nil
}

// @author 雾晚: participate in the core's staged lifecycle and scoped rollback.
func (s *URLTest) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	switch stage {
	case adapter.StartStateStart:
		s.ctx = scope.Context()
		scope.Add(s.Close)
		return s.initialize()
	case adapter.StartStateStarted:
		return s.PostStart()
	}
	return nil
}

func (s *URLTest) initialize() error {
	outbounds := make([]adapter.Outbound, 0, len(s.tags))
	for i, tag := range s.tags {
		detour, loaded := s.outbound.Outbound(tag)
		if !loaded {
			return E.New("outbound ", i, " not found: ", tag)
		}
		outbounds = append(outbounds, detour)
	}
	grp, err := NewURLTestGroup(s.ctx, s.outbound, s.logger, outbounds, s.link, s.interval, s.tolerance, s.idleTimeout, s.interruptExternalConnections)
	if err != nil {
		return err
	}
	s.group = grp
	return nil
}

func (s *URLTest) PostStart() error {
	if s.group == nil || s.closed.Load() {
		return E.New("URL test group not started or closed")
	}
	s.group.PostStart()
	return nil
}

func (s *URLTest) Close() error {
	s.closed.Store(true)
	return common.Close(
		common.PtrOrNil(s.group),
	)
}

func (s *URLTest) Now() string {
	if s.group == nil {
		return ""
	}
	s.group.updateAccess.RLock()
	defer s.group.updateAccess.RUnlock()
	if s.group.selectedOutboundTCP != nil {
		return s.group.selectedOutboundTCP.Tag()
	} else if s.group.selectedOutboundUDP != nil {
		return s.group.selectedOutboundUDP.Tag()
	}
	return ""
}

func (s *URLTest) All() []string {
	return s.tags
}

func (s *URLTest) Selected(network string) adapter.Outbound {
	group := s.group
	if group == nil {
		return nil
	}
	outbound := group.selectedFresh(network)
	if outbound == nil {
		outbound, _ = s.group.Select(network)
	}
	return outbound
}

func (s *URLTest) AttachConnection(closer io.Closer) func() {
	if s.group == nil || s.closed.Load() {
		_ = closer.Close()
		return func() {}
	}
	s.group.Touch()
	return s.group.interruptGroup.Add(closer, true)
}

func (s *URLTest) References() []string {
	return s.tags
}

func (s *URLTest) URLTest(ctx context.Context) (map[string]uint16, error) {
	if s.group == nil || s.closed.Load() {
		return nil, E.New("URL test group not started or closed")
	}
	return s.group.URLTest(ctx)
}

func (s *URLTest) CheckOutbounds() {
	if s.group == nil || s.closed.Load() {
		return
	}
	s.group.CheckOutbounds(s.ctx, true)
}

func (s *URLTest) PerformUpdateCheck() {
	if s.group == nil || s.closed.Load() {
		return
	}
	s.group.performUpdateCheck()
}

func (s *URLTest) InterfaceUpdated(ctx context.Context) {
	grp := s.group
	if grp == nil || s.closed.Load() {
		return
	}
	if grp.pause.IsDevicePaused() || grp.pause.IsNetworkPaused() {
		return
	}
	go func() {
		s.checkAccess.Lock()
		defer s.checkAccess.Unlock()
		if ctx.Err() != nil {
			return
		}
		now := time.Now().Unix()
		last := s.lastInterfaceCheck.Load()
		if now-last < 30 {
			return
		}
		s.lastInterfaceCheck.Store(now)
		grp.CheckOutbounds(ctx, false)
	}()
}

// dialOne 尝试单个候选。bounded 为真（还有后备候选可试）时施加 3 秒单步超时，
// 黑洞候选超时后计入该候选的连续失败；立即失败与父上下文取消不计入。
// 成功时复位该候选的连续失败计数。
func (s *URLTest) dialOne(ctx context.Context, bounded bool, detour adapter.Outbound, network string, destination M.Socksaddr) (net.Conn, error) {
	attemptCtx := ctx
	var cancel context.CancelFunc
	if bounded {
		attemptCtx, cancel = context.WithTimeout(ctx, urlTestDialAttemptTimeout)
		defer cancel()
	}
	conn, err := detour.DialContext(attemptCtx, network, destination)
	if err == nil {
		s.group.resetMemberFailure(detour.Tag())
		return conn, nil
	}
	if bounded && attemptCtx.Err() != nil && ctx.Err() == nil {
		s.group.applyMemberFailure(detour.Tag())
	}
	return nil, err
}

// listenOne 是 dialOne 的 UDP 对应物。
func (s *URLTest) listenOne(ctx context.Context, bounded bool, detour adapter.Outbound, destination M.Socksaddr) (net.PacketConn, error) {
	attemptCtx := ctx
	var cancel context.CancelFunc
	if bounded {
		attemptCtx, cancel = context.WithTimeout(ctx, urlTestDialAttemptTimeout)
		defer cancel()
	}
	conn, err := detour.ListenPacket(attemptCtx, destination)
	if err == nil {
		s.group.resetMemberFailure(detour.Tag())
		return conn, nil
	}
	if bounded && attemptCtx.Err() != nil && ctx.Err() == nil {
		s.group.applyMemberFailure(detour.Tag())
	}
	return nil, err
}

func (s *URLTest) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if s.group == nil || s.closed.Load() {
		return nil, E.New("URL test group not started or closed")
	}
	networkName := N.NetworkName(network)
	switch networkName {
	case N.NetworkTCP, N.NetworkUDP:
	default:
		return nil, E.Extend(N.ErrUnknownNetwork, network)
	}
	// B4/O1：已有新鲜选择（首轮探针中已有成员成功并流式就绪）时不等整轮，
	// 直接用它建连；无新鲜选择时仍等首轮探针，避免盲连列表首位超时节点
	//（既有启动探针测试 TestStartupProbeIsAsyncAndFirstConnectionWaits）。
	if s.group.selectedFresh(networkName) == nil {
		if err := s.group.waitForStartupProbe(ctx); err != nil {
			return nil, err
		}
	}
	s.group.Touch()
	detour := s.group.selectedFresh(networkName)
	if detour == nil {
		detour, _ = s.group.Select(networkName)
	}
	if detour == nil {
		return nil, E.New("missing supported outbound")
	}
	// 容灾候选：先按新鲜历史过滤/排序，后备存在时主选才受单步超时约束。
	alternates := s.group.failoverCandidates(detour, networkName)
	conn, err := s.dialOne(ctx, len(alternates) > 0, detour, network, destination)
	if err == nil {
		return s.group.interruptGroup.NewConn(conn, interrupt.IsExternalConnectionFromContext(ctx), interrupt.IsResourceDownloadFromContext(ctx)), nil
	}
	s.logger.ErrorContext(ctx, err)

	// 容灾候选快速故障转移，防止主选节点临时抖动导致全量连接直接断连。
	// 成功者立即成为当前选择，避免之后每条连接都先撞同一个坏主选。
	for i, alt := range alternates {
		altConn, altErr := s.dialOne(ctx, i < len(alternates)-1, alt, network, destination)
		if altErr == nil {
			s.group.promoteFailover(alt, networkName)
			return s.group.interruptGroup.NewConn(altConn, interrupt.IsExternalConnectionFromContext(ctx), interrupt.IsResourceDownloadFromContext(ctx)), nil
		}
	}
	return nil, err
}

func (s *URLTest) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	if s.group == nil || s.closed.Load() {
		return nil, E.New("URL test group not started or closed")
	}
	// B4/O1：与 DialContext 同理——已有新鲜选择时不等首轮整轮探针。
	if s.group.selectedFresh(N.NetworkUDP) == nil {
		if err := s.group.waitForStartupProbe(ctx); err != nil {
			return nil, err
		}
	}
	s.group.Touch()
	detour := s.group.selectedFresh(N.NetworkUDP)
	if detour == nil {
		detour, _ = s.group.Select(N.NetworkUDP)
	}
	if detour == nil {
		return nil, E.New("missing supported outbound")
	}
	// 容灾候选：先按新鲜历史过滤/排序，后备存在时主选才受单步超时约束。
	alternates := s.group.failoverCandidates(detour, N.NetworkUDP)
	conn, err := s.listenOne(ctx, len(alternates) > 0, detour, destination)
	if err == nil {
		return s.group.interruptGroup.NewPacketConn(conn, interrupt.IsExternalConnectionFromContext(ctx), interrupt.IsResourceDownloadFromContext(ctx)), nil
	}
	s.logger.ErrorContext(ctx, err)

	// UDP 候选快速容灾；成功者同样提升为当前选择。
	for i, alt := range alternates {
		altConn, altErr := s.listenOne(ctx, i < len(alternates)-1, alt, destination)
		if altErr == nil {
			s.group.promoteFailover(alt, N.NetworkUDP)
			return s.group.interruptGroup.NewPacketConn(altConn, interrupt.IsExternalConnectionFromContext(ctx), interrupt.IsResourceDownloadFromContext(ctx)), nil
		}
	}
	return nil, err
}

// failoverCandidates 按新鲜历史为故障转移排序候选：新鲜优先、延迟升序；
// 只要存在新鲜候选，就排除过期候选，避免先撞已知过期路径。全部过期时
// 保留配置顺序，维持原有的兜底连通能力。
func (g *URLTestGroup) failoverCandidates(primary adapter.Outbound, network string) []adapter.Outbound {
	type candidate struct {
		outbound adapter.Outbound
		delay    uint16
		fresh    bool
		order    int
	}
	candidates := make([]candidate, 0, len(g.outbounds))
	anyFresh := false
	for order, alt := range g.outbounds {
		if alt == primary || !common.Contains(alt.Network(), network) {
			continue
		}
		history := g.freshHistory(group.RealTag(alt, network))
		if history != nil {
			anyFresh = true
		}
		delay := uint16(65535)
		if history != nil {
			delay = history.Delay
		}
		candidates = append(candidates, candidate{outbound: alt, delay: delay, fresh: history != nil, order: order})
	}
	sort.SliceStable(candidates, func(i, j int) bool {
		if candidates[i].fresh != candidates[j].fresh {
			return candidates[i].fresh
		}
		if candidates[i].fresh && candidates[i].delay != candidates[j].delay {
			return candidates[i].delay < candidates[j].delay
		}
		return candidates[i].order < candidates[j].order
	})
	result := make([]adapter.Outbound, 0, len(candidates))
	for _, item := range candidates {
		if anyFresh && !item.fresh {
			continue
		}
		result = append(result, item.outbound)
	}
	return result
}

// promoteFailover 把故障转移成功的成员提升为当前选择，并按组选项决定是否
// 中断现有连接；下次建连直接走成功成员，不必重复等待坏主选超时。
func (g *URLTestGroup) promoteFailover(winner adapter.Outbound, network string) {
	if winner == nil {
		return
	}
	g.updateAccess.Lock()
	defer g.updateAccess.Unlock()
	selected := false
	switch network {
	case N.NetworkTCP:
		if g.selectedOutboundTCP != winner {
			g.selectedOutboundTCP = winner
			selected = true
		}
	case N.NetworkUDP:
		if g.selectedOutboundUDP != winner {
			g.selectedOutboundUDP = winner
			selected = true
		}
	default:
		return
	}
	tag := winner.Tag()
	g.observedTag.Store(&tag)
	g.currentTier = g.tierOfTag(tag)
	if selected && g.interruptExternalConnections {
		g.interruptGroup.Interrupt(true)
	}
	g.history.NotifyUpdated()
}

func (s *URLTest) NewConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	ctx = interrupt.ContextWithIsExternalConnection(ctx, true)
	s.connection.NewConnection(ctx, s, conn, metadata, onClose)
}

func (s *URLTest) NewPacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	ctx = interrupt.ContextWithIsExternalConnection(ctx, true)
	s.connection.NewPacketConnection(ctx, s, conn, metadata, onClose)
}

type URLTestGroup struct {
	ctx                 context.Context
	cancel              context.CancelFunc
	startupDone         chan struct{}
	outbound            adapter.OutboundManager
	logger              log.Logger
	outbounds           []adapter.Outbound
	link                string
	interval            time.Duration
	tolerance           uint16
	idleTimeout         time.Duration
	history             *urltest.HistoryStorage
	checkAccess         sync.Mutex
	checkFlight         *urlTestFlight
	pause               pause.Manager
	pauseCallback       *list.Element[pause.Callback]
	interruptGroup      *interrupt.Group
	selectedOutboundTCP adapter.Outbound
	// @author 雾晚: race-free observation; never advances selection.
	observedTag                  atomic.Pointer[string]
	selectedOutboundUDP          adapter.Outbound
	interruptExternalConnections bool
	access                       sync.Mutex
	updateAccess                 sync.RWMutex
	ticker                       *time.Ticker
	close                        chan struct{}
	started                      bool
	lastActive                   common.TypedValue[time.Time]
	// B1/O3: 连续失败计数（探测失败与单步建连超时共用同一计数）。
	// urlTestBatch 每轮新建，计数必须落在持久的组上，否则跨轮即丢。
	// key 为成员 Tag（与批量探测的 history key 一致）。
	failAccess sync.Mutex
	failCount  map[string]int
	// B7c/O7：与 outbounds 等长的层级序号（由成员 tag 后缀解析；未分层组全为 0）。
	tiers []int
	// B7c/O7：层级恢复门状态（每层连续健康轮次数与上次失败时间）与当前
	// 所在层级（-1 = 尚未选过）。门状态只在 performRoundEndCheck
	//（每轮探测结束，持有 updateAccess 写锁）里更新，selectLocked 只读。
	tierGate    tierRecoveryGateState
	currentTier int
}

// tierRecoveryGateState 是层级恢复门的状态：每层连续健康轮次数与上次失败时间。
type tierRecoveryGateState struct {
	consecutiveOK map[int]int
	lastFailure   map[int]time.Time
}

func NewURLTestGroup(ctx context.Context, outboundManager adapter.OutboundManager, logger log.Logger, outbounds []adapter.Outbound, link string, interval time.Duration, tolerance uint16, idleTimeout time.Duration, interruptExternalConnections bool) (*URLTestGroup, error) {
	if interval == 0 {
		interval = C.DefaultURLTestInterval
	}
	if idleTimeout == 0 {
		idleTimeout = C.DefaultURLTestIdleTimeout
	}
	if interval > idleTimeout {
		return nil, E.New("interval must be less or equal than idle_timeout")
	}
	history := service.PtrFromContext[urltest.HistoryStorage](ctx)
	if history == nil {
		return nil, E.New("missing URL test history storage")
	}
	groupCtx, cancel := context.WithCancel(ctx)
	// B7c/O7：由成员 tag 后缀解析层级。只有全部成员都带层级后缀、
	// 且至少分出两层时才激活分层；否则全部视为同一层（行为与改动前一致）。
	// 这一守卫避免普通节点名恰好以 " [T<n>]" 结尾时被误认作层级组。
	tiers := make([]int, len(outbounds))
	if len(outbounds) > 1 {
		parsed := make([]int, len(outbounds))
		distinct := make(map[int]bool)
		tiered := true
		for i, o := range outbounds {
			tier, ok := parseTierTagIndex(o.Tag())
			if !ok {
				tiered = false
				break
			}
			parsed[i] = tier
			distinct[tier] = true
		}
		if tiered && len(distinct) >= 2 {
			tiers = parsed
		}
	}
	return &URLTestGroup{
		ctx:                          groupCtx,
		cancel:                       cancel,
		startupDone:                  make(chan struct{}),
		outbound:                     outboundManager,
		logger:                       logger,
		outbounds:                    outbounds,
		link:                         link,
		interval:                     interval,
		tolerance:                    tolerance,
		idleTimeout:                  idleTimeout,
		history:                      history,
		close:                        make(chan struct{}),
		pause:                        service.FromContext[pause.Manager](ctx),
		interruptGroup:               interrupt.NewGroup(),
		interruptExternalConnections: interruptExternalConnections,
		tiers:                        tiers,
		tierGate: tierRecoveryGateState{
			consecutiveOK: make(map[int]int),
			lastFailure:   make(map[int]time.Time),
		},
		currentTier: -1,
	}, nil
}

// PostStart never waits for the network. Connections await the bounded initial
// probe instead, so core lifecycle/state reads remain responsive. @author 雾晚
func (g *URLTestGroup) PostStart() {
	g.access.Lock()
	if g.started || g.ctx.Err() != nil {
		g.access.Unlock()
		return
	}
	g.started = true
	g.lastActive.Store(time.Now())
	g.access.Unlock()
	go func() {
		defer close(g.startupDone)
		g.CheckOutbounds(g.ctx, true)
	}()
}

func (g *URLTestGroup) waitForStartupProbe(ctx context.Context) error {
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-g.ctx.Done():
		return g.ctx.Err()
	case <-g.startupDone:
		// A completed probe must not mask cancellation or shutdown.
		if err := ctx.Err(); err != nil {
			return err
		}
		return g.ctx.Err()
	}
}

func (g *URLTestGroup) Touch() {
	g.access.Lock()
	defer g.access.Unlock()
	if !g.started || g.ctx.Err() != nil {
		return
	}
	if g.ticker != nil {
		g.lastActive.Store(time.Now())
		return
	}
	ticker := time.NewTicker(g.interval)
	g.ticker = ticker
	g.pauseCallback = pause.RegisterTicker(g.pause, ticker, g.interval, nil)
	go g.loopCheck(ticker, g.close)
}

// @author 雾晚: close even an idle group; never recreate a ticker after shutdown.
func (g *URLTestGroup) Close() error {
	g.cancel()
	g.access.Lock()
	g.started = false
	if g.ticker != nil {
		g.ticker.Stop()
		g.ticker = nil
	}
	if g.pauseCallback != nil {
		g.pause.UnregisterCallback(g.pauseCallback)
		g.pauseCallback = nil
	}
	select {
	case <-g.close:
	default:
		close(g.close)
	}
	g.access.Unlock()
	g.interruptGroup.Interrupt(true)
	return nil
}

// applyMemberFailure 记录成员的一次连续失败（探测失败或单步建连超时），并执行
// B1/O3 失败策略：第一次只记惩罚（不续期旧 Time、不清史），第二次连续失败
// 清理故障史，防止假死节点被重新选回。返回本次累计的连续失败次数
// （第二次返回 2，计数随即复位）。批量取消（调用方关闭/整轮预算耗尽）不是
// 成员失败，MUST NOT 调用本方法。
func (g *URLTestGroup) applyMemberFailure(tag string) int {
	g.failAccess.Lock()
	defer g.failAccess.Unlock()
	if g.failCount == nil {
		g.failCount = make(map[string]int)
	}
	g.failCount[tag]++
	count := g.failCount[tag]
	if count >= 2 {
		g.history.DeleteURLTestHistory(tag)
		delete(g.failCount, tag)
		return count
	}
	penalizeOrDeleteHistory(g.history, tag, g.interval)
	return count
}

// resetMemberFailure 在成员一次成功后把连续失败计数复位为 0。
func (g *URLTestGroup) resetMemberFailure(tag string) {
	g.failAccess.Lock()
	defer g.failAccess.Unlock()
	delete(g.failCount, tag)
}

// freshHistory 只读缓存：延迟仍在 3*interval 新鲜窗口内才返回，过期即视
// 为无种子。只读，不改写 history.Time（B4/O1 种子约束）；窗口口径与
// B1/O3 的惩罚窗口（penalizeOrDeleteHistory）一致。
func (g *URLTestGroup) freshHistory(tag string) *adapter.URLTestHistory {
	history := g.history.LoadURLTestHistory(tag)
	if history == nil || time.Since(history.Time) >= 3*g.interval {
		return nil
	}
	return history
}

// isActive 报告组是否处于活跃态（未 idle）：以 lastActive 是否落在
// idleTimeout 窗口内判定。lastActive 是 TypedValue（原子读写），零值
// （PostStart 前）视为不活跃；ctx 已取消也视为不活跃。流式就绪只在
// 活跃时触发择优（B4/O1 设计约束）。
func (g *URLTestGroup) isActive() bool {
	if g.ctx.Err() != nil {
		return false
	}
	return time.Since(g.lastActive.Load()) <= g.idleTimeout
}

// selectedFresh 只返回仍有新鲜缓存的已存选择；过期即返回 nil，调用方回
// 落到 Select() 重新按缓存延迟预选（idle 后首次连接不得沿用过期选择）。
func (g *URLTestGroup) selectedFresh(network string) adapter.Outbound {
	g.updateAccess.RLock()
	defer g.updateAccess.RUnlock()
	var stored adapter.Outbound
	switch network {
	case N.NetworkTCP:
		stored = g.selectedOutboundTCP
	case N.NetworkUDP:
		stored = g.selectedOutboundUDP
	default:
		return nil
	}
	if stored == nil {
		return nil
	}
	if g.freshHistory(group.RealTag(stored, network)) == nil {
		return nil
	}
	return stored
}

func (g *URLTestGroup) Select(network string) (adapter.Outbound, bool) {
	g.updateAccess.RLock()
	defer g.updateAccess.RUnlock()
	return g.selectLocked(network)
}

func (g *URLTestGroup) selectLocked(network string) (adapter.Outbound, bool) {
	// B7c/O7 层级选路：先定生效层级（当前最低健康层，回切受门控），
	// 再在该层内按延迟 + 容差择优。未分层组（tiers 全 0）只有一层，
	// 逻辑与改动前一致；全层无新鲜历史时回落列表首位（既有行为）。
	effectiveTier, hasHealthy := g.effectiveTierLocked(network)
	if !hasHealthy {
		for _, detour := range g.outbounds {
			if !common.Contains(detour.Network(), network) {
				continue
			}
			return detour, false
		}
		return nil, false
	}
	// 层内择优：与改动前的 selectLocked 同一口径，只是候选集限定在本层；
	// 当前选中成员若在本层且新鲜，仍作为比较起点（粘性）。
	var minDelay uint16
	var minOutbound adapter.Outbound
	switch network {
	case N.NetworkTCP:
		if g.selectedOutboundTCP != nil && g.tierOfTag(g.selectedOutboundTCP.Tag()) == effectiveTier {
			if history := g.freshHistory(group.RealTag(g.selectedOutboundTCP, N.NetworkTCP)); history != nil {
				minOutbound = g.selectedOutboundTCP
				minDelay = history.Delay
			}
		}
	case N.NetworkUDP:
		if g.selectedOutboundUDP != nil && g.tierOfTag(g.selectedOutboundUDP.Tag()) == effectiveTier {
			if history := g.freshHistory(group.RealTag(g.selectedOutboundUDP, N.NetworkUDP)); history != nil {
				minOutbound = g.selectedOutboundUDP
				minDelay = history.Delay
			}
		}
	}
	for i, detour := range g.outbounds {
		if g.tierAt(i) != effectiveTier {
			continue
		}
		if !common.Contains(detour.Network(), network) {
			continue
		}
		history := g.freshHistory(group.RealTag(detour, network))
		if history == nil {
			continue
		}
		if minDelay == 0 || minDelay > history.Delay+g.tolerance {
			minDelay = history.Delay
			minOutbound = detour
		}
	}
	if minOutbound == nil {
		// 有效层内无新鲜历史（门控判定与选择之间历史过期）：回落该层首位。
		for i, detour := range g.outbounds {
			if g.tierAt(i) != effectiveTier {
				continue
			}
			if !common.Contains(detour.Network(), network) {
				continue
			}
			return detour, false
		}
		return nil, false
	}
	return minOutbound, true
}

// tierAt 返回第 i 个成员的层级序号（tiers 表缺失时按未分层处理，返回 0）。
func (g *URLTestGroup) tierAt(i int) int {
	if i < 0 || i >= len(g.tiers) {
		return 0
	}
	return g.tiers[i]
}

// tierOfTag 按 tag 查成员所在的层级序号（tag 在组内唯一；查不到返回 0）。
func (g *URLTestGroup) tierOfTag(tag string) int {
	for i, o := range g.outbounds {
		if o.Tag() == tag {
			return g.tierAt(i)
		}
	}
	return 0
}

// distinctTiers 返回本组出现的层级序号（升序）。
func (g *URLTestGroup) distinctTiers() []int {
	seen := make(map[int]bool)
	var tiers []int
	for _, tier := range g.tiers {
		if !seen[tier] {
			seen[tier] = true
			tiers = append(tiers, tier)
		}
	}
	sort.Ints(tiers)
	return tiers
}

// tierHealthyLocked 报告某层在指定网络上是否至少一个成员有新鲜历史
// （调用方须持有 updateAccess 读锁或写锁）。
func (g *URLTestGroup) tierHealthyLocked(tier int, network string) bool {
	for i, detour := range g.outbounds {
		if g.tierAt(i) != tier {
			continue
		}
		if !common.Contains(detour.Network(), network) {
			continue
		}
		if g.freshHistory(group.RealTag(detour, network)) != nil {
			return true
		}
	}
	return false
}

// effectiveTierLocked 决定本次选路的生效层级（调用方须持有读锁或写锁）。
// 候选层 = 最低健康层；回切受门控：候选层高于当前层（序号更小）且门未通过、
// 当前层仍健康时，留在当前层，不来回跳；当前层也全灭时直接回退候选层。
func (g *URLTestGroup) effectiveTierLocked(network string) (int, bool) {
	candidate := -1
	for _, tier := range g.distinctTiers() {
		if g.tierHealthyLocked(tier, network) {
			candidate = tier
			break
		}
	}
	if candidate < 0 {
		return 0, false
	}
	if g.currentTier >= 0 && candidate < g.currentTier &&
		!g.canSwitchBackTo(candidate, time.Now()) &&
		g.tierHealthyLocked(g.currentTier, network) {
		return g.currentTier, true
	}
	return candidate, true
}

// canSwitchBackTo 判定是否允许切回某高层：连续健康轮次达标，且距上次失败
// 已满无失败持续时间；从未失败过的层只看轮次。
func (g *URLTestGroup) canSwitchBackTo(tier int, now time.Time) bool {
	if g.tierGate.consecutiveOK[tier] < tierSwitchBackMinChecks {
		return false
	}
	if lastFail, failed := g.tierGate.lastFailure[tier]; failed {
		return !now.Before(lastFail.Add(tierSwitchBackMinFailureFree))
	}
	return true
}

// updateTierGateLocked 按当前新鲜历史推进层级恢复门（调用方须持有写锁，
// 每轮探测结束调一次）：有新鲜成员的层连续计数 +1，全灭的层清零并记失败时间。
func (g *URLTestGroup) updateTierGateLocked(now time.Time) {
	healthy := make(map[int]bool)
	for i, detour := range g.outbounds {
		tier := g.tierAt(i)
		if healthy[tier] {
			continue
		}
		if common.Contains(detour.Network(), N.NetworkTCP) &&
			g.freshHistory(group.RealTag(detour, N.NetworkTCP)) != nil {
			healthy[tier] = true
			continue
		}
		if common.Contains(detour.Network(), N.NetworkUDP) &&
			g.freshHistory(group.RealTag(detour, N.NetworkUDP)) != nil {
			healthy[tier] = true
		}
	}
	for _, tier := range g.distinctTiers() {
		if healthy[tier] {
			g.tierGate.consecutiveOK[tier]++
		} else {
			g.tierGate.consecutiveOK[tier] = 0
			g.tierGate.lastFailure[tier] = now
		}
	}
}

func (g *URLTestGroup) loopCheck(ticker *time.Ticker, closeChan <-chan struct{}) {
	if time.Since(g.lastActive.Load()) > g.interval {
		g.lastActive.Store(time.Now())
		g.CheckOutbounds(g.ctx, false)
	}
	for {
		select {
		case <-closeChan:
			return
		case <-ticker.C:
		}
		if time.Since(g.lastActive.Load()) > g.idleTimeout {
			g.access.Lock()
			if g.ticker == ticker {
				g.ticker.Stop()
				g.ticker = nil
				g.pause.UnregisterCallback(g.pauseCallback)
				g.pauseCallback = nil
			}
			g.access.Unlock()
			return
		}
		g.CheckOutbounds(g.ctx, false)
	}
}

func (g *URLTestGroup) CheckOutbounds(ctx context.Context, force bool) {
	_, _ = g.urlTest(ctx, force)
}

func (g *URLTestGroup) URLTest(ctx context.Context) (map[string]uint16, error) {
	return g.urlTest(ctx, true)
}

func (g *URLTestGroup) urlTest(ctx context.Context, force bool) (result map[string]uint16, err error) {
	// Every probe entry point belongs to the group lifecycle, including explicit
	// checks whose caller supplied an independent context. Closed groups cannot
	// restart probes, and closing a group also cancels an in-flight manual check.
	if err := g.ctx.Err(); err != nil {
		return nil, err
	}
	// 入口统一施加总预算：批量并发只有六槽，单成员三秒超时挡不住成员众多的整轮拖长。@author 雾晚
	ctx, cancel := context.WithTimeout(ctx, probeBudget)
	stop := context.AfterFunc(g.ctx, cancel)
	defer stop()
	defer cancel()
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	g.checkAccess.Lock()
	if flight := g.checkFlight; flight != nil {
		g.checkAccess.Unlock()
		// Join an in-flight probe and share its result: startup must not signal
		// readiness before it has populated history and selection, and a manual
		// check must not mistake the joined round for its own empty success.
		select {
		case <-flight.done:
			return flight.result, flight.err
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-g.ctx.Done():
			return nil, g.ctx.Err()
		}
	}
	flight := &urlTestFlight{done: make(chan struct{})}
	g.checkFlight = flight
	g.checkAccess.Unlock()
	defer func() {
		g.checkAccess.Lock()
		flight.result, flight.err = result, err
		g.checkFlight = nil
		close(flight.done)
		g.checkAccess.Unlock()
	}()
	result = URLTestOutboundsWithGroup(ctx, g.outbound, g.history, g.logger, g.outbounds, g.link, g.interval, force, g)
	if ctxErr := ctx.Err(); ctxErr != nil {
		// P1：整轮预算到期时不能直接丢弃本轮收尾。批量取消本身不代表成员失败
		//（失败判定在 urlTestBatch 内已按 b.ctx.Err 跳过），但只要有本轮结果或
		// 新鲜历史，就说明本轮具备可判定的观测，仍须推进层级恢复门并重算选择。
		// 组关闭/调用方取消（Canceled）不计数，避免把中断当成健康轮次。
		if g.ctx.Err() == nil &&
			errors.Is(ctxErr, context.DeadlineExceeded) &&
			(len(result) > 0 || g.hasFreshRoundEvidence()) {
			g.performRoundEndCheck()
		}
		return result, ctxErr
	}
	// B7c/O7：轮次结束走 performRoundEndCheck（推进层级恢复门 + 重算选择）；
	// 流式就绪仍走 performUpdateCheck（只重算选择，不计轮次）。
	g.performRoundEndCheck()
	return result, nil
}

// hasFreshRoundEvidence 报告本轮是否有可用于轮次收尾的新鲜历史。
// 预算到期时批量探测会被取消，但已成功的成员（含嵌套组的流式结果）仍会
// 写入 history；层级恢复门按轮次计数，不能因为其余成员拖到超时就整轮作废。
func (g *URLTestGroup) hasFreshRoundEvidence() bool {
	for _, detour := range g.outbounds {
		if common.Contains(detour.Network(), N.NetworkTCP) &&
			g.freshHistory(group.RealTag(detour, N.NetworkTCP)) != nil {
			return true
		}
		if common.Contains(detour.Network(), N.NetworkUDP) &&
			g.freshHistory(group.RealTag(detour, N.NetworkUDP)) != nil {
			return true
		}
	}
	return false
}

// urlTestFlight is one in-flight probe round. The leader publishes its result
// before closing done, so callers that joined the round share the real result.
// @author 雾晚
type urlTestFlight struct {
	done   chan struct{}
	result map[string]uint16
	err    error
}

type urlTestResult struct {
	delay uint16
	err   error
}

type urlTestBatch struct {
	ctx      context.Context
	outbound adapter.OutboundManager
	history  *urltest.HistoryStorage
	logger   log.Logger
	batch    *batch.Batch[any]
	// B1/O3: 归属组（可为空）。非空时失败/成功更新其连续失败计数。
	group   *URLTestGroup
	checked map[string]bool
	groups  []adapter.OutboundGroup
	access  sync.Mutex
	result  map[string]uint16
}

func URLTestOutbounds(ctx context.Context, outboundManager adapter.OutboundManager, history *urltest.HistoryStorage, logger log.Logger, outbounds []adapter.Outbound, link string, interval time.Duration, force bool) map[string]uint16 {
	return URLTestOutboundsWithGroup(ctx, outboundManager, history, logger, outbounds, link, interval, force, nil)
}

// URLTestOutboundsWithGroup 是 URLTestOutbounds 的组归属版本。owner 非空时，
// 探测失败与成功会更新该组的连续失败计数（两次失败清史、一次成功复位）；
// owner 为空时（如 loadbalance 调用方，无归属组）只做饱和惩罚，不做连续失败清史。
// 注意：参数名不用 group——包内已有同名 group 包（group.RealTag），用 group
// 会遮住它，导致下面嵌套组的 groupHistory 读不到。
func URLTestOutboundsWithGroup(ctx context.Context, outboundManager adapter.OutboundManager, history *urltest.HistoryStorage, logger log.Logger, outbounds []adapter.Outbound, link string, interval time.Duration, force bool, owner *URLTestGroup) map[string]uint16 {
	b, _ := batch.New(ctx, batch.WithConcurrencyNum[any](6))
	testBatch := &urlTestBatch{
		ctx:      ctx,
		outbound: outboundManager,
		history:  history,
		logger:   logger,
		batch:    b,
		checked:  make(map[string]bool),
		result:   make(map[string]uint16),
		group:    owner,
	}
	testBatch.test(outbounds, link, interval, force)
	b.Wait()
	for _, outboundGroup := range testBatch.groups {
		groupHistory := history.LoadURLTestHistory(group.RealTag(outboundGroup, N.NetworkTCP))
		if groupHistory != nil {
			testBatch.result[outboundGroup.Tag()] = groupHistory.Delay
		}
	}
	return testBatch.result
}

func (b *urlTestBatch) test(outbounds []adapter.Outbound, link string, interval time.Duration, force bool) {
	for _, detour := range outbounds {
		tag := detour.Tag()
		if b.checked[tag] {
			continue
		}
		switch nested := detour.(type) {
		case *URLTest:
			b.checked[tag] = true
			b.groups = append(b.groups, nested)
			b.batch.Go(tag, func() (any, error) {
				nestedResult, _ := nested.group.urlTest(b.ctx, force)
				b.access.Lock()
				maps.Copy(b.result, nestedResult)
				b.access.Unlock()
				return nil, nil
			})
		case adapter.OutboundGroup:
			b.checked[tag] = true
			b.groups = append(b.groups, nested)
			b.test(common.FilterNotNil(common.Map(nested.All(), func(it string) adapter.Outbound {
				member, _ := b.outbound.Outbound(it)
				return member
			})), link, interval, force)
		default:
			history := b.history.LoadURLTestHistory(tag)
			if !force && history != nil && time.Since(history.Time) < interval {
				continue
			}
			b.checked[tag] = true
			b.batch.Go(tag, func() (any, error) {
				if b.ctx.Err() != nil {
					return nil, nil
				}
				testCtx, cancel := context.WithTimeout(b.ctx, 3*time.Second)
				defer cancel()
				testChan := make(chan urlTestResult, 1)
				go func() {
					delay, testErr := ProbeOutbound(testCtx, detour, link, 3*time.Second)
					testChan <- urlTestResult{delay, testErr}
				}()
				var testResult urlTestResult
				select {
				case testResult = <-testChan:
				case <-testCtx.Done():
					testResult.err = testCtx.Err()
				}
				// Cancellation of the batch (startup budget, caller cancellation or
				// group shutdown) is not evidence of member failure. Preserve both
				// fresh and expired history instead of penalizing/deleting it.
				// A member's own timeout still uses the normal failure policy.
				if b.ctx.Err() != nil {
					return nil, nil
				}
				if testResult.err != nil {
					b.logger.Debug("outbound ", tag, " unavailable: ", testResult.err)
					if b.group != nil {
						// 归属组：按连续失败策略处理（首次惩罚、连续两次清史）。
						b.group.applyMemberFailure(tag)
					} else {
						// 无归属组：只做饱和惩罚，不做连续失败清史。
						penalizeOrDeleteHistory(b.history, tag, interval)
					}
				} else {
					b.logger.Debug("outbound ", tag, " available: ", testResult.delay, "ms")
					b.history.StoreURLTestHistory(tag, &adapter.URLTestHistory{
						Time:  time.Now(),
						Delay: testResult.delay,
					})
					if b.group != nil {
						b.group.resetMemberFailure(tag)
					}
					b.access.Lock()
					b.result[tag] = testResult.delay
					b.access.Unlock()
					// B4/O1 流式就绪（上游 8.20 recordProbeProgress 的 wanBox
					// 适配）：单个成员成功即重算选择并通知，不等整批跑完；
					// 只在组活跃（未 idle）时触发择优，idle 后的首次连接
					// 仍按缓存延迟重新预选。倒计时仍按整轮结束重置——本处
					// 不碰 ticker、不碰 lastActive、不改写 history.Time。
					if b.group != nil && b.group.isActive() {
						b.group.performUpdateCheck()
					}
				}
				return nil, nil
			})
		}
	}
}

func (g *URLTestGroup) performUpdateCheck() {
	g.updateAccess.Lock()
	defer g.updateAccess.Unlock()
	g.performUpdateCheckLocked()
}

// performRoundEndCheck 是每轮探测结束的更新入口：先按本轮结果推进层级恢复门
// （健康层连续计数 +1，全灭层清零并记失败时间），再重算选择。
// 流式就绪（单成员成功）只调 performUpdateCheck，不推进门计数——
// “检查次数”按轮次计，不按单个成员成功计。
func (g *URLTestGroup) performRoundEndCheck() {
	g.updateAccess.Lock()
	defer g.updateAccess.Unlock()
	g.updateTierGateLocked(time.Now())
	g.performUpdateCheckLocked()
}

func (g *URLTestGroup) performUpdateCheckLocked() {
	var (
		updated  bool
		selected bool
	)
	if outbound, exists := g.selectLocked(N.NetworkTCP); outbound != nil && (g.selectedOutboundTCP == nil || (exists && outbound != g.selectedOutboundTCP)) {
		if g.selectedOutboundTCP != nil {
			updated = true
		}
		g.selectedOutboundTCP = outbound
		tag := outbound.Tag()
		g.observedTag.Store(&tag)
		selected = true
	}
	if outbound, exists := g.selectLocked(N.NetworkUDP); outbound != nil && (g.selectedOutboundUDP == nil || (exists && outbound != g.selectedOutboundUDP)) {
		if g.selectedOutboundUDP != nil {
			updated = true
		}
		g.selectedOutboundUDP = outbound
		selected = true
	}
	// B7c/O7：记录当前选中成员所在的层级，供下一轮回切门控参考。
	if g.selectedOutboundTCP != nil {
		g.currentTier = g.tierOfTag(g.selectedOutboundTCP.Tag())
	} else if g.selectedOutboundUDP != nil {
		g.currentTier = g.tierOfTag(g.selectedOutboundUDP.Tag())
	}
	if updated && g.interruptExternalConnections {
		g.interruptGroup.Interrupt(true)
	}
	if selected {
		g.history.NotifyUpdated()
	}
}

// @author 雾晚
func (s *URLTest) LastSelectedTag() string {
	if s.group == nil {
		return ""
	}
	tag := s.group.observedTag.Load()
	if tag == nil {
		return ""
	}
	return *tag
}
