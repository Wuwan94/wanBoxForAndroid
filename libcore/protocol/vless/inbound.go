package vless

import (
	"context"
	"net"
	"os"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/common/listener"
	"github.com/sagernet/sing-box/common/mux"
	"github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/common/uot"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	singboxVLESS "github.com/sagernet/sing-box/protocol/vless"
	"github.com/sagernet/sing-vmess/packetaddr"
	"github.com/sagernet/sing-vmess/vless"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/auth"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	F "github.com/sagernet/sing/common/format"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"

	"libcore/protocol/vless/xhttp"
)

type VLESSInboundOptions struct {
	option.ListenOptions
	Users []option.VLESSUser `json:"users,omitempty"`
	option.InboundTLSOptionsContainer
	Multiplex *option.InboundMultiplexOptions `json:"multiplex,omitempty"`
	Transport *V2RayTransportOptions          `json:"transport,omitempty"`
}

func RegisterInbound(registry *inbound.Registry) {
	inbound.Register[VLESSInboundOptions](registry, C.TypeVLESS, NewInbound)
}

var _ adapter.TCPInjectableInbound = (*Inbound)(nil)

type Inbound struct {
	inbound.Adapter
	ctx        context.Context
	router     adapter.ConnectionRouterEx
	logger     logger.ContextLogger
	listener   *listener.Listener
	users      []option.VLESSUser
	service    *vless.Service[int]
	tlsConfig  tls.ServerConfig
	transport  adapter.V2RayServerTransport
	references []string
}

func NewInbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options VLESSInboundOptions) (adapter.Inbound, error) {
	if options.Transport == nil || normalizeTransportType(options.Transport.Type) != xhttp.V2RayTransportTypeXHTTP {
		standardOptions := option.VLESSInboundOptions{
			ListenOptions:              options.ListenOptions,
			Users:                      options.Users,
			InboundTLSOptionsContainer: options.InboundTLSOptionsContainer,
			Multiplex:                  options.Multiplex,
		}
		if options.Transport != nil {
			transport := options.Transport.V2RayTransportOptions
			transport.Type = normalizeTransportType(transport.Type)
			standardOptions.Transport = &transport
		}
		return singboxVLESS.NewInbound(ctx, router, logger, tag, standardOptions)
	}
	h := &Inbound{
		Adapter: inbound.NewAdapter(C.TypeVLESS, tag),
		ctx:     ctx,
		router:  uot.NewRouter(router, logger),
		logger:  logger,
		users:   options.Users,
	}
	var err error
	h.router, err = mux.NewRouterWithOptions(h.router, logger, common.PtrValueOrDefault(options.Multiplex))
	if err != nil {
		return nil, err
	}
	service := vless.NewService[int](logger, adapter.NewUpstreamContextHandler(h.newConnectionEx, h.newPacketConnectionEx))
	service.UpdateUsers(common.MapIndexed(h.users, func(index int, _ option.VLESSUser) int {
		return index
	}), common.Map(h.users, func(user option.VLESSUser) string {
		return user.UUID
	}), common.Map(h.users, func(user option.VLESSUser) string {
		return user.Flow
	}))
	h.service = service
	if options.TLS != nil {
		h.tlsConfig, err = tls.NewServerWithOptions(tls.ServerOptions{
			Context: ctx,
			Logger:  logger,
			Options: common.PtrValueOrDefault(options.TLS),
		})
		if err != nil {
			return nil, err
		}
		if options.TLS.Reality != nil && options.TLS.Reality.Enabled && options.TLS.Reality.Handshake.Detour != "" {
			h.references = []string{options.TLS.Reality.Handshake.Detour}
		}
	}
	h.transport, err = xhttp.NewServer(ctx, logger, options.Transport.XHTTPOptions, h.tlsConfig, (*inboundTransportHandler)(h))
	if err != nil {
		return nil, E.Cause(err, "create server transport: ", xhttp.V2RayTransportTypeXHTTP)
	}

	h.listener = listener.New(listener.Options{
		Context:           ctx,
		Logger:            logger,
		Network:           []string{N.NetworkTCP},
		Listen:            options.ListenOptions,
		ConnectionHandler: h,
	})
	return h, nil
}

func (h *Inbound) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	if stage != adapter.StartStateStart {
		return nil
	}
	if h.tlsConfig != nil {
		if err := h.tlsConfig.Start(); err != nil {
			return err
		}
		scope.Add(h.tlsConfig.Close)
	}
	if h.transport == nil {
		if err := h.listener.Start(); err != nil {
			return err
		}
		scope.Add(h.listener.Close)
		return nil
	}
	scope.Add(h.transport.Close)
	scope.Add(h.listener.Close)
	if common.Contains(h.transport.Network(), N.NetworkTCP) {
		tcpListener, err := h.listener.ListenTCP()
		if err != nil {
			return err
		}
		go func() {
			serveErr := h.transport.Serve(tcpListener)
			if serveErr != nil && !E.IsClosed(serveErr) {
				h.logger.Error("transport serve error: ", serveErr)
			}
		}()
	}
	if common.Contains(h.transport.Network(), N.NetworkUDP) {
		udpConn, err := h.listener.ListenUDP()
		if err != nil {
			return err
		}
		go func() {
			serveErr := h.transport.ServePacket(udpConn)
			if serveErr != nil && !E.IsClosed(serveErr) {
				h.logger.Error("transport packet serve error: ", serveErr)
			}
		}()
	}
	return nil
}

func (h *Inbound) NewConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	if h.tlsConfig != nil && h.transport == nil {
		tlsConn, err := tls.ServerHandshake(ctx, conn, h.tlsConfig)
		if err != nil {
			N.CloseOnHandshakeFailure(conn, onClose, err)
			h.logger.ErrorContext(ctx, E.Cause(err, "process connection from ", metadata.Source, ": TLS handshake"))
			return
		}
		conn = tlsConn
	}
	if err := h.service.NewConnection(adapter.WithContext(ctx, &metadata), conn, metadata.Source, onClose); err != nil {
		N.CloseOnHandshakeFailure(conn, onClose, err)
		h.logger.ErrorContext(ctx, E.Cause(err, "process connection from ", metadata.Source))
	}
}

func (h *Inbound) newConnectionEx(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	metadata.Inbound = h.Tag()
	metadata.InboundType = h.Type()
	userIndex, loaded := auth.UserFromContext[int](ctx)
	if !loaded {
		N.CloseOnHandshakeFailure(conn, onClose, os.ErrInvalid)
		return
	}
	user := h.users[userIndex].Name
	if user == "" {
		user = F.ToString(userIndex)
	} else {
		metadata.User = user
	}
	h.logger.InfoContext(ctx, "[", user, "] inbound connection to ", metadata.Destination)
	h.router.RouteConnectionEx(ctx, conn, metadata, onClose)
}

func (h *Inbound) newPacketConnectionEx(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	metadata.Inbound = h.Tag()
	metadata.InboundType = h.Type()
	userIndex, loaded := auth.UserFromContext[int](ctx)
	if !loaded {
		N.CloseOnHandshakeFailure(conn, onClose, os.ErrInvalid)
		return
	}
	user := h.users[userIndex].Name
	if user == "" {
		user = F.ToString(userIndex)
	} else {
		metadata.User = user
	}
	if metadata.Destination.Fqdn == packetaddr.SeqPacketMagicAddress {
		metadata.Destination = M.Socksaddr{}
		conn = packetaddr.NewConn(bufio.NewNetPacketConn(conn), metadata.Destination)
		h.logger.InfoContext(ctx, "[", user, "] inbound packet addr connection")
	} else {
		h.logger.InfoContext(ctx, "[", user, "] inbound packet connection to ", metadata.Destination)
	}
	h.router.RoutePacketConnectionEx(ctx, conn, metadata, onClose)
}

var _ adapter.V2RayServerTransportHandler = (*inboundTransportHandler)(nil)

type inboundTransportHandler Inbound

func (h *inboundTransportHandler) NewConnectionEx(ctx context.Context, conn net.Conn, source M.Socksaddr, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	var metadata adapter.InboundContext
	metadata.Source = source
	metadata.Destination = destination
	//nolint:staticcheck
	metadata.InboundDetour = h.listener.ListenOptions().Detour
	//nolint:staticcheck
	h.logger.InfoContext(ctx, "inbound connection from ", metadata.Source)
	(*Inbound)(h).NewConnection(ctx, conn, metadata, onClose)
}

func (h *Inbound) References() []string { return h.references }
