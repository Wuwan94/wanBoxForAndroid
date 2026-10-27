# wanBox v3.0.10-preview.18 — Root eBPF inbound with TUN fallback

This preview routes the managed Root configuration through the NetProxy-compatible reF1nd sing-box eBPF inbound by default. If the core rejects or cannot start that inbound, the manager retries with the existing Root TUN path. Ordinary VPN, tests, and exported configurations continue to use TUN.

## Changes
- Add the Root eBPF inbound using the existing per-app UID allowlist/bypass behavior, IPv6 setting, and LAN bypass preference.
- Preserve the logical `tun-in` route tag and enable TCP/UDP local capture through the cgroup data plane.
- Retry the same profile on TUN after recognized eBPF configuration or startup failures; other module errors remain visible.
- Pin reF1nd/sing-box `v1.15.0-alpha.11-reF1nd` at commit `78ea0ce9257bd3bedd4d1185d6cc4723f04096b4` and fix the CI source path.

## Install and compatibility
- ARM64, Android 12+, Root. Manager version `3.0.10-preview.18` / Android versionCode `5140`; module versionCode `1028`.
- Use the APK and module from this release together. The release workflow signs the manager with the project's established preview certificate.
- Preview.16 used a different signing certificate, so Android will not install this package as an in-place update over it. Back up first; do not uninstall or clear data just to force an update. Migration from preview.16's different communication architecture has not been tested.

## Validation limits
- Local Preview Debug unit tests: 315 passed, 0 failed, 0 skipped; Debug APK assembled and ZIP integrity checked.
- This host has no KVM-backed emulator. KSU device behavior, kernel eBPF attachment, traffic capture, and the manager-to-module path on a real device have not been verified.
