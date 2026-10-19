package catalog

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"path/filepath"
	"strings"

	"github.com/Wuwan94/wanBoxForAndroid/src/native/wanbox/internal/processlock"
)

// AcquireRoot 获取 Catalog 根锁，等待期间响应取消。
func AcquireRoot(ctx context.Context, root string) (func(), error) {
	return acquireFileLock(ctx, root, "root")
}

// Acquire 获取同组长操作锁，磁盘读取和提交仍须按“分组锁 -> 根锁”获取根锁。
func Acquire(ctx context.Context, root, groupID string) (func(), error) {
	if !isValidGroupID(groupID) {
		return nil, fmt.Errorf("非法 Catalog 分组 ID: %s", groupID)
	}
	return acquireFileLock(ctx, root, "group-"+groupID)
}

func acquireCatalogMutation(ctx context.Context, root, groupID string) (func(), error) {
	groupRelease, err := Acquire(ctx, root, groupID)
	if err != nil {
		return nil, err
	}
	rootRelease, err := acquireCatalogRootAndRecover(ctx, root)
	if err != nil {
		groupRelease()
		return nil, err
	}
	return func() { rootRelease(); groupRelease() }, nil
}

func acquireCatalogRootAndRecover(ctx context.Context, root string) (func(), error) {
	release, err := AcquireRoot(ctx, root)
	if err != nil {
		return nil, err
	}
	if err := recoverTransactionsLocked(root); err != nil {
		release()
		return nil, err
	}
	return release, nil
}

func acquireFileLock(ctx context.Context, root, scope string) (func(), error) {
	path, err := catalogLockPath(root, scope)
	if err != nil {
		return nil, err
	}
	lock, err := processlock.Acquire(ctx, path)
	if err != nil {
		return nil, err
	}
	return func() { _ = lock.Release() }, nil
}

func catalogLockPath(root, scope string) (string, error) {
	if strings.TrimSpace(root) == "" {
		return "", fmt.Errorf("Catalog 根目录不能为空")
	}
	absRoot, err := filepath.Abs(filepath.Clean(root))
	if err != nil {
		return "", err
	}
	digest := sha256.Sum256([]byte(absRoot + "\x00" + scope))
	base := filepath.Base(absRoot)
	if base == "." || base == string(filepath.Separator) || base == "" {
		base = "catalog"
	}
	return filepath.Join(filepath.Dir(absRoot), "."+base+".wanbox-"+hex.EncodeToString(digest[:8])+".lock"), nil
}
