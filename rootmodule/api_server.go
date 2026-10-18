// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"net"
	"net/http"
	"os"
	"regexp"
	"strings"
	"time"
)

// apiConfigFile 是管理接口的唯一监听与凭据事实源。
// 只允许 loopback：管理面不暴露到局域网，Root 能力不能放到网络上。
const apiConfigFile = "api.json"

// 每个安装独立的 TLS 身份，客户端按指纹钉扎，不依赖设备时钟。
const (
	apiCertFile = "api-cert.pem"
	apiKeyFile  = "api-key.pem"
)

var (
	apiCodePattern = regexp.MustCompile(`^[a-z_]{1,80}$`)
	apiCertLocks   = make(chan struct{}, 1)
)

func init() { apiCertLocks <- struct{}{} }

type APIConfig struct {
	Listen string `json:"listen"`
	Token  string `json:"token"`
	// CertSHA256 随首次凭据交付给客户端，用于证书钉扎。
	CertSHA256 string `json:"certSha256"`
}

func sha256Hex(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

// EnsureAPIConfig 读取或初始化管理接口配置。
// 已有文件一律不覆盖：token 或证书被重置会让已连接的管理端全部失效。
func (r *Runtime) EnsureAPIConfig() (APIConfig, error) {
	var cfg APIConfig
	if e := readJSON(r.path(apiConfigFile), &cfg, 1<<20); e == nil {
		if !validAPIConfig(cfg) {
			return APIConfig{}, errors.New("api_config_invalid")
		}
		return cfg, nil
	} else if !os.IsNotExist(e) {
		return APIConfig{}, errors.New("api_config_invalid")
	}
	if e := r.init(); e != nil {
		return APIConfig{}, e
	}
	certDER, e := r.ensureAPICertificate()
	if e != nil {
		return APIConfig{}, e
	}
	token := make([]byte, 32)
	if _, e = rand.Read(token); e != nil {
		return APIConfig{}, e
	}
	cfg = APIConfig{Listen: "127.0.0.1:0", Token: hex.EncodeToString(token), CertSHA256: sha256Hex(certDER)}
	if e = jsonWrite(r.path(apiConfigFile), cfg); e != nil {
		return APIConfig{}, e
	}
	return cfg, nil
}

func validAPIConfig(c APIConfig) bool {
	if len(c.Token) < 32 {
		return false
	}
	host, port, e := net.SplitHostPort(c.Listen)
	if e != nil || port == "" {
		return false
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// ensureAPICertificate 生成自签 ECDSA 证书（只签回环地址）。
// 已有证书且私钥可配对时直接复用，避免每次启动都让客户端重新钉扎。
func (r *Runtime) ensureAPICertificate() ([]byte, error) {
	<-apiCertLocks
	defer func() { apiCertLocks <- struct{}{} }()
	certPath, keyPath := r.path(apiCertFile), r.path(apiKeyFile)
	if der, ok := loadAPICertificate(certPath, keyPath); ok {
		return der, nil
	}
	key, e := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if e != nil {
		return nil, e
	}
	serial, e := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if e != nil {
		return nil, e
	}
	tpl := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: "wanbox-api"},
		NotBefore:             time.Unix(0, 0),
		NotAfter:              time.Date(2099, 12, 31, 23, 59, 59, 0, time.UTC),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		IsCA:                  true,
		IPAddresses:           []net.IP{net.ParseIP("127.0.0.1")},
		DNSNames:              []string{"localhost"},
	}
	der, e := x509.CreateCertificate(rand.Reader, tpl, tpl, &key.PublicKey, key)
	if e != nil {
		return nil, e
	}
	keyDER, e := x509.MarshalECPrivateKey(key)
	if e != nil {
		return nil, e
	}
	if e = AtomicWrite(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0600); e != nil {
		return nil, e
	}
	if e = AtomicWrite(keyPath, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}), 0600); e != nil {
		return nil, e
	}
	return der, nil
}

func loadAPICertificate(certPath, keyPath string) ([]byte, bool) {
	certPEM, e := os.ReadFile(certPath)
	if e != nil {
		return nil, false
	}
	keyPEM, e := os.ReadFile(keyPath)
	if e != nil {
		return nil, false
	}
	if _, e = tls.X509KeyPair(certPEM, keyPEM); e != nil {
		return nil, false
	}
	block, _ := pem.Decode(certPEM)
	if block == nil {
		return nil, false
	}
	return block.Bytes, true
}

// Serve 是模块唯一长期后台宿主：先暴露管理接口，再走开机自启流程。
// 代理服务停止、核心启动失败都不影响管理接口，客户端始终能读状态、改节点。
func (r *Runtime) Serve(ctx context.Context, bootReady func() bool) error {
	if e := r.init(); e != nil {
		Trace("serve: init failed: %v", e)
		return e
	}
	unlock, e := fileLock(r.path("api.lock"))
	if e != nil {
		Trace("serve: lock failed (another instance?): %v", e)
		return errors.New("api_already_running")
	}
	defer unlock()

	cfg, e := r.EnsureAPIConfig()
	if e != nil {
		Trace("serve: api config failed: %v", e)
		return e
	}
	// 先交付一次：即使随后绑定失败，App 也能读到当前凭据并看到真实错误。
	delivered := r.DeliverAPIConfig(cfg) == nil
	if !delivered {
		r.recordEvent("api_credential_delivery_failed")
	}
	Trace("serve: config ready listen=%s delivered=%v", cfg.Listen, delivered)
	if _, e = r.ensureAPICertificate(); e != nil {
		return e
	}
	cert, e := tls.LoadX509KeyPair(r.path(apiCertFile), r.path(apiKeyFile))
	if e != nil {
		return errors.New("api_certificate_invalid")
	}
	listener, e := net.Listen("tcp", cfg.Listen)
	if e != nil {
		Trace("serve: listen %s failed: %v, retry ephemeral", cfg.Listen, e)
		// 上次记录的端口可能被其它进程占用，退回系统分配端口重试一次，
		// 避免整个管理面因一个端口冲突而永久失效。
		if listener, e = net.Listen("tcp", "127.0.0.1:0"); e != nil {
			// 管理接口起不来不能拖垮代理：仍然执行开机自启并常驻，
			// 等下次开机重新绑定，避免模块因端口占用整体失效。
			r.recordEvent("api_listen_failed")
			Trace("serve: listen failed permanently: %v", e)
			go func() { _ = r.Boot(ctx, bootReady) }()
			<-ctx.Done()
			return nil
		}
	}
	server := &http.Server{
		Handler:           r.apiHandler(cfg),
		ReadHeaderTimeout: 10 * time.Second,
		TLSConfig: &tls.Config{
			MinVersion:   tls.VersionTLS12,
			Certificates: []tls.Certificate{cert},
		},
	}
	serveErr := make(chan error, 1)
	go func() {
		if e := server.ServeTLS(listener, "", ""); e != nil && !errors.Is(e, http.ErrServerClosed) {
			serveErr <- e
		}
	}()
	// 实际端口可能由系统分配，回写给客户端读取。
	if cfg.Listen != listener.Addr().String() {
		cfg.Listen = listener.Addr().String()
		if e = jsonWrite(r.path(apiConfigFile), cfg); e != nil {
			_ = server.Close()
			Trace("serve: write api.json failed: %v", e)
			return errors.New("api_config_write_failed")
		}
		// 端口变化后必须重新交付，否则 App 仍拿着旧地址连不上。
		if e = r.DeliverAPIConfig(cfg); e != nil {
			r.recordEvent("api_credential_delivery_failed")
		}
	}
	Trace("serve: listening %s", listener.Addr().String())
	go func() { _ = r.Boot(ctx, bootReady) }()
	// 管理端可能重装或清除数据，定期补交付一次凭据，避免 App 拿着被删掉的文件报不可用。
	go r.credentialDeliveryLoop(ctx, cfg, delivered)
	select {
	case <-ctx.Done():
	case e = <-serveErr:
	}
	shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = server.Shutdown(shutdown)
	return e
}

func (r *Runtime) apiHandler(cfg APIConfig) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/health", func(w http.ResponseWriter, _ *http.Request) {
		writeAPIJSON(w, http.StatusOK, map[string]any{"schema": 1, "ok": true})
	})
	mux.HandleFunc("GET /v1/status", r.apiAuth(cfg, func(w http.ResponseWriter, _ *http.Request) {
		writeAPIJSON(w, http.StatusOK, map[string]any{"schema": 1, "ok": true, "state": r.Status()})
	}))
	mux.HandleFunc("POST /v1/select", r.apiAuth(cfg, func(w http.ResponseWriter, req *http.Request) {
		var body struct {
			ProfileID int64 `json:"profileId"`
		}
		if e := json.NewDecoder(http.MaxBytesReader(w, req.Body, 4096)).Decode(&body); e != nil {
			writeAPIError(w, http.StatusBadRequest, "request_invalid")
			return
		}
		// 只换运行态选择，不重启核心；失败返回错误码，由客户端决定是否全量应用。
		if e := r.SelectNode(req.Context(), body.ProfileID); e != nil {
			writeAPIError(w, http.StatusConflict, e.Error())
			return
		}
		writeAPIJSON(w, http.StatusOK, map[string]any{"schema": 1, "ok": true, "state": r.Status()})
	}))
	mux.HandleFunc("POST /v1/service/start", r.apiAuth(cfg, func(w http.ResponseWriter, req *http.Request) {
		if e := r.Start(req.Context()); e != nil {
			writeAPIError(w, http.StatusConflict, e.Error())
			return
		}
		writeAPIJSON(w, http.StatusOK, map[string]any{"schema": 1, "ok": true, "state": r.Status()})
	}))
	mux.HandleFunc("POST /v1/service/stop", r.apiAuth(cfg, func(w http.ResponseWriter, req *http.Request) {
		if e := r.Stop(req.Context()); e != nil {
			writeAPIError(w, http.StatusConflict, e.Error())
			return
		}
		writeAPIJSON(w, http.StatusOK, map[string]any{"schema": 1, "ok": true, "state": r.Status()})
	}))
	mux.HandleFunc("GET /v1/events", r.apiAuth(cfg, r.apiEvents))
	r.registerExtraRoutes(mux, cfg)
	return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		// 只接受回环 Host，防止 DNS rebinding 把外部请求指到本机管理面。
		if !isLoopbackHost(req.Host) {
			writeAPIError(w, http.StatusForbidden, "host_not_allowed")
			return
		}
		w.Header().Set("X-Content-Type-Options", "nosniff")
		mux.ServeHTTP(w, req)
	})
}

func (r *Runtime) apiAuth(cfg APIConfig, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, req *http.Request) {
		const prefix = "Bearer "
		header := req.Header.Get("Authorization")
		if !strings.HasPrefix(header, prefix) ||
			subtle.ConstantTimeCompare([]byte(strings.TrimPrefix(header, prefix)), []byte(cfg.Token)) != 1 {
			writeAPIError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		next(w, req)
	}
}

// apiEvents 推送状态快照：先发全量，再按固定节奏补发，客户端不需要轮询。
func (r *Runtime) apiEvents(w http.ResponseWriter, req *http.Request) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		writeAPIError(w, http.StatusInternalServerError, "stream_unsupported")
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	writeEvent := func() bool {
		payload, e := json.Marshal(map[string]any{"schema": 1, "state": r.Status()})
		if e != nil {
			return false
		}
		if _, e = fmt.Fprintf(w, "event: status\ndata: %s\n\n", payload); e != nil {
			return false
		}
		flusher.Flush()
		return true
	}
	if !writeEvent() {
		return
	}
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-req.Context().Done():
			return
		case <-ticker.C:
			if !writeEvent() {
				return
			}
		}
	}
}

func isLoopbackHost(host string) bool {
	h, _, e := net.SplitHostPort(host)
	if e != nil {
		h = host
	}
	ip := net.ParseIP(h)
	return ip != nil && ip.IsLoopback()
}

func writeAPIJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeAPIError(w http.ResponseWriter, status int, code string) {
	if !apiCodePattern.MatchString(code) {
		code = "module_operation_failed"
	}
	writeAPIJSON(w, status, map[string]any{"schema": 1, "ok": false, "error": code})
}

// APIAccess 供 App 首次经 su 读取一次凭据，之后全部走 HTTPS。
func (r *Runtime) APIAccess() (APIConfig, error) {
	return r.EnsureAPIConfig()
}
