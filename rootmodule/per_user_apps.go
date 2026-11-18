// B6（N5）：分应用名单按 Android 用户细化。持久化键为严格的
// `<userId>:<package>` 字符串；同一包名在不同用户下是不同条目。
// UID 解析由 Root 侧按用户查询 Android package service 后合并，
// 不得把包名直接交给 sing-box，不得跨用户合并 UID，
// 不维护模块侧 UID 缓存（安装/重装/UID 变化后由重启或配置 reload 重新解析）。
package module

import (
	"context"
	"errors"
	"fmt"
	"os/exec"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

// perUserAppRef 是严格的 `<userId>:<package>` 引用。
var perUserAppRef = regexp.MustCompile(`^(\d+):([a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+)$`)

// ParsePerUserAppRef 解析一条 `<userId>:<package>` 引用。
// userId 范围与 F1 的 userId 校验一致（0..21474）。
func ParsePerUserAppRef(ref string) (userID int, pkg string, err error) {
	m := perUserAppRef.FindStringSubmatch(strings.TrimSpace(ref))
	if m == nil {
		return 0, "", errors.New("per_user_app_ref_invalid")
	}
	userID, err = strconv.Atoi(m[1])
	if err != nil || userID < 0 || userID > 21474 {
		return 0, "", errors.New("per_user_app_ref_invalid")
	}
	return userID, m[2], nil
}

// appUIDQuery 按用户查询单个包的 UID；默认实现走 `pm list packages`。
// 调用方（wanboxctl / 快照校验）可注入桩。
type appUIDQuery func(userID int, pkg string) (int, error)

var queryAndroidAppUID appUIDQuery = func(userID int, pkg string) (int, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "pm", "list", "packages", "--user", strconv.Itoa(userID), "-U")
	cmd.WaitDelay = 100 * time.Millisecond
	out, err := cmd.Output()
	if err != nil {
		return 0, errors.New("per_user_app_query_failed")
	}
	prefix := "package:" + pkg + " uid:"
	for _, line := range strings.Split(string(out), "\n") {
		line = strings.TrimSpace(line)
		if !strings.HasPrefix(line, prefix) {
			continue
		}
		uid, err := strconv.Atoi(strings.TrimSpace(strings.TrimPrefix(line, prefix)))
		if err != nil || uid < 0 {
			return 0, errors.New("per_user_app_uid_invalid")
		}
		return uid, nil
	}
	return 0, errors.New("per_user_app_not_found")
}

// ResolvePerUserAppUIDs 把一批 `<userId>:<package>` 引用解析为 UID 列表。
// 同一包名在不同用户下分别查询、分别产出 UID（不合并为一个）；
// 找不到的引用进入 missing，不静默丢弃也不中断其余解析；
// 引用本身非法时返回错误。结果排序、去重；UID 0 永不自动加入
// （调用方也不得把 rootUidPolicy 的 UID 0 混入名单）。
func ResolvePerUserAppUIDs(refs []string, query appUIDQuery) (uids []int, missing []string, err error) {
	if query == nil {
		query = queryAndroidAppUID
	}
	seen := map[int]bool{}
	for _, ref := range refs {
		ref = strings.TrimSpace(ref)
		if ref == "" {
			continue
		}
		userID, pkg, err := ParsePerUserAppRef(ref)
		if err != nil {
			return nil, nil, fmt.Errorf("%w: %s", err, ref)
		}
		uid, qerr := query(userID, pkg)
		if qerr != nil {
			missing = append(missing, ref)
			continue
		}
		if uid == 0 || seen[uid] {
			continue
		}
		seen[uid] = true
		uids = append(uids, uid)
	}
	sort.Ints(uids)
	return uids, missing, nil
}

// ExcludeAllUIDRange 是空白名单护栏在运行时设置的唯一范围值。
const ExcludeAllUIDRange = "0:4294967294"

// EmptyWhitelistGuard 是空白名单护栏的纯判定：白名单模式（proxyApps 且非
// bypass）下，解析后 UID 与原生 include UID/range 均为空时返回 true；
// 调用方此时 MUST 只在运行时设置 exclude_uid_range=["0:4294967294"]，
// 不得生成空 include 后静默变全量接管，不得注入占位 UID。
// 完整的 include/exclude 合并（含 rootUidPolicy 的 UID 0 正交处理）
// 仍由调用方的入站筛选函数负责，本函数只回答“护栏是否生效”。
func EmptyWhitelistGuard(proxyApps, bypass bool, resolvedUIDs, nativeIncludeUIDs []int, nativeIncludeRanges []string) bool {
	return proxyApps && !bypass &&
		len(resolvedUIDs) == 0 && len(nativeIncludeUIDs) == 0 && len(nativeIncludeRanges) == 0
}

// MigrateLegacyIndividual 迁移旧单用户名单：每行可能是包名、数字 UID，
// 或已是 `<userId>:<package>`。包名按当前用户（user 0）迁移；
// 数字 UID 用 reverseLookup 反查包名；无法反查的条目 MUST 保留在旧字段
// （返回 retained），由用户手动确认，不得静默丢弃。顺序保留、去重。
// reverseLookup 为 nil 时数字 UID 一律保留。
func MigrateLegacyIndividual(lines []string, reverseLookup func(uid int) (string, bool)) (migrated, retained []string) {
	seenMigrated := map[string]bool{}
	seenRetained := map[string]bool{}
	for _, raw := range lines {
		line := strings.TrimSpace(raw)
		if line == "" {
			continue
		}
		if _, _, err := ParsePerUserAppRef(line); err == nil {
			if !seenMigrated[line] {
				seenMigrated[line] = true
				migrated = append(migrated, line)
			}
			continue
		}
		if uid, err := strconv.Atoi(line); err == nil && uid >= 0 {
			if reverseLookup != nil {
				if pkg, ok := reverseLookup(uid); ok {
					ref := fmt.Sprintf("0:%s", pkg)
					if !seenMigrated[ref] {
						seenMigrated[ref] = true
						migrated = append(migrated, ref)
					}
					continue
				}
			}
			if !seenRetained[line] {
				seenRetained[line] = true
				retained = append(retained, line)
			}
			continue
		}
		// 裸包名：按当前用户（user 0）迁移。
		if androidPackage.MatchString(line) {
			ref := fmt.Sprintf("0:%s", line)
			if !seenMigrated[ref] {
				seenMigrated[ref] = true
				migrated = append(migrated, ref)
			}
			continue
		}
		if !seenRetained[line] {
			seenRetained[line] = true
			retained = append(retained, line)
		}
	}
	return migrated, retained
}
