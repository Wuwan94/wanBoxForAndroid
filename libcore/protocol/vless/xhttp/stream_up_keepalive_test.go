package xhttp

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/log"
	N "github.com/sagernet/sing/common/network"
	Xbadoption "libcore/protocol/vless/internal/xray/badoption"
)

func TestStreamUpResponseWritesConfiguredKeepalivePadding(t *testing.T) {
	transport, err := NewServer(context.Background(), log.NewNOPFactory().Logger(), V2RayXHTTPOptions{V2RayXHTTPBaseOptions: V2RayXHTTPBaseOptions{
		Mode:                 "stream-up",
		ScStreamUpServerSecs: Xbadoption.Range{From: 1, To: 1},
		XPaddingBytes:        Xbadoption.Range{From: 4, To: 4},
		XPaddingObfsMode:     true,
		XPaddingPlacement:    PlacementHeader,
		XPaddingMethod:       "repeat-x",
	}}, nil, tunnelHandler(func(net.Conn, N.CloseHandlerFunc) {}))
	if err != nil {
		t.Fatalf("NewServer rejected a valid stream-up keepalive interval: %v", err)
	}

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		_ = transport.Close()
		t.Fatal(err)
	}
	serveDone := make(chan error, 1)
	go func() { serveDone <- transport.Serve(listener) }()

	clientTransport := &http.Transport{Proxy: nil}
	client := &http.Client{Transport: clientTransport, Timeout: 4 * time.Second}
	requestBody, uploadBody := io.Pipe()
	request, err := http.NewRequest(http.MethodPost, "http://"+listener.Addr().String()+"/keepalive", requestBody)
	if err != nil {
		_ = uploadBody.Close()
		_ = transport.Close()
		t.Fatal(err)
	}
	request.Header.Set("X-Padding", "xxxx")
	var response *http.Response
	t.Cleanup(func() {
		_ = uploadBody.Close()
		if response != nil {
			_ = response.Body.Close()
		}
		client.CloseIdleConnections()
		_ = transport.Close()
		select {
		case <-serveDone:
		case <-time.After(time.Second):
			t.Error("XHTTP server did not stop after Close")
		}
	})

	response, err = client.Do(request)
	if err != nil {
		t.Fatalf("stream-up request: %v", err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("stream-up status = %d, want %d", response.StatusCode, http.StatusOK)
	}

	for index := 1; index <= 2; index++ {
		padding := make([]byte, 4)
		if _, err := io.ReadFull(response.Body, padding); err != nil {
			t.Fatalf("read stream-up keepalive padding %d: %v", index, err)
		}
		if string(padding) != "XXXX" {
			t.Fatalf("stream-up keepalive padding %d = %q, want %q", index, padding, "XXXX")
		}
	}
}

func TestStreamUpResponseKeepaliveDefaultIntervalRange(t *testing.T) {
	interval := (&V2RayXHTTPBaseOptions{}).GetNormalizedScStreamUpServerSecs()
	if interval.From != 20 || interval.To != 80 {
		t.Fatalf("default stream-up keepalive interval = %+v, want 20-80 seconds", interval)
	}
}

func TestStreamUpResponseKeepaliveLegacyRefererCompatibility(t *testing.T) {
	transport, err := NewServer(context.Background(), log.NewNOPFactory().Logger(), V2RayXHTTPOptions{V2RayXHTTPBaseOptions: V2RayXHTTPBaseOptions{
		Mode:                 "stream-up",
		ScStreamUpServerSecs: Xbadoption.Range{From: 1, To: 1},
		XPaddingBytes:        Xbadoption.Range{From: 4, To: 4},
	}}, nil, tunnelHandler(func(net.Conn, N.CloseHandlerFunc) {}))
	if err != nil {
		t.Fatal(err)
	}
	var listener net.Listener
	var serveDone chan error
	t.Cleanup(func() {
		_ = transport.Close()
		if listener != nil {
			_ = listener.Close()
		}
		if serveDone != nil {
			select {
			case <-serveDone:
			case <-time.After(time.Second):
				t.Error("XHTTP server did not stop after Close")
			}
		}
	})
	listener, err = net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	serveDone = make(chan error, 1)
	go func() { serveDone <- transport.Serve(listener) }()

	client := &http.Client{Transport: &http.Transport{Proxy: nil}, Timeout: 4 * time.Second}
	t.Cleanup(client.CloseIdleConnections)
	requestBody, uploadBody := io.Pipe()
	t.Cleanup(func() {
		_ = uploadBody.Close()
		_ = requestBody.Close()
	})
	request, err := http.NewRequest(http.MethodPost, "http://"+listener.Addr().String()+"/legacy-referer-keepalive", requestBody)
	if err != nil {
		t.Fatal(err)
	}
	// Zero-value padding options retain the legacy Referer compatibility path.
	request.Header.Set("Referer", "https://client.example/?x_padding=XXXX")
	response, err := client.Do(request)
	if response != nil {
		t.Cleanup(func() { _ = response.Body.Close() })
	}
	if err != nil {
		t.Fatalf("legacy Referer stream-up request: %v", err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("legacy Referer stream-up status = %d, want %d", response.StatusCode, http.StatusOK)
	}
	padding := make([]byte, 4)
	if _, err := io.ReadFull(response.Body, padding); err != nil {
		t.Fatalf("read first legacy Referer stream-up response padding: %v", err)
	}
	if string(padding) != "XXXX" {
		t.Fatalf("first legacy Referer stream-up response padding = %q, want %q", padding, "XXXX")
	}
}

func TestStreamUpResponseKeepaliveNotActivatedWithoutRefererInNonObfsMode(t *testing.T) {
	transport, err := NewServer(context.Background(), log.NewNOPFactory().Logger(), V2RayXHTTPOptions{V2RayXHTTPBaseOptions: V2RayXHTTPBaseOptions{
		Mode:                 "stream-up",
		ScStreamUpServerSecs: Xbadoption.Range{From: 1, To: 1},
		XPaddingBytes:        Xbadoption.Range{From: 4, To: 4},
		XPaddingObfsMode:     false,
		XPaddingPlacement:    PlacementHeader,
		XPaddingMethod:       "repeat-x",
	}}, nil, tunnelHandler(func(net.Conn, N.CloseHandlerFunc) {}))
	if err != nil {
		t.Fatal(err)
	}
	var listener net.Listener
	var serveDone chan error
	t.Cleanup(func() {
		_ = transport.Close()
		if listener != nil {
			_ = listener.Close()
		}
		if serveDone != nil {
			select {
			case err := <-serveDone:
				if err != nil {
					t.Errorf("XHTTP Serve: %v", err)
				}
			case <-time.After(time.Second):
				t.Error("XHTTP server did not stop after Close")
			}
		}
	})
	listener, err = net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	serveDone = make(chan error, 1)
	go func() { serveDone <- transport.Serve(listener) }()

	client := &http.Client{Transport: &http.Transport{Proxy: nil}, Timeout: 4 * time.Second}
	t.Cleanup(client.CloseIdleConnections)
	request, err := http.NewRequest(http.MethodPost, "http://"+listener.Addr().String()+"/no-referer-non-obfs", nil)
	if err != nil {
		t.Fatal(err)
	}
	// Explicit header padding is valid without the legacy Referer keepalive marker.
	request.Header.Set("X-Padding", "xxxx")
	response, err := client.Do(request)
	if response != nil {
		t.Cleanup(func() { _ = response.Body.Close() })
	}
	if err != nil {
		t.Fatalf("no-Referer/non-obfs stream-up request: %v", err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("stream-up status = %d, want %d", response.StatusCode, http.StatusOK)
	}
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("read no-Referer/non-obfs response to EOF: %v", err)
	}
	if len(body) != 0 {
		t.Fatalf("no-Referer/non-obfs keepalive wrote response padding %q, want an empty body", body)
	}
}

func TestStreamUpResponseKeepaliveStopsOnRequestCancellation(t *testing.T) {
	transport, err := NewServer(context.Background(), log.NewNOPFactory().Logger(), V2RayXHTTPOptions{V2RayXHTTPBaseOptions: V2RayXHTTPBaseOptions{
		Mode:                 "stream-up",
		ScStreamUpServerSecs: Xbadoption.Range{From: 1, To: 1},
		XPaddingBytes:        Xbadoption.Range{From: 4, To: 4},
		XPaddingObfsMode:     true,
		XPaddingPlacement:    PlacementHeader,
		XPaddingMethod:       "repeat-x",
	}}, nil, tunnelHandler(func(net.Conn, N.CloseHandlerFunc) {}))
	if err != nil {
		t.Fatal(err)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		_ = transport.Close()
		t.Fatal(err)
	}
	serveDone := make(chan error, 1)
	go func() { serveDone <- transport.Serve(listener) }()

	client := &http.Client{Transport: &http.Transport{Proxy: nil}, Timeout: 5 * time.Second}
	ctx, cancel := context.WithCancel(context.Background())
	requestBody, uploadBody := io.Pipe()
	var response *http.Response
	t.Cleanup(func() {
		cancel()
		_ = uploadBody.Close()
		_ = requestBody.Close()
		if response != nil {
			_ = response.Body.Close()
		}
		client.CloseIdleConnections()
		_ = transport.Close()
		select {
		case err := <-serveDone:
			if err != nil {
				t.Errorf("XHTTP Serve: %v", err)
			}
		case <-time.After(time.Second):
			t.Error("XHTTP server did not stop after Close")
		}
	})

	request, err := http.NewRequestWithContext(ctx, http.MethodPost, "http://"+listener.Addr().String()+"/cancel-keepalive", requestBody)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("X-Padding", "xxxx")
	response, err = client.Do(request)
	if err != nil {
		t.Fatalf("stream-up request: %v", err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("stream-up status = %d, want %d", response.StatusCode, http.StatusOK)
	}
	padding := make([]byte, 4)
	if _, err := io.ReadFull(response.Body, padding); err != nil {
		t.Fatalf("read initial stream-up keepalive padding: %v", err)
	}
	if string(padding) != "XXXX" {
		t.Fatalf("initial stream-up keepalive padding = %q, want %q", padding, "XXXX")
	}

	readDone := make(chan error, 1)
	go func() {
		_, err := io.Copy(io.Discard, response.Body)
		readDone <- err
	}()
	// Keep the upload pipe open so only context cancellation ends the request.
	cancel()
	select {
	case err := <-readDone:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("response read after cancellation = %v, want context.Canceled", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("stream-up response read remained blocked after request cancellation")
	}
}

func TestStreamUpResponseKeepaliveCanBeDisabled(t *testing.T) {
	transport, err := NewServer(context.Background(), log.NewNOPFactory().Logger(), V2RayXHTTPOptions{V2RayXHTTPBaseOptions: V2RayXHTTPBaseOptions{
		Mode:                 "stream-up",
		ScStreamUpServerSecs: Xbadoption.Range{From: -1, To: -1},
		XPaddingBytes:        Xbadoption.Range{From: 4, To: 4},
		XPaddingObfsMode:     true,
		XPaddingPlacement:    PlacementHeader,
		XPaddingMethod:       "repeat-x",
	}}, nil, tunnelHandler(func(net.Conn, N.CloseHandlerFunc) {}))
	if err != nil {
		t.Fatalf("NewServer rejected the Xray keepalive-disable sentinel: %v", err)
	}

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		_ = transport.Close()
		t.Fatal(err)
	}
	serveDone := make(chan error, 1)
	go func() { serveDone <- transport.Serve(listener) }()
	clientTransport := &http.Transport{Proxy: nil}
	client := &http.Client{Transport: clientTransport, Timeout: 2 * time.Second}
	var response *http.Response
	t.Cleanup(func() {
		if response != nil {
			_ = response.Body.Close()
		}
		client.CloseIdleConnections()
		_ = transport.Close()
		select {
		case <-serveDone:
		case <-time.After(time.Second):
			t.Error("XHTTP server did not stop after Close")
		}
	})

	request, err := http.NewRequest(http.MethodPost, "http://"+listener.Addr().String()+"/disabled", nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("X-Padding", "xxxx")
	response, err = client.Do(request)
	if err != nil {
		t.Fatalf("stream-up request with keepalive disabled: %v", err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("stream-up status = %d, want %d", response.StatusCode, http.StatusOK)
	}
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("read response after disabled keepalive: %v", err)
	}
	if len(body) != 0 {
		t.Fatalf("disabled keepalive wrote response padding %q", body)
	}
}

func TestStreamUpResponseKeepaliveStopsOnUploadEOF(t *testing.T) {
	transport, err := NewServer(context.Background(), log.NewNOPFactory().Logger(), V2RayXHTTPOptions{V2RayXHTTPBaseOptions: V2RayXHTTPBaseOptions{
		Mode:              "stream-up",
		XPaddingBytes:     Xbadoption.Range{From: 4, To: 4},
		XPaddingObfsMode:  true,
		XPaddingPlacement: PlacementHeader,
		XPaddingMethod:    "repeat-x",
	}}, nil, tunnelHandler(func(net.Conn, N.CloseHandlerFunc) {}))
	if err != nil {
		t.Fatal(err)
	}
	var listener net.Listener
	var serveDone chan error
	t.Cleanup(func() {
		_ = transport.Close()
		if listener != nil {
			_ = listener.Close()
		}
		if serveDone != nil {
			select {
			case err := <-serveDone:
				if err != nil {
					t.Errorf("XHTTP Serve: %v", err)
				}
			case <-time.After(time.Second):
				t.Error("XHTTP server did not stop after Close")
			}
		}
	})
	listener, err = net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	serveDone = make(chan error, 1)
	go func() { serveDone <- transport.Serve(listener) }()

	// Allow several keepalive intervals, but bound a leaked response writer.
	client := &http.Client{Transport: &http.Transport{Proxy: nil}, Timeout: 5 * time.Second}
	t.Cleanup(client.CloseIdleConnections)
	// A bodyless POST reaches upload EOF immediately, without cancellation.
	request, err := http.NewRequest(http.MethodPost, "http://"+listener.Addr().String()+"/upload-eof-keepalive", nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("X-Padding", "xxxx")
	response, err := client.Do(request)
	if response != nil {
		t.Cleanup(func() { _ = response.Body.Close() })
	}
	if err != nil {
		t.Fatalf("stream-up request: %v", err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("stream-up status = %d, want %d", response.StatusCode, http.StatusOK)
	}
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("read stream-up response to EOF after upload EOF: %v", err)
	}
	if string(body) != "XXXX" {
		t.Fatalf("stream-up response after upload EOF = %q, want exactly one %q padding chunk", body, "XXXX")
	}
}

func TestStreamUpResponseKeepaliveBlockedWriteStopsOnUploadEOF(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	transport, err := NewServer(ctx, log.NewNOPFactory().Logger(), V2RayXHTTPOptions{V2RayXHTTPBaseOptions: V2RayXHTTPBaseOptions{
		Mode:                 "stream-up",
		ScStreamUpServerSecs: Xbadoption.Range{From: 1, To: 1},
		XPaddingBytes:        Xbadoption.Range{From: 4, To: 4},
		XPaddingObfsMode:     true,
		XPaddingPlacement:    PlacementHeader,
		XPaddingMethod:       "repeat-x",
	}}, nil, tunnelHandler(func(net.Conn, N.CloseHandlerFunc) {}))
	if err != nil {
		t.Fatal(err)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		_ = transport.Close()
		t.Fatal(err)
	}
	wrappedListener := &streamUpBlockedWriteListener{Listener: listener, accepted: make(chan *streamUpBlockedWriteConn, 1)}
	serveDone := make(chan error, 1)
	go func() { serveDone <- transport.Serve(wrappedListener) }()

	client := &http.Client{Transport: &http.Transport{Proxy: nil}, Timeout: 10 * time.Second}
	requestBody, uploadBody := io.Pipe()
	var response *http.Response
	var conn *streamUpBlockedWriteConn
	var drainDone chan struct{}
	var drainErr error
	t.Cleanup(func() {
		// Release the artificial write before waiting for any server/client work.
		if conn != nil {
			_ = conn.Close()
		}
		cancel()
		_ = uploadBody.Close()
		_ = requestBody.Close()
		_ = transport.Close()
		_ = listener.Close()
		if response != nil {
			_ = response.Body.Close()
		}
		client.CloseIdleConnections()
		if drainDone != nil {
			select {
			case <-drainDone:
			case <-time.After(time.Second):
				t.Error("response drain did not stop during cleanup")
			}
		}
		select {
		case err := <-serveDone:
			if err != nil {
				t.Errorf("XHTTP Serve: %v", err)
			}
		case <-time.After(time.Second):
			t.Error("XHTTP server did not stop after Close")
		}
	})

	request, err := http.NewRequestWithContext(ctx, http.MethodPost, "http://"+listener.Addr().String()+"/blocked-keepalive", requestBody)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("X-Padding", "xxxx")
	response, err = client.Do(request)
	if err != nil {
		t.Fatalf("stream-up request: %v", err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("stream-up status = %d, want %d", response.StatusCode, http.StatusOK)
	}
	select {
	case conn = <-wrappedListener.accepted:
	case <-time.After(time.Second):
		t.Fatal("server did not expose the accepted connection")
	}
	// Leave the response body unread and the upload open until a keepalive
	// attempts a write after the initial response headers have been sent.
	select {
	case <-conn.writeAttempted:
	case <-time.After(2 * time.Second):
		t.Fatal("server did not attempt a keepalive response write")
	}
	select {
	case <-conn.writeFailed:
	case <-time.After(time.Second):
		t.Fatal("deadline-bounded response write did not fail in the test connection")
	}
	// With a 1-second interval, a writer that ignored the Flush error would
	// attempt another padding cycle. The error path must terminate immediately.
	time.Sleep(1200 * time.Millisecond)
	conn.mu.Lock()
	futureWriteDeadlineCount := conn.futureWriteDeadlineCount
	conn.mu.Unlock()
	if futureWriteDeadlineCount != 1 {
		t.Fatalf("keepalive retried after its response write/flush error: %d future deadlines", futureWriteDeadlineCount)
	}
	if err := uploadBody.Close(); err != nil {
		t.Fatal(err)
	}
	drainDone = make(chan struct{})
	go func() {
		defer close(drainDone)
		_, drainErr = io.Copy(io.Discard, response.Body)
		if err := response.Body.Close(); drainErr == nil {
			drainErr = err
		}
	}()
	select {
	case <-drainDone:
		// A server-side write timeout can truncate the HTTP response. Completion
		// is the contract here; client cancellation/timeout must not satisfy it.
		if errors.Is(drainErr, context.Canceled) || errors.Is(drainErr, context.DeadlineExceeded) {
			t.Fatalf("response only finished because the client stopped it: %v", drainErr)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("response drain/close remained blocked after upload EOF; keepalive write needs a finite deadline")
	}
	conn.mu.Lock()
	hadFutureDeadline := conn.hadFutureDeadline
	conn.mu.Unlock()
	if !hadFutureDeadline {
		t.Fatal("keepalive response write never received a finite future write deadline")
	}
}

func TestStreamUpResponseKeepaliveStopsWhenWriteDeadlineSetFails(t *testing.T) {
	transport, err := NewServer(context.Background(), log.NewNOPFactory().Logger(), V2RayXHTTPOptions{V2RayXHTTPBaseOptions: V2RayXHTTPBaseOptions{
		Mode:                 "stream-up",
		ScStreamUpServerSecs: Xbadoption.Range{From: 1, To: 1},
		XPaddingBytes:        Xbadoption.Range{From: 4, To: 4},
		XPaddingObfsMode:     true,
		XPaddingPlacement:    PlacementHeader,
		XPaddingMethod:       "repeat-x",
	}}, nil, tunnelHandler(func(net.Conn, N.CloseHandlerFunc) {}))
	if err != nil {
		t.Fatal(err)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		_ = transport.Close()
		t.Fatal(err)
	}
	wrappedListener := &streamUpBlockedWriteListener{
		Listener:                        listener,
		accepted:                        make(chan *streamUpBlockedWriteConn, 1),
		writeDeadlineErr:                errors.New("simulated response write deadline failure"),
		allowWritesAfterDeadlineFailure: true,
	}
	serveDone := make(chan error, 1)
	go func() { serveDone <- transport.Serve(wrappedListener) }()
	client := &http.Client{Transport: &http.Transport{Proxy: nil}, Timeout: 4 * time.Second}
	var response *http.Response
	t.Cleanup(func() {
		if response != nil {
			_ = response.Body.Close()
		}
		client.CloseIdleConnections()
		_ = transport.Close()
		_ = listener.Close()
		select {
		case err := <-serveDone:
			if err != nil {
				t.Errorf("XHTTP Serve: %v", err)
			}
		case <-time.After(time.Second):
			t.Error("XHTTP server did not stop after Close")
		}
	})
	request, err := http.NewRequest(http.MethodPost, "http://"+listener.Addr().String()+"/deadline-error", nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("X-Padding", "xxxx")
	response, err = client.Do(request)
	if err != nil {
		t.Fatalf("stream-up request: %v", err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("stream-up status = %d, want %d", response.StatusCode, http.StatusOK)
	}
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("read response after write-deadline setup failure: %v", err)
	}
	if len(body) != 0 {
		t.Fatalf("keepalive wrote padding after deadline setup failed: %q", body)
	}
	conn := <-wrappedListener.accepted
	conn.mu.Lock()
	deadlineSetFailed := conn.deadlineSetFailed
	conn.mu.Unlock()
	if !deadlineSetFailed {
		t.Fatal("keepalive did not attempt or stop after a failed response write deadline")
	}
	select {
	case <-conn.writeAttempted:
		t.Fatal("keepalive wrote response padding after deadline setup failed")
	default:
	}
}

type streamUpBlockedWriteListener struct {
	net.Listener
	accepted                        chan *streamUpBlockedWriteConn
	writeDeadlineErr                error
	allowWritesAfterDeadlineFailure bool
}

func (l *streamUpBlockedWriteListener) Accept() (net.Conn, error) {
	conn, err := l.Listener.Accept()
	if err != nil {
		return nil, err
	}
	wrapped := &streamUpBlockedWriteConn{
		Conn: conn, writeAttempted: make(chan struct{}), writeFailed: make(chan struct{}), released: make(chan struct{}), deadlineChanged: make(chan struct{}), writeDeadlineErr: l.writeDeadlineErr, allowWritesAfterDeadlineFailure: l.allowWritesAfterDeadlineFailure,
	}
	l.accepted <- wrapped
	return wrapped, nil
}

type streamUpBlockedWriteConn struct {
	net.Conn
	mu                              sync.Mutex
	headersWritten                  bool
	writeDeadline                   time.Time
	hadFutureDeadline               bool
	futureWriteDeadlineCount        int
	deadlineSetFailed               bool
	deadlineChanged                 chan struct{}
	writeAttempted                  chan struct{}
	writeOnce                       sync.Once
	writeFailed                     chan struct{}
	writeFailureOnce                sync.Once
	released                        chan struct{}
	writeDeadlineErr                error
	allowWritesAfterDeadlineFailure bool
	closeOnce                       sync.Once
}

func (c *streamUpBlockedWriteConn) Write(p []byte) (int, error) {
	c.mu.Lock()
	if !c.headersWritten {
		c.headersWritten = true
		c.mu.Unlock()
		return c.Conn.Write(p)
	}
	c.mu.Unlock()
	if c.allowWritesAfterDeadlineFailure {
		c.mu.Lock()
		deadlineSetFailed := c.deadlineSetFailed
		c.mu.Unlock()
		if deadlineSetFailed {
			if bytes.Contains(p, []byte("XXXX")) {
				c.writeOnce.Do(func() { close(c.writeAttempted) })
			}
			return c.Conn.Write(p)
		}
	}
	c.writeOnce.Do(func() { close(c.writeAttempted) })
	for {
		c.mu.Lock()
		deadline, changed := c.writeDeadline, c.deadlineChanged
		c.mu.Unlock()
		// Simulate an unresponsive peer without waiting out a production timeout.
		// No deadline means this write stays blocked until explicit cleanup.
		if !deadline.IsZero() {
			c.writeFailureOnce.Do(func() { close(c.writeFailed) })
			return 0, os.ErrDeadlineExceeded
		}
		select {
		case <-changed:
		case <-c.released:
			return 0, net.ErrClosed
		}
	}
}

func (c *streamUpBlockedWriteConn) SetWriteDeadline(deadline time.Time) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if deadline.After(time.Now()) && c.writeDeadlineErr != nil {
		c.deadlineSetFailed = true
		return c.writeDeadlineErr
	}
	if err := c.Conn.SetWriteDeadline(deadline); err != nil {
		return err
	}
	c.writeDeadline = deadline
	if deadline.After(time.Now()) {
		c.hadFutureDeadline = true
		c.futureWriteDeadlineCount++
	}
	close(c.deadlineChanged)
	c.deadlineChanged = make(chan struct{})
	return nil
}

func (c *streamUpBlockedWriteConn) Close() error {
	c.closeOnce.Do(func() { close(c.released) })
	return c.Conn.Close()
}
