#!/usr/bin/env bash
# @author 雾晚: emulator-only evidence; never a Root-device regression claim.
set -euo pipefail
export WANBOX_INTERNAL_TEST=1
G=(./gradlew --no-daemon --max-workers=2 -PwanboxThin=true -PwanboxInternalTestAbi=x86_64)
mkdir -p closeout-evidence
# Measure unprofiled candidate and collect real ART profile, then rebuild and compare.
"${G[@]}" :benchmark:connectedOssBenchmarkAndroidTest -PwanboxBenchmark=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lit008834.wanbox.benchmark.StartupBenchmark#coldStartNoProfile \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
cp -a benchmark/build/outputs closeout-evidence/no-profile
"${G[@]}" :benchmark:connectedOssBenchmarkAndroidTest -PwanboxBenchmark=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lit008834.wanbox.benchmark.BaselineProfileGenerator \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
mapfile -t profiles < <(find benchmark/build/outputs -type f -name '*-baseline-prof.txt')
test "${#profiles[@]}" -eq 1
test -s "${profiles[0]}"
cp "${profiles[0]}" app/src/main/baseline-prof.txt
cp "${profiles[0]}" closeout-evidence/baseline-prof.txt
"${G[@]}" :benchmark:connectedOssBenchmarkAndroidTest -PwanboxBenchmark=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lit008834.wanbox.benchmark.StartupBenchmark#coldStartBaselineProfile \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
cp -a benchmark/build/outputs closeout-evidence/with-profile
# Both source versions build their actual BackupHelper/Restore implementation.
"${G[@]}" app:assemblePreviewDebug app:assemblePreviewDebugAndroidTest
mkdir -p closeout-new-apks
cp app/build/outputs/apk/preview/debug/*x86_64*.apk closeout-new-apks/app.apk
cp app/build/outputs/apk/androidTest/preview/debug/*.apk closeout-new-apks/tests.apk
old=../preview3-compat
# Fixed v3.0.13-preview.3 baseline; the only copied source is this fictional-data interchange test.
git worktree add --detach "$old" 77e291a150819a70d87c0af8458f1c603088e432
cp app/src/androidTest/java/io/nekohasekai/sagernet/BackupCrossVersionTest.kt "$old/app/src/androidTest/java/io/nekohasekai/sagernet/"
mkdir -p "$old/app/libs"
cp app/libs/libcore.aar "$old/app/libs/"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$old/local.properties"
(cd "$old" && ./gradlew app:assemblePreviewDebug app:assemblePreviewDebugAndroidTest -PwanboxThin=true -PwanboxInternalTestAbi=x86_64 --no-daemon --max-workers=2)
AAPT=$(find "$ANDROID_HOME/build-tools" -name aapt | sort -V | tail -1)
new_package=$("$AAPT" dump badging closeout-new-apks/app.apk | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
old_apk=$(find "$old/app/build/outputs/apk/preview/debug" -name '*x86_64*.apk')
old_test=$(find "$old/app/build/outputs/apk/androidTest/preview/debug" -name '*.apk')
test -n "$new_package"
test "$("$AAPT" dump badging "$old_apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")" = "$new_package"
run_mode() {
  local mode=$1 label=$2
  adb shell am instrument -w -e class io.nekohasekai.sagernet.BackupCrossVersionTest -e wanboxBackupMode "$mode" \
    "$new_package.test/androidx.test.runner.AndroidJUnitRunner" | tee "closeout-evidence/$label.txt"
  grep -q 'OK (1 test)' "closeout-evidence/$label.txt"
}
install_pair() { adb install -r -d "$1"; adb install -r -d "$2"; }
install_pair "$old_apk" "$old_test"
run_mode export old-export
adb pull "/sdcard/Android/data/$new_package/files/cross-version.json" closeout-evidence/old-backup.json
install_pair closeout-new-apks/app.apk closeout-new-apks/tests.apk
run_mode import old-to-new
run_mode export new-export
adb pull "/sdcard/Android/data/$new_package/files/cross-version.json" closeout-evidence/new-backup.json
install_pair "$old_apk" "$old_test"
run_mode import new-to-old
printf 'Source=%s\nBaseline=77e291a150819a70d87c0af8458f1c603088e432 (v3.0.13-preview.3)\nEnvironment=API35 x86_64 emulator, not Root device\n' "$(git rev-parse HEAD)" > closeout-evidence/provenance.txt
sha256sum closeout-evidence/*backup.json closeout-evidence/baseline-prof.txt > closeout-evidence/SHA256SUMS
