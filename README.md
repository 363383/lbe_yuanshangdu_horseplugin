# Unity Android BLE GATT 蓝牙插件

将 Unity Android 蓝牙插件从 Bluetooth Classic SPP (RFCOMM) 迁移到 BLE GATT，解决系统配对弹窗问题。

> **所属项目**：元上都 VR（[http://120.48.0.82/svn/project/YuanShangDuVR/](http://120.48.0.82/svn/project/YuanShangDuVR/)）
> 本仓库为 Unity 项目中 Android BLE 蓝牙插件（`unity3dbluetoothplugin-release.aar`）的独立源码。

## 项目结构

```
ble_java/
├── README.md                          ← 本文件
├── build-apk.sh                       ← 编译测试 APK 脚本
├── build-aar.sh                       ← 编译 Unity AAR 脚本
│
├── docs/                              ← 文档
│   ├── UNITY_SDK_GUIDE.md             ← Unity 接入完整文档
│   └── plane.md                       ← 原始需求与技术方案
│
├── output/                            ← 构建产物
│   ├── apk/ble-test.apk               ← 独立测试 APK（真机对 ESP32 测试）
│   └── aar/unity3dbluetoothplugin-release.aar  ← Unity 插件
│
├── app/                               ← 测试 App 模块 (Android Application)
│   └── src/main/java/.../
│       ├── BleCallback.java           ← 回调接口
│       ├── BluetoothConnector.java    ← BLE 核心（测试版，直接依赖 BleCallback）
│       └── TestActivity.java          ← 测试 UI
│
├── library/                           ← AAR 库模块 (Android Library)
│   └── src/main/java/.../
│       ├── BleCallback.java           ← 回调接口
│       └── BluetoothConnector.java    ← BLE 核心（AAR 版，反射调用 UnityPlayer）
│
├── aar_extracted/                     ← 旧版 SPP AAR 解压内容（参考，不再使用）
│   ├── AndroidManifest.xml
│   ├── BluetoothConnector.java        ← 旧版 SPP/RFCOMM 实现
│   └── DeviceListAdapter.java
│
├── build.gradle                       ← Gradle 根配置
├── settings.gradle                    ← 模块注册
├── gradle.properties
└── gradlew / gradlew.bat              ← Gradle Wrapper
```

## 前置条件

- **JDK 17+** — Gradle 8.7 要求 JDK 17 或更高
- **Android SDK** — 编译目标 SDK 35
- **Android SDK 路径配置**（二选一）：

  | 方法 | 命令 |
  |------|------|
  | 自动生成 | 在项目根目录执行 `./gradlew`（首次会自动生成 `local.properties`） |
  | 手动指定 | 创建 `local.properties`，写入 `sdk.dir=/path/to/Android/sdk` |

> Android SDK 典型路径：
> - macOS: `~/Library/Android/sdk`
> - Windows: `C:\Users\你的用户名\AppData\Local\Android\Sdk`
> - Linux: `~/Android/Sdk`

## 快速开始

### 编译测试 APK（真机测试）

```bash
./build-apk.sh          # 编译
./build-apk.sh install  # 编译并安装到手机
```

产物在 `output/apk/ble-test.apk`。

### 编译 Unity AAR

```bash
./build-aar.sh
```

产物在 `output/aar/unity3dbluetoothplugin-release.aar`，放入 Unity 项目的 `Assets/Plugins/Android/`。

> ⚠️ 首次编译会下载 Gradle 8.7 和依赖（约 2-3 分钟），请保持网络通畅。

## Unity 接入

详见 [docs/UNITY_SDK_GUIDE.md](docs/UNITY_SDK_GUIDE.md)。

### 最简调用

```csharp
var ble = new AndroidJavaClass("com.example.unity3dbluetoothplugin.BluetoothConnector");

ble.CallStatic("StartConnection", "28:05:A5:29:06:B6");     // 连接
ble.CallStatic("WriteData", "220");                         // 发送
ble.CallStatic("StopConnection");                           // 断开
```

## 旧版 SPP 参考

`aar_extracted/` 目录保留旧版 SPP/RFCOMM 实现的解压内容，仅作参考对照。新版 BLE 实现与旧版 Unity C# 接口完全兼容。

## 技术栈

- Java 17
- Android SDK 35 (minSdk 27)
- Gradle 8.7 + AGP 8.4.0
- BLE GATT (BluetoothGatt)
