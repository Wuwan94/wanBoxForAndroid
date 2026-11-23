package libcore

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/pem"
	"fmt"
	"io"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

const vlessXHTTPTestUUID = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d"

func TestVLESSXHTTPInboundRoundTrip(t *testing.T) {
	echoListener := newEchoListener(t)
	for _, mode := range []string{"auto", "packet-up", "stream-up", "stream-one"} {
		t.Run(mode, func(t *testing.T) {
			runVLESSXHTTPRoundTrip(t, mode, echoListener.Addr().(*net.TCPAddr).Port, "", "")
		})
	}
}

func TestVLESSXHTTPInboundTLSHTTP2RoundTrip(t *testing.T) {
	echoListener := newEchoListener(t)
	certificatePath, keyPath := writeVLESSTestCertificate(t)
	serverTLS := fmt.Sprintf(`"tls": { "enabled": true, "certificate_path": %q, "key_path": %q, "alpn": ["h2"] },`, certificatePath, keyPath)
	clientTLS := `"tls": { "enabled": true, "server_name": "localhost", "insecure": true, "alpn": ["h2"] },`
	runVLESSXHTTPRoundTrip(t, "auto", echoListener.Addr().(*net.TCPAddr).Port, serverTLS, clientTLS)
}

func TestVLESSXHTTPInboundHeaderAndCookiePlacements(t *testing.T) {
	echoListener := newEchoListener(t)
	echoPort := echoListener.Addr().(*net.TCPAddr).Port
	tests := []struct {
		name             string
		transportOptions string
	}{
		{
			name: "header",
			transportOptions: `, "session_placement": "header", "session_key": "X-Session-ID", ` +
				`"seq_placement": "header", "seq_key": "X-Seq", "uplink_data_placement": "header", ` +
				`"headers": {"X-Fronted-By": "wanbox-test"}`,
		},
		{
			name: "cookie",
			transportOptions: `, "session_placement": "cookie", "session_key": "xsession", ` +
				`"seq_placement": "cookie", "seq_key": "xseq", "uplink_data_placement": "cookie"`,
		},
		{
			name:             "get-method-body-uplink",
			transportOptions: `, "uplink_http_method": "GET"`,
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			runVLESSXHTTPRoundTripWithTransportOptions(t, "auto", echoPort, "", "", test.transportOptions)
		})
	}
}

func TestVLESSInboundWithoutTransportRoundTrip(t *testing.T) {
	runVLESSInboundRoundTrip(t, "", "")
}

func TestVLESSInboundWebSocketRoundTrip(t *testing.T) {
	transport := `, "transport": { "type": "ws", "path": "/ws" }`
	runVLESSInboundRoundTrip(t, transport, transport)
}

func runVLESSInboundRoundTrip(t *testing.T, serverTransport, clientTransport string) {
	t.Helper()
	echoListener := newEchoListener(t)
	serverPort := freeTCPPort(t)
	serverConfig := fmt.Sprintf(`{
  "log": { "disabled": true },
  "inbounds": [{
	    "type": "vless",
	    "listen": "127.0.0.1",
	    "listen_port": %d,
	    "users": [{ "uuid": "%s" }]
	    %s
	  }],
	  "outbounds": [{ "type": "direct", "tag": "direct" }],
	  "route": { "final": "direct" }
	}`, serverPort, vlessXHTTPTestUUID, serverTransport)
	server, err := checkVlessConfig(serverConfig)
	if err != nil {
		t.Fatalf("create VLESS inbound box: %v", err)
	}
	if err := server.Start(); err != nil {
		_ = server.Close()
		t.Fatalf("start VLESS inbound box: %v", err)
	}
	t.Cleanup(func() { _ = server.Close() })

	clientConfig := fmt.Sprintf(`{
  "log": { "disabled": true },
  "outbounds": [{
    "type": "vless",
    "tag": "plain-vless-out",
	    "server": "127.0.0.1",
	    "server_port": %d,
	    "uuid": "%s"
	    %s
	  }],
	  "route": { "final": "plain-vless-out" }
	}`, serverPort, vlessXHTTPTestUUID, clientTransport)
	client, err := checkVlessConfig(clientConfig)
	if err != nil {
		t.Fatalf("create VLESS outbound box: %v", err)
	}
	if err := client.Start(); err != nil {
		_ = client.Close()
		t.Fatalf("start VLESS outbound box: %v", err)
	}
	t.Cleanup(func() { _ = client.Close() })

	outbound, ok := client.Outbound().Outbound("plain-vless-out")
	if !ok {
		t.Fatal("VLESS outbound was not registered")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	conn, err := outbound.DialContext(ctx, N.NetworkTCP, M.ParseSocksaddrHostPort("127.0.0.1", uint16(echoListener.Addr().(*net.TCPAddr).Port)))
	if err != nil {
		t.Fatalf("dial echo target through VLESS: %v", err)
	}
	defer conn.Close()
	want := []byte("ordinary VLESS inbound remains available")
	if _, err := conn.Write(want); err != nil {
		t.Fatalf("write through VLESS: %v", err)
	}
	got := make([]byte, len(want))
	readResult := make(chan error, 1)
	go func() {
		_, readErr := io.ReadFull(conn, got)
		readResult <- readErr
	}()
	select {
	case err := <-readResult:
		if err != nil {
			t.Fatalf("read echoed traffic through VLESS: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timed out reading echoed traffic through VLESS")
	}
	if string(got) != string(want) {
		t.Fatalf("VLESS echoed traffic = %q, want %q", got, want)
	}
}

func newEchoListener(t *testing.T) net.Listener {
	t.Helper()
	echoListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen for echo target: %v", err)
	}
	t.Cleanup(func() { _ = echoListener.Close() })
	go func() {
		for {
			conn, acceptErr := echoListener.Accept()
			if acceptErr != nil {
				return
			}
			go func() {
				defer conn.Close()
				_, _ = io.Copy(conn, conn)
			}()
		}
	}()
	return echoListener
}

func runVLESSXHTTPRoundTrip(t *testing.T, mode string, echoPort int, serverTLS, clientTLS string) {
	runVLESSXHTTPRoundTripWithTransportOptions(t, mode, echoPort, serverTLS, clientTLS, "")
}

func runVLESSXHTTPRoundTripWithTransportOptions(t *testing.T, mode string, echoPort int, serverTLS, clientTLS, transportOptions string) {
	t.Helper()
	serverPort := freeTCPPort(t)
	serverConfig := fmt.Sprintf(`{
	  "log": { "disabled": true },
	  "inbounds": [{
	    "type": "vless",
	    "tag": "xhttp-in",
	    "listen": "127.0.0.1",
	    "listen_port": %d,
	    "users": [{ "uuid": "%s" }],
	    %s
	    "transport": { "type": "xhttp", "mode": "%s", "path": "/xhttp"%s }
	  }],
	  "outbounds": [{ "type": "direct", "tag": "direct" }],
	  "route": { "final": "direct" }
	}`, serverPort, vlessXHTTPTestUUID, serverTLS, mode, transportOptions)
	server, err := checkVlessConfig(serverConfig)
	if err != nil {
		t.Fatalf("create VLESS XHTTP inbound box: %v", err)
	}
	if err := server.Start(); err != nil {
		_ = server.Close()
		t.Fatalf("start VLESS XHTTP inbound box: %v", err)
	}
	t.Cleanup(func() { _ = server.Close() })

	clientConfig := fmt.Sprintf(`{
	  "log": { "disabled": true },
	  "outbounds": [{
	    "type": "vless",
	    "tag": "xhttp-out",
	    "server": "127.0.0.1",
	    "server_port": %d,
	    "uuid": "%s",
	    %s
	    "transport": { "type": "xhttp", "mode": "%s", "path": "/xhttp"%s }
	  }],
	  "route": { "final": "xhttp-out" }
	}`, serverPort, vlessXHTTPTestUUID, clientTLS, mode, transportOptions)
	client, err := checkVlessConfig(clientConfig)
	if err != nil {
		t.Fatalf("create VLESS XHTTP outbound box: %v", err)
	}
	if err := client.Start(); err != nil {
		_ = client.Close()
		t.Fatalf("start VLESS XHTTP outbound box: %v", err)
	}
	t.Cleanup(func() { _ = client.Close() })

	outbound, ok := client.Outbound().Outbound("xhttp-out")
	if !ok {
		t.Fatal("VLESS XHTTP outbound was not registered")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	conn, err := outbound.DialContext(ctx, N.NetworkTCP, M.ParseSocksaddrHostPort("127.0.0.1", uint16(echoPort)))
	if err != nil {
		t.Fatalf("dial echo target through VLESS XHTTP: %v", err)
	}
	defer conn.Close()
	if err := conn.SetDeadline(time.Now().Add(5 * time.Second)); err != nil {
		t.Fatalf("set connection deadline: %v", err)
	}

	want := []byte("bidirectional XHTTP traffic")
	if _, err := conn.Write(want); err != nil {
		t.Fatalf("write through VLESS XHTTP: %v", err)
	}
	got := make([]byte, len(want))
	if _, err := io.ReadFull(conn, got); err != nil {
		t.Fatalf("read echoed traffic through VLESS XHTTP: %v", err)
	}
	if string(got) != string(want) {
		t.Fatalf("echoed traffic = %q, want %q", got, want)
	}
}

func writeVLESSTestCertificate(t *testing.T) (string, string) {
	t.Helper()
	privateKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate TLS test private key: %v", err)
	}
	now := time.Now()
	template := x509.Certificate{
		SerialNumber:          big.NewInt(1),
		NotBefore:             now.Add(-time.Hour),
		NotAfter:              now.Add(time.Hour),
		DNSNames:              []string{"localhost"},
		IPAddresses:           []net.IP{net.ParseIP("127.0.0.1")},
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
	}
	certificate, err := x509.CreateCertificate(rand.Reader, &template, &template, &privateKey.PublicKey, privateKey)
	if err != nil {
		t.Fatalf("create TLS test certificate: %v", err)
	}
	privateKeyBytes, err := x509.MarshalECPrivateKey(privateKey)
	if err != nil {
		t.Fatalf("marshal TLS test private key: %v", err)
	}
	directory := t.TempDir()
	certificatePath := filepath.Join(directory, "certificate.pem")
	keyPath := filepath.Join(directory, "key.pem")
	if err := os.WriteFile(certificatePath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certificate}), 0o600); err != nil {
		t.Fatalf("write TLS test certificate: %v", err)
	}
	if err := os.WriteFile(keyPath, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: privateKeyBytes}), 0o600); err != nil {
		t.Fatalf("write TLS test private key: %v", err)
	}
	return certificatePath, keyPath
}

func freeTCPPort(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("find available TCP port: %v", err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	if err := listener.Close(); err != nil {
		t.Fatalf("release temporary TCP port: %v", err)
	}
	return port
}
