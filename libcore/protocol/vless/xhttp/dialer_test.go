package xhttp

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"
)

type dialerTestTransport func(*http.Request) (*http.Response, error)

func (f dialerTestTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	return f(req)
}

type dialerTestResponseBody struct {
	*bytes.Reader
	drained chan int64
}

func (b *dialerTestResponseBody) Close() error {
	b.drained <- b.Size() - int64(b.Len())
	return nil
}

func TestUploadOnlyDrainsSuccessfulResponseToEnd(t *testing.T) {
	// Verify response ownership without opening any network connection.
	payload := bytes.Repeat([]byte("padding\n"), 8*1024)
	responseBody := &dialerTestResponseBody{Reader: bytes.NewReader(payload), drained: make(chan int64, 1)}
	client := &DefaultDialerClient{
		options: &V2RayXHTTPBaseOptions{Mode: "stream-up"},
		client: &http.Client{Transport: dialerTestTransport(func(req *http.Request) (*http.Response, error) {
			defer req.Body.Close()
			return &http.Response{StatusCode: http.StatusOK, Body: responseBody}, nil
		})},
	}
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	reader, _, _, err := client.OpenStream(ctx, "https://example.test/", "session", strings.NewReader("upload"), true)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	select {
	case got := <-responseBody.drained:
		if got != int64(len(payload)) {
			t.Fatalf("successful response drained %d of %d bytes before close", got, len(payload))
		}
	case <-ctx.Done():
		t.Fatal("successful response was not closed")
	}
	if _, err := reader.Read(make([]byte, 1)); !errors.Is(err, io.ErrClosedPipe) {
		t.Fatalf("upload-only reader error = %v, want closed pipe", err)
	}
}
