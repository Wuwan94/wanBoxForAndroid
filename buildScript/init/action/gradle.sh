#!/bin/bash
# Prepare script for Actions Gradle build
set -e

# Install the pinned compile platform explicitly; do not rely on the runner image.
# @author 雾晚
SDKMANAGER="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
    SDKMANAGER=$(command -v sdkmanager || true)
fi
if [ ! -x "$SDKMANAGER" ]; then
    echo "Android sdkmanager not found; set ANDROID_HOME or add it to PATH." >&2
    exit 1
fi
yes | "$SDKMANAGER" "platforms;android-36" "build-tools;35.0.1"

#### Download assets
bash buildScript/lib/assets.sh

exit

#### Download "external" from Internet
#rm -rf external
#mkdir -p external
#cd external
#
#echo "Downloading preferencex-android"
#wget -q -O tmp.zip https://github.com/SagerNet/preferencex-android/archive/8bdb0c6ae44f378b073c6a1c850d03d729b70ff8.zip
#unzip tmp.zip > /dev/null 2>&1
#mv preferencex-android-* preferencex
#
#rm tmp.zip
