#!/bin/bash
# 通过数据线一键安装到手机（需要先在手机上开启 USB 调试）
# 用法：./install_apk.sh
set -e
cd "$(dirname "$0")"

ADB="$HOME/Library/Android/sdk/platform-tools/adb"
if [ ! -x "$ADB" ]; then
  echo "找不到 adb：$ADB"
  echo "请确认已安装 Android SDK platform-tools"
  exit 1
fi

# 优先安装正式签名包，其次调试包
APK="app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || APK="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
  echo "还没编译出 APK，请先执行：./build_apk.sh"
  exit 1
fi
echo "准备安装：$APK"

# 检查是否有已连接的设备
DEVICES="$("$ADB" devices | sed '1d' | grep -w device | wc -l | tr -d ' ')"
if [ "$DEVICES" = "0" ]; then
  echo
  echo "没有检测到已连接的手机。请按下面步骤操作："
  echo "  1. 用数据线把手机连到电脑"
  echo "  2. 手机「设置 → 关于手机 → 连续点 7 次版本号」开启开发者选项"
  echo "  3. 手机「设置 → 开发者选项 → 打开 USB 调试」"
  echo "  4. 手机上弹出「允许 USB 调试吗？」，点「允许」"
  echo "  5. 再运行一次 ./install_apk.sh"
  echo
  "$ADB" devices
  exit 1
fi

if "$ADB" install -r "$APK"; then
  echo
  echo "安装完成！请在手机上找到「景深合成相机」打开使用。"
else
  echo
  echo "安装失败。如果提示签名不一致（INSTALL_FAILED_UPDATE_INCOMPATIBLE），"
  echo "说明手机上装过旧版本，先卸载后重试："
  echo "  $ADB uninstall com.example.focusstack"
  exit 1
fi
