package xhttp

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/netip"
	"strings"
	"sync"
	"time"
)

const (
	maxXHTTPSessionsPerIP     = 32
	maxXHTTPUploads           = 64
	maxXHTTPUploadsPerIP      = 8
	maxXHTTPUploadsPerSession = 2
	maxXHTTPReadingBytes      = 24 << 20
)

var (
	errSessionClosed  = errors.New("xhttp session closed")
	errSessionSource  = errors.New("xhttp session belongs to a different peer")
	errDownlinkActive = errors.New("xhttp session already has a downlink")
	errSessionLimit   = errors.New("xhttp session quota reached")
)

// Only the actual socket peer controls quota and binding; forwarded headers are untrusted.
func directSourceIP(remoteAddr string) (netip.Addr, error) {
	peer, err := netip.ParseAddrPort(remoteAddr)
	return peer.Addr().Unmap(), err
}

type uploadAdmission struct {
	mu           sync.Mutex
	global       int
	readingBytes int64
	byIP         map[netip.Addr]int
	bySession    map[uploadScope]int
}
type uploadScope struct {
	source netip.Addr
	id     string
}

func newUploadAdmission() uploadAdmission {
	return uploadAdmission{byIP: make(map[netip.Addr]int), bySession: make(map[uploadScope]int)}
}

func (a *uploadAdmission) acquire(source netip.Addr, id string, bytes int64) func() {
	a.mu.Lock()
	defer a.mu.Unlock()
	scope := uploadScope{source, id}
	if a.global >= maxXHTTPUploads || a.byIP[source] >= maxXHTTPUploadsPerIP || (id != "" && a.bySession[scope] >= maxXHTTPUploadsPerSession) || a.readingBytes+bytes > maxXHTTPReadingBytes {
		return nil
	}
	a.global++
	a.readingBytes += bytes
	a.byIP[source]++
	if id != "" {
		a.bySession[scope]++
	}
	return func() {
		a.mu.Lock()
		defer a.mu.Unlock()
		a.global--
		a.readingBytes -= bytes
		a.byIP[source]--
		if a.byIP[source] == 0 {
			delete(a.byIP, source)
		}
		if id != "" {
			a.bySession[scope]--
			if a.bySession[scope] == 0 {
				delete(a.bySession, scope)
			}
		}
	}
}

// An upload only refreshes its deadline when another Read is issued after forward progress.
// The first empty read starts the timer; repeated empty reads cannot extend it.
type deadlineBody struct {
	io.Reader
	controller   *http.ResponseController
	timeout      time.Duration
	needDeadline bool
	started      bool
	eof          bool
}

func (b *deadlineBody) Read(p []byte) (int, error) {
	if !b.started || b.needDeadline {
		if err := b.controller.SetReadDeadline(time.Now().Add(b.timeout)); err != nil {
			return 0, err
		}
		b.started = true
		b.needDeadline = false
	}
	n, err := b.Reader.Read(p)
	b.needDeadline = n > 0
	b.eof = err == io.EOF
	return n, err
}
func clearUploadDeadline(w http.ResponseWriter) {
	_ = http.NewResponseController(w).SetReadDeadline(time.Time{})
}

func isPreflight(r *http.Request) bool {
	return r.Method == http.MethodOptions && strings.TrimSpace(r.Header.Get("Access-Control-Request-Method")) != ""
}

// x/net's HTTP/1.1 upgrade path reads the entire body before the transport handler.
// Keep bodyless upgrades and HTTP/2 prior knowledge; don't let upgrades bypass upload limits.
func bodylessH2CUpgrade(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if headerHasH2C(r.Header.Values("Upgrade")) && expectsUploadBody(r) {
			_ = http.NewResponseController(w).EnableFullDuplex()
			w.Header().Set("Connection", "close")
			http.Error(w, "h2c upgrade must not contain a request body", http.StatusBadRequest)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func expectsUploadBody(r *http.Request) bool {
	return r.ContentLength != 0 || len(r.TransferEncoding) > 0
}

func sessionDone(s *serverSession) <-chan struct{} {
	if s == nil {
		return nil
	}
	return s.closed
}

// Queue permits only one writer to the tunnel at a time. The upload admission
// limit bounds the queue; each HTTP handler retains ownership of its own body.
func (s *serverSession) waitStream(ctx context.Context) bool {
	s.streamMu.Lock()
	if ctx.Err() != nil {
		s.streamMu.Unlock()
		return false
	}
	select {
	case <-s.closed:
		s.streamMu.Unlock()
		return false
	default:
	}
	if !s.streamBusy {
		s.streamBusy = true
		s.streamMu.Unlock()
		return true
	}
	ready := make(chan struct{})
	s.streamQueue = append(s.streamQueue, ready)
	s.streamMu.Unlock()
	select {
	case <-ready:
	case <-ctx.Done():
	case <-s.closed:
	}
	s.streamMu.Lock()
	defer s.streamMu.Unlock()
	if ctx.Err() == nil {
		select {
		case <-s.closed:
		default:
			return true
		}
	}
	for i, waiter := range s.streamQueue {
		if waiter == ready {
			s.streamQueue = append(s.streamQueue[:i], s.streamQueue[i+1:]...)
			return false
		}
	}
	s.finishStreamLocked()
	return false
}
func (s *serverSession) finishStream() {
	s.streamMu.Lock()
	defer s.streamMu.Unlock()
	s.finishStreamLocked()
}
func (s *serverSession) finishStreamLocked() {
	if len(s.streamQueue) == 0 {
		s.streamBusy = false
		return
	}
	ready := s.streamQueue[0]
	s.streamQueue[0] = nil
	s.streamQueue = s.streamQueue[1:]
	close(ready)
}

func headerHasH2C(values []string) bool {
	for _, value := range values {
		for _, token := range strings.Split(value, ",") {
			if strings.EqualFold(strings.TrimSpace(token), "h2c") {
				return true
			}
		}
	}
	return false
}

func (s *serverSession) claimUploadMode(packet bool) bool {
	mode := "stream"
	if packet {
		mode = "packet"
	}
	s.streamMu.Lock()
	defer s.streamMu.Unlock()
	if s.uploadMode == "" {
		s.uploadMode = mode
	}
	return s.uploadMode == mode
}
