package xhttp

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	Xbadoption "libcore/protocol/vless/internal/xray/badoption"
)

type deadlineResponseWriter struct {
	headers       http.Header
	readDeadline  time.Time
	writeDeadline time.Time
}

func (w *deadlineResponseWriter) Header() http.Header {
	if w.headers == nil {
		w.headers = make(http.Header)
	}
	return w.headers
}

func (*deadlineResponseWriter) Write([]byte) (int, error) { return 0, nil }
func (*deadlineResponseWriter) WriteHeader(int)           {}

func (w *deadlineResponseWriter) SetReadDeadline(deadline time.Time) error {
	w.readDeadline = deadline
	return nil
}

func (w *deadlineResponseWriter) SetWriteDeadline(deadline time.Time) error {
	w.writeDeadline = deadline
	return nil
}

func TestValidSessionID(t *testing.T) {
	for _, test := range []struct {
		id    string
		valid bool
	}{
		{id: "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d", valid: true},
		{id: "session_123", valid: true},
		{id: "", valid: false},
		{id: "has.dot", valid: false},
		{id: "has/slash", valid: false},
		{id: "has space", valid: false},
		{id: "非ASCII", valid: false},
		{id: strings.Repeat("a", maxXHTTPSessionIDLength+1), valid: false},
	} {
		t.Run(test.id, func(t *testing.T) {
			if got := validSessionID(test.id); got != test.valid {
				t.Fatalf("validSessionID(%q) = %t, want %t", test.id, got, test.valid)
			}
		})
	}
}

func TestValidateRequestHeaderOptions(t *testing.T) {
	for _, test := range []struct {
		name      string
		headers   map[string]string
		wantError bool
	}{
		{name: "valid token", headers: map[string]string{"X-Fronted-By": "wanbox-test"}},
		{name: "empty name", headers: map[string]string{"": "value"}, wantError: true},
		{name: "space in name", headers: map[string]string{"X Bad": "value"}, wantError: true},
		{name: "control character", headers: map[string]string{"X-Bad\r\nInjected": "value"}, wantError: true},
		{name: "Host", headers: map[string]string{"Host": "example.com"}, wantError: true},
		{name: "lowercase host", headers: map[string]string{"host": "example.com"}, wantError: true},
		{name: "mixed-case host", headers: map[string]string{"hOsT": "example.com"}, wantError: true},
		{name: "case-insensitive duplicate", headers: map[string]string{"X-Fronted-By": "one", "x-fronted-by": "two"}, wantError: true},
	} {
		t.Run(test.name, func(t *testing.T) {
			err := validateRequestHeaderOptions(test.headers)
			if (err != nil) != test.wantError {
				t.Fatalf("validateRequestHeaderOptions(%v) error = %v, wantError %t", test.headers, err, test.wantError)
			}
		})
	}
}

func TestValidateXHTTPPlacements(t *testing.T) {
	for _, test := range []struct {
		name      string
		configure func(*V2RayXHTTPBaseOptions)
		wantError bool
	}{
		{name: "defaults"},
		{name: "invalid session placement", configure: func(options *V2RayXHTTPBaseOptions) { options.SessionPlacement = "fragment" }, wantError: true},
		{name: "invalid uplink data placement", configure: func(options *V2RayXHTTPBaseOptions) { options.UplinkDataPlacement = "query" }, wantError: true},
		{name: "invalid padding placement", configure: func(options *V2RayXHTTPBaseOptions) { options.XPaddingPlacement = "body" }, wantError: true},
		{name: "invalid session header key", configure: func(options *V2RayXHTTPBaseOptions) {
			options.SessionPlacement = PlacementHeader
			options.SessionKey = "bad key"
		}, wantError: true},
		{name: "Host session header key", configure: func(options *V2RayXHTTPBaseOptions) {
			options.SessionPlacement = PlacementHeader
			options.SessionKey = "Host"
		}, wantError: true},
		{name: "invalid auto data key", configure: func(options *V2RayXHTTPBaseOptions) {
			options.UplinkDataPlacement = "auto"
			options.UplinkDataKey = "bad key"
		}, wantError: true},
		{name: "Host padding header", configure: func(options *V2RayXHTTPBaseOptions) {
			options.XPaddingObfsMode = true
			options.XPaddingPlacement = PlacementQueryInHeader
			options.XPaddingHeader = "hOsT"
		}, wantError: true},
	} {
		t.Run(test.name, func(t *testing.T) {
			var options V2RayXHTTPOptions
			if test.configure != nil {
				test.configure(&options.V2RayXHTTPBaseOptions)
			}
			err := validateXHTTPPlacements(options)
			if (err != nil) != test.wantError {
				t.Fatalf("validateXHTTPPlacements() error = %v, wantError %t", err, test.wantError)
			}
		})
	}
}

func TestClientQueryInHeaderPaddingMatchesServer(t *testing.T) {
	options := V2RayXHTTPBaseOptions{
		XPaddingBytes:     Xbadoption.Range{From: 20, To: 20},
		XPaddingObfsMode:  true,
		XPaddingPlacement: PlacementQueryInHeader,
	}
	request := httptest.NewRequest(http.MethodGet, "http://example.com/xhttp", nil)
	options.ApplyPadding(request)
	server := &Server{options: V2RayXHTTPOptions{V2RayXHTTPBaseOptions: options}}
	if !server.validPadding(request) {
		t.Fatalf("server rejected client queryinheader padding in %q", request.Header.Get("Referer"))
	}
}

func TestClientHeaderPaddingMatchesServer(t *testing.T) {
	options := V2RayXHTTPBaseOptions{
		XPaddingBytes:     Xbadoption.Range{From: 20, To: 20},
		XPaddingObfsMode:  true,
		XPaddingPlacement: PlacementHeader,
		XPaddingKey:       "Custom-Padding-Key",
	}
	request := httptest.NewRequest(http.MethodGet, "http://example.com/xhttp", nil)
	options.ApplyPadding(request)
	server := &Server{options: V2RayXHTTPOptions{V2RayXHTTPBaseOptions: options}}
	if !server.validPadding(request) {
		t.Fatalf("server rejected client custom header padding %q", request.Header.Get("Custom-Padding-Key"))
	}
}

func TestResponseConnForwardsDeadlines(t *testing.T) {
	reader, writer := io.Pipe()
	defer reader.Close()
	defer writer.Close()
	responseWriter := &deadlineResponseWriter{}
	conn := &responseConn{reader: reader, writer: responseWriter}
	deadline := time.Now().Add(time.Minute)
	if err := conn.SetDeadline(deadline); err != nil {
		t.Fatalf("set response connection deadline: %v", err)
	}
	if !responseWriter.readDeadline.Equal(deadline) || !responseWriter.writeDeadline.Equal(deadline) {
		t.Fatalf("forwarded deadlines = (%v, %v), want (%v, %v)", responseWriter.readDeadline, responseWriter.writeDeadline, deadline, deadline)
	}
}

func TestOptionsPreflightSkipsConfiguredRequestHeaders(t *testing.T) {
	server := &Server{
		options: V2RayXHTTPOptions{V2RayXHTTPBaseOptions: V2RayXHTTPBaseOptions{
			Headers:          map[string]string{"X-Fronted-By": "wanbox-test"},
			SessionPlacement: PlacementCookie,
		}},
		basePath: "/xhttp",
	}
	request := httptest.NewRequest(http.MethodOptions, "http://example.com/xhttp", nil)
	request.Header.Set("Origin", "https://example.com")
	request.Header.Set("Access-Control-Request-Method", http.MethodPost)
	request.Header.Set("Access-Control-Request-Headers", "content-type, x-padding, x-session-id")
	response := httptest.NewRecorder()
	server.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("OPTIONS status = %d, want %d", response.Code, http.StatusOK)
	}
	if got := response.Header().Get("Access-Control-Allow-Origin"); got != "https://example.com" {
		t.Fatalf("Access-Control-Allow-Origin = %q, want request origin", got)
	}
	if got := response.Header().Get("Access-Control-Allow-Methods"); !strings.Contains(got, http.MethodGet) || !strings.Contains(got, http.MethodPost) {
		t.Fatalf("Access-Control-Allow-Methods = %q, want GET and POST", got)
	}
	if got := response.Header().Get("Access-Control-Allow-Headers"); got != "content-type, x-padding, x-session-id" {
		t.Fatalf("Access-Control-Allow-Headers = %q, want requested headers", got)
	}
	if got := response.Header().Get("Access-Control-Allow-Credentials"); got != "" {
		t.Fatalf("Access-Control-Allow-Credentials = %q, want no credentialed CORS by default", got)
	}
	if got := response.Header().Get("Vary"); !strings.Contains(got, "Origin") || !strings.Contains(got, "Access-Control-Request-Headers") {
		t.Fatalf("Vary = %q, want Origin and Access-Control-Request-Headers", got)
	}
}

func TestServerUsesConfiguredMaximumPacketBytes(t *testing.T) {
	server := &Server{options: V2RayXHTTPOptions{V2RayXHTTPBaseOptions: V2RayXHTTPBaseOptions{
		ScMaxEachPostBytes: Xbadoption.Range{From: 1024, To: 4096},
	}}}
	if got := server.maxPacketUploadBytes(); got != 4096 {
		t.Fatalf("maxPacketUploadBytes() = %d, want configured upper bound 4096", got)
	}
}

func TestServerSessionBufferLimitsAndCloseRelease(t *testing.T) {
	var totalBufferedBytes atomic.Int64
	buffer := make([]byte, maxXHTTPBufferedBytesPerSession)
	perSessionLimit := newServerSession("per-session-limit", 2, &totalBufferedBytes)
	if !perSessionLimit.push(serverPacket{sequence: 1, payload: buffer}) {
		t.Fatal("session rejected a packet at its per-session byte limit")
	}
	if perSessionLimit.push(serverPacket{sequence: 2, payload: []byte{1}}) {
		t.Fatal("session accepted a packet beyond its per-session byte limit")
	}
	perSessionLimit.close()
	deadline := time.Now().Add(2 * time.Second)
	for totalBufferedBytes.Load() != 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if got := totalBufferedBytes.Load(); got != 0 {
		t.Fatalf("total buffered bytes after per-session close = %d, want 0", got)
	}

	sessions := make([]*serverSession, 0, maxXHTTPBufferedBytes/maxXHTTPBufferedBytesPerSession)
	for index := 0; index < cap(sessions); index++ {
		session := newServerSession("session", 2, &totalBufferedBytes)
		if !session.push(serverPacket{sequence: 1, payload: buffer}) {
			t.Fatalf("session %d rejected a packet within its buffer limit", index)
		}
		if got := session.bufferedBytes.Load(); got != maxXHTTPBufferedBytesPerSession {
			t.Fatalf("session %d buffered bytes = %d, want %d", index, got, maxXHTTPBufferedBytesPerSession)
		}
		sessions = append(sessions, session)
	}
	if got := totalBufferedBytes.Load(); got != maxXHTTPBufferedBytes {
		t.Fatalf("total buffered bytes = %d, want %d", got, maxXHTTPBufferedBytes)
	}

	globalLimit := newServerSession("global-limit", 2, &totalBufferedBytes)
	if globalLimit.push(serverPacket{sequence: 1, payload: []byte{1}}) {
		t.Fatal("session accepted a packet beyond the global byte limit")
	}
	globalLimit.close()

	for _, session := range sessions {
		session.close()
	}
	deadline = time.Now().Add(2 * time.Second)
	for totalBufferedBytes.Load() != 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if got := totalBufferedBytes.Load(); got != 0 {
		t.Fatalf("total buffered bytes after close = %d, want 0", got)
	}
	for index, session := range sessions {
		if got := session.bufferedBytes.Load(); got != 0 {
			t.Fatalf("session %d buffered bytes after close = %d, want 0", index, got)
		}
	}
}
