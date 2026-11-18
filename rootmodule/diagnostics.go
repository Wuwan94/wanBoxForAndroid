// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

// B5（N2）脱敏诊断包：脱敏规则按
// openspec/changes/borrow-netproxy-nightly/design.md §2 的表重写实现，
// 不复制上游 redact.go 原文。只借设计与行为。

// MaxDiagnosticsLogBytes 是诊断包内每份日志文件的尾部上限（与上游同值）。
const MaxDiagnosticsLogBytes = 4 << 20

// maxDiagnosticsLogCap 是运行期日志文件触发截尾的上限；超过后只保留尾部。
const maxDiagnosticsLogCap = 8 << 20

const diagnosticsCorruptJSON = "{\"diagnostic_error\":\"JSON 配置损坏，已省略原文以保护敏感信息\"}\n"

var (
	urlUserinfoPattern = regexp.MustCompile(`(https?://)[^/@\s]+@`)
	querySecretPattern = regexp.MustCompile(`(?i)([?&](?:token|key|secret|password|auth|uuid|hwid)=)[^&\s]+`)
	authHeaderPattern  = regexp.MustCompile(`(?i)((?:authorization|proxy-authorization)\s*:\s*(?:bearer|basic)\s+)[^\r\n\s,;]+`)
	lineSecretPattern  = regexp.MustCompile(`(?i)((?:["']?(?:x-hwid|hwid|uuid|token|password|secret|auth(?:[_-]?(?:str|key))?|p(?:re)?[_-]?shared[_-]?key|psk|private[_-]?key|public[_-]?key|short[_-]?id|custom[_-]?headers)["']?\s*[:=]\s*["']?))[^"'\r\n\s,;}&]+`)
	httpURLPattern     = regexp.MustCompile(`(?i)\bhttps?://[^\s\p{Cc}<>"'，；。]+`)
	otherURLPattern    = regexp.MustCompile(`(?i)\b[a-z][a-z0-9+.-]*://[^\s\p{Cc}<>"'，；。]+`)
	macPattern         = regexp.MustCompile(`(?i)\b[0-9a-f]{2}(?::[0-9a-f]{2}){5}\b`)
	ipv4Pattern        = regexp.MustCompile(`\b(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)(?:\.(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)){3}\b`)
	ipv6Candidate      = regexp.MustCompile(`(?i)[0-9a-f]{0,4}(?::[0-9a-f]{0,4}){2,7}`)
)

// sensitiveJSONKeys 是脱敏的 JSON 键表（比较时小写、- 与 _ 等价）。
var sensitiveJSONKeys = map[string]struct{}{
	"access_token": {}, "api_key": {}, "auth": {}, "auth_key": {}, "auth_str": {},
	"authorization": {}, "client_secret": {}, "custom_headers": {}, "headers": {},
	"hwid": {}, "password": {}, "pre_shared_key": {}, "private_key": {},
	"private_key_passphrase": {}, "private_key_path": {}, "proxy_authorization": {},
	"psk": {}, "public_key": {}, "secret": {}, "short_id": {}, "token": {},
	"url": {}, "user_agent": {}, "username": {}, "uuid": {},
	"proxy_apps": {}, "bypass_apps": {}, "include_package": {}, "exclude_package": {},
}

// serverJSONKeys 是严于上游的服务器地址/SNI 键：一律打码。
var serverJSONKeys = map[string]struct{}{
	"server": {}, "address": {}, "server_name": {},
}

func normalizeJSONKey(key string) string {
	return strings.ReplaceAll(strings.ToLower(key), "-", "_")
}

// RedactText 对日志、配置和诊断文本使用统一的脱敏规则。
func RedactText(value string) string {
	var document any
	decoder := json.NewDecoder(strings.NewReader(value))
	decoder.UseNumber()
	if err := decoder.Decode(&document); err == nil {
		switch document.(type) {
		case map[string]any, []any:
			redactJSONValue(document)
			if encoded, err := json.MarshalIndent(document, "", "  "); err == nil {
				value = string(encoded) + "\n"
			}
		}
	}
	value = urlUserinfoPattern.ReplaceAllString(value, `$1***@`)
	value = querySecretPattern.ReplaceAllString(value, `${1}***`)
	value = authHeaderPattern.ReplaceAllString(value, `${1}***`)
	value = lineSecretPattern.ReplaceAllString(value, `${1}***`)
	value = httpURLPattern.ReplaceAllString(value, "[订阅链接已隐藏]")
	value = otherURLPattern.ReplaceAllString(value, "[节点链接已隐藏]")
	value = macPattern.ReplaceAllString(value, "**:**:**:**:**:**")
	value = ipv4Pattern.ReplaceAllStringFunc(value, maskIPv4Text)
	value = ipv6Candidate.ReplaceAllStringFunc(value, maskIPv6Text)
	return value
}

const maxRedactMessageRunes = 1024

// RedactMessage 返回适合单行落盘的脱敏短消息。
func RedactMessage(value string) string {
	value = strings.Join(strings.Fields(RedactText(value)), " ")
	runes := []rune(value)
	if len(runes) <= maxRedactMessageRunes {
		return value
	}
	return string(runes[:maxRedactMessageRunes]) + "…"
}

func redactJSONValue(value any) {
	switch item := value.(type) {
	case map[string]any:
		for key, child := range item {
			normalized := normalizeJSONKey(key)
			switch {
			case normalized == "wifi":
				if policy, ok := child.(map[string]any); ok {
					for _, name := range []string{"blacklist", "whitelist"} {
						if list, ok := policy[name].([]any); ok {
							for i := range list {
								list[i] = "***"
							}
						}
					}
				}
				redactJSONValue(child)
			case normalized == "ssid":
				switch list := child.(type) {
				case []any:
					for i := range list {
						list[i] = "***"
					}
				default:
					item[key] = "***"
				}
			case isSensitiveJSONKey(normalized):
				item[key] = "***"
			default:
				redactJSONValue(child)
			}
		}
	case []any:
		for _, child := range item {
			redactJSONValue(child)
		}
	}
}

func isSensitiveJSONKey(normalized string) bool {
	if _, ok := sensitiveJSONKeys[normalized]; ok {
		return true
	}
	_, ok := serverJSONKeys[normalized]
	return ok
}

// --- IP 打码（wanBox 自定，上游无此规则） ---

var (
	privateIPv4Nets []*net.IPNet
	loopbackIPv4Net *net.IPNet
	linkLocalIPv4   *net.IPNet
	cgnatIPv4Net    *net.IPNet
	loopbackIPv6    = net.ParseIP("::1")
	linkLocalIPv6   *net.IPNet
	privateIPv6     *net.IPNet
)

func init() {
	for _, cidr := range []string{"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"} {
		_, n, _ := net.ParseCIDR(cidr)
		privateIPv4Nets = append(privateIPv4Nets, n)
	}
	_, loopbackIPv4Net, _ = net.ParseCIDR("127.0.0.0/8")
	_, linkLocalIPv4, _ = net.ParseCIDR("169.254.0.0/16")
	_, cgnatIPv4Net, _ = net.ParseCIDR("100.64.0.0/10")
	_, linkLocalIPv6, _ = net.ParseCIDR("fe80::/10")
	_, privateIPv6, _ = net.ParseCIDR("fc00::/7")
}

func ipv4Category(ip net.IP) string {
	switch {
	case loopbackIPv4Net.Contains(ip):
		return "<loopback>"
	case linkLocalIPv4.Contains(ip):
		return "<link-local>"
	case cgnatIPv4Net.Contains(ip):
		return "<cgnat>"
	default:
		for _, n := range privateIPv4Nets {
			if n.Contains(ip) {
				return "<private>"
			}
		}
	}
	return ""
}

func maskIPv4Text(match string) string {
	ip := net.ParseIP(match)
	if ip == nil {
		return match
	}
	if category := ipv4Category(ip); category != "" {
		return category
	}
	// 公网 IPv4 保留前两个 octet（与 design.md 例示 203.0.x.x 一致），其余打码。
	parts := strings.Split(match, ".")
	return parts[0] + "." + parts[1] + ".x.x"
}

func maskIPv6Text(match string) string {
	ip := net.ParseIP(match)
	if ip == nil || ip.To4() != nil {
		// 非 IPv6 文本（如时间片段）或 IPv4 映射地址不在此处理；
		// 后者由 IPv4 规则在地址的 v4 部分上处理。
		if ip != nil && ip.To4() != nil {
			if category := ipv4Category(ip.To4()); category != "" {
				return category
			}
			return strings.Split(ip.To4().String(), ".")[0] + ".x.x"
		}
		return match
	}
	if ip.Equal(loopbackIPv6) {
		return "<loopback>"
	}
	switch {
	case linkLocalIPv6.Contains(ip):
		return "<link-local>"
	case privateIPv6.Contains(ip):
		return "<private>"
	}
	raw := ip.To16()
	return fmt.Sprintf("%x:%x:xxxx:xxxx::", uint16(raw[0])<<8|uint16(raw[1]), uint16(raw[2])<<8|uint16(raw[3]))
}

// --- 诊断包导出 ---

// DiagnosticsRequest 是导出诊断包的请求（由管理端经 stdin 传入）。
type DiagnosticsRequest struct {
	Destination        string `json:"destination"`
	AppVersion         string `json:"appVersion"`
	AppVersionCode     int    `json:"appVersionCode"`
	PreviewVersion     string `json:"previewVersion"`
	PreviewVersionCode int    `json:"previewVersionCode"`
	SingBoxVersion     string `json:"singBoxVersion"`
}

type diagnosticsEntry struct {
	name    string
	content []byte
}

type diagnosticsCollector struct {
	entries []diagnosticsEntry
	notes   []string // 缺失或已省略原文的文件，写入 README。
}

func (c *diagnosticsCollector) add(name string, content []byte) {
	c.entries = append(c.entries, diagnosticsEntry{name: name, content: content})
}

func (c *diagnosticsCollector) missing(name, reason string) {
	c.notes = append(c.notes, name+"（"+reason+"）")
}

// readFileTail 读取文件尾部，上限 maxBytes；缺失返回 os.IsNotExist 错误。
func readFileTail(path string, maxBytes int64) ([]byte, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return nil, err
	}
	if info.Size() > maxBytes {
		if _, err := f.Seek(info.Size()-maxBytes, io.SeekStart); err != nil {
			return nil, err
		}
	}
	return io.ReadAll(io.LimitReader(f, maxBytes+1))
}

func redactJSONFile(content []byte) []byte {
	if !json.Valid(content) {
		return []byte(diagnosticsCorruptJSON)
	}
	return []byte(RedactText(string(content)))
}

// redactSnapshot 脱敏 Root 快照：files 值是 base64 内容，一律打码；
// cache.db 与证书/私钥文件直接剔除，不得进入诊断包。
func redactSnapshot(content []byte) []byte {
	var document map[string]any
	decoder := json.NewDecoder(bytes.NewReader(content))
	decoder.UseNumber()
	if err := decoder.Decode(&document); err != nil {
		return []byte(diagnosticsCorruptJSON)
	}
	if files, ok := document["files"].(map[string]any); ok {
		for name := range files {
			lower := strings.ToLower(name)
			if name == "cache.db" || strings.HasSuffix(lower, ".crt") || strings.HasSuffix(lower, ".pem") ||
				strings.HasSuffix(lower, ".key") || strings.Contains(lower, "cert") || strings.Contains(lower, "private") {
				delete(files, name)
				continue
			}
			files[name] = "***"
		}
	}
	encoded, err := json.MarshalIndent(document, "", "  ")
	if err != nil {
		return []byte(diagnosticsCorruptJSON)
	}
	return []byte(RedactText(string(encoded)))
}

func moduleVersionName(dir string) string {
	b, err := os.ReadFile(filepath.Join(dir, "module.prop"))
	if err != nil {
		return "unknown"
	}
	for _, line := range strings.Split(string(b), "\n") {
		if after, ok := strings.CutPrefix(strings.TrimSpace(line), "version="); ok && strings.TrimSpace(after) != "" {
			return strings.TrimSpace(after)
		}
	}
	return "unknown"
}

var remoteRuleSafeTag = strings.NewReplacer(":", "_", "/", "_", "\\", "_", "?", "_", "&", "_", "=", "_")

type remoteRuleMeta struct {
	tag     string
	source  string
	format  string
	hash    string
	updated string
}

// redactRuleURL 只打码 URL 内的凭据（userinfo 与敏感 query 值），保留
// scheme/host/path 作远程规则元数据的来源；订阅地址全文隐藏规则不适用于此。
func redactRuleURL(raw string) string {
	redacted := urlUserinfoPattern.ReplaceAllString(raw, `$1***@`)
	return querySecretPattern.ReplaceAllString(redacted, `${1}***`)
}

func (r *Runtime) remoteRuleMetadata(generation string, config []byte) []remoteRuleMeta {
	var document struct {
		Route struct {
			RuleSet []struct {
				Tag    string `json:"tag"`
				Type   string `json:"type"`
				Format string `json:"format"`
				URL    string `json:"url"`
			} `json:"rule_set"`
		} `json:"route"`
	}
	if err := json.Unmarshal(config, &document); err != nil {
		return nil
	}
	var metas []remoteRuleMeta
	for _, rs := range document.Route.RuleSet {
		if rs.Type != "remote" || rs.Tag == "" {
			continue
		}
		meta := remoteRuleMeta{tag: rs.Tag, source: redactRuleURL(rs.URL), format: rs.Format, hash: "unknown", updated: "unknown"}
		// 远程规则原文不导出；哈希与更新时间取已下载的 .srs 文件。
		srsPath := filepath.Join(generation, "assets", "srs", remoteRuleSafeTag.Replace(rs.Tag)+".srs")
		if info, err := os.Stat(srsPath); err == nil {
			if b, err := os.ReadFile(srsPath); err == nil {
				sum := sha256.Sum256(b)
				meta.hash = hex.EncodeToString(sum[:])
			}
			meta.updated = info.ModTime().Format(time.RFC3339)
		}
		metas = append(metas, meta)
	}
	return metas
}

func versionOrUnknown(value string) string {
	if strings.TrimSpace(value) == "" {
		return "unknown"
	}
	return value
}

func versionCodeOrUnknown(code int) string {
	if code <= 0 {
		return "unknown"
	}
	return strconv.Itoa(code)
}

// ExportDiagnostics 生成脱敏诊断包：先写同目录临时文件，fsync 后原子重命名。
// 导出不依赖核心运行；单个文件缺失只记入 README，不使整包失败。
// 返回 notes（缺失/已省略原文的文件清单）。
func (r *Runtime) ExportDiagnostics(req DiagnosticsRequest) ([]string, error) {
	dest := strings.TrimSpace(req.Destination)
	if dest == "" {
		return nil, errors.New("diagnostics_invalid_request")
	}
	base := filepath.Base(dest)
	if !strings.HasPrefix(base, "wanbox-diagnostics-") || !strings.HasSuffix(base, ".tar.gz") {
		return nil, errors.New("diagnostics_invalid_request")
	}
	dir := filepath.Dir(dest)
	if info, err := os.Stat(dir); err != nil || !info.IsDir() {
		return nil, errors.New("diagnostics_destination_unavailable")
	}
	if info, err := os.Lstat(dest); err == nil {
		if !info.Mode().IsRegular() {
			return nil, errors.New("diagnostics_target_not_regular")
		}
	} else if !os.IsNotExist(err) {
		return nil, err
	}

	collector := &diagnosticsCollector{}
	generation := ""
	if rev, err := r.CurrentRevision(); err == nil && rev != "" {
		generation = r.path("generations", rev)
	}

	// 日志：尾部 4 MiB/文件。轮转备份（.1/.2）可选：存在则收录，缺失不记入清单。
	for _, item := range []struct {
		name     string
		optional bool
	}{
		{"service.log", false}, {"service.log.1", true}, {"service.log.2", true},
		{"sing-box.log", false}, {"sing-box.log.1", true}, {"sing-box.log.2", true},
	} {
		content, err := readFileTail(r.path("logs", item.name), MaxDiagnosticsLogBytes)
		if os.IsNotExist(err) {
			if !item.optional {
				collector.missing("logs/"+item.name, "文件不存在")
			}
			continue
		}
		if err != nil {
			return nil, err
		}
		collector.add("logs/"+item.name, []byte(RedactText(string(content))))
	}

	// 生成配置（脱敏；损坏则省略原文）与远程规则元数据。
	var remoteMetas []remoteRuleMeta
	if generation != "" {
		if content, err := os.ReadFile(filepath.Join(generation, "run", "config.json")); err == nil {
			redacted := redactJSONFile(content)
			if string(redacted) == diagnosticsCorruptJSON {
				collector.missing("config/singbox-config.json", "JSON 已损坏，原文已省略")
			}
			collector.add("config/singbox-config.json", redacted)
			remoteMetas = r.remoteRuleMetadata(generation, content)
			// 本地规则：只导出路由规则的匹配条件（脱敏后），不含凭据。
			if rules := extractRouteRules(content); rules != nil {
				collector.add("rules/local/route-rules.json", rules)
			} else {
				collector.missing("rules/local/route-rules.json", "无路由规则")
			}
		} else if os.IsNotExist(err) {
			collector.missing("config/singbox-config.json", "文件不存在")
			collector.missing("rules/local/route-rules.json", "无生成配置")
		} else {
			return nil, err
		}
	} else {
		collector.missing("config/singbox-config.json", "无当前生成")
		collector.missing("rules/local/route-rules.json", "无当前生成")
	}

	// Root 快照（脱敏版）。
	if generation != "" {
		if content, err := os.ReadFile(filepath.Join(generation, "snapshot.json")); err == nil {
			redacted := redactSnapshot(content)
			if string(redacted) == diagnosticsCorruptJSON {
				collector.missing("config/root-snapshot.json", "JSON 已损坏，原文已省略")
			}
			collector.add("config/root-snapshot.json", redacted)
		} else if os.IsNotExist(err) {
			collector.missing("config/root-snapshot.json", "文件不存在")
		} else {
			return nil, err
		}
	} else {
		collector.missing("config/root-snapshot.json", "无当前生成")
	}

	// 服务状态快照（脱敏后）。
	if content, err := os.ReadFile(r.path("state.json")); err == nil {
		redacted := redactJSONFile(content)
		if string(redacted) == diagnosticsCorruptJSON {
			collector.missing("state/service.json", "JSON 已损坏，原文已省略")
		}
		collector.add("state/service.json", redacted)
	} else if os.IsNotExist(err) {
		collector.missing("state/service.json", "文件不存在")
	} else {
		return nil, err
	}

	// 模块状态：版本与诊断字段，不含凭据。
	status := r.Status()
	moduleCode, _ := moduleVersionCode(r.ModuleDir)
	moduleStatus := map[string]any{
		"appVersion":         versionOrUnknown(req.AppVersion),
		"appVersionCode":     versionCodeOrUnknown(req.AppVersionCode),
		"previewVersion":     versionOrUnknown(req.PreviewVersion),
		"previewVersionCode": versionCodeOrUnknown(req.PreviewVersionCode),
		"moduleVersion":      moduleVersionName(r.ModuleDir),
		"moduleVersionCode":  moduleCode,
		"singBoxVersion":     versionOrUnknown(req.SingBoxVersion),
		"phase":              status.Phase,
		"revision":           status.Revision,
		"health":             status.Health,
		"generatedAt":        time.Now().Format(time.RFC3339),
	}
	encodedStatus, err := json.MarshalIndent(moduleStatus, "", "  ")
	if err != nil {
		return nil, err
	}
	collector.add("config/module-status.json", append(encodedStatus, '\n'))

	// README：版本、时间、脱敏声明、缺失清单、远程规则元数据、残留风险。
	collector.add("README.txt", []byte(r.diagnosticsReadme(req, moduleCode, collector.notes, remoteMetas)))

	sort.Slice(collector.entries, func(i, j int) bool { return collector.entries[i].name < collector.entries[j].name })
	if err := writeDiagnosticsArchive(dest, collector.entries); err != nil {
		return nil, err
	}
	return collector.notes, nil
}

// extractRouteRules 从生成配置中抽取 route.rules 数组并脱敏后返回；
// 无规则或配置损坏时返回 nil。
func extractRouteRules(config []byte) []byte {
	var document struct {
		Route struct {
			Rules []json.RawMessage `json:"rules"`
		} `json:"route"`
	}
	if err := json.Unmarshal(config, &document); err != nil || len(document.Route.Rules) == 0 {
		return nil
	}
	redacted := make([]any, 0, len(document.Route.Rules))
	for _, raw := range document.Route.Rules {
		var rule any
		decoder := json.NewDecoder(bytes.NewReader(raw))
		decoder.UseNumber()
		if err := decoder.Decode(&rule); err != nil {
			return nil
		}
		redactJSONValue(rule)
		redacted = append(redacted, rule)
	}
	encoded, err := json.MarshalIndent(redacted, "", "  ")
	if err != nil {
		return nil
	}
	return []byte(RedactText(string(encoded)) + "\n")
}

func (r *Runtime) diagnosticsReadme(req DiagnosticsRequest, moduleCode int, notes []string, remoteMetas []remoteRuleMeta) string {
	var b strings.Builder
	fmt.Fprintf(&b, "wanBox 诊断包\n")
	fmt.Fprintf(&b, "正式版: %s（版本码 %s）\n", versionOrUnknown(req.AppVersion), versionCodeOrUnknown(req.AppVersionCode))
	fmt.Fprintf(&b, "预览版: %s（版本码 %s）\n", versionOrUnknown(req.PreviewVersion), versionCodeOrUnknown(req.PreviewVersionCode))
	fmt.Fprintf(&b, "模块版本: %s（版本码 %s）\n", moduleVersionName(r.ModuleDir), versionCodeOrUnknown(moduleCode))
	fmt.Fprintf(&b, "内核版本: %s\n", versionOrUnknown(req.SingBoxVersion))
	fmt.Fprintf(&b, "生成时间: %s\n", time.Now().Format(time.RFC3339))
	b.WriteString("敏感信息已脱敏：订阅地址、节点链接、凭据、服务器地址、Wi-Fi 名称、MAC 地址、IP 地址已按规则打码。\n")
	b.WriteString("残留风险：路由规则的域名与规则集名会原样保留，可能反映分流意图；请检查后再对外分享。\n")
	b.WriteString("远程规则集只导出元数据，不含规则原文。\n")
	if len(remoteMetas) > 0 {
		b.WriteString("远程规则集:\n")
		for _, meta := range remoteMetas {
			fmt.Fprintf(&b, "- %s 来源: %s 格式: %s 哈希: %s 更新时间: %s\n",
				meta.tag, versionOrUnknown(meta.source), versionOrUnknown(meta.format), meta.hash, meta.updated)
		}
	}
	if len(notes) > 0 {
		b.WriteString("缺失或已省略的文件:\n")
		for _, note := range notes {
			fmt.Fprintf(&b, "- %s\n", note)
		}
	} else {
		b.WriteString("缺失或已省略的文件: 无\n")
	}
	return b.String()
}

func writeDiagnosticsArchive(dest string, entries []diagnosticsEntry) error {
	dir := filepath.Dir(dest)
	tmp, err := os.CreateTemp(dir, ".diagnostics-")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName)
	if err := tmp.Chmod(0600); err != nil {
		tmp.Close()
		return err
	}
	archiveErr := func() error {
		gz := gzip.NewWriter(tmp)
		tw := tar.NewWriter(gz)
		for _, entry := range entries {
			header := &tar.Header{
				Name:    filepath.ToSlash(entry.name),
				Mode:    0600,
				Size:    int64(len(entry.content)),
				ModTime: time.Now(),
			}
			if err := tw.WriteHeader(header); err != nil {
				return err
			}
			if _, err := tw.Write(entry.content); err != nil {
				return err
			}
		}
		if err := tw.Close(); err != nil {
			return err
		}
		return gz.Close()
	}()
	if err := errors.Join(archiveErr, tmp.Sync(), tmp.Close()); err != nil {
		return err
	}
	if err := os.Rename(tmpName, dest); err != nil {
		return err
	}
	return syncDir(dir)
}

// --- 运行期日志落盘（供 supervisor 使用；尽力而为，失败不阻断启动） ---

// openCappedLogFile 打开追加日志文件；已有内容超过上限时先就地截为尾部。
func openCappedLogFile(path string) (*os.File, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return nil, err
	}
	f, err := os.OpenFile(path, os.O_RDWR|os.O_CREATE|os.O_APPEND, 0600)
	if err != nil {
		return nil, err
	}
	if err := trimLogFileToTail(f, MaxDiagnosticsLogBytes); err != nil {
		f.Close()
		return nil, err
	}
	return f, nil
}

// trimLogFileToTail 把已打开的日志文件就地截为尾部 keepBytes（原地截断，
// 不改名不断句柄；调用方须是唯一写者）。
func trimLogFileToTail(f *os.File, keepBytes int64) error {
	info, err := f.Stat()
	if err != nil || info.Size() <= maxDiagnosticsLogCap {
		return err
	}
	tail := make([]byte, keepBytes)
	if _, err := f.ReadAt(tail, info.Size()-keepBytes); err != nil {
		return err
	}
	if err := f.Truncate(0); err != nil {
		return err
	}
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		return err
	}
	_, err = f.Write(tail)
	return err
}

// cappedLogWriter 是带截尾的追加写者：写满上限后就地截为尾部。
type cappedLogWriter struct {
	file  *os.File
	lines int64
}

func newCappedLogWriter(path string) (*cappedLogWriter, error) {
	f, err := openCappedLogFile(path)
	if err != nil {
		return nil, err
	}
	return &cappedLogWriter{file: f}, nil
}

func (w *cappedLogWriter) WriteLine(line string) {
	if w == nil || w.file == nil {
		return
	}
	if !strings.HasSuffix(line, "\n") {
		line += "\n"
	}
	if _, err := w.file.WriteString(line); err != nil {
		return
	}
	w.lines++
	if w.lines%512 == 0 {
		_ = trimLogFileToTail(w.file, MaxDiagnosticsLogBytes)
	}
}

func (w *cappedLogWriter) Close() error {
	if w == nil || w.file == nil {
		return nil
	}
	return w.file.Close()
}
