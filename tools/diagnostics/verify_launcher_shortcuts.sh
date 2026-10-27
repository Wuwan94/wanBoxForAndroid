#!/usr/bin/env bash
# @author 雾晚
set -euo pipefail
# The signed release fixture is created by connectedOssReleaseAndroidTest.
# Keep this check in the trusted release stage, never the fork-safe debug stage.
mkdir -p probe
trap 'adb logcat -d > probe/launcher-shortcut-logcat.txt || true' EXIT
# @author 雾晚: only this disposable emulator gives the independent probe the HOME role.
# Prove real published shortcuts can launch private controls through LauncherApps,
# rather than treating an ordinary same-UID startActivity as launcher verification.
# The static shortcut resource targets the shipping ID, not Debug's .debug suffix.
release_apk=$(find app/build/outputs/apk/oss/release -name '*x86_64*.apk' | head -n 1)
test -s "$release_apk"
adb install -r "$release_apk"
adb install -r probe/probe.apk
# This stage starts its own disposable emulator; do not rely on debug-stage state.
if adb shell pm list packages | tr -d '\r' | grep -Fxq 'package:com.google.android.googlesdksetup'; then
  adb shell am force-stop com.google.android.googlesdksetup
  adb shell pm disable-user --user 0 com.google.android.googlesdksetup
fi
adb shell wm size 720x1280
adb shell wm density 320
adb shell am force-stop com.google.android.apps.nexuslauncher || true
AAPT=$(find "${ANDROID_HOME}/build-tools" -name aapt | sort -V | tail -n 1)
package=$("$AAPT" dump badging "$release_apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
test -n "$package"
original_home=$(adb shell cmd role get-role-holders --user 0 android.app.role.HOME | tr -d '\r')
cleanup() {
  adb logcat -d > probe/launcher-shortcut-logcat.txt || true
  adb shell cmd role remove-role-holder --user 0 android.app.role.HOME com.wanbox.auditprobe || true
  if [[ -n "$original_home" ]]; then
    adb shell cmd role add-role-holder --user 0 android.app.role.HOME "$original_home" || true
  fi
}
trap cleanup EXIT
adb shell cmd role add-role-holder --user 0 android.app.role.HOME com.wanbox.auditprobe
for shortcut in enable disable toggle; do
  adb shell am force-stop "$package"
  adb shell am force-stop com.wanbox.auditprobe
  adb logcat -c
  adb shell am start -n com.wanbox.auditprobe/.ShortcutLaunchProbe --es target "$package" --es shortcut "$shortcut"
  for attempt in $(seq 1 20); do
    if adb logcat -d -s WanBoxAuditProbe:I | grep -q "LAUNCHER_SHORTCUT_STARTED $shortcut"; then break; fi
    sleep 1
  done
  adb logcat -d -s WanBoxAuditProbe:I | grep -q "LAUNCHER_SHORTCUT_STARTED $shortcut"
  sleep 2
  if adb logcat -d -s AndroidRuntime:E | grep -q 'FATAL EXCEPTION'; then
    echo "Shortcut $shortcut crashed during platform launch" >&2
    exit 1
  fi
done
echo 'LauncherApps accepted all three published private system shortcuts without an app confirmation.'
