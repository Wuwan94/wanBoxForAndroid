package libcore

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

// B3/O6：0 规则 .srs 占位文件不得被当作有效缓存（借鉴 OwnBox 8.8，
// 上游 commit d401e93 的 libcore/ruleset_test.go 结构）。

func writeTestSRS(t *testing.T, path string, rules []option.HeadlessRule) {
	t.Helper()
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if err := srs.Write(f, option.PlainRuleSet{Rules: rules}, C.RuleSetVersionCurrent); err != nil {
		t.Fatal(err)
	}
}

func populatedTestRule() []option.HeadlessRule {
	return []option.HeadlessRule{
		{
			Type: C.RuleTypeDefault,
			DefaultOptions: option.DefaultHeadlessRule{
				DomainSuffix: []string{"example.cn"},
			},
		},
	}
}

func TestIsPopulatedSRSFileRejectsEmptyPlaceholder(t *testing.T) {
	dir := t.TempDir()

	placeholder := filepath.Join(dir, "empty.srs")
	writeTestSRS(t, placeholder, nil)
	if isPopulatedSRSFile(placeholder) {
		t.Fatalf("expected empty SRS placeholder %s not to be treated as populated", placeholder)
	}

	populated := filepath.Join(dir, "populated.srs")
	writeTestSRS(t, populated, populatedTestRule())
	if !isPopulatedSRSFile(populated) {
		t.Fatalf("expected populated SRS file %s to be recognized", populated)
	}

	missing := filepath.Join(dir, "missing.srs")
	if isPopulatedSRSFile(missing) {
		t.Fatalf("expected missing file %s not to be treated as populated", missing)
	}

	zeroByte := filepath.Join(dir, "zero.srs")
	if err := os.WriteFile(zeroByte, nil, 0644); err != nil {
		t.Fatal(err)
	}
	if isPopulatedSRSFile(zeroByte) {
		t.Fatalf("expected zero-byte file %s not to be treated as populated", zeroByte)
	}

	// 非二进制极简规则源：非空即视为有效（与上游判定一致）。
	smallText := filepath.Join(dir, "tiny.json")
	if err := os.WriteFile(smallText, []byte(`{"a":1}`), 0644); err != nil {
		t.Fatal(err)
	}
	if !isPopulatedSRSFile(smallText) {
		t.Fatalf("expected non-empty tiny JSON file %s to be treated as populated", smallText)
	}

	// >128 字节直接放行（启动快速路径，不读盘解析）。
	large := filepath.Join(dir, "large.srs")
	if err := os.WriteFile(large, make([]byte, 129), 0644); err != nil {
		t.Fatal(err)
	}
	if !isPopulatedSRSFile(large) {
		t.Fatalf("expected large file %s to be treated as populated", large)
	}
}

func TestPrepareRemoteRuleSetsPrefersPopulatedCache(t *testing.T) {
	tmpDir := t.TempDir()
	oldAssets := externalAssetsPath
	externalAssetsPath = tmpDir
	t.Cleanup(func() { externalAssetsPath = oldAssets })

	srsDir := filepath.Join(tmpDir, "srs")
	if err := os.MkdirAll(srsDir, 0755); err != nil {
		t.Fatal(err)
	}

	// tag="geoip:cn"：altPath="geoip-cn.srs"，dstPath="geoip_cn.srs"。
	altPlaceholder := filepath.Join(srsDir, "geoip-cn.srs")
	writeTestSRS(t, altPlaceholder, nil)
	if isPopulatedSRSFile(altPlaceholder) {
		t.Fatalf("expected empty SRS placeholder %s not to be treated as populated", altPlaceholder)
	}

	populatedDst := filepath.Join(srsDir, "geoip_cn.srs")
	writeTestSRS(t, populatedDst, populatedTestRule())
	if !isPopulatedSRSFile(populatedDst) {
		t.Fatalf("expected populated SRS file %s to be recognized", populatedDst)
	}

	sets := []option.RuleSet{
		{
			Type: C.RuleSetTypeRemote,
			Tag:  []string{"geoip:cn"},
		},
	}
	if err := prepareRemoteRuleSets(sets); err != nil {
		t.Fatal(err)
	}
	if sets[0].RemoteOptions.InitialPath != populatedDst {
		t.Fatalf("expected InitialPath=%s, got %s", populatedDst, sets[0].RemoteOptions.InitialPath)
	}
}

func TestPrepareRemoteRuleSetsKeepsPlaceholderWhenNoCache(t *testing.T) {
	tmpDir := t.TempDir()
	oldAssets := externalAssetsPath
	externalAssetsPath = tmpDir
	t.Cleanup(func() { externalAssetsPath = oldAssets })

	sets := []option.RuleSet{
		{
			Type: C.RuleSetTypeRemote,
			Tag:  []string{"geosite:cn"},
		},
	}
	if err := prepareRemoteRuleSets(sets); err != nil {
		t.Fatal(err)
	}
	initial := sets[0].RemoteOptions.InitialPath
	if initial == "" {
		t.Fatal("expected InitialPath to be set to a placeholder when no cache exists")
	}
	if isPopulatedSRSFile(initial) {
		t.Fatalf("expected placeholder %s not to be treated as populated", initial)
	}
	if _, err := os.Stat(initial); err != nil {
		t.Fatalf("expected placeholder file %s to exist: %v", initial, err)
	}

	// 第二次调用不得把占位换成其他路径（路径稳定，不重写）。
	sets2 := []option.RuleSet{
		{
			Type: C.RuleSetTypeRemote,
			Tag:  []string{"geosite:cn"},
		},
	}
	if err := prepareRemoteRuleSets(sets2); err != nil {
		t.Fatal(err)
	}
	if sets2[0].RemoteOptions.InitialPath != initial {
		t.Fatalf("expected stable placeholder path %s, got %s", initial, sets2[0].RemoteOptions.InitialPath)
	}
}

func TestConvertGeoRuleSetCacheReuseRejectsEmptyPlaceholder(t *testing.T) {
	tmpDir := t.TempDir()
	oldAssets := externalAssetsPath
	externalAssetsPath = tmpDir
	t.Cleanup(func() { externalAssetsPath = oldAssets })

	// 无效的 db：转换必然失败走空回退；本测试只验证“不复用 0 规则缓存”这一判定，
	// 不验证回退内容。
	dbPath := filepath.Join(tmpDir, "geoip.db")
	if err := os.WriteFile(dbPath, []byte("not a maxmind db"), 0644); err != nil {
		t.Fatal(err)
	}
	oldTime := time.Now().Add(-2 * time.Hour)
	newTime := time.Now().Add(-1 * time.Hour)
	if err := os.Chtimes(dbPath, oldTime, oldTime); err != nil {
		t.Fatal(err)
	}

	srsDir := filepath.Join(tmpDir, "srs")
	if err := os.MkdirAll(srsDir, 0755); err != nil {
		t.Fatal(err)
	}
	// db 旧、占位新：旧逻辑会直接复用占位；新逻辑必须重新生成。
	dst := filepath.Join(srsDir, "geoip_cn.srs")
	writeTestSRS(t, dst, nil)
	if err := os.Chtimes(dst, newTime, newTime); err != nil {
		t.Fatal(err)
	}

	got, err := convertGeoRuleSetToSRS("geoip:cn", "cn", dbPath, true)
	if err != nil {
		t.Fatalf("convertGeoRuleSetToSRS returned error: %v", err)
	}
	if got != dst {
		t.Fatalf("expected dst=%s, got %s", dst, got)
	}
	info, err := os.Stat(dst)
	if err != nil {
		t.Fatal(err)
	}
	if !info.ModTime().After(newTime) {
		t.Fatalf("expected empty placeholder cache to be regenerated, mtime unchanged at %v", info.ModTime())
	}
	if isPopulatedSRSFile(dst) {
		t.Fatalf("expected regenerated fallback %s to remain unpopulated (db is invalid)", dst)
	}
}

func TestConvertGeoRuleSetCacheReuseKeepsPopulatedCache(t *testing.T) {
	tmpDir := t.TempDir()
	oldAssets := externalAssetsPath
	externalAssetsPath = tmpDir
	t.Cleanup(func() { externalAssetsPath = oldAssets })

	dbPath := filepath.Join(tmpDir, "geoip.db")
	if err := os.WriteFile(dbPath, []byte("not a maxmind db"), 0644); err != nil {
		t.Fatal(err)
	}
	oldTime := time.Now().Add(-2 * time.Hour)
	newTime := time.Now().Add(-1 * time.Hour)
	if err := os.Chtimes(dbPath, oldTime, oldTime); err != nil {
		t.Fatal(err)
	}

	srsDir := filepath.Join(tmpDir, "srs")
	if err := os.MkdirAll(srsDir, 0755); err != nil {
		t.Fatal(err)
	}
	dst := filepath.Join(srsDir, "geoip_cn.srs")
	writeTestSRS(t, dst, populatedTestRule())
	if err := os.Chtimes(dst, newTime, newTime); err != nil {
		t.Fatal(err)
	}

	got, err := convertGeoRuleSetToSRS("geoip:cn", "cn", dbPath, true)
	if err != nil {
		t.Fatalf("convertGeoRuleSetToSRS returned error: %v", err)
	}
	if got != dst {
		t.Fatalf("expected dst=%s, got %s", dst, got)
	}
	info, err := os.Stat(dst)
	if err != nil {
		t.Fatal(err)
	}
	if !info.ModTime().Equal(newTime) {
		t.Fatalf("expected populated cache to be reused without rewrite, mtime moved to %v", info.ModTime())
	}
}

// B3 核查结论：wanBox 规则模型不支持 local: 本地规则集（无 RuleSetCatalog、
// 无多目录解析、无 local: 标签模型）。Go 侧对此类条目不做任何解析；
// Kotlin 侧配置生成即明确拒绝（见 RulesetEntryTest）。本测试锁定 Go 侧行为。
func TestParseGeoRuleSetPathIgnoresLocalEntries(t *testing.T) {
	if _, _, _, ok := parseGeoRuleSetPath("local:custom.srs"); ok {
		t.Fatalf("expected local: entry to be ignored by geo rule-set parsing")
	}
	if _, _, _, ok := parseGeoRuleSetPath("local:geoip-lite.db"); ok {
		t.Fatalf("expected local: entry to be ignored by geo rule-set parsing")
	}
}
