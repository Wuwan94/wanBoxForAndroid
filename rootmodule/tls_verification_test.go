// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// B2：无效证书必须失败的回归测试。
//
// 项目里控制证书校验的是 HysteriaBean.allowInsecure 与
// DataStore.globalAllowInsecure（两者默认均为 false），由 HysteriaFmt.kt
// 以 `bean.allowInsecure == true || globalAllowInsecure` 组合成 tls.insecure。
// 这里验证「默认启用验证」在真实 TLS 握手上的行为：自签 / 过期 / 域名
// 不匹配三种无效证书都必须失败，且只有显式跳过时才成功。
//
// 注意：Hysteria 插件的 `--no-check` 是"关闭版本更新检查"，与本测试无关，
// 见 tools/test_no_check_semantics.py 的源码契约检查。

func selfSignedCert(t *testing.T, notBefore, notAfter time.Time, dnsNames []string) tls.Certificate {
	t.Helper()
	key, e := rsa.GenerateKey(rand.Reader, 2048)
	if e != nil {
		t.Fatal(e)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(time.Now().UnixNano()),
		Subject:      pkix.Name{CommonName: "invalid.local"},
		NotBefore:    notBefore,
		NotAfter:     notAfter,
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		DNSNames:     dnsNames,
	}
	der, e := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if e != nil {
		t.Fatal(e)
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
}

func tlsServer(t *testing.T, cert *tls.Certificate) *httptest.Server {
	t.Helper()
	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	if cert != nil {
		srv.TLS = &tls.Config{Certificates: []tls.Certificate{*cert}}
	} else {
		srv.TLS = &tls.Config{}
	}
	srv.StartTLS()
	t.Cleanup(srv.Close)
	return srv
}

func dialTLS(t *testing.T, srv *httptest.Server, serverName string, skip bool) error {
	t.Helper()
	addr := strings.TrimPrefix(srv.URL, "https://")
	conn, e := tls.Dial("tcp", addr, &tls.Config{
		ServerName:         serverName,
		InsecureSkipVerify: skip,
		MinVersion:         tls.VersionTLS12,
	})
	if e != nil {
		return e
	}
	return conn.Close()
}

func TestSelfSignedCertificateFailsByDefaultAndOnlyWorksWithExplicitOptIn(t *testing.T) {
	srv := tlsServer(t, nil)
	if e := dialTLS(t, srv, "127.0.0.1", false); e == nil {
		t.Fatal("self-signed certificate was accepted without explicit opt-in")
	}
	if e := dialTLS(t, srv, "127.0.0.1", true); e != nil {
		t.Fatalf("explicit opt-in should allow the connection: %v", e)
	}
}

func TestExpiredCertificateFailsByDefault(t *testing.T) {
	cert := selfSignedCert(t,
		time.Now().Add(-48*time.Hour),
		time.Now().Add(-24*time.Hour),
		[]string{"invalid.local"},
	)
	srv := tlsServer(t, &cert)
	if e := dialTLS(t, srv, "invalid.local", false); e == nil {
		t.Fatal("expired certificate was accepted without explicit opt-in")
	}
}

func TestHostnameMismatchFailsByDefault(t *testing.T) {
	cert := selfSignedCert(t,
		time.Now().Add(-time.Hour),
		time.Now().Add(24*time.Hour),
		[]string{"other.example"},
	)
	srv := tlsServer(t, &cert)
	if e := dialTLS(t, srv, "invalid.local", false); e == nil {
		t.Fatal("hostname mismatch was accepted without explicit opt-in")
	}
}

// insecureFromPreferences mirrors the `||` composition used by HysteriaFmt.kt.
// It documents the contract: without an explicit opt-in on either side, the
// generated TLS options must never skip verification.
func insecureFromPreferences(beanAllowInsecure, globalAllowInsecure bool) bool {
	return beanAllowInsecure || globalAllowInsecure
}

func TestInsecureRequiresExplicitOptIn(t *testing.T) {
	cases := []struct {
		bean, global, want bool
	}{
		{false, false, false},
		{true, false, true},
		{false, true, true},
		{true, true, true},
	}
	for _, c := range cases {
		if got := insecureFromPreferences(c.bean, c.global); got != c.want {
			t.Fatalf("bean=%v global=%v: got %v want %v", c.bean, c.global, got, c.want)
		}
	}
}
