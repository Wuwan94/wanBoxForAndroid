package libcore

import (
	"context"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"encoding/pem"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// TestRealXrayXHTTPInterop is an opt-in loopback integration matrix. Set XRAY_BIN
// to an official Xray-core release binary to run it; ordinary unit-test runs skip it.
func TestRealXrayXHTTPInterop(t *testing.T) {
	xrayBin := os.Getenv("XRAY_BIN")
	if xrayBin == "" {
		t.Skip("set XRAY_BIN to an official Xray-core binary to run loopback interoperability tests")
	}
	if _, err := os.Stat(xrayBin); err != nil {
		t.Fatalf("XRAY_BIN %q: %v", xrayBin, err)
	}

	for _, mode := range []string{"auto", "packet-up", "stream-up", "stream-one"} {
		mode := mode
		t.Run("xray-client_to_wanbox-server/"+mode, func(t *testing.T) {
			runXrayClientToWanBox(t, xrayBin, mode, false, "")
		})
		t.Run("wanbox-client_to_xray-server/"+mode, func(t *testing.T) {
			runWanBoxClientToXray(t, xrayBin, mode, false)
		})
	}

	t.Run("xray-client_to_wanbox-server/auto-tls", func(t *testing.T) {
		runXrayClientToWanBox(t, xrayBin, "auto", true, "")
	})
	t.Run("wanbox-client_to_xray-server/auto-tls", func(t *testing.T) {
		runWanBoxClientToXray(t, xrayBin, "auto", true)
	})
	for _, placement := range []string{"header", "cookie"} {
		placement := placement
		t.Run("xray-client_to_wanbox-server/packet-up-"+placement, func(t *testing.T) {
			runXrayClientToWanBox(t, xrayBin, "packet-up", false, placement)
		})
	}
}

func runXrayClientToWanBox(t *testing.T, xrayBin, mode string, useTLS bool, placement string) {
	t.Helper()
	echo := newEchoListener(t)
	serverPort := freeTCPPort(t)
	serverTLS, clientSecurity, clientTLSSettings := "", "none", ""
	if useTLS {
		certificatePath, keyPath := writeVLESSTestCertificate(t)
		serverTLS = fmt.Sprintf(`"tls":{"enabled":true,"certificate_path":%q,"key_path":%q,"alpn":["h2"]},`, certificatePath, keyPath)
		clientSecurity = "tls"
		clientTLSSettings = fmt.Sprintf(`,"tlsSettings":{"serverName":"localhost","pinnedPeerCertSha256":%q,"alpn":["h2"]}`, xrayCertFingerprint(t, certificatePath))
	}
	placementOptions, err := xrayPlacementOptions(placement)
	if err != nil {
		t.Fatal(err)
	}
	serverConfig := fmt.Sprintf(`{
		"log":{"disabled":true},
		"inbounds":[{"type":"vless","tag":"xhttp-in","listen":"127.0.0.1","listen_port":%d,
		"users":[{"uuid":"%s"}],%s
		"transport":{"type":"xhttp","mode":%q,"path":"/xhttp"%s}}],
		"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct"}
	}`, serverPort, vlessXHTTPTestUUID, serverTLS, mode, placementOptions.wanBoxJSON)
	server, err := checkVlessConfig(serverConfig)
	if err != nil {
		t.Fatalf("create wanBox XHTTP server config: %v\n%s", err, serverConfig)
	}
	if err := server.Start(); err != nil {
		_ = server.Close()
		t.Fatalf("start wanBox XHTTP server: %v", err)
	}
	t.Cleanup(func() { _ = server.Close() })

	socksPort := freeTCPPort(t)
	clientConfig := fmt.Sprintf(`{
		"log":{"loglevel":"warning"},
		"inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"socks","settings":{"auth":"noauth","udp":false}}],
		"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"127.0.0.1","port":%d,"users":[{"id":"%s","encryption":"none"}]}]},
		"streamSettings":{"network":"xhttp","security":%q%s,"xhttpSettings":{"mode":%q,"path":"/xhttp"%s}}}]
	}`, socksPort, serverPort, vlessXHTTPTestUUID, clientSecurity, clientTLSSettings, mode, placementOptions.xrayJSON)
	process := startRealXray(t, xrayBin, clientConfig)
	waitForXrayTCP(t, process, net.JoinHostPort("127.0.0.1", fmt.Sprint(socksPort)))
	roundTripViaSocks5(t, net.JoinHostPort("127.0.0.1", fmt.Sprint(socksPort)), echo.Addr().(*net.TCPAddr).Port, "Xray client -> wanBox server "+mode+" "+placement)
}

func runWanBoxClientToXray(t *testing.T, xrayBin, mode string, useTLS bool) {
	t.Helper()
	echo := newEchoListener(t)
	serverPort := freeTCPPort(t)
	serverSecurity, serverTLS := "none", ""
	clientTLS := ""
	if useTLS {
		certificatePath, keyPath := writeVLESSTestCertificate(t)
		serverSecurity = "tls"
		serverTLS = fmt.Sprintf(`,"tlsSettings":{"alpn":["h2"],"certificates":[{"certificateFile":%q,"keyFile":%q}]}`, certificatePath, keyPath)
		clientTLS = fmt.Sprintf(`"tls":{"enabled":true,"server_name":"localhost","certificate_path":%q,"alpn":["h2"]},`, certificatePath)
	}
	serverConfig := fmt.Sprintf(`{
		"log":{"loglevel":"debug"},
		"inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"vless",
		"settings":{"clients":[{"id":"%s"}],"decryption":"none"},
		"streamSettings":{"network":"xhttp","security":%q%s,"xhttpSettings":{"mode":%q,"path":"/xhttp"}}}],
		"outbounds":[{"protocol":"freedom","tag":"direct","settings":{"finalRules":[{"action":"allow","network":["tcp"],"ip":["127.0.0.1/32"]}]}}]
	}`, serverPort, vlessXHTTPTestUUID, serverSecurity, serverTLS, mode)
	process := startRealXray(t, xrayBin, serverConfig)
	waitForXrayTCP(t, process, net.JoinHostPort("127.0.0.1", fmt.Sprint(serverPort)))

	clientConfig := fmt.Sprintf(`{
		"log":{"disabled":true},
		"outbounds":[{"type":"vless","tag":"xhttp-out","server":"127.0.0.1","server_port":%d,"uuid":"%s",%s
		"transport":{"type":"xhttp","mode":%q,"path":"/xhttp"}}],
		"route":{"final":"xhttp-out"}
	}`, serverPort, vlessXHTTPTestUUID, clientTLS, mode)
	client, err := checkVlessConfig(clientConfig)
	if err != nil {
		t.Fatalf("create wanBox XHTTP client config: %v\n%s", err, clientConfig)
	}
	if err := client.Start(); err != nil {
		_ = client.Close()
		t.Fatalf("start wanBox XHTTP client: %v", err)
	}
	t.Cleanup(func() { _ = client.Close() })
	outbound, ok := client.Outbound().Outbound("xhttp-out")
	if !ok {
		t.Fatal("wanBox XHTTP outbound was not registered")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	conn, err := outbound.DialContext(ctx, N.NetworkTCP, M.ParseSocksaddrHostPort("127.0.0.1", uint16(echo.Addr().(*net.TCPAddr).Port)))
	if err != nil {
		t.Fatalf("dial echo target through wanBox XHTTP client: %v", err)
	}
	defer conn.Close()
	assertInteropEcho(t, conn, "wanBox client -> Xray server "+mode)
}

func xrayCertFingerprint(t *testing.T, certificatePath string) string {
	t.Helper()
	certificatePEM, err := os.ReadFile(certificatePath)
	if err != nil {
		t.Fatalf("read test certificate: %v", err)
	}
	block, _ := pem.Decode(certificatePEM)
	if block == nil {
		t.Fatal("decode test certificate PEM")
	}
	certificate, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		t.Fatalf("parse test certificate: %v", err)
	}
	fingerprint := sha256.Sum256(certificate.Raw)
	return hex.EncodeToString(fingerprint[:])
}

type placementOptions struct {
	wanBoxJSON string
	xrayJSON   string
}

func xrayPlacementOptions(placement string) (placementOptions, error) {
	if placement == "" {
		return placementOptions{}, nil
	}
	if placement != "header" && placement != "cookie" {
		return placementOptions{}, fmt.Errorf("unsupported test placement %q", placement)
	}
	wanBoxJSON := fmt.Sprintf(`,"session_placement":%q,"session_key":%q,"seq_placement":%q,"seq_key":%q,"uplink_data_placement":%q,"uplink_data_key":%q`, placement, "X-Session-ID", placement, "X-Seq", placement, "x-data")
	xrayJSON := fmt.Sprintf(`,"sessionIDPlacement":%q,"sessionIDKey":%q,"seqPlacement":%q,"seqKey":%q,"uplinkDataPlacement":%q,"uplinkDataKey":%q`, placement, "X-Session-ID", placement, "X-Seq", placement, "x-data")
	if placement == "cookie" {
		wanBoxJSON = fmt.Sprintf(`,"session_placement":%q,"session_key":%q,"seq_placement":%q,"seq_key":%q,"uplink_data_placement":%q,"uplink_data_key":%q`, placement, "x_session", placement, "x_seq", placement, "x_data")
		xrayJSON = fmt.Sprintf(`,"sessionIDPlacement":%q,"sessionIDKey":%q,"seqPlacement":%q,"seqKey":%q,"uplinkDataPlacement":%q,"uplinkDataKey":%q`, placement, "x_session", placement, "x_seq", placement, "x_data")
	}
	return placementOptions{wanBoxJSON: wanBoxJSON, xrayJSON: xrayJSON}, nil
}

type realXrayProcess struct {
	cmd     *exec.Cmd
	done    chan struct{}
	waitErr error
	logPath string
}

func startRealXray(t *testing.T, xrayBin, config string) *realXrayProcess {
	t.Helper()
	configPath := filepath.Join(t.TempDir(), "xray.json")
	if err := os.WriteFile(configPath, []byte(config), 0o600); err != nil {
		t.Fatalf("write Xray config: %v", err)
	}
	if output, err := exec.Command(xrayBin, "run", "-test", "-c", configPath).CombinedOutput(); err != nil {
		t.Fatalf("Xray config validation failed: %v\n%s\nconfig:\n%s", err, output, config)
	}
	logPath := filepath.Join(t.TempDir(), "xray.log")
	logFile, err := os.Create(logPath)
	if err != nil {
		t.Fatalf("create Xray log: %v", err)
	}
	cmd := exec.Command(xrayBin, "run", "-c", configPath)
	cmd.Stdout = logFile
	cmd.Stderr = logFile
	if err := cmd.Start(); err != nil {
		_ = logFile.Close()
		t.Fatalf("start Xray: %v", err)
	}
	process := &realXrayProcess{cmd: cmd, done: make(chan struct{}), logPath: logPath}
	go func() {
		process.waitErr = cmd.Wait()
		_ = logFile.Close()
		close(process.done)
	}()
	t.Cleanup(func() {
		if t.Failed() {
			log, _ := os.ReadFile(process.logPath)
			t.Logf("Xray process log:\n%s", log)
		}
	})
	t.Cleanup(func() {
		select {
		case <-process.done:
		default:
			_ = cmd.Process.Kill()
			<-process.done
		}
	})
	return process
}

func waitForXrayTCP(t *testing.T, process *realXrayProcess, address string) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		select {
		case <-process.done:
			log, _ := os.ReadFile(process.logPath)
			t.Fatalf("Xray exited before listening (%v):\n%s", process.waitErr, log)
		default:
		}
		conn, err := net.DialTimeout("tcp", address, 100*time.Millisecond)
		if err == nil {
			_ = conn.Close()
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	log, _ := os.ReadFile(process.logPath)
	t.Fatalf("Xray did not listen on %s within 10s:\n%s", address, log)
}

func roundTripViaSocks5(t *testing.T, socksAddress string, targetPort int, label string) {
	t.Helper()
	conn, err := net.DialTimeout("tcp", socksAddress, 5*time.Second)
	if err != nil {
		t.Fatalf("connect to Xray SOCKS inbound: %v", err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
	if _, err := conn.Write([]byte{5, 1, 0}); err != nil {
		t.Fatalf("write SOCKS greeting: %v", err)
	}
	var greeting [2]byte
	if _, err := io.ReadFull(conn, greeting[:]); err != nil || greeting != [2]byte{5, 0} {
		t.Fatalf("SOCKS greeting response = %v, err=%v", greeting, err)
	}
	request := []byte{5, 1, 0, 1, 127, 0, 0, 1, byte(targetPort >> 8), byte(targetPort)}
	if _, err := conn.Write(request); err != nil {
		t.Fatalf("write SOCKS CONNECT: %v", err)
	}
	var response [10]byte
	if _, err := io.ReadFull(conn, response[:]); err != nil || response[0] != 5 || response[1] != 0 {
		t.Fatalf("SOCKS CONNECT response = %v, err=%v", response, err)
	}
	assertInteropEcho(t, conn, label)
}

func assertInteropEcho(t *testing.T, conn net.Conn, label string) {
	t.Helper()
	_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
	want := []byte("wanBox-Xray-loopback-interop:" + label)
	if _, err := conn.Write(want); err != nil {
		t.Fatalf("write through XHTTP: %v", err)
	}
	got := make([]byte, len(want))
	if _, err := io.ReadFull(conn, got); err != nil {
		t.Fatalf("read echoed traffic through XHTTP: %v", err)
	}
	if string(got) != string(want) {
		t.Fatalf("echoed traffic = %q, want %q", got, want)
	}
}
