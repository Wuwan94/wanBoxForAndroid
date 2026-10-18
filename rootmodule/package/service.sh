#!/system/bin/sh
# @author 雾晚
MODDIR=${0%/*}
[ "$#" -eq 0 ] || exit 2
[ -x "$MODDIR/bin/wanboxctl" ] || exit 1
# serve 是模块唯一长期宿主：先起本地 HTTPS 管理接口，再按 auto_start 拉起代理。
# 代理停止或核心启动失败都不会退出该进程，管理端仍可改节点、读状态。
exec "$MODDIR/bin/wanboxctl" __internal serve
