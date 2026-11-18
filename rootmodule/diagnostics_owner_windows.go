//go:build windows

package module

// applyDiagnosticsOwnership 在 Windows 上没有 POSIX 属主概念；保留空实现
// 以便同一套导出代码在本地测试与 Root Android 构建之间共用。
func applyDiagnosticsOwnership(path, dir string) {
	_ = path
	_ = dir
}
