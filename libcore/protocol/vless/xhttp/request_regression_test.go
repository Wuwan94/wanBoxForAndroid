package xhttp

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	Xbadoption "libcore/protocol/vless/internal/xray/badoption"
)

// Exercise the HTTP admission/timeout boundary against real loopback sockets.
// Only shorten requested read deadlines: the fixture cannot create a deadline
// when the transport forgets to request one (as on the pre-hardening baseline).
type regressionDeadlineWriter struct {
	http.ResponseWriter
	cap time.Duration
}

func (w regressionDeadlineWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }
func (w regressionDeadlineWriter) Flush() {
	_ = http.NewResponseController(w.ResponseWriter).Flush()
}
func (w regressionDeadlineWriter) SetReadDeadline(deadline time.Time) error {
	if cap := time.Now().Add(w.cap); w.cap > 0 && !deadline.IsZero() && deadline.After(cap) {
		deadline = cap
	}
	return http.NewResponseController(w.ResponseWriter).SetReadDeadline(deadline)
}

type regressionReadBody struct {
	io.ReadCloser
	once sync.Once
	read chan<- string
	path string
}

func (b *regressionReadBody) Read(p []byte) (int, error) {
	b.once.Do(func() { b.read <- b.path })
	return b.ReadCloser.Read(p)
}

type regressionEchoHandler struct{}

func (regressionEchoHandler) NewConnectionEx(_ context.Context, c net.Conn, _, _ M.Socksaddr, done N.CloseHandlerFunc) {
	go func() {
		_, err := io.Copy(c, c)
		done(err)
	}()
}

func newRequestRegressionServer(t *testing.T, maxBuffered int64, deadlineCap time.Duration) (*httptest.Server, <-chan string) {
	t.Helper()
	transport, err := NewServer(context.Background(), log.NewNOPFactory().Logger(), V2RayXHTTPOptions{V2RayXHTTPBaseOptions: V2RayXHTTPBaseOptions{
		Mode: "packet-up", ScMaxBufferedPosts: maxBuffered, ScMaxEachPostBytes: Xbadoption.Range{From: 256, To: 256},
		XPaddingObfsMode: true, XPaddingPlacement: PlacementHeader,
		XPaddingMethod: "repeat-x", XPaddingBytes: Xbadoption.Range{From: 4, To: 4},
	}}, nil, regressionEchoHandler{})
	if err != nil {
		t.Fatal(err)
	}
	reads := make(chan string, 32)
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost && r.Header.Get("X-Regression-Idle") == "yes" {
			r.Body = &regressionReadBody{ReadCloser: r.Body, read: reads, path: r.URL.Path}
		}
		transport.(*Server).ServeHTTP(regressionDeadlineWriter{ResponseWriter: w, cap: deadlineCap}, r)
	}))
	t.Cleanup(func() {
		// Close transport pipes before waiting for HTTP handlers to finish.
		_ = transport.Close()
		ts.CloseClientConnections()
		ts.Close()
	})
	return ts, reads
}

func regressionClient(t *testing.T, source string) *http.Client {
	t.Helper()
	dialer := &net.Dialer{Timeout: time.Second}
	if source != "" {
		dialer.LocalAddr = &net.TCPAddr{IP: net.ParseIP(source)}
	}
	transport := &http.Transport{DialContext: dialer.DialContext, Proxy: nil}
	t.Cleanup(transport.CloseIdleConnections)
	return &http.Client{Transport: transport, Timeout: 6 * time.Second}
}

func regressionRequest(t *testing.T, c *http.Client, method, url, body string) *http.Response {
	t.Helper()
	r, err := http.NewRequest(method, url, strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	r.Header.Set("X-Padding", "xxxx")
	response, err := c.Do(r)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = response.Body.Close() })
	return response
}

func regressionUpload(t *testing.T, c *http.Client, url, body string, wantStatus int) {
	t.Helper()
	response := regressionRequest(t, c, http.MethodPost, url, body)
	data, err := io.ReadAll(response.Body)
	_ = response.Body.Close()
	if err != nil || response.StatusCode != wantStatus {
		t.Fatalf("POST %s: status=%d body=%q err=%v, want %d", url, response.StatusCode, data, err, wantStatus)
	}
}

func regressionDownlink(t *testing.T, c *http.Client, url string) *http.Response {
	t.Helper()
	response := regressionRequest(t, c, http.MethodGet, url, "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("GET %s: status=%d, want 200", url, response.StatusCode)
	}
	return response
}

func regressionEcho(t *testing.T, down *http.Response, want string) {
	t.Helper()
	data := make([]byte, len(want))
	if _, err := io.ReadFull(down.Body, data); err != nil || string(data) != want {
		t.Fatalf("tunnel echo=%q err=%v, want %q", data, err, want)
	}
}

// Send complete headers and deliberately no body bytes, without closing the
// socket or its write side. Read notifications only synchronize body-read entry;
// all behavior assertions use HTTP responses and tunnel bytes.
func regressionIdlePost(t *testing.T, ts *httptest.Server, path string) net.Conn {
	t.Helper()
	c, err := net.DialTimeout("tcp", ts.Listener.Addr().String(), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	if _, err := fmt.Fprintf(c, "POST %s HTTP/1.1\r\nHost: %s\r\nX-Padding: xxxx\r\nX-Regression-Idle: yes\r\nContent-Length: 1\r\nConnection: close\r\n\r\n", path, ts.Listener.Addr()); err != nil {
		t.Fatal(err)
	}
	return c
}

func regressionAwaitRead(t *testing.T, reads <-chan string, want string) {
	t.Helper()
	select {
	case path := <-reads:
		if path != want {
			t.Fatalf("body read started at %q, want %q", path, want)
		}
	case <-time.After(time.Second):
		t.Fatalf("upload %s did not enter body reading", want)
	}
}

func regressionRawStatus(t *testing.T, c net.Conn, want int) {
	t.Helper()
	if err := c.SetReadDeadline(time.Now().Add(4 * time.Second)); err != nil {
		t.Fatal(err)
	}
	response, err := http.ReadResponse(bufio.NewReader(c), &http.Request{Method: http.MethodPost})
	if err != nil {
		t.Fatalf("idle POST response: %v (want %d)", err, want)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil || response.StatusCode != want {
		t.Fatalf("idle POST status=%d body=%q err=%v, want %d", response.StatusCode, body, err, want)
	}
}

func TestIdleUploadsDoNotBlockOtherSession(t *testing.T) {
	ts, reads := newRequestRegressionServer(t, 0, 0)
	for i := 0; i < 4; i++ {
		path := fmt.Sprintf("/idle-%d/0", i)
		regressionIdlePost(t, ts, path)
		regressionAwaitRead(t, reads, path)
	}
	client := regressionClient(t, "")
	down := regressionDownlink(t, client, ts.URL+"/healthy")
	regressionUpload(t, client, ts.URL+"/healthy/0", "healthy", http.StatusOK)
	regressionEcho(t, down, "healthy")
}

func TestIdleUploadTimeoutReleasesSessionSlots(t *testing.T) {
	ts, reads := newRequestRegressionServer(t, 0, 2*time.Second)
	client := regressionClient(t, "")
	down := regressionDownlink(t, client, ts.URL+"/reuse")
	first := regressionIdlePost(t, ts, "/reuse/0")
	regressionAwaitRead(t, reads, "/reuse/0")
	second := regressionIdlePost(t, ts, "/reuse/1")
	regressionAwaitRead(t, reads, "/reuse/1")
	regressionRawStatus(t, first, http.StatusRequestTimeout)
	regressionRawStatus(t, second, http.StatusRequestTimeout)
	// Both slots must be reusable in the same still-live session, not just one
	// spare slot in the global pool. Neither client closed the expired bodies.
	third := regressionIdlePost(t, ts, "/reuse/0")
	regressionAwaitRead(t, reads, "/reuse/0")
	fourth := regressionIdlePost(t, ts, "/reuse/1")
	regressionAwaitRead(t, reads, "/reuse/1")
	for i, c := range []net.Conn{third, fourth} {
		if _, err := c.Write([]byte{byte('a' + i)}); err != nil {
			t.Fatal(err)
		}
		regressionRawStatus(t, c, http.StatusOK)
	}
	regressionEcho(t, down, "ab")
}

func TestSessionQuotaIsolatesRealPeerSources(t *testing.T) {
	ts, _ := newRequestRegressionServer(t, 0, 0)
	first := regressionClient(t, "127.0.0.2")
	second := regressionClient(t, "127.0.0.3")
	// Keep actual downlinks open so the session table, not a test-mutated
	// counter, reaches the specified per-peer quota of 32.
	for i := 0; i < 32; i++ {
		regressionDownlink(t, first, fmt.Sprintf("%s/peer-one-%d", ts.URL, i))
	}
	rejected := regressionRequest(t, first, http.MethodGet, ts.URL+"/peer-one-overflow", "")
	if rejected.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("33rd session from one peer: status=%d, want 429", rejected.StatusCode)
	}
	_ = rejected.Body.Close()
	down := regressionDownlink(t, second, ts.URL+"/peer-two")
	regressionUpload(t, second, ts.URL+"/peer-two/0", "other-peer", http.StatusOK)
	regressionEcho(t, down, "other-peer")
}

func TestSessionRejectsAnotherRealPeer(t *testing.T) {
	ts, _ := newRequestRegressionServer(t, 0, 0)
	owner := regressionClient(t, "127.0.0.2")
	other := regressionClient(t, "127.0.0.3")
	down := regressionDownlink(t, owner, ts.URL+"/bound-peer")
	regressionUpload(t, other, ts.URL+"/bound-peer/0", "intruder", http.StatusForbidden)
	regressionUpload(t, owner, ts.URL+"/bound-peer/0", "owner", http.StatusOK)
	regressionEcho(t, down, "owner")
}

func TestSessionRejectsSecondDownlink(t *testing.T) {
	ts, _ := newRequestRegressionServer(t, 0, 0)
	client := regressionClient(t, "")
	down := regressionDownlink(t, client, ts.URL+"/one-reader")
	duplicate := regressionRequest(t, client, http.MethodGet, ts.URL+"/one-reader", "")
	if duplicate.StatusCode != http.StatusConflict {
		t.Fatalf("second downlink status=%d, want 409", duplicate.StatusCode)
	}
	_ = duplicate.Body.Close()
	regressionUpload(t, client, ts.URL+"/one-reader/0", "original-reader", http.StatusOK)
	regressionEcho(t, down, "original-reader")
}

func TestPacketOverflowTerminatesHTTPDownlink(t *testing.T) {
	ts, _ := newRequestRegressionServer(t, 1, 0)
	client := regressionClient(t, "")
	down := regressionDownlink(t, client, ts.URL+"/overflow")
	// Sequence zero stays missing. Two distinct future packets exceed the
	// configured single pending-packet budget; silence must not leave GET live.
	regressionUpload(t, client, ts.URL+"/overflow/1", "one", http.StatusOK)
	response := regressionRequest(t, client, http.MethodPost, ts.URL+"/overflow/2", "two")
	if response.StatusCode != http.StatusOK && response.StatusCode != http.StatusConflict {
		t.Fatalf("overflow upload status=%d, want 200 or 409", response.StatusCode)
	}
	_ = response.Body.Close()
	data, err := io.ReadAll(down.Body)
	if err != nil || len(data) != 0 {
		t.Fatalf("overflow downlink must terminate without missing-prefix data: body=%q err=%v", data, err)
	}
}

func TestDownlinkCancellationDropsSessionAndSameIDStartsFreshSession(t *testing.T) {
	transport, err := NewServer(context.Background(), log.NewNOPFactory().Logger(), V2RayXHTTPOptions{V2RayXHTTPBaseOptions: V2RayXHTTPBaseOptions{
		Mode: "packet-up", ScMaxEachPostBytes: Xbadoption.Range{From: 256, To: 256},
		XPaddingObfsMode: true, XPaddingPlacement: PlacementHeader,
		XPaddingMethod: "repeat-x", XPaddingBytes: Xbadoption.Range{From: 4, To: 4},
	}}, nil, regressionEchoHandler{})
	if err != nil {
		t.Fatal(err)
	}
	server := transport.(*Server)
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		server.ServeHTTP(w, r)
	}))
	t.Cleanup(func() {
		_ = transport.Close()
		ts.CloseClientConnections()
		ts.Close()
	})

	client := regressionClient(t, "")
	down := regressionDownlink(t, client, ts.URL+"/deadline-session")
	server.sessionsMu.Lock()
	original := server.sessions["deadline-session"]
	server.sessionsMu.Unlock()
	if original == nil {
		t.Fatal("downlink did not create its server session")
	}
	_ = down.Body.Close()

	removedBy := time.Now().Add(2 * time.Second)
	for {
		server.sessionsMu.Lock()
		current := server.sessions["deadline-session"]
		server.sessionsMu.Unlock()
		if current == nil {
			break
		}
		if time.Now().After(removedBy) {
			t.Fatal("cancelling the downlink left its server session registered")
		}
		time.Sleep(time.Millisecond)
	}

	regressionUpload(t, client, ts.URL+"/deadline-session/0", "new session", http.StatusOK)
	server.sessionsMu.Lock()
	restarted := server.sessions["deadline-session"]
	server.sessionsMu.Unlock()
	if restarted == nil {
		t.Fatal("reusing a closed session ID did not create a new server session")
	}
	if restarted == original {
		t.Fatal("reusing a closed session ID resumed the torn-down server session")
	}
}
