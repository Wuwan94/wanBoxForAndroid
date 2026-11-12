#!/system/bin/sh
# @author 雾晚
# wanBox Root Module 卸载收尾脚本（Fail-Safe 纯净防断网保证）
#
# 双轨防御：wanboxctl 优雅停止优先；二进制损坏/死锁时 Shell 原生兜底强退，
# 再强清 TUN 接口与策略路由。任何一步失败都不中断后续清理，始终 exit 0。
# 用户数据默认保留（/data/adb/wanbox/config/），仅显式全清标志才抹除。

MODDIR=${0%/*}
LOG_FILE="/data/adb/wanbox/uninstall.log"

log() {
    # 数据目录不存在时静默跳过：重定向失败不能打断卸载收尾
    { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"; } 2>/dev/null
}

log "开始执行 wanBox 卸载流程..."

# 轨 1：优先尝试优雅停止
STOPPED=false
if [ -x "$MODDIR/bin/wanboxctl" ]; then
    log "通过 wanboxctl 优雅停止服务..."
    if "$MODDIR/bin/wanboxctl" stop; then
        STOPPED=true
    fi
fi

# 轨 2：Shell 原生进程兜底强退（防 wanboxctl 损坏或死锁）
if [ "$STOPPED" != "true" ]; then
    log "wanboxctl 不可用或停止失败，启动原生信号降级回收..."
    # 查找 rootbox 和 wanboxctl 遗留进程
    for proc in rootbox wanboxctl; do
        PIDS=$(pgrep -f "$proc" 2>/dev/null)
        if [ -n "$PIDS" ]; then
            log "发现遗留 $proc 进程: $PIDS，发送 SIGTERM..."
            kill -TERM $PIDS 2>/dev/null
            sleep 2
            # 超时硬杀
            REMAIN=$(pgrep -f "$proc" 2>/dev/null)
            if [ -n "$REMAIN" ]; then
                log "强制清理孤儿进程: $REMAIN"
                kill -KILL $REMAIN 2>/dev/null
            fi
        fi
    done
fi

# 轨 3：强制清理网络栈接口，彻底避免断网黑洞
log "检查并清理残留 TUN 虚拟网卡与路由..."
if ip link show wanbox-tun >/dev/null 2>&1; then
    ip link set wanbox-tun down 2>/dev/null
    ip link delete wanbox-tun 2>/dev/null
    log "wanbox-tun 接口已强制注销"
fi

# 清理 wanbox 专有策略路由（pref 9000 范围）
ip rule show 2>/dev/null | grep -E "wanbox|9000" | while read -r line; do
    pref=$(echo "$line" | sed -n 's/^\([0-9]\+\):.*/\1/p')
    if [ -n "$pref" ]; then
        ip rule del pref "$pref" 2>/dev/null
    fi
done
# 热更新瞬态黑洞守护占 pref 9001，规则行不含 wanbox 字样，须显式清除，
# 否则卸载后可能残留永久黑洞导致断网
ip rule del pref 9001 2>/dev/null

# 轨 4：运行时垃圾与临时挂载清理
log "清理运行时锁文件与 IPC 套接字..."
rm -rf /data/adb/wanbox/run/* 2>/dev/null
rm -rf /data/local/tmp/.wanbox-* 2>/dev/null

# 轨 5：用户数据安全规范
# 保护 /data/adb/wanbox/config/ 下的用户订阅与节点，防止误点卸载丢数据
# 若检测到显式全清标志，才执行数据抹除
if [ -f "/data/adb/wanbox/.purge_user_data" ]; then
    log "检测到完全清理标志，清除所有持久化配置..."
    rm -rf /data/adb/wanbox 2>/dev/null
else
    log "保留用户配置资产 (/data/adb/wanbox/config/)，便于重装后无缝恢复"
fi

log "wanBox 卸载流程执行完毕，网络已恢复物理直连"
exit 0
