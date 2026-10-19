#!/system/bin/sh
# @author 雾晚
MODDIR=${0%/*}
[ -x "$MODDIR/bin/wanboxctl" ] || exit 1
# 先停管理面再停核心：serve 是独立于核心的长期宿主，只 stop 会把 serve 留下。
# serve-stop 按 api.pid 校验进程身份后 TERM/KILL，没有 pid 文件时直接成功（幂等）。
"$MODDIR/bin/wanboxctl" __internal serve-stop >/dev/null 2>&1
# Only the module's identified processes are stopped. sing-tun owns teardown;
# never flush iptables, nftables or other modules' routes. Preserve user data.
exec "$MODDIR/bin/wanboxctl" stop
