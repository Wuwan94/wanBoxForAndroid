package xhttp

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	Xbadoption "libcore/protocol/vless/internal/xray/badoption"
)

type tunnelHandler func(net.Conn, N.CloseHandlerFunc)

func (f tunnelHandler) NewConnectionEx(_ context.Context, c net.Conn, _, _ M.Socksaddr, onClose N.CloseHandlerFunc) {
	f(c, onClose)
}

func TestInboundRejectsUnsupportedOptions(t *testing.T) {
	for _, tc := range []struct {
		name string
		opts V2RayXHTTPBaseOptions
	}{
		{"method", V2RayXHTTPBaseOptions{UplinkHttpMethod: http.MethodOptions}},
		{"keepalive", V2RayXHTTPBaseOptions{ScStreamUpServerSecs: Xbadoption.Range{From: 1, To: 2}}},
		{"interval", V2RayXHTTPBaseOptions{ScMinPostsIntervalMs: Xbadoption.Range{From: 20, To: 10}}},
		{"post-memory", V2RayXHTTPBaseOptions{ScMaxEachPostBytes: Xbadoption.Range{From: 1000000, To: 4 << 20}}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s, err := NewServer(context.Background(), log.NewNOPFactory().Logger(), V2RayXHTTPOptions{V2RayXHTTPBaseOptions: tc.opts}, nil, nil)
			if err == nil {
				defer s.Close()
				t.Fatal("unsupported configuration accepted")
			}
		})
	}
}

func TestOptionsWithoutRequestedMethodIsNotPreflight(t *testing.T) {
	s, _ := NewServer(context.Background(), log.NewNOPFactory().Logger(), V2RayXHTTPOptions{}, nil, nil)
	defer s.Close()
	w := httptest.NewRecorder()
	s.(*Server).ServeHTTP(w, httptest.NewRequest(http.MethodOptions, "http://example.com/", nil))
	if w.Code != http.StatusMethodNotAllowed || w.Header().Get("Access-Control-Allow-Methods") != "" {
		t.Fatalf("non-preflight status=%d headers=%v", w.Code, w.Header())
	}
}

func TestSequentialStreamUploadsReachOneTunnel(t *testing.T) {
	got := make(chan string, 1)
	handler := tunnelHandler(func(c net.Conn, done N.CloseHandlerFunc) {
		go func() {
			b := make([]byte, 6)
			n, err := io.ReadFull(c, b)
			got <- string(b[:n])
			done(err)
		}()
	})
	s, err := NewServer(context.Background(), log.NewNOPFactory().Logger(), V2RayXHTTPOptions{V2RayXHTTPBaseOptions: V2RayXHTTPBaseOptions{
		Mode: "stream-up", XPaddingObfsMode: true, XPaddingPlacement: PlacementHeader, XPaddingMethod: "repeat-x", XPaddingBytes: Xbadoption.Range{From: 4, To: 4},
	}}, nil, handler)
	if err != nil {
		t.Fatal(err)
	}
	ts := httptest.NewServer(s.(*Server))
	defer ts.Close()
	defer s.Close()
	c := ts.Client()
	c.Timeout = 2 * time.Second
	req := func(method, body string) *http.Response {
		r, err := http.NewRequest(method, ts.URL+"/continuation", strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		r.Header.Set("X-Padding", "xxxx")
		response, err := c.Do(r)
		if err != nil {
			t.Fatal(err)
		}
		return response
	}
	down := req(http.MethodGet, "")
	defer down.Body.Close()
	if down.StatusCode != 200 {
		t.Fatalf("downlink status = %d", down.StatusCode)
	}
	for _, part := range []string{"one", "two"} {
		up := req(http.MethodPost, part)
		_, err := io.Copy(io.Discard, up.Body)
		up.Body.Close()
		if up.StatusCode != 200 || err != nil {
			t.Fatalf("uplink %q status=%d err=%v", part, up.StatusCode, err)
		}
	}
	select {
	case data := <-got:
		if data != "onetwo" {
			t.Fatalf("tunnel received %q", data)
		}
	case <-time.After(time.Second):
		t.Fatal("tunnel did not receive all bytes")
	}
}

type deadlineRecorder struct {
	*httptest.ResponseRecorder
	updates []time.Time
}

func (w *deadlineRecorder) SetReadDeadline(deadline time.Time) error {
	w.updates = append(w.updates, deadline)
	return nil
}

type uploadReaderFunc func([]byte) (int, error)

func (f uploadReaderFunc) Read(p []byte) (int, error) { return f(p) }

func TestUploadDeadlineRefreshOnlyAfterProgress(t *testing.T) {
	w := &deadlineRecorder{ResponseRecorder: httptest.NewRecorder()}
	calls := 0
	body := &deadlineBody{controller: http.NewResponseController(w), timeout: time.Second, Reader: uploadReaderFunc(func(p []byte) (int, error) {
		calls++
		switch calls {
		case 1, 3:
			return 0, nil
		case 2:
			p[0] = 'a'
			return 1, nil
		default:
			return 0, os.ErrDeadlineExceeded
		}
	})}
	for i, wantUpdates := range []int{1, 1, 2, 2} {
		_, err := body.Read(make([]byte, 1))
		if i == 3 && !errors.Is(err, os.ErrDeadlineExceeded) {
			t.Fatalf("deadline error = %v", err)
		}
		if len(w.updates) != wantUpdates {
			t.Fatalf("read %d: deadline writes = %d, want %d", i, len(w.updates), wantUpdates)
		}
	}
}

func TestUploadAdmissionScopedLimitsAndRelease(t *testing.T) {
	a := newUploadAdmission()
	first, second := netip.MustParseAddr("192.0.2.5"), netip.MustParseAddr("192.0.2.6")
	a.byIP[first] = maxXHTTPUploadsPerIP
	if release := a.acquire(first, "", 0); release != nil {
		release()
		t.Fatal("source over quota was admitted")
	}
	a.readingBytes = maxXHTTPReadingBytes - 1
	if release := a.acquire(second, "session", 2); release != nil {
		release()
		t.Fatal("upload reserved more than the read budget")
	}
	release := a.acquire(second, "session", 1)
	if release == nil {
		t.Fatal("another source was denied an available slot")
	}
	release()
	if a.global != 0 || len(a.bySession) != 0 || a.byIP[second] != 0 || a.readingBytes != maxXHTTPReadingBytes-1 {
		t.Fatal("upload did not release all reservations")
	}
}

func TestSessionBindingAdmissionAndCleanup(t *testing.T) {
	transport, err := NewServer(context.Background(), log.NewNOPFactory().Logger(), V2RayXHTTPOptions{}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	s := transport.(*Server)
	defer s.Close()
	first, second := netip.MustParseAddr("192.0.2.5"), netip.MustParseAddr("192.0.2.6")
	s.sessionsPerIP[first] = maxXHTTPSessionsPerIP
	if _, err := s.session("another-session", first, true); !errors.Is(err, errSessionLimit) {
		t.Fatalf("quota denial = %v", err)
	}
	session, err := s.session("valid-session", second, true)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.session("valid-session", first, false); !errors.Is(err, errSessionSource) {
		t.Fatalf("wrong source denial = %v", err)
	}
	if _, err := s.session("valid-session", second, true); !errors.Is(err, errDownlinkActive) {
		t.Fatalf("duplicate downlink denial = %v", err)
	}
	s.closeSession("valid-session", session)
	if s.sessions["valid-session"] != nil || s.sessionsPerIP[second] != 0 {
		t.Fatal("closed session retained a reservation")
	}
}

func TestReadFailureReleasesUploadReservation(t *testing.T) {
	transport, err := NewServer(context.Background(), log.NewNOPFactory().Logger(), V2RayXHTTPOptions{V2RayXHTTPBaseOptions: V2RayXHTTPBaseOptions{
		Mode: "packet-up", XPaddingObfsMode: true, XPaddingPlacement: PlacementHeader, XPaddingMethod: "repeat-x", XPaddingBytes: Xbadoption.Range{From: 4, To: 4},
	}}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	s := transport.(*Server)
	defer s.Close()
	r := httptest.NewRequest(http.MethodPost, "http://example.com/isolated-session/0", uploadReaderFunc(func([]byte) (int, error) {
		return 0, os.ErrDeadlineExceeded
	}))
	r.Header.Set("X-Padding", "xxxx")
	w := &deadlineRecorder{ResponseRecorder: httptest.NewRecorder()}
	s.ServeHTTP(w, r)
	if w.Code != http.StatusRequestTimeout || s.uploadLimits.global != 0 || s.uploadLimits.readingBytes != 0 {
		t.Fatalf("status=%d slots=%d bytes=%d", w.Code, s.uploadLimits.global, s.uploadLimits.readingBytes)
	}
}

func TestTunnelFailureDropsSessionImmediately(t *testing.T) {
	handler := tunnelHandler(func(_ net.Conn, done N.CloseHandlerFunc) { done(errors.New("test handshake failure")) })
	transport, err := NewServer(context.Background(), log.NewNOPFactory().Logger(), V2RayXHTTPOptions{V2RayXHTTPBaseOptions: V2RayXHTTPBaseOptions{
		Mode: "packet-up", XPaddingObfsMode: true, XPaddingPlacement: PlacementHeader, XPaddingMethod: "repeat-x", XPaddingBytes: Xbadoption.Range{From: 4, To: 4},
	}}, nil, handler)
	if err != nil {
		t.Fatal(err)
	}
	s := transport.(*Server)
	defer s.Close()
	r := httptest.NewRequest(http.MethodGet, "http://example.com/isolated-session", nil)
	r.Header.Set("X-Padding", "xxxx")
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	if w.Code != http.StatusOK || len(s.sessions) != 0 || len(s.sessionsPerIP) != 0 {
		t.Fatalf("status=%d registered sessions=%d peer reservations=%d", w.Code, len(s.sessions), len(s.sessionsPerIP))
	}
}

func TestPacketAssemblerClosesAndReleasesOnBufferOverflow(t *testing.T) {
	var total atomic.Int64
	reader, writer := io.Pipe()
	session := &serverSession{reader: reader, writer: writer, closed: make(chan struct{}), connected: make(chan struct{}),
		packets: make(chan serverPacket, 2), maxBuffered: 1, totalBufferedBytes: &total}
	defer session.close()
	for _, seq := range []uint64{1, 2} {
		if !session.push(serverPacket{sequence: seq, payload: []byte{'x'}}) {
			t.Fatal("packet did not enter test queue")
		}
	}
	done := make(chan struct{})
	go func() { session.copyPackets(); close(done) }()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("packet assembler did not stop")
	}
	select {
	case <-session.closed:
	default:
		t.Fatal("session survived an overflow")
	}
	if total.Load() != 0 || session.bufferedBytes.Load() != 0 {
		t.Fatal("overflow did not release buffered bytes")
	}
}
