#!/bin/bash
# 一键编译 APK（不需要 Android Studio）
# 用法：
#   ./build_apk.sh            # 打正式签名包（release，签名 Wxf8555）
#   ./build_apk.sh debug      # 打调试包（debug）
set -e
cd "$(dirname "$0")"

MODE="${1:-release}"

# 1) locate JDK 17（Adoptium 解压版，放在 ~/android-toolchain）
JDK_DIR="$(ls -d "$HOME"/android-toolchain/jdk-17* 2>/dev/null | head -1)"
if [ -z "$JDK_DIR" ]; then
  echo "找不到 JDK 17，期望目录：~/android-toolchain/jdk-17*"
  exit 1
fi
export JAVA_HOME="$JDK_DIR/Contents/Home"

# 2) locate Android SDK
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# 3) locate Gradle
GRADLE="$HOME/android-toolchain/gradle-8.4/bin/gradle"
if [ ! -x "$GRADLE" ]; then
  echo "找不到 Gradle：$GRADLE"
  exit 1
fi

echo "JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version
echo "ANDROID_HOME=$ANDROID_HOME"
echo

# 4) build
if [ "$MODE" = "debug" ]; then
  "$GRADLE" assembleDebug
  APK="app/build/outputs/apk/debug/app-debug.apk"
else
  "$GRADLE" assembleRelease
  APK="app/build/outputs/apk/release/app-release.apk"
fi

echo
echo "编译完成：$(pwd)/$APK"
