#!/usr/bin/env bash
set -euo pipefail
mkdir -p pr101-ui-repeat
adb install -r app/build/outputs/apk/oss/debug/*x86_64*.apk
adb install -r app/build/outputs/apk/androidTest/oss/debug/*.apk
classes='io.nekohasekai.sagernet.GroupDraftAbandonPersistenceTest#rebuildFailureSurvivesActivityDestructionAndUserCanRetry,io.nekohasekai.sagernet.OutboundReferenceAlarmTest#failedInstallerRestorationLeavesReviewPendingWithoutWarning,io.nekohasekai.sagernet.SubscriptionGroupOwnershipTest#ordinaryEditorCannotTakeSubscriptionNodesOrPartiallySave'
for iteration in $(seq 1 30); do
  adb logcat -c
  adb shell am instrument -w -r -e class "$classes" com.lit008834.pixel.wanboxforandroid.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "pr101-ui-repeat/iteration-${iteration}.txt"
  adb logcat -d > "pr101-ui-repeat/logcat-${iteration}.txt"
  if ! grep -q 'OK (3 tests)' "pr101-ui-repeat/iteration-${iteration}.txt"; then
    adb shell uiautomator dump /sdcard/pr101-window.xml || true
    adb pull /sdcard/pr101-window.xml pr101-ui-repeat/window.xml || true
    adb shell dumpsys window windows > pr101-ui-repeat/windows.txt
    adb shell dumpsys accessibility > pr101-ui-repeat/accessibility.txt
    adb exec-out screencap -p > pr101-ui-repeat/failure.png
    echo "::error::PR101 UI repro failed at iteration ${iteration}; no failure retry allowed"
    exit 1
  fi
  echo "PR101_UI_REPEAT_PASS=$iteration"
done
