// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"time"
)

// 零 Root 凭据交付。
//
// 管理 APK 不申请超级用户、不再调用 su，因此 api.json 不能放在 /data/adb/wanbox
// （0700 root，普通应用无法遍历）。模块必须在启动时主动把同一份凭据写到
// App 自己的目录里：私有目录（no_backup）为主，外置私有目录为兜底。
//
// 包名必须与 nb4a.properties 的 PACKAGE_NAME 一致；构建脚本会校验模块与 APK 身份。
const managerPackage = "com.lit008834.pixel.wanboxforandroid"

const deliveredCredentialFile = "module-api.json"

// credentialTarget 是一个交付目标。
// appOwned 表示目标位于 App 私有数据区：root 写入后必须改属主并恢复 SELinux 上下文，
// 否则应用侧会因权限或上下文不符读不到。
type credentialTarget struct {
	path     string
	appOwned bool
}

func credentialTargets() []credentialTarget {
	pkg := managerPackage
	return []credentialTarget{
		{path: filepath.Join("/data/user/0", pkg, "no_backup", deliveredCredentialFile), appOwned: true},
		{path: filepath.Join("/data/user_de/0", pkg, "no_backup", deliveredCredentialFile), appOwned: true},
		{path: filepath.Join("/storage/emulated/0/Android/data", pkg, "files", deliveredCredentialFile), appOwned: false},
	}
}

// credentialDeliveryLoop 周期性补交付：覆盖 App 重装、清除数据后私有目录被删的情况。
func (r *Runtime) credentialDeliveryLoop(ctx context.Context, cfg APIConfig) {
	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			_ = r.DeliverAPIConfig(cfg)
		}
	}
}

// DeliverAPIConfig 把管理凭据写入所有 App 可读位置，返回是否至少成功一处。
// 交付失败不应阻断管理接口本身：调用方记录事件即可，App 会重试读取。
func (r *Runtime) DeliverAPIConfig(cfg APIConfig) error {
	if !validAPIConfig(cfg) {
		return errors.New("api_config_invalid")
	}
	payload, e := json.Marshal(cfg)
	if e != nil {
		return e
	}
	uid, gid, hasOwner := appDataOwner()
	delivered := 0
	var lastErr error
	for _, target := range credentialTargets() {
		if e := deliverTo(target, payload, uid, gid, hasOwner); e != nil {
			lastErr = e
			continue
		}
		delivered++
	}
	if delivered == 0 {
		if lastErr == nil {
			lastErr = errors.New("credential_delivery_unavailable")
		}
		return lastErr
	}
	return nil
}

func deliverTo(target credentialTarget, payload []byte, uid, gid int, hasOwner bool) error {
	dir := filepath.Dir(target.path)
	if info, e := os.Lstat(dir); e != nil {
		if target.appOwned {
			// 私有目录只有 App 建过才存在；App 未安装或从未启动时跳过，不去创建别人的目录。
			return errors.New("credential_target_missing")
		}
		if e := os.MkdirAll(dir, 0770); e != nil {
			return e
		}
	} else if !info.IsDir() {
		return errors.New("credential_target_invalid")
	}
	if e := AtomicWrite(target.path, payload, 0600); e != nil {
		return e
	}
	if target.appOwned && hasOwner {
		if e := os.Chown(target.path, uid, gid); e != nil {
			return e
		}
	}
	// 外置目录由 FUSE 映射权限，只放宽到属主可读，避免 root 创建后 App 打不开。
	if !target.appOwned {
		_ = os.Chmod(target.path, 0644)
	}
	if target.appOwned {
		restoreContext(dir)
	}
	return nil
}

// appDataOwner 通过 App 数据目录的属主推断 uid/gid，避免依赖包管理器。
func appDataOwner() (int, int, bool) {
	for _, p := range []string{
		filepath.Join("/data/user/0", managerPackage),
		filepath.Join("/data/user_de/0", managerPackage),
	} {
		info, e := os.Stat(p)
		if e != nil {
			continue
		}
		if uid, gid, ok := statOwner(info); ok {
			return uid, gid, true
		}
	}
	return 0, 0, false
}

// restoreContext 让新建文件落入 App 的数据域上下文；缺少工具时静默跳过。
func restoreContext(dir string) {
	bin := ""
	if _, e := os.Stat("/system/bin/restorecon"); e == nil {
		bin = "/system/bin/restorecon"
	} else if _, e := os.Stat("/system/bin/toybox"); e == nil {
		bin = "/system/bin/toybox"
	}
	if bin == "" {
		return
	}
	args := []string{"-R", dir}
	if filepath.Base(bin) == "toybox" {
		args = []string{"restorecon", "-R", dir}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_ = exec.CommandContext(ctx, bin, args...).Run()
}
