#!/bin/bash
# =============================================
# 编译 Unity BLE 插件 AAR
# 用法: ./build-aar.sh
# =============================================
set -e

echo "🔨 编译 Unity AAR..."

# 清理代理环境变量（避免干扰 Gradle）
unset HTTP_PROXY http_proxy HTTPS_PROXY https_proxy

./gradlew :library:assembleRelease

cp library/build/outputs/aar/library-release.aar output/aar/unity3dbluetoothplugin-release.aar
echo "✅ AAR 已生成: output/aar/unity3dbluetoothplugin-release.aar"
echo ""
echo "Unity 接入: 将此文件放入 Assets/Plugins/Android/"
