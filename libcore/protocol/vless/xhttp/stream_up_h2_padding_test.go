package xhttp

import (
	"bytes"
	"context"
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/log"
	N "github.com/sagernet/sing/common/network"
	"golang.org/x/net/http2"
	Xbadoption "libcore/protocol/vless/internal/xray/badoption"
)

// Observe actual response DATA consumed from a TLS/h2 connection; do not supply
// a fake response, bypass the production server, or replace the upload stream.
type paddingObservedTransport struct {
	base    http.RoundTripper
	bytes   atomic.Int64
	reached chan struct{}
	once    sync.Once
}

func (o *paddingObservedTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	response, err := o.base.RoundTrip(r)
	if err != nil {
		return response, err
	}
	if response.ProtoMajor != 2 {
		response.Body.Close()
		return nil, io.ErrUnexpectedEOF
	}
	if r.Method == http.MethodPost {
		response.Body = &paddingObservedBody{ReadCloser: response.Body, observer: o}
	}
	return response, nil
}

type paddingObservedBody struct {
	io.ReadCloser
	observer *paddingObservedTransport
}

func (b *paddingObservedBody) Read(p []byte) (int, error) {
	n, err := b.ReadCloser.Read(p)
	if b.observer.bytes.Add(int64(n)) > 32*1024 {
		b.observer.once.Do(func() { close(b.observer.reached) })
	}
	return n, err
}

func TestHTTP2StreamUpUploadSurvivesLargeResponsePadding(t *testing.T) {
	// The user-requested seam is a real DefaultDialerClient stream-up session
	// against NewServer, followed by application bytes after >32 KiB padding.
	const prefix = "before-padding:"
	late := bytes.Repeat([]byte("after-padding-payload/"), 4096)
	want := append([]byte(prefix), late...)
	type receipt struct {
		data []byte
		err  error
	}
	received := make(chan receipt, 1)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	options := V2RayXHTTPBaseOptions{
		Mode: "stream-up", ScStreamUpServerSecs: Xbadoption.Range{From: 1, To: 1},
		XPaddingBytes: Xbadoption.Range{From: 4096, To: 4096}, XPaddingObfsMode: true,
		XPaddingPlacement: PlacementHeader, XPaddingMethod: "repeat-x",
	}
	transport, err := NewServer(ctx, log.NewNOPFactory().Logger(), V2RayXHTTPOptions{V2RayXHTTPBaseOptions: options}, nil,
		tunnelHandler(func(conn net.Conn, done N.CloseHandlerFunc) {
			go func() {
				data := make([]byte, len(want))
				n, err := io.ReadFull(conn, data)
				received <- receipt{data[:n], err}
				done(err)
			}()
		}))
	if err != nil {
		t.Fatal(err)
	}
	uploadCanceled := make(chan struct{})
	ts := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			stop := context.AfterFunc(r.Context(), func() { close(uploadCanceled) })
			defer stop()
		}
		transport.(*Server).ServeHTTP(w, r)
	}))
	ts.EnableHTTP2 = true
	ts.StartTLS()
	h2 := &http2.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}} // loopback certificate only
	observer := &paddingObservedTransport{base: h2, reached: make(chan struct{})}
	client := &DefaultDialerClient{options: &options, client: &http.Client{Transport: observer}, httpVersion: "2"}
	body, writer := io.Pipe()
	var down, up io.ReadCloser
	t.Cleanup(func() {
		cancel()
		writer.Close()
		body.Close()
		if up != nil {
			up.Close()
		}
		if down != nil {
			down.Close()
		}
		transport.Close()
		h2.CloseIdleConnections()
		ts.Close()
	})
	down, _, _, err = client.OpenStream(ctx, ts.URL, "large-padding-session", nil, false)
	if err != nil {
		t.Fatal(err)
	}
	up, _, _, err = client.OpenStream(ctx, ts.URL, "large-padding-session", body, true)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = writer.Write([]byte(prefix)); err != nil {
		t.Fatalf("initial upload: %v", err)
	}
	// The broken client resets at exactly 32 KiB. Let that path reach the same
	// late-upload assertion rather than reporting a misleading padding timeout.
	select {
	case <-observer.reached:
	case <-uploadCanceled:
	case <-ctx.Done():
		t.Fatalf("waiting for real response padding: %d bytes: %v", observer.bytes.Load(), ctx.Err())
	}
	n, writeErr := writer.Write(late)
	writer.Close()
	select {
	case got := <-received:
		t.Logf("TLS HTTP/2 response padding read=%d; late write=%d/%d err=%v; server received=%d/%d err=%v", observer.bytes.Load(), n, len(late), writeErr, len(got.data), len(want), got.err)
		if !bytes.Equal(got.data, want) {
			t.Fatalf("server lost post-padding upload: received %d/%d bytes (read error %v, write error %v)", len(got.data), len(want), got.err, writeErr)
		}
		if writeErr != nil || n != len(late) {
			t.Fatalf("late upload: %d/%d: %v", n, len(late), writeErr)
		}
		if observer.bytes.Load() <= 32*1024 {
			t.Fatalf("test did not cross 32 KiB: %d", observer.bytes.Load())
		}
	case <-ctx.Done():
		t.Fatalf("server did not receive post-padding upload: padding=%d write=%d/%d err=%v", observer.bytes.Load(), n, len(late), writeErr)
	}
}
