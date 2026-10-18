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
	"strings"
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

// credentialDeliveryLoop 周期性补交付：覆盖 App 首次启动才建目录、重装或清除数据后私有目录被删的情况。
// 启动后前 5 分钟每 20 秒补一次（App 首次运行才会创建接收目录），之后每 60 秒一次。
func (r *Runtime) credentialDeliveryLoop(ctx context.Context, cfg APIConfig, delivered bool) {
	warmupUntil := time.Now().Add(5 * time.Minute)
	reported := delivered
	for {
		interval := time.Minute
		if time.Now().Before(warmupUntil) {
			interval = 20 * time.Second
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(interval):
			if e := r.DeliverAPIConfig(cfg); e == nil && !reported {
				// 只在“从失败转为成功”时记一次，方便 root 侧排查交付时机。
				r.recordEvent("credential_delivered")
				reported = true
			}
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
	appCtx := appDataContext()
	delivered := 0
	var lastErr error
	for _, target := range credentialTargets() {
		e := deliverTo(target, payload, uid, gid, hasOwner, appCtx)
		if e != nil {
			lastErr = e
			r.noteDelivery(target.path, e)
			continue
		}
		delivered++
		r.noteDelivery(target.path, nil)
	}
	if delivered == 0 {
		if lastErr == nil {
			lastErr = errors.New("credential_delivery_unavailable")
		}
		return lastErr
	}
	return nil
}

func deliverTo(target credentialTarget, payload []byte, uid, gid int, hasOwner bool, appCtx string) error {
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
		// 先按策略恢复，再强制对齐 App 数据目录的精确上下文（含 MLS 分类）。
		// Android 13+ 上仅靠 restorecon 常拿不到分类，App 读自己目录里的
		// root 创建文件会被 SELinux 拒绝，表现为「文件在但读不到」。
		restoreContext(dir)
		if appCtx != "" {
			applyContext(target.path, appCtx)
		}
	}
	return nil
}

// appDataContext 读取 App 数据目录的 SELinux 上下文，作为凭据文件的目标上下文。
func appDataContext() string {
	dir := filepath.Join("/data/user/0", managerPackage)
	if _, e := os.Stat(dir); e != nil {
		return ""
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	out, e := exec.CommandContext(ctx, "/system/bin/ls", "-Zd", dir).Output()
	if e != nil {
		return ""
	}
	fields := strings.Fields(string(out))
	if len(fields) == 0 || !strings.HasPrefix(fields[0], "u:object_r:") {
		return ""
	}
	return fields[0]
}

// applyContext 把指定上下文写到目标文件；失败不阻断交付，交给 App 侧读取时暴露。
func applyContext(path, appCtx string) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_ = exec.CommandContext(ctx, "/system/bin/chcon", appCtx, path).Run()
}

// noteDelivery 把每次交付结果追加到模块目录，root 侧可直接查看排查。
func (r *Runtime) noteDelivery(target string, err error) {
	line := time.Now().Format(time.RFC3339) + " " + target
	if err == nil {
		line += " ok\n"
	} else {
		line += " fail " + err.Error() + "\n"
	}
	path := r.path("delivery.log")
	if info, e := os.Stat(path); e == nil && info.Size() > 64<<10 {
		_ = os.Remove(path)
	}
	f, e := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0600)
	if e != nil {
		return
	}
	defer f.Close()
	_, _ = f.WriteString(line)
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
