#!/bin/bash
# =============================================
# 编译 BLE 测试 APK
# 用法: ./build-apk.sh [install]
#   ./build-apk.sh          # 仅编译
#   ./build-apk.sh install  # 编译并安装到连接的手机
# =============================================
set -e

echo "🔨 编译测试 APK..."

# 清理代理环境变量（避免干扰 Gradle）
unset HTTP_PROXY http_proxy HTTPS_PROXY https_proxy

./gradlew :app:assembleDebug

cp app/build/outputs/apk/debug/app-debug.apk output/apk/ble-test.apk
echo "✅ APK 已生成: output/apk/ble-test.apk"

if [ "$1" = "install" ]; then
    echo "📱 安装到设备..."
    adb install -r output/apk/ble-test.apk
    echo "✅ 安装完成"
fi
