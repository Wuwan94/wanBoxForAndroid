package xhttp

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type deadlineReadResult struct {
	n   int
	err error
	buf []byte
}

type deadlineWriteResult struct {
	n   int
	err error
}

type controlledDeadlineReader struct {
	started    chan struct{}
	data       chan []byte
	closed     chan struct{}
	startOnce  sync.Once
	closeOnce  sync.Once
	closeCalls atomic.Int32
}

func newControlledDeadlineReader() *controlledDeadlineReader {
	return &controlledDeadlineReader{
		started: make(chan struct{}),
		data:    make(chan []byte, 1),
		closed:  make(chan struct{}),
	}
}

func (r *controlledDeadlineReader) Read(p []byte) (int, error) {
	r.startOnce.Do(func() { close(r.started) })
	select {
	case data := <-r.data:
		return copy(p, data), nil
	case <-r.closed:
		return 0, io.ErrClosedPipe
	}
}

func (r *controlledDeadlineReader) Close() error {
	r.closeOnce.Do(func() {
		r.closeCalls.Add(1)
		close(r.closed)
	})
	return nil
}

type controlledDeadlineWriter struct {
	started    chan struct{}
	release    chan struct{}
	closed     chan struct{}
	block      bool
	startOnce  sync.Once
	closeOnce  sync.Once
	closeCalls atomic.Int32
}

func newControlledDeadlineWriter(block bool) *controlledDeadlineWriter {
	return &controlledDeadlineWriter{
		started: make(chan struct{}),
		release: make(chan struct{}),
		closed:  make(chan struct{}),
		block:   block,
	}
}

func (w *controlledDeadlineWriter) Write(p []byte) (int, error) {
	w.startOnce.Do(func() { close(w.started) })
	if !w.block {
		select {
		case <-w.closed:
			return 0, io.ErrClosedPipe
		default:
			return len(p), nil
		}
	}
	select {
	case <-w.release:
		return len(p), nil
	case <-w.closed:
		return 0, io.ErrClosedPipe
	}
}

func (w *controlledDeadlineWriter) Close() error {
	w.closeOnce.Do(func() {
		w.closeCalls.Add(1)
		close(w.closed)
	})
	return nil
}

func awaitSignal(t *testing.T, ch <-chan struct{}, what string) {
	t.Helper()
	select {
	case <-ch:
	case <-time.After(3 * time.Second):
		t.Fatalf("timed out waiting for %s", what)
	}
}

func awaitRead(t *testing.T, ch <-chan deadlineReadResult, what string) deadlineReadResult {
	t.Helper()
	select {
	case result := <-ch:
		return result
	case <-time.After(3 * time.Second):
		t.Fatalf("timed out waiting for %s", what)
		return deadlineReadResult{}
	}
}

func awaitWrite(t *testing.T, ch <-chan deadlineWriteResult, what string) deadlineWriteResult {
	t.Helper()
	select {
	case result := <-ch:
		return result
	case <-time.After(3 * time.Second):
		t.Fatalf("timed out waiting for %s", what)
		return deadlineWriteResult{}
	}
}

func beginDeadlineRead(conn net.Conn) <-chan deadlineReadResult {
	result := make(chan deadlineReadResult, 1)
	go func() {
		buf := make([]byte, 32)
		n, err := conn.Read(buf)
		result <- deadlineReadResult{n: n, err: err, buf: append([]byte(nil), buf[:n]...)}
	}()
	return result
}

func beginDeadlineWrite(conn net.Conn, payload []byte) <-chan deadlineWriteResult {
	result := make(chan deadlineWriteResult, 1)
	go func() {
		n, err := conn.Write(payload)
		result <- deadlineWriteResult{n: n, err: err}
	}()
	return result
}

func assertDeadlineError(t *testing.T, err error) {
	t.Helper()
	if !errors.Is(err, os.ErrDeadlineExceeded) {
		t.Fatalf("error = %v, want errors.Is(err, os.ErrDeadlineExceeded)", err)
	}
	var netErr net.Error
	if !errors.As(err, &netErr) || !netErr.Timeout() {
		t.Fatalf("error %T (%v) does not report Timeout()", err, err)
	}
}

func TestSplitConnReadDeadlineClosesXHTTPSession(t *testing.T) {
	reader := newControlledDeadlineReader()
	writer := newControlledDeadlineWriter(false)
	conn := &splitConn{reader: reader, writer: writer}
	t.Cleanup(func() { _ = conn.Close() })

	readResult := beginDeadlineRead(conn)
	awaitSignal(t, reader.started, "underlying read to start")
	if err := conn.SetReadDeadline(time.Now().Add(100 * time.Millisecond)); err != nil {
		t.Fatalf("SetReadDeadline() error = %v", err)
	}
	result := awaitRead(t, readResult, "read deadline")
	assertDeadlineError(t, result.err)
	if result.n != 0 {
		t.Fatalf("timed-out read returned %d bytes, want 0", result.n)
	}
	if got := reader.closeCalls.Load(); got != 1 {
		t.Fatalf("reader Close calls = %d, want exactly 1", got)
	}

	if !conn.IsClosed() {
		t.Fatal("read timeout left the XHTTP session open after its downlink request was cancelled")
	}
	if got := writer.closeCalls.Load(); got != 1 {
		t.Fatalf("write direction Close calls = %d, want 1 after session teardown", got)
	}
	if n, err := conn.Write([]byte("session is terminal")); n != 0 || err == nil {
		t.Fatalf("Write() after read timeout = (%d, %v), want closed connection", n, err)
	}
	if err := conn.SetReadDeadline(time.Time{}); err != nil {
		t.Fatalf("clear read deadline after timeout error = %v", err)
	}
	if _, err := conn.Read(make([]byte, 1)); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("Read() after timed-out session was cleared = %v, want closed connection", err)
	}
}

func TestSplitConnWriteDeadlineInterruptsOnlyWrite(t *testing.T) {
	reader := newControlledDeadlineReader()
	writer := newControlledDeadlineWriter(true)
	var uploadCanceled atomic.Int32
	conn := &splitConn{
		reader:       reader,
		writer:       writer,
		onWriteClose: func() { uploadCanceled.Add(1) },
	}
	t.Cleanup(func() { _ = conn.Close() })

	writeResult := beginDeadlineWrite(conn, []byte("blocked write"))
	awaitSignal(t, writer.started, "underlying write to start")
	if err := conn.SetWriteDeadline(time.Now().Add(100 * time.Millisecond)); err != nil {
		t.Fatalf("SetWriteDeadline() error = %v", err)
	}
	result := awaitWrite(t, writeResult, "write deadline")
	assertDeadlineError(t, result.err)
	if got := writer.closeCalls.Load(); got != 1 {
		t.Fatalf("writer Close calls = %d, want exactly 1", got)
	}
	if got := uploadCanceled.Load(); got != 1 {
		t.Fatalf("write-close cancellation calls = %d, want exactly 1", got)
	}

	readResult := beginDeadlineRead(conn)
	select {
	case reader.data <- []byte("still-readable"):
	case <-reader.closed:
		t.Fatal("write timeout unexpectedly closed the reader")
	}
	read := awaitRead(t, readResult, "read after write timeout")
	if read.err != nil || string(read.buf) != "still-readable" {
		t.Fatalf("Read() after write timeout = (%q, %v)", read.buf, read.err)
	}
	if got := reader.closeCalls.Load(); got != 0 {
		t.Fatalf("write timeout closed reader %d times", got)
	}
	if err := conn.SetWriteDeadline(time.Time{}); err != nil {
		t.Fatalf("clear write deadline after timeout error = %v", err)
	}
	if _, err := conn.Write([]byte("cannot reopen a one-shot upload")); !errors.Is(err, io.ErrClosedPipe) {
		t.Fatalf("Write() after timed-out one-shot stream was cleared = %v, want closed pipe", err)
	}
}

func TestSplitConnDeadlineAppliesToBlockedReadAndWrite(t *testing.T) {
	reader := newControlledDeadlineReader()
	writer := newControlledDeadlineWriter(true)
	conn := &splitConn{reader: reader, writer: writer}
	t.Cleanup(func() { _ = conn.Close() })

	readResult := beginDeadlineRead(conn)
	writeResult := beginDeadlineWrite(conn, []byte("blocked"))
	awaitSignal(t, reader.started, "underlying read to start")
	awaitSignal(t, writer.started, "underlying write to start")
	if err := conn.SetDeadline(time.Now().Add(100 * time.Millisecond)); err != nil {
		t.Fatalf("SetDeadline() error = %v", err)
	}
	assertDeadlineError(t, awaitRead(t, readResult, "combined read deadline").err)
	assertDeadlineError(t, awaitWrite(t, writeResult, "combined write deadline").err)
	if reader.closeCalls.Load() != 1 || writer.closeCalls.Load() != 1 {
		t.Fatalf("directional Close calls = (read %d, write %d), want (1, 1)", reader.closeCalls.Load(), writer.closeCalls.Load())
	}
}

func TestSplitConnExpiredDeadlineCanBeClearedForFutureIO(t *testing.T) {
	reader := newControlledDeadlineReader()
	writer := newControlledDeadlineWriter(false)
	conn := &splitConn{reader: reader, writer: writer}
	t.Cleanup(func() { _ = conn.Close() })

	if err := conn.SetReadDeadline(time.Now().Add(-time.Second)); err != nil {
		t.Fatalf("SetReadDeadline(past) error = %v", err)
	}
	result := awaitRead(t, beginDeadlineRead(conn), "already-expired read")
	assertDeadlineError(t, result.err)
	select {
	case <-reader.started:
		t.Fatal("an already-expired read reached the underlying reader")
	default:
	}
	if got := reader.closeCalls.Load(); got != 0 {
		t.Fatalf("an already-expired deadline closed the reader %d times", got)
	}

	if err := conn.SetReadDeadline(time.Time{}); err != nil {
		t.Fatalf("clear read deadline error = %v", err)
	}
	readResult := beginDeadlineRead(conn)
	awaitSignal(t, reader.started, "read after clearing expired deadline")
	reader.data <- []byte("ok")
	read := awaitRead(t, readResult, "read after clear")
	if read.err != nil || string(read.buf) != "ok" {
		t.Fatalf("Read() after clear = (%q, %v), want ok", read.buf, read.err)
	}

	if err := conn.SetWriteDeadline(time.Now().Add(-time.Second)); err != nil {
		t.Fatalf("SetWriteDeadline(past) error = %v", err)
	}
	write := awaitWrite(t, beginDeadlineWrite(conn, []byte("expired")), "already-expired write")
	assertDeadlineError(t, write.err)
	if got := writer.closeCalls.Load(); got != 0 {
		t.Fatalf("an already-expired deadline closed the writer %d times", got)
	}
	if err := conn.SetWriteDeadline(time.Time{}); err != nil {
		t.Fatalf("clear write deadline error = %v", err)
	}
	write = awaitWrite(t, beginDeadlineWrite(conn, []byte("ok")), "write after clear")
	if write.err != nil || write.n != 2 {
		t.Fatalf("Write() after clear = (%d, %v), want (2, nil)", write.n, write.err)
	}
}

func TestSplitConnDeadlineUpdateShortensBlockedRead(t *testing.T) {
	reader := newControlledDeadlineReader()
	conn := &splitConn{reader: reader, writer: newControlledDeadlineWriter(false)}
	t.Cleanup(func() { _ = conn.Close() })
	readResult := beginDeadlineRead(conn)
	awaitSignal(t, reader.started, "underlying read to start")

	originalDeadline := time.Now().Add(5 * time.Second)
	if err := conn.SetReadDeadline(originalDeadline); err != nil {
		t.Fatalf("SetReadDeadline(original) error = %v", err)
	}
	startedAt := time.Now()
	if err := conn.SetReadDeadline(time.Now().Add(100 * time.Millisecond)); err != nil {
		t.Fatalf("SetReadDeadline(updated) error = %v", err)
	}
	result := awaitRead(t, readResult, "updated read deadline")
	assertDeadlineError(t, result.err)
	if elapsed := time.Since(startedAt); elapsed >= time.Until(originalDeadline) {
		t.Fatalf("updated deadline was not applied promptly; elapsed %v", elapsed)
	}
}

func TestSplitConnClearingDeadlineKeepsBlockedReadAlive(t *testing.T) {
	reader := newControlledDeadlineReader()
	conn := &splitConn{reader: reader, writer: newControlledDeadlineWriter(false)}
	t.Cleanup(func() { _ = conn.Close() })
	readResult := beginDeadlineRead(conn)
	awaitSignal(t, reader.started, "underlying read to start")

	deadline := time.Now().Add(100 * time.Millisecond)
	if err := conn.SetReadDeadline(deadline); err != nil {
		t.Fatalf("SetReadDeadline() error = %v", err)
	}
	if err := conn.SetReadDeadline(time.Time{}); err != nil {
		t.Fatalf("clearing SetReadDeadline() error = %v", err)
	}
	wait := time.NewTimer(time.Until(deadline) + 50*time.Millisecond)
	defer wait.Stop()
	select {
	case result := <-readResult:
		t.Fatalf("blocked read ended after deadline clear: (%d, %v)", result.n, result.err)
	case <-wait.C:
	}

	reader.data <- []byte("continued")
	result := awaitRead(t, readResult, "read after deadline clear")
	if result.err != nil || string(result.buf) != "continued" {
		t.Fatalf("Read() after clearing active deadline = (%q, %v)", result.buf, result.err)
	}
}

func TestSplitConnCloseUnblocksConcurrentReadWriteAndClosesEachSideOnce(t *testing.T) {
	reader := newControlledDeadlineReader()
	writer := newControlledDeadlineWriter(true)
	conn := &splitConn{reader: reader, writer: writer}
	readResult := beginDeadlineRead(conn)
	writeResult := beginDeadlineWrite(conn, []byte("blocked"))
	awaitSignal(t, reader.started, "underlying read to start")
	awaitSignal(t, writer.started, "underlying write to start")

	const closers = 12
	var closerWG sync.WaitGroup
	for i := 0; i < closers; i++ {
		closerWG.Add(1)
		go func() {
			defer closerWG.Done()
			_ = conn.Close()
		}()
	}
	closerWG.Wait()
	awaitRead(t, readResult, "read released by Close")
	awaitWrite(t, writeResult, "write released by Close")
	if reader.closeCalls.Load() != 1 || writer.closeCalls.Load() != 1 {
		t.Fatalf("Close calls = (read %d, write %d), want exactly (1, 1)", reader.closeCalls.Load(), writer.closeCalls.Load())
	}
}

type deadlineBoundaryDialer struct {
	reader io.ReadCloser
	body   io.Reader
}

func (d *deadlineBoundaryDialer) IsClosed() bool { return false }
func (d *deadlineBoundaryDialer) Close() error   { return nil }
func (d *deadlineBoundaryDialer) OpenStream(_ context.Context, _, _ string, body io.Reader, _ bool) (io.ReadCloser, net.Addr, net.Addr, error) {
	d.body = body
	return d.reader, nil, nil, nil
}
func (*deadlineBoundaryDialer) PostPacket(context.Context, string, string, int64, io.Reader, int64) error {
	return nil
}

func newClientForDeadlineBoundary(dialer DialerClient) *Client {
	return &Client{
		options: &V2RayXHTTPOptions{V2RayXHTTPBaseOptions: V2RayXHTTPBaseOptions{Mode: "stream-one"}},
		rawURL:  "http://xhttp.invalid/stream",
		getHTTPClient: func() (DialerClient, *XmuxClient) {
			return dialer, nil
		},
	}
}

func newPacketUpClientForDeadlineBoundary(dialer DialerClient) *Client {
	getHTTPClient := func() (DialerClient, *XmuxClient) { return dialer, nil }
	return &Client{
		options:        &V2RayXHTTPOptions{V2RayXHTTPBaseOptions: V2RayXHTTPBaseOptions{Mode: "packet-up"}},
		rawURL:         "http://xhttp.invalid/stream",
		getHTTPClient:  getHTTPClient,
		getHTTPClient2: getHTTPClient,
	}
}

func TestClientDialContextReturnedConnHonorsReadDeadline(t *testing.T) {
	reader := newControlledDeadlineReader()
	dialer := &deadlineBoundaryDialer{reader: reader}
	client := newClientForDeadlineBoundary(dialer)
	conn, err := client.DialContext(context.Background())
	if err != nil {
		t.Fatalf("Client.DialContext() error = %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })

	readResult := beginDeadlineRead(conn)
	awaitSignal(t, reader.started, "client-returned connection read")
	if err := conn.SetReadDeadline(time.Now().Add(100 * time.Millisecond)); err != nil {
		t.Fatalf("SetReadDeadline() error = %v", err)
	}
	assertDeadlineError(t, awaitRead(t, readResult, "client connection read deadline").err)
}

func TestClientDialContextPacketUpReadDeadlineClosesUpload(t *testing.T) {
	reader := newControlledDeadlineReader()
	dialer := &deadlineBoundaryDialer{reader: reader}
	client := newPacketUpClientForDeadlineBoundary(dialer)
	conn, err := client.DialContext(context.Background())
	if err != nil {
		t.Fatalf("Client.DialContext() error = %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })

	readResult := beginDeadlineRead(conn)
	awaitSignal(t, reader.started, "packet-up client connection read")
	if err := conn.SetReadDeadline(time.Now().Add(100 * time.Millisecond)); err != nil {
		t.Fatalf("SetReadDeadline() error = %v", err)
	}
	assertDeadlineError(t, awaitRead(t, readResult, "packet-up client read deadline").err)
	if n, err := conn.Write([]byte("the session must not continue uploading")); n != 0 || err == nil {
		t.Fatalf("Write() after packet-up downlink timeout = (%d, %v), want terminal connection error", n, err)
	}
}

func TestClientDialContextReturnedConnHonorsWriteDeadline(t *testing.T) {
	reader := newControlledDeadlineReader()
	dialer := &deadlineBoundaryDialer{reader: reader}
	client := newClientForDeadlineBoundary(dialer)
	conn, err := client.DialContext(context.Background())
	if err != nil {
		t.Fatalf("Client.DialContext() error = %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if dialer.body == nil {
		t.Fatal("OpenStream did not receive the client request body")
	}

	if err := conn.SetWriteDeadline(time.Now().Add(100 * time.Millisecond)); err != nil {
		t.Fatalf("SetWriteDeadline() error = %v", err)
	}
	writeResult := beginDeadlineWrite(conn, []byte("body is deliberately not consumed"))
	assertDeadlineError(t, awaitWrite(t, writeResult, "client connection write deadline").err)
}

func TestClientDialContextReturnedConnHonorsTotalDeadline(t *testing.T) {
	reader := newControlledDeadlineReader()
	dialer := &deadlineBoundaryDialer{reader: reader}
	client := newClientForDeadlineBoundary(dialer)
	conn, err := client.DialContext(context.Background())
	if err != nil {
		t.Fatalf("Client.DialContext() error = %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })

	readResult := beginDeadlineRead(conn)
	writeResult := beginDeadlineWrite(conn, []byte("body is deliberately not consumed"))
	awaitSignal(t, reader.started, "client-returned connection read")
	if err := conn.SetDeadline(time.Now().Add(100 * time.Millisecond)); err != nil {
		t.Fatalf("SetDeadline() error = %v", err)
	}
	assertDeadlineError(t, awaitRead(t, readResult, "client connection total read deadline").err)
	assertDeadlineError(t, awaitWrite(t, writeResult, "client connection total write deadline").err)
}

func TestClientDialContextReadDeadlineCancelsRealHTTP11Response(t *testing.T) {
	responseStarted := make(chan struct{})
	requestCanceled := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(responseStarted)
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		<-r.Context().Done()
		close(requestCanceled)
	}))
	defer server.Close()

	dialer := &DefaultDialerClient{
		options:     &V2RayXHTTPBaseOptions{},
		client:      server.Client(),
		httpVersion: "1.1",
	}
	t.Cleanup(func() { _ = dialer.Close() })
	client := &Client{
		options: &V2RayXHTTPOptions{V2RayXHTTPBaseOptions: V2RayXHTTPBaseOptions{Mode: "stream-one"}},
		rawURL:  server.URL,
		getHTTPClient: func() (DialerClient, *XmuxClient) {
			return dialer, nil
		},
	}
	conn, err := client.DialContext(context.Background())
	if err != nil {
		t.Fatalf("Client.DialContext() with real HTTP/1.1 transport error = %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })

	readResult := beginDeadlineRead(conn)
	awaitSignal(t, responseStarted, "real HTTP/1.1 response to start")
	if err := conn.SetReadDeadline(time.Now().Add(100 * time.Millisecond)); err != nil {
		t.Fatalf("SetReadDeadline() error = %v", err)
	}
	assertDeadlineError(t, awaitRead(t, readResult, "real HTTP/1.1 response read deadline").err)
	awaitSignal(t, requestCanceled, "deadline to cancel the HTTP request context")
}

func TestSplitConnDeadlineUpdateShortensBlockedWrite(t *testing.T) {
	writer := newControlledDeadlineWriter(true)
	conn := &splitConn{reader: newControlledDeadlineReader(), writer: writer}
	t.Cleanup(func() { _ = conn.Close() })
	writeResult := beginDeadlineWrite(conn, []byte("blocked write"))
	awaitSignal(t, writer.started, "underlying write to start")

	originalDeadline := time.Now().Add(5 * time.Second)
	if err := conn.SetWriteDeadline(originalDeadline); err != nil {
		t.Fatalf("SetWriteDeadline(original) error = %v", err)
	}
	startedAt := time.Now()
	if err := conn.SetWriteDeadline(time.Now().Add(100 * time.Millisecond)); err != nil {
		t.Fatalf("SetWriteDeadline(updated) error = %v", err)
	}
	result := awaitWrite(t, writeResult, "updated write deadline")
	assertDeadlineError(t, result.err)
	if elapsed := time.Since(startedAt); elapsed >= time.Until(originalDeadline) {
		t.Fatalf("updated write deadline was not applied promptly; elapsed %v", elapsed)
	}
}

func TestSplitConnClearingDeadlineKeepsBlockedWriteAlive(t *testing.T) {
	writer := newControlledDeadlineWriter(true)
	conn := &splitConn{reader: newControlledDeadlineReader(), writer: writer}
	t.Cleanup(func() { _ = conn.Close() })
	writeResult := beginDeadlineWrite(conn, []byte("still pending"))
	awaitSignal(t, writer.started, "underlying write to start")

	deadline := time.Now().Add(100 * time.Millisecond)
	if err := conn.SetWriteDeadline(deadline); err != nil {
		t.Fatalf("SetWriteDeadline() error = %v", err)
	}
	if err := conn.SetWriteDeadline(time.Time{}); err != nil {
		t.Fatalf("clearing SetWriteDeadline() error = %v", err)
	}
	wait := time.NewTimer(time.Until(deadline) + 50*time.Millisecond)
	defer wait.Stop()
	select {
	case result := <-writeResult:
		t.Fatalf("blocked write ended after deadline clear: (%d, %v)", result.n, result.err)
	case <-wait.C:
	}

	close(writer.release)
	result := awaitWrite(t, writeResult, "write after deadline clear")
	if result.err != nil || result.n != len("still pending") {
		t.Fatalf("Write() after clearing active deadline = (%d, %v)", result.n, result.err)
	}
}

func TestSplitConnSetDeadlineCanClearBothBlockedDirections(t *testing.T) {
	reader := newControlledDeadlineReader()
	writer := newControlledDeadlineWriter(true)
	conn := &splitConn{reader: reader, writer: writer}
	t.Cleanup(func() { _ = conn.Close() })
	readResult := beginDeadlineRead(conn)
	writeResult := beginDeadlineWrite(conn, []byte("both"))
	awaitSignal(t, reader.started, "underlying read to start")
	awaitSignal(t, writer.started, "underlying write to start")

	deadline := time.Now().Add(100 * time.Millisecond)
	if err := conn.SetDeadline(deadline); err != nil {
		t.Fatalf("SetDeadline() error = %v", err)
	}
	if err := conn.SetDeadline(time.Time{}); err != nil {
		t.Fatalf("clearing SetDeadline() error = %v", err)
	}
	wait := time.NewTimer(time.Until(deadline) + 50*time.Millisecond)
	defer wait.Stop()
	select {
	case result := <-readResult:
		t.Fatalf("read ended after total deadline clear: (%d, %v)", result.n, result.err)
	case result := <-writeResult:
		t.Fatalf("write ended after total deadline clear: (%d, %v)", result.n, result.err)
	case <-wait.C:
	}

	reader.data <- []byte("read")
	close(writer.release)
	read := awaitRead(t, readResult, "read after clearing total deadline")
	write := awaitWrite(t, writeResult, "write after clearing total deadline")
	if read.err != nil || string(read.buf) != "read" {
		t.Fatalf("Read() after clearing total deadline = (%q, %v)", read.buf, read.err)
	}
	if write.err != nil || write.n != 4 {
		t.Fatalf("Write() after clearing total deadline = (%d, %v)", write.n, write.err)
	}
}

func TestSplitConnDeadlineAndCloseRaceDoesNotLeakOrDeadlock(t *testing.T) {
	for i := 0; i < 12; i++ {
		reader := newControlledDeadlineReader()
		writer := newControlledDeadlineWriter(true)
		conn := &splitConn{reader: reader, writer: writer}
		readResult := beginDeadlineRead(conn)
		writeResult := beginDeadlineWrite(conn, []byte("race"))
		awaitSignal(t, reader.started, "racing underlying read to start")
		awaitSignal(t, writer.started, "racing underlying write to start")

		start := make(chan struct{})
		setterDone := make(chan struct{})
		closerDone := make(chan struct{})
		go func() {
			<-start
			_ = conn.SetDeadline(time.Now().Add(time.Millisecond))
			close(setterDone)
		}()
		go func() {
			<-start
			_ = conn.Close()
			close(closerDone)
		}()
		close(start)
		awaitRead(t, readResult, "racing read completion")
		awaitWrite(t, writeResult, "racing write completion")
		awaitSignal(t, setterDone, "racing deadline setter")
		awaitSignal(t, closerDone, "racing connection close")
		if reader.closeCalls.Load() != 1 || writer.closeCalls.Load() != 1 {
			t.Fatalf("iteration %d: close counts = (read %d, write %d), want (1, 1)", i, reader.closeCalls.Load(), writer.closeCalls.Load())
		}
	}
}

func TestDefaultDialerPostPacketCancellationClosesHTTP11Connection(t *testing.T) {
	requestStarted := make(chan struct{})
	requestCanceled := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(_ http.ResponseWriter, request *http.Request) {
		_, _ = io.Copy(io.Discard, request.Body)
		close(requestStarted)
		<-request.Context().Done()
		close(requestCanceled)
	}))
	t.Cleanup(func() {
		server.CloseClientConnections()
		server.Close()
	})

	dialer := &DefaultDialerClient{
		options:     &V2RayXHTTPBaseOptions{},
		httpVersion: "1.1",
		dialUploadConn: func(ctx context.Context) (net.Conn, error) {
			return (&net.Dialer{}).DialContext(ctx, "tcp", server.Listener.Addr().String())
		},
	}
	t.Cleanup(func() { _ = dialer.Close() })

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	postResult := make(chan error, 1)
	go func() {
		postResult <- dialer.PostPacket(ctx, server.URL, "deadline-session", 0, strings.NewReader("payload"), int64(len("payload")))
	}()
	awaitSignal(t, requestStarted, "HTTP/1.1 packet upload to reach the server")
	cancel()

	select {
	case err := <-postResult:
		if err == nil {
			t.Fatal("PostPacket() succeeded after its context was canceled while waiting for the response")
		}
	case <-time.After(time.Second):
		t.Fatal("PostPacket() remained blocked after its context was canceled")
	}
	awaitSignal(t, requestCanceled, "HTTP/1.1 upload connection cancellation")
}

func TestWaitForUploadIntervalStopsWhenCanceled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	result := make(chan bool, 1)
	go func() { result <- waitForUploadInterval(ctx, time.Hour) }()
	select {
	case waited := <-result:
		if waited {
			t.Fatal("upload interval continued after its context was canceled")
		}
	case <-time.After(time.Second):
		t.Fatal("upload interval did not respond to context cancellation")
	}
}

func TestDefaultDialerPostPacketCancellationDoesNotCloseHTTP2Client(t *testing.T) {
	requestStarted := make(chan struct{})
	requestCanceled := make(chan struct{})
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(_ http.ResponseWriter, request *http.Request) {
		_, _ = io.Copy(io.Discard, request.Body)
		close(requestStarted)
		<-request.Context().Done()
		close(requestCanceled)
	}))
	server.EnableHTTP2 = true
	server.StartTLS()
	t.Cleanup(server.Close)

	dialer := &DefaultDialerClient{
		options:     &V2RayXHTTPBaseOptions{},
		client:      server.Client(),
		httpVersion: "2",
	}
	t.Cleanup(func() { _ = dialer.Close() })

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	postResult := make(chan error, 1)
	go func() {
		postResult <- dialer.PostPacket(ctx, server.URL, "deadline-session", 0, strings.NewReader("payload"), int64(len("payload")))
	}()
	awaitSignal(t, requestStarted, "HTTP/2 packet upload to reach the server")
	cancel()

	select {
	case err := <-postResult:
		if err == nil {
			t.Fatal("PostPacket() succeeded after its HTTP/2 request context was canceled")
		}
	case <-time.After(time.Second):
		t.Fatal("HTTP/2 PostPacket() remained blocked after context cancellation")
	}
	awaitSignal(t, requestCanceled, "HTTP/2 upload request cancellation")
	if dialer.IsClosed() {
		t.Fatal("canceling one HTTP/2 upload incorrectly closed the shared dialer")
	}
}

func TestClientDialContextStreamOneReadDeadlineDoesNotCloseSharedHTTP2Client(t *testing.T) {
	requestStarted := make(chan struct{})
	requestCanceled := make(chan struct{})
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(_ http.ResponseWriter, request *http.Request) {
		close(requestStarted)
		<-request.Context().Done()
		close(requestCanceled)
	}))
	server.EnableHTTP2 = true
	server.StartTLS()
	t.Cleanup(server.Close)

	dialer := &DefaultDialerClient{
		options:     &V2RayXHTTPBaseOptions{},
		client:      server.Client(),
		httpVersion: "2",
	}
	t.Cleanup(func() { _ = dialer.Close() })
	client := &Client{
		options: &V2RayXHTTPOptions{V2RayXHTTPBaseOptions: V2RayXHTTPBaseOptions{Mode: "stream-one"}},
		rawURL:  server.URL,
		getHTTPClient: func() (DialerClient, *XmuxClient) {
			return dialer, nil
		},
	}
	conn, err := client.DialContext(context.Background())
	if err != nil {
		t.Fatalf("Client.DialContext() with HTTP/2 stream-one = %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	awaitSignal(t, requestStarted, "stream-one HTTP/2 request to reach server")

	readResult := beginDeadlineRead(conn)
	if err := conn.SetReadDeadline(time.Now().Add(100 * time.Millisecond)); err != nil {
		t.Fatalf("SetReadDeadline() error = %v", err)
	}
	assertDeadlineError(t, awaitRead(t, readResult, "stream-one HTTP/2 read deadline").err)
	awaitSignal(t, requestCanceled, "stream-one HTTP/2 request cancellation")
	if dialer.IsClosed() {
		t.Fatal("canceling one stream-one HTTP/2 request incorrectly closed the shared dialer")
	}
}
