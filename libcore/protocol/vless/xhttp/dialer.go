package xhttp

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"sync"
	"sync/atomic"

	"libcore/protocol/vless/internal/xray/signal/done"
)

// interface to abstract between use of browser dialer, vs net/http
type DialerClient interface {
	XmuxConn

	// ctx, url, sessionId, body, uploadOnly
	// The returned ReadCloser's Close must cancel its request and promptly unblock
	// a concurrent Read; splitConn relies on this to enforce read deadlines without
	// leaking a blocked I/O worker.
	OpenStream(context.Context, string, string, io.Reader, bool) (io.ReadCloser, net.Addr, net.Addr, error)

	// ctx, url, sessionId, seq, body, contentLength
	PostPacket(context.Context, string, string, int64, io.Reader, int64) error
}

// implements xhttp.DialerClient in terms of direct network connections
type DefaultDialerClient struct {
	options        *V2RayXHTTPBaseOptions
	client         *http.Client
	closed         atomic.Bool
	httpVersion    string
	h1Mu           sync.Mutex
	h1Conns        []*H1Conn
	dialUploadConn func(ctxInner context.Context) (net.Conn, error)
}

func (c *DefaultDialerClient) IsClosed() bool {
	return c.closed.Load()
}

func (c *DefaultDialerClient) Close() error {
	c.closed.Store(true)
	c.h1Mu.Lock()
	for _, conn := range c.h1Conns {
		if conn != nil {
			_ = conn.Close()
		}
	}
	c.h1Conns = nil
	c.h1Mu.Unlock()
	if c.client != nil {
		if transport, ok := c.client.Transport.(interface{ CloseIdleConnections() }); ok {
			transport.CloseIdleConnections()
		}
	}
	return nil
}

func (c *DefaultDialerClient) getH1Conn(ctx context.Context) (*H1Conn, bool, error) {
	c.h1Mu.Lock()
	if c.closed.Load() {
		c.h1Mu.Unlock()
		return nil, false, net.ErrClosed
	}
	for len(c.h1Conns) > 0 {
		conn := c.h1Conns[len(c.h1Conns)-1]
		c.h1Conns = c.h1Conns[:len(c.h1Conns)-1]
		if conn != nil && !conn.IsClosed() {
			c.h1Mu.Unlock()
			return conn, false, nil
		}
		if conn != nil {
			_ = conn.Close()
		}
	}
	c.h1Mu.Unlock()

	newConn, err := c.dialUploadConn(ctx)
	if err != nil {
		return nil, false, err
	}
	return NewH1Conn(newConn), true, nil
}

func (c *DefaultDialerClient) putH1Conn(conn *H1Conn, discard bool) {
	if conn == nil {
		return
	}
	if discard || c.closed.Load() || conn.IsClosed() {
		_ = conn.Close()
		return
	}
	c.h1Mu.Lock()
	if c.closed.Load() {
		c.h1Mu.Unlock()
		_ = conn.Close()
		return
	}
	c.h1Conns = append(c.h1Conns, conn)
	c.h1Mu.Unlock()
}

func closeH1ConnOnContextDone(ctx context.Context, conn *H1Conn) func() bool {
	return context.AfterFunc(ctx, func() { _ = conn.Close() })
}

func (c *DefaultDialerClient) OpenStream(ctx context.Context, url string, sessionId string, body io.Reader, uploadOnly bool) (wrc io.ReadCloser, remoteAddr, localAddr net.Addr, err error) {
	// this is done when the TCP/UDP connection to the server was established,
	// and we can unblock the Dial function and print correct net addresses in
	// logs
	gotConn := done.New()
	ctxTrace := httptrace.WithClientTrace(ctx, &httptrace.ClientTrace{
		GotConn: func(connInfo httptrace.GotConnInfo) {
			remoteAddr = connInfo.Conn.RemoteAddr()
			localAddr = connInfo.Conn.LocalAddr()
			gotConn.Close()
		},
	})
	method := "GET" // stream-down
	if body != nil {
		method = c.options.GetNormalizedUplinkHTTPMethod() // stream-up/one
	}
	requestCtx, cancelRequest := context.WithCancel(context.WithoutCancel(ctxTrace))
	req, rErr := http.NewRequestWithContext(requestCtx, method, url, body)
	if rErr != nil {
		cancelRequest()
		return nil, nil, nil, rErr
	}
	req.Header = c.options.GetRequestHeader(req.URL.String())
	c.options.ApplySessionAndSeq(req, sessionId, -1)
	if req.Header.Get("X-Accel-Buffering") == "" {
		req.Header.Set("X-Accel-Buffering", "no")
	}
	if req.Header.Get("Cache-Control") == "" {
		req.Header.Set("Cache-Control", "no-store, no-cache, must-revalidate")
	}
	if method == "GET" {
		if !c.options.NoSSEHeader && req.Header.Get("Accept") == "" {
			req.Header.Set("Accept", "text/event-stream")
		}
	} else if method == "POST" || method == "PUT" {
		if req.Header.Get("Content-Type") == "" {
			if !c.options.NoGRPCHeader {
				req.Header.Set("Content-Type", "application/grpc")
			} else {
				req.Header.Set("Content-Type", "application/octet-stream")
			}
		}
	}
	waitReader := &WaitReadCloser{Wait: make(chan struct{}), cancel: cancelRequest}
	wrc = waitReader

	var doErr error
	var doErrMu sync.Mutex
	setDoErr := func(e error) {
		doErrMu.Lock()
		if doErr == nil {
			doErr = e
		}
		doErrMu.Unlock()
	}

	go func() {
		resp, dErr := c.client.Do(req)
		if dErr != nil {
			requestCanceled := requestCtx.Err() != nil && errors.Is(dErr, context.Canceled)
			cancelRequest()
			if !uploadOnly && !requestCanceled { // an explicit request close must not poison the shared client
				c.closed.Store(true)
			}
			setDoErr(dErr)
			waitReader.SetErr(dErr)
			gotConn.Close()
			if closer, ok := body.(io.Closer); ok {
				closer.Close()
			}
			waitReader.Close()
			return
		}
		if resp.StatusCode != http.StatusOK {
			c.closed.Store(true)
			statusErr := fmt.Errorf("bad status code: %s", resp.Status)
			setDoErr(statusErr)
			waitReader.SetErr(statusErr)
			cancelRequest()
			if closer, ok := body.(io.Closer); ok {
				closer.Close()
			}
			io.Copy(io.Discard, io.LimitReader(resp.Body, 32*1024))
			resp.Body.Close()
			waitReader.Close()
			return
		}
		if uploadOnly { // stream-up
			// Drain keepalive padding for the lifetime of the response. Canceling
			// early also interrupts the upload on the same HTTP/2 stream.
			io.Copy(io.Discard, resp.Body)
			cancelRequest()
			resp.Body.Close()
			waitReader.Close()
			return
		}
		waitReader.Set(resp.Body)
	}()

	if body == nil {
		select {
		case <-waitReader.Wait:
		case <-ctx.Done():
			waitReader.SetErr(ctx.Err())
			waitReader.Close()
			return nil, nil, nil, ctx.Err()
		}
	} else {
		select {
		case <-gotConn.Wait():
		case <-waitReader.Wait:
		case <-ctx.Done():
			waitReader.SetErr(ctx.Err())
			waitReader.Close()
			if closer, ok := body.(io.Closer); ok {
				closer.Close()
			}
			return nil, nil, nil, ctx.Err()
		}
	}

	doErrMu.Lock()
	err = doErr
	doErrMu.Unlock()
	if err != nil {
		return nil, nil, nil, err
	}
	return
}

func (c *DefaultDialerClient) PostPacket(ctx context.Context, url string, sessionId string, seq int64, body io.Reader, contentLength int64) error {
	if c.closed.Load() {
		return net.ErrClosed
	}
	payloadBytes, err := io.ReadAll(body)
	if err != nil {
		return err
	}
	reqMethod := c.options.GetNormalizedUplinkHTTPMethod()
	var req *http.Request
	inBody := c.options.GetNormalizedUplinkDataPlacement() == "body"
	if inBody {
		req, err = http.NewRequestWithContext(ctx, reqMethod, url, bytes.NewReader(payloadBytes))
		if err != nil {
			return err
		}
		req.ContentLength = int64(len(payloadBytes))
	} else {
		req, err = http.NewRequestWithContext(ctx, reqMethod, url, http.NoBody)
		if err != nil {
			return err
		}
		req.ContentLength = 0
	}
	req.Header = c.options.GetRequestHeader(req.URL.String())
	c.options.ApplySessionAndSeq(req, sessionId, seq)
	if !inBody {
		c.options.ApplyUplinkPayload(req, payloadBytes)
	}
	if req.Header.Get("X-Accel-Buffering") == "" {
		req.Header.Set("X-Accel-Buffering", "no")
	}
	if req.Header.Get("Cache-Control") == "" {
		req.Header.Set("Cache-Control", "no-store, no-cache, must-revalidate")
	}
	if inBody && req.Header.Get("Content-Type") == "" {
		if !c.options.NoGRPCHeader {
			req.Header.Set("Content-Type", "application/grpc")
		} else {
			req.Header.Set("Content-Type", "application/octet-stream")
		}
	}
	if c.httpVersion != "1.1" {
		resp, err := c.client.Do(req)
		if err != nil {
			if ctx.Err() == nil {
				c.closed.Store(true)
			}
			return err
		}
		_, copyErr := io.Copy(io.Discard, io.LimitReader(resp.Body, 32*1024))
		closeErr := resp.Body.Close()
		if resp.StatusCode != 200 {
			c.closed.Store(true)
			if copyErr != nil {
				return copyErr
			}
			if closeErr != nil {
				return closeErr
			}
			return fmt.Errorf("bad status code: %s", resp.Status)
		}
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
	} else {
		requestBuff := new(bytes.Buffer)
		if err := req.Write(requestBuff); err != nil {
			return err
		}
		h1UploadConn, newConnection, err := c.getH1Conn(ctx)
		if err != nil {
			return err
		}
		stopCancel := closeH1ConnOnContextDone(ctx, h1UploadConn)
		defer func() { stopCancel() }()
		putH1Conn := func(discard bool) {
			stopCancel()
			c.putH1Conn(h1UploadConn, discard || ctx.Err() != nil)
		}
		_, err = h1UploadConn.Write(requestBuff.Bytes())
		if err != nil {
			putH1Conn(true)
			if ctx.Err() != nil {
				return ctx.Err()
			}
			if newConnection {
				return err
			}
			// Retry once with a fresh connection if cached connection failed on write
			h1UploadConn, _, err = c.getH1Conn(ctx)
			if err != nil {
				return err
			}
			stopCancel = closeH1ConnOnContextDone(ctx, h1UploadConn)
			_, err = h1UploadConn.Write(requestBuff.Bytes())
			if err != nil {
				putH1Conn(true)
				if ctx.Err() != nil {
					return ctx.Err()
				}
				return err
			}
		}
		h1UploadConn.UnreadedResponsesCount++
		hasErr := false
		for h1UploadConn.UnreadedResponsesCount > 0 {
			resp, err := http.ReadResponse(h1UploadConn.RespBufReader, req)
			if err != nil {
				if ctx.Err() == nil {
					c.closed.Store(true)
				}
				hasErr = true
				putH1Conn(true)
				if ctx.Err() != nil {
					return ctx.Err()
				}
				return fmt.Errorf("error while reading response: %s", err.Error())
			}
			_, copyErr := io.Copy(io.Discard, io.LimitReader(resp.Body, 32*1024))
			closeErr := resp.Body.Close()
			h1UploadConn.UnreadedResponsesCount--
			if resp.StatusCode != 200 {
				c.closed.Store(true)
				hasErr = true
				putH1Conn(true)
				if copyErr != nil {
					if ctx.Err() != nil {
						return ctx.Err()
					}
					return copyErr
				}
				if closeErr != nil {
					if ctx.Err() != nil {
						return ctx.Err()
					}
					return closeErr
				}
				return fmt.Errorf("got non-200 error response code: %d", resp.StatusCode)
			}
			if copyErr != nil {
				hasErr = true
				putH1Conn(true)
				if ctx.Err() != nil {
					return ctx.Err()
				}
				return copyErr
			}
			if closeErr != nil {
				hasErr = true
				putH1Conn(true)
				if ctx.Err() != nil {
					return ctx.Err()
				}
				return closeErr
			}
		}
		if !hasErr {
			putH1Conn(false)
			if ctx.Err() != nil {
				return ctx.Err()
			}
		}
	}

	return nil
}

type WaitReadCloser struct {
	Wait chan struct{}
	io.ReadCloser
	cancel func()
	err    error
	mu     sync.Mutex
	once   sync.Once
	closed bool
}

func (w *WaitReadCloser) notify() {
	w.once.Do(func() {
		close(w.Wait)
	})
}

func (w *WaitReadCloser) Set(rc io.ReadCloser) {
	w.mu.Lock()
	if w.closed || w.ReadCloser != nil {
		w.mu.Unlock()
		rc.Close()
		return
	}
	w.ReadCloser = rc
	w.mu.Unlock()
	w.notify()
}

func (w *WaitReadCloser) SetErr(err error) {
	w.mu.Lock()
	if w.err == nil {
		w.err = err
	}
	w.mu.Unlock()
	w.notify()
}

func (w *WaitReadCloser) Read(b []byte) (int, error) {
	w.mu.Lock()
	rc := w.ReadCloser
	err := w.err
	w.mu.Unlock()

	if rc == nil {
		if err != nil {
			return 0, err
		}
		<-w.Wait
		w.mu.Lock()
		rc = w.ReadCloser
		err = w.err
		w.mu.Unlock()
		if rc == nil {
			if err != nil {
				return 0, err
			}
			return 0, io.ErrClosedPipe
		}
	}
	return rc.Read(b)
}

func (w *WaitReadCloser) Close() error {
	w.mu.Lock()
	if w.closed {
		w.mu.Unlock()
		return nil
	}
	w.closed = true
	rc := w.ReadCloser
	w.ReadCloser = nil
	cancel := w.cancel
	w.mu.Unlock()

	w.notify()
	if cancel != nil {
		cancel()
	}
	if rc != nil {
		return rc.Close()
	}

	return nil
}
