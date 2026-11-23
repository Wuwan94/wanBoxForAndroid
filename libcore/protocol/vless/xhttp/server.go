// Package xhttp implements the V2Ray XHTTP transport. Its wire behavior is
// informed by the public flyzstu/xhttp-box implementation (GPL-3.0-or-later
// with an additional name/non-affiliation clause); wanBox is independent.
package xhttp

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/tls"
	C "github.com/sagernet/sing-box/constant"
	boxlog "github.com/sagernet/sing-box/log"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	aTLS "github.com/sagernet/sing/common/tls"
	sHTTP "github.com/sagernet/sing/protocol/http"
	"golang.org/x/net/http/httpguts"
	"golang.org/x/net/http2"
	"golang.org/x/net/http2/h2c"
	"golang.org/x/net/http2/hpack"
)

const (
	maxXHTTPSessions                = 1024
	maxXHTTPBufferedPosts           = 256
	maxXHTTPBufferedBytesPerSession = 16 << 20
	maxXHTTPBufferedBytes           = 64 << 20
	maxXHTTPPostBytes               = 16 << 20
	maxXHTTPConcurrentUploads       = 4
	maxXHTTPPaddingBytes            = 4096
	maxXHTTPSessionIDLength         = 128
)

// Server implements the XHTTP server side of the V2Ray transport contract.
// Its request and session handling follows the public XHTTP wire format used
// by Xray-compatible implementations. The server supports TCP-based HTTP/1.1,
// HTTP/2, and h2c; HTTP/3 is not advertised by this transport.
type Server struct {
	ctx            context.Context
	logger         logger.ContextLogger
	tlsConfig      tls.ServerConfig
	handler        adapter.V2RayServerTransportHandler
	options        V2RayXHTTPOptions
	basePath       string
	httpServer     *http.Server
	sessionsMu     sync.Mutex
	sessions       map[string]*serverSession
	bufferedBytes  atomic.Int64
	uploadSlots    chan struct{}
	closed         bool
	sessionTimeout time.Duration
}

var _ adapter.V2RayServerTransport = (*Server)(nil)

func NewServer(ctx context.Context, logger logger.ContextLogger, options V2RayXHTTPOptions, tlsConfig tls.ServerConfig, handler adapter.V2RayServerTransportHandler) (adapter.V2RayServerTransport, error) {
	mode, err := NormalizeXHTTPMode(options.Mode)
	if err != nil {
		return nil, err
	}
	options.Mode = mode
	if err := validateRequestHeaderOptions(options.Headers); err != nil {
		return nil, err
	}
	if err := validateXHTTPPlacements(options); err != nil {
		return nil, err
	}
	padding := options.GetNormalizedXPaddingBytes()
	if padding.From <= 0 || padding.To < padding.From || padding.To > maxXHTTPPaddingBytes {
		return nil, E.New("invalid xhttp x_padding_bytes")
	}
	postLimit := options.GetNormalizedScMaxEachPostBytes()
	if postLimit.From <= 0 || postLimit.To < postLimit.From || postLimit.To > maxXHTTPPostBytes {
		return nil, E.New("invalid xhttp sc_max_each_post_bytes")
	}
	if options.ScMaxBufferedPosts < 0 || options.ScMaxBufferedPosts > maxXHTTPBufferedPosts {
		return nil, E.New("xhttp sc_max_buffered_posts must be between 1 and ", maxXHTTPBufferedPosts)
	}
	if options.GetNormalizedScMaxBufferedPosts() < 1 {
		return nil, E.New("invalid xhttp sc_max_buffered_posts")
	}
	if options.GetNormalizedUplinkHTTPMethod() == http.MethodGet && mode != "packet-up" && mode != "auto" {
		return nil, E.New("xhttp uplink_http_method GET requires packet-up mode")
	}
	if tlsConfig != nil {
		for _, protocol := range tlsConfig.NextProtos() {
			if protocol == "h3" {
				return nil, E.New("xhttp server transport currently supports HTTP/1.1 and HTTP/2, not HTTP/3")
			}
		}
	}

	server := &Server{
		ctx:            ctx,
		logger:         logger,
		tlsConfig:      tlsConfig,
		handler:        handler,
		options:        options,
		basePath:       options.GetNormalizedPath(),
		sessions:       make(map[string]*serverSession),
		uploadSlots:    make(chan struct{}, maxXHTTPConcurrentUploads),
		sessionTimeout: 30 * time.Second,
	}
	server.httpServer = &http.Server{
		Handler:           h2c.NewHandler(server, &http2.Server{}),
		ReadHeaderTimeout: C.TCPTimeout,
		MaxHeaderBytes:    8192,
		BaseContext:       func(net.Listener) context.Context { return ctx },
		ConnContext:       func(ctx context.Context, _ net.Conn) context.Context { return boxlog.ContextWithNewID(ctx) },
	}
	return server, nil
}

func (s *Server) Network() []string {
	return []string{N.NetworkTCP}
}

func (s *Server) Serve(listener net.Listener) error {
	if s.tlsConfig != nil {
		nextProtos := s.tlsConfig.NextProtos()
		if len(nextProtos) == 0 {
			s.tlsConfig.SetNextProtos([]string{http2.NextProtoTLS, "http/1.1"})
		} else {
			hasHTTP2 := false
			for _, protocol := range nextProtos {
				if protocol == http2.NextProtoTLS {
					hasHTTP2 = true
					break
				}
			}
			if !hasHTTP2 {
				s.tlsConfig.SetNextProtos(append([]string{http2.NextProtoTLS}, nextProtos...))
			}
		}
		listener = aTLS.NewListener(listener, s.tlsConfig)
	}
	err := s.httpServer.Serve(listener)
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

func (s *Server) ServePacket(net.PacketConn) error {
	return errors.New("xhttp server transport requires TCP")
}

func (s *Server) Close() error {
	s.sessionsMu.Lock()
	s.closed = true
	sessions := make([]*serverSession, 0, len(s.sessions))
	for id, session := range s.sessions {
		delete(s.sessions, id)
		sessions = append(sessions, session)
	}
	s.sessionsMu.Unlock()
	for _, session := range sessions {
		session.close()
	}
	return s.httpServer.Close()
}

func (s *Server) ServeHTTP(writer http.ResponseWriter, request *http.Request) {
	if s.options.Host != "" && !validHTTPHost(request.Host, s.options.Host) {
		s.invalid(writer, request, http.StatusNotFound, "bad host")
		return
	}
	if !pathMatches(request.URL.Path, s.basePath) {
		s.invalid(writer, request, http.StatusNotFound, "bad path")
		return
	}
	s.writeCORS(writer, request)
	s.applyResponsePadding(writer)
	if request.Method == http.MethodOptions {
		writer.WriteHeader(http.StatusOK)
		return
	}
	if !validConfiguredHeaders(request.Header, s.options.Headers) {
		s.invalid(writer, request, http.StatusBadRequest, "missing or invalid xhttp header")
		return
	}
	if !s.validPadding(request) {
		s.invalid(writer, request, http.StatusBadRequest, "invalid xhttp padding")
		return
	}

	sessionID, sequence := s.extractMetadata(request)
	if sessionID != "" && !validSessionID(sessionID) {
		s.invalid(writer, request, http.StatusBadRequest, "invalid xhttp session id")
		return
	}
	if s.options.Mode == "stream-one" {
		if request.Method == http.MethodGet || request.Body == nil {
			s.invalid(writer, request, http.StatusBadRequest, "stream-one requires an upload body")
			return
		}
		s.serveConnection(writer, request, request.Body, nil)
		return
	}

	if request.Method != http.MethodGet || sequence != "" {
		if sessionID == "" {
			s.invalid(writer, request, http.StatusBadRequest, "missing xhttp session id")
			return
		}
		if sequence == "" {
			if s.options.Mode == "packet-up" {
				s.invalid(writer, request, http.StatusBadRequest, "packet-up requires a sequence")
				return
			}
			session, ok := s.session(sessionID)
			if !ok {
				s.invalid(writer, request, http.StatusTooManyRequests, "xhttp session limit reached")
				return
			}
			uploadDone, started := session.startStream(request.Body)
			if !started {
				s.invalid(writer, request, http.StatusConflict, "duplicate xhttp stream upload")
				return
			}
			_ = http.NewResponseController(writer).EnableFullDuplex()
			writer.Header().Set("X-Accel-Buffering", "no")
			writer.Header().Set("Cache-Control", "no-store")
			writer.WriteHeader(http.StatusOK)
			if flusher, ok := writer.(http.Flusher); ok {
				flusher.Flush()
			}
			select {
			case <-request.Context().Done():
			case <-uploadDone:
			}
			return
		}
		if s.options.Mode == "stream-up" {
			s.invalid(writer, request, http.StatusBadRequest, "stream-up does not use packet sequence numbers")
			return
		}
		sequenceNumber, err := strconv.ParseUint(sequence, 10, 64)
		if err != nil {
			s.invalid(writer, request, http.StatusBadRequest, "invalid xhttp sequence")
			return
		}
		select {
		case s.uploadSlots <- struct{}{}:
			defer func() { <-s.uploadSlots }()
		default:
			s.invalid(writer, request, http.StatusTooManyRequests, "xhttp upload limit reached")
			return
		}
		maxBytes := s.maxPacketUploadBytes()
		payload, err := readPacketPayload(request, s.options, maxBytes)
		if err != nil || len(payload) > maxBytes {
			s.invalid(writer, request, http.StatusBadRequest, "invalid xhttp upload")
			return
		}
		session, ok := s.session(sessionID)
		if !ok {
			s.invalid(writer, request, http.StatusTooManyRequests, "xhttp session limit reached")
			return
		}
		if !session.push(serverPacket{sequence: sequenceNumber, payload: payload}) {
			s.invalid(writer, request, http.StatusConflict, "closed or overloaded xhttp session")
			return
		}
		writer.Header().Set("Cache-Control", "no-store")
		writer.WriteHeader(http.StatusOK)
		return
	}

	if request.Method != http.MethodGet {
		s.invalid(writer, request, http.StatusMethodNotAllowed, "unsupported xhttp method")
		return
	}
	if sessionID == "" {
		s.invalid(writer, request, http.StatusBadRequest, "missing xhttp session id")
		return
	}
	session, ok := s.session(sessionID)
	if !ok {
		s.invalid(writer, request, http.StatusTooManyRequests, "xhttp session limit reached")
		return
	}
	session.markConnected()
	s.serveConnection(writer, request, session.reader, session)
}

func (s *Server) serveConnection(writer http.ResponseWriter, request *http.Request, reader io.Reader, session *serverSession) {
	if request.Body != nil {
		_ = http.NewResponseController(writer).EnableFullDuplex()
	}
	writer.Header().Set("X-Accel-Buffering", "no")
	writer.Header().Set("Cache-Control", "no-store")
	if !s.options.NoSSEHeader {
		writer.Header().Set("Content-Type", "text/event-stream")
	}
	writer.WriteHeader(http.StatusOK)
	if flusher, ok := writer.(http.Flusher); ok {
		flusher.Flush()
	}

	response := &responseConn{
		reader:     reader,
		writer:     writer,
		remoteAddr: resolveTCPAddr(request.RemoteAddr),
	}
	if localAddr, ok := request.Context().Value(http.LocalAddrContextKey).(net.Addr); ok {
		response.localAddr = localAddr
	}
	defer response.Close()
	if session != nil {
		defer s.closeSession(session.id, session)
	}
	done := make(chan struct{})
	var closeOnce sync.Once
	onClose := func(error) { closeOnce.Do(func() { close(done) }) }
	s.handler.NewConnectionEx(request.Context(), response, sHTTP.SourceAddress(request), M.Socksaddr{}, onClose)
	select {
	case <-done:
	case <-request.Context().Done():
	}
}

func (s *Server) session(id string) (*serverSession, bool) {
	if !validSessionID(id) {
		return nil, false
	}
	s.sessionsMu.Lock()
	if s.closed {
		s.sessionsMu.Unlock()
		return nil, false
	}
	if existing, ok := s.sessions[id]; ok {
		s.sessionsMu.Unlock()
		return existing, true
	}
	if len(s.sessions) >= maxXHTTPSessions {
		s.sessionsMu.Unlock()
		return nil, false
	}
	session := newServerSession(id, s.options.GetNormalizedScMaxBufferedPosts(), &s.bufferedBytes)
	s.sessions[id] = session
	s.sessionsMu.Unlock()
	go func() {
		timer := time.NewTimer(s.sessionTimeout)
		defer timer.Stop()
		select {
		case <-timer.C:
			s.closeSession(id, session)
		case <-session.connected:
		case <-s.ctx.Done():
			s.closeSession(id, session)
		}
	}()
	return session, true
}

func (s *Server) closeSession(id string, session *serverSession) {
	session.markConnected()
	s.sessionsMu.Lock()
	if s.sessions[id] == session {
		session.close()
		delete(s.sessions, id)
	}
	s.sessionsMu.Unlock()
}

func (s *Server) writeCORS(writer http.ResponseWriter, request *http.Request) {
	origin := request.Header.Get("Origin")
	if origin == "" {
		origin = "*"
	}
	responseHeaders := writer.Header()
	responseHeaders.Set("Access-Control-Allow-Origin", origin)
	vary := []string{"Origin"}
	if request.Method == http.MethodOptions {
		methods := []string{http.MethodGet, s.options.GetNormalizedUplinkHTTPMethod()}
		if methods[0] == methods[1] {
			methods = methods[:1]
		}
		responseHeaders.Set("Access-Control-Allow-Methods", strings.Join(methods, ", "))
		if requestedHeaders := strings.TrimSpace(request.Header.Get("Access-Control-Request-Headers")); requestedHeaders != "" {
			responseHeaders.Set("Access-Control-Allow-Headers", requestedHeaders)
			vary = append(vary, "Access-Control-Request-Headers")
		}
	}
	responseHeaders.Set("Vary", strings.Join(vary, ", "))
}

func (s *Server) applyResponsePadding(writer http.ResponseWriter) {
	paddingRange := s.options.GetNormalizedXPaddingBytes()
	length := int(paddingRange.Rand())
	method := strings.ToLower(strings.TrimSpace(s.options.XPaddingMethod))
	if !s.options.XPaddingObfsMode && s.options.XPaddingPlacement == "" {
		writer.Header().Set("X-Padding", GeneratePadding("repeat-x", length))
		return
	}
	value := GeneratePadding(method, length)
	placement := strings.ToLower(strings.TrimSpace(s.options.XPaddingPlacement))
	key := strings.TrimSpace(s.options.XPaddingKey)
	if key == "" {
		key = "x_padding"
	}
	header := strings.TrimSpace(s.options.XPaddingHeader)
	if header == "" {
		header = "X-Padding"
	}
	switch placement {
	case PlacementHeader:
		writer.Header().Set(header, value)
	case PlacementCookie:
		http.SetCookie(writer, &http.Cookie{Name: key, Value: value, Path: "/"})
	}
}

func (s *Server) validPadding(request *http.Request) bool {
	paddingRange := s.options.GetNormalizedXPaddingBytes()
	placement := strings.ToLower(strings.TrimSpace(s.options.XPaddingPlacement))
	key := strings.TrimSpace(s.options.XPaddingKey)
	header := strings.TrimSpace(s.options.XPaddingHeader)
	method := strings.ToLower(strings.TrimSpace(s.options.XPaddingMethod))
	if !s.options.XPaddingObfsMode && placement == "" {
		placement, key, header, method = PlacementQueryInHeader, "x_padding", "Referer", "repeat-x"
	} else if placement == "" {
		placement = PlacementHeader
	}
	if key == "" {
		key = "x_padding"
	}
	if header == "" {
		switch placement {
		case PlacementQueryInHeader:
			header = "Referer"
		case PlacementHeader:
			header = s.options.XPaddingKey
			if header == "" {
				header = "X-Padding"
			}
		default:
			header = "X-Padding"
		}
	}
	var value string
	switch placement {
	case PlacementHeader:
		value = request.Header.Get(header)
	case PlacementCookie:
		cookie, _ := request.Cookie(key)
		if cookie != nil {
			value = cookie.Value
		}
	case PlacementQuery:
		value = request.URL.Query().Get(key)
	case PlacementQueryInHeader:
		reference := request.Header.Get(header)
		if parsed, err := url.Parse(reference); err == nil {
			value = parsed.Query().Get(key)
		}
	default:
		return false
	}
	length := len(value)
	if method == PaddingMethodTokenish {
		length = int(hpack.HuffmanEncodeLength(value))
		return length >= int(paddingRange.From)-2 && length <= int(paddingRange.To)+2
	}
	return length >= int(paddingRange.From) && length <= int(paddingRange.To)
}

func (s *Server) extractMetadata(request *http.Request) (string, string) {
	var pathParts []string
	if s.options.GetNormalizedSessionPlacement() == PlacementPath || s.options.GetNormalizedSeqPlacement() == PlacementPath {
		rest := strings.Trim(strings.TrimPrefix(request.URL.Path, s.basePath), "/")
		if rest != "" {
			pathParts = strings.Split(rest, "/")
		}
	}
	nextPath := func() string {
		if len(pathParts) == 0 {
			return ""
		}
		value := pathParts[0]
		pathParts = pathParts[1:]
		return value
	}
	extract := func(placement, key string) string {
		switch placement {
		case PlacementPath:
			return nextPath()
		case PlacementQuery:
			return request.URL.Query().Get(key)
		case PlacementHeader:
			return request.Header.Get(key)
		case PlacementCookie:
			cookie, _ := request.Cookie(key)
			if cookie != nil {
				return cookie.Value
			}
		}
		return ""
	}
	return extract(s.options.GetNormalizedSessionPlacement(), s.options.GetNormalizedSessionKey()),
		extract(s.options.GetNormalizedSeqPlacement(), s.options.GetNormalizedSeqKey())
}

func (s *Server) maxPacketUploadBytes() int {
	return int(s.options.GetNormalizedScMaxEachPostBytes().To)
}

func (s *Server) invalid(writer http.ResponseWriter, request *http.Request, status int, message string) {
	writer.WriteHeader(status)
	s.logger.DebugContext(request.Context(), E.New("process xhttp request from ", request.RemoteAddr, ": ", message))
}

func pathMatches(requestPath, basePath string) bool {
	if !strings.HasPrefix(requestPath, basePath) {
		return false
	}
	if len(requestPath) == len(basePath) || strings.HasSuffix(basePath, "/") {
		return true
	}
	return requestPath[len(basePath)] == '/'
}

func validSessionID(id string) bool {
	if len(id) == 0 || len(id) > maxXHTTPSessionIDLength {
		return false
	}
	for index := 0; index < len(id); index++ {
		value := id[index]
		if value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z' || value >= '0' && value <= '9' || value == '-' || value == '_' {
			continue
		}
		return false
	}
	return true
}

func validHTTPHost(requestHost, expected string) bool {
	requestHost = strings.TrimSpace(strings.ToLower(requestHost))
	expected = strings.TrimSpace(strings.ToLower(expected))
	if host, _, err := net.SplitHostPort(requestHost); err == nil {
		requestHost = host
	}
	if host, _, err := net.SplitHostPort(expected); err == nil {
		expected = host
	}
	return requestHost == expected
}

func validConfiguredHeaders(requestHeaders http.Header, configured map[string]string) bool {
	for key, expected := range configured {
		if len(requestHeaders.Values(key)) == 0 || requestHeaders.Get(key) != expected {
			return false
		}
	}
	return true
}

func validateRequestHeaderOptions(configured map[string]string) error {
	seen := make(map[string]struct{}, len(configured))
	for name := range configured {
		if !httpguts.ValidHeaderFieldName(name) {
			return E.New("invalid xhttp header name: ", name)
		}
		if strings.EqualFold(name, "Host") {
			return E.New("xhttp headers must not contain Host")
		}
		canonicalName := strings.ToLower(http.CanonicalHeaderKey(name))
		if _, exists := seen[canonicalName]; exists {
			return E.New("duplicate xhttp header name: ", name)
		}
		seen[canonicalName] = struct{}{}
	}
	return nil
}

func validateXHTTPPlacements(options V2RayXHTTPOptions) error {
	base := options.V2RayXHTTPBaseOptions
	sessionPlacement := base.GetNormalizedSessionPlacement()
	if !validXHTTPMetadataPlacement(sessionPlacement) {
		return E.New("unsupported xhttp session placement: ", sessionPlacement)
	}
	seqPlacement := base.GetNormalizedSeqPlacement()
	if !validXHTTPMetadataPlacement(seqPlacement) {
		return E.New("unsupported xhttp sequence placement: ", seqPlacement)
	}
	dataPlacement := base.GetNormalizedUplinkDataPlacement()
	if dataPlacement != "body" && dataPlacement != PlacementHeader && dataPlacement != PlacementCookie && dataPlacement != "auto" {
		return E.New("unsupported xhttp uplink data placement: ", dataPlacement)
	}
	paddingPlacement := strings.ToLower(strings.TrimSpace(base.XPaddingPlacement))
	if paddingPlacement == "" {
		if base.XPaddingObfsMode {
			paddingPlacement = PlacementHeader
		} else {
			paddingPlacement = PlacementQueryInHeader
		}
	}
	if paddingPlacement != PlacementHeader && paddingPlacement != PlacementCookie && paddingPlacement != PlacementQuery && paddingPlacement != PlacementQueryInHeader {
		return E.New("unsupported xhttp padding placement: ", paddingPlacement)
	}
	for _, value := range []struct {
		name      string
		placement string
		key       string
	}{
		{name: "session", placement: sessionPlacement, key: base.GetNormalizedSessionKey()},
		{name: "sequence", placement: seqPlacement, key: base.GetNormalizedSeqKey()},
		{name: "uplink data", placement: dataPlacement, key: base.GetNormalizedUplinkDataKey()},
		{name: "padding", placement: paddingPlacement, key: base.XPaddingKey},
	} {
		if value.placement != PlacementHeader && value.placement != PlacementCookie && value.placement != "auto" {
			continue
		}
		if value.name == "padding" && (value.key == "" || paddingPlacement == PlacementQueryInHeader) {
			continue
		}
		if !httpguts.ValidHeaderFieldName(value.key) {
			return E.New("invalid xhttp ", value.name, " key: ", value.key)
		}
		if value.placement == PlacementHeader && (value.name == "session" || value.name == "sequence") && strings.EqualFold(value.key, "Host") {
			return E.New("xhttp ", value.name, " key must not be Host")
		}
	}
	headerName := strings.TrimSpace(base.XPaddingHeader)
	if headerName == "" {
		switch paddingPlacement {
		case PlacementQueryInHeader:
			headerName = "Referer"
		case PlacementHeader:
			headerName = base.XPaddingKey
			if headerName == "" {
				headerName = "X-Padding"
			}
		}
	}
	if paddingPlacement == PlacementHeader || paddingPlacement == PlacementQueryInHeader {
		if !httpguts.ValidHeaderFieldName(headerName) || strings.EqualFold(headerName, "Host") {
			return E.New("invalid xhttp x_padding_header: ", headerName)
		}
	}
	return nil
}

func validXHTTPMetadataPlacement(placement string) bool {
	return placement == PlacementPath || placement == PlacementQuery || placement == PlacementHeader || placement == PlacementCookie
}

func resolveTCPAddr(address string) net.Addr {
	resolved, err := net.ResolveTCPAddr("tcp", address)
	if err != nil {
		return &net.TCPAddr{}
	}
	return resolved
}

type responseConn struct {
	reader     io.Reader
	writer     http.ResponseWriter
	mu         sync.Mutex
	closed     bool
	remoteAddr net.Addr
	localAddr  net.Addr
}

func (c *responseConn) Read(buffer []byte) (int, error) { return c.reader.Read(buffer) }

func (c *responseConn) Write(buffer []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return 0, io.ErrClosedPipe
	}
	written, err := c.writer.Write(buffer)
	if flusher, ok := c.writer.(http.Flusher); ok {
		flusher.Flush()
	}
	return written, err
}

func (c *responseConn) Close() error {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil
	}
	c.closed = true
	if closer, ok := c.reader.(io.Closer); ok {
		c.mu.Unlock()
		return closer.Close()
	}
	c.mu.Unlock()
	return nil
}

func (c *responseConn) LocalAddr() net.Addr  { return c.localAddr }
func (c *responseConn) RemoteAddr() net.Addr { return c.remoteAddr }

func (c *responseConn) SetDeadline(deadline time.Time) error {
	readErr := c.SetReadDeadline(deadline)
	writeErr := c.SetWriteDeadline(deadline)
	if readErr != nil && writeErr != nil {
		return errors.Join(readErr, writeErr)
	}
	if readErr != nil {
		return readErr
	}
	return writeErr
}

func (c *responseConn) SetReadDeadline(deadline time.Time) error {
	return http.NewResponseController(c.writer).SetReadDeadline(deadline)
}

func (c *responseConn) SetWriteDeadline(deadline time.Time) error {
	return http.NewResponseController(c.writer).SetWriteDeadline(deadline)
}

type serverPacket struct {
	sequence uint64
	payload  []byte
}

type serverSession struct {
	id                 string
	reader             *io.PipeReader
	writer             *io.PipeWriter
	packets            chan serverPacket
	closed             chan struct{}
	connected          chan struct{}
	closeOnce          sync.Once
	connectOnce        sync.Once
	streamOnce         sync.Once
	pushMu             sync.Mutex
	bufferedBytes      atomic.Int64
	totalBufferedBytes *atomic.Int64
	maxBuffered        int
}

func newServerSession(id string, maxBuffered int, totalBufferedBytes *atomic.Int64) *serverSession {
	reader, writer := io.Pipe()
	session := &serverSession{
		id:                 id,
		reader:             reader,
		writer:             writer,
		packets:            make(chan serverPacket, maxBuffered),
		closed:             make(chan struct{}),
		connected:          make(chan struct{}),
		totalBufferedBytes: totalBufferedBytes,
		maxBuffered:        maxBuffered,
	}
	go session.copyPackets()
	return session
}

func (s *serverSession) copyPackets() {
	pending := make(map[uint64]serverPacket)
	defer func() {
		for _, packet := range pending {
			s.releasePacket(packet)
		}
		for {
			select {
			case packet := <-s.packets:
				s.releasePacket(packet)
			default:
				return
			}
		}
	}()
	var next uint64
	writePacket := func(packet serverPacket) bool {
		defer s.releasePacket(packet)
		_, err := s.writer.Write(packet.payload)
		return err == nil
	}
	for {
		select {
		case item := <-s.packets:
			if item.sequence < next {
				s.releasePacket(item)
				continue
			}
			if item.sequence != next {
				if len(pending) >= s.maxBuffered {
					s.releasePacket(item)
					continue
				}
				if _, exists := pending[item.sequence]; exists {
					s.releasePacket(item)
				} else {
					pending[item.sequence] = item
				}
				continue
			}
			if !writePacket(item) {
				return
			}
			next++
			for {
				packet, ok := pending[next]
				if !ok {
					break
				}
				delete(pending, next)
				if !writePacket(packet) {
					return
				}
				next++
			}
		case <-s.closed:
			return
		}
	}
}

func (s *serverSession) push(packet serverPacket) bool {
	s.pushMu.Lock()
	defer s.pushMu.Unlock()
	select {
	case <-s.closed:
		return false
	default:
	}
	size := int64(len(packet.payload))
	if size > maxXHTTPBufferedBytesPerSession {
		return false
	}
	if size > 0 {
		if buffered := s.bufferedBytes.Add(size); buffered > maxXHTTPBufferedBytesPerSession {
			s.bufferedBytes.Add(-size)
			return false
		}
		if buffered := s.totalBufferedBytes.Add(size); buffered > maxXHTTPBufferedBytes {
			s.bufferedBytes.Add(-size)
			s.totalBufferedBytes.Add(-size)
			return false
		}
	}
	select {
	case s.packets <- packet:
		return true
	default:
		s.releasePacket(packet)
		return false
	}
}

func (s *serverSession) releasePacket(packet serverPacket) {
	size := int64(len(packet.payload))
	if size > 0 {
		s.bufferedBytes.Add(-size)
		s.totalBufferedBytes.Add(-size)
	}
}

func (s *serverSession) startStream(reader io.ReadCloser) (<-chan struct{}, bool) {
	started := false
	var uploadDone chan struct{}
	s.streamOnce.Do(func() {
		started = true
		uploadDone = make(chan struct{})
		go func() {
			defer close(uploadDone)
			defer reader.Close()
			_, _ = io.Copy(s.writer, reader)
			_ = s.writer.Close()
		}()
	})
	return uploadDone, started
}

func (s *serverSession) markConnected() { s.connectOnce.Do(func() { close(s.connected) }) }

func (s *serverSession) close() {
	s.closeOnce.Do(func() {
		s.pushMu.Lock()
		close(s.closed)
		s.pushMu.Unlock()
		_ = s.reader.Close()
		_ = s.writer.Close()
		s.markConnected()
	})
}

func readPacketPayload(request *http.Request, options V2RayXHTTPOptions, maxBytes int) ([]byte, error) {
	placement := options.GetNormalizedUplinkDataPlacement()
	var headerPayload, cookiePayload, bodyPayload []byte
	var err error
	if placement == PlacementHeader || placement == "auto" {
		headerPayload, err = decodeHeaderPayload(request, options.GetNormalizedUplinkDataKey())
		if err != nil {
			return nil, err
		}
	}
	if placement == PlacementCookie || placement == "auto" {
		cookiePayload, err = decodeCookiePayload(request, options.GetNormalizedUplinkDataKey())
		if err != nil {
			return nil, err
		}
	}
	if placement == "body" || placement == "auto" || placement == "" {
		bodyPayload, err = readRequestBody(request, maxBytes)
		if err != nil {
			return nil, err
		}
	}
	if len(headerPayload) == 0 && len(cookiePayload) == 0 {
		return bodyPayload, nil
	}
	if len(headerPayload) == 0 && len(bodyPayload) == 0 {
		return cookiePayload, nil
	}
	if len(cookiePayload) == 0 && len(bodyPayload) == 0 {
		return headerPayload, nil
	}
	payload := make([]byte, 0, len(headerPayload)+len(cookiePayload)+len(bodyPayload))
	payload = append(payload, headerPayload...)
	payload = append(payload, cookiePayload...)
	payload = append(payload, bodyPayload...)
	return payload, nil
}

func readRequestBody(request *http.Request, maxBytes int) ([]byte, error) {
	if request.Body == nil {
		return nil, nil
	}
	payload, err := io.ReadAll(io.LimitReader(request.Body, int64(maxBytes)+1))
	if err != nil {
		return nil, err
	}
	if len(payload) > maxBytes {
		return nil, fmt.Errorf("xhttp request body exceeds %d bytes", maxBytes)
	}
	return payload, nil
}

func decodeHeaderPayload(request *http.Request, key string) ([]byte, error) {
	var encoded strings.Builder
	for index := 0; ; index++ {
		value := request.Header.Get(fmt.Sprintf("%s-%d", key, index))
		if value == "" {
			break
		}
		encoded.WriteString(value)
	}
	if encoded.Len() == 0 {
		return nil, nil
	}
	return base64.RawURLEncoding.DecodeString(encoded.String())
}

func decodeCookiePayload(request *http.Request, key string) ([]byte, error) {
	var encoded strings.Builder
	for index := 0; ; index++ {
		cookie, _ := request.Cookie(fmt.Sprintf("%s_%d", key, index))
		if cookie == nil {
			break
		}
		encoded.WriteString(cookie.Value)
	}
	if encoded.Len() == 0 {
		return nil, nil
	}
	return base64.RawURLEncoding.DecodeString(encoded.String())
}
