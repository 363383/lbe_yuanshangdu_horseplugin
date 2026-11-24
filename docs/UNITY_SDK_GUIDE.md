# Unity BLE GATT 蓝牙插件接入文档

## 一、概述

`unity3dbluetoothplugin-release.aar` 是一个 Unity Android 蓝牙插件，通过 **BLE GATT** 协议与 ESP32 设备通信。相比旧版 Bluetooth Classic (SPP/RFCOMM)，BLE 方案**不会弹出系统配对对话框**，适合 VR 等沉浸式场景。

| 项目 | 说明 |
|------|------|
| 插件文件 | `unity3dbluetoothplugin-release.aar` |
| 包名 | `com.example.unity3dbluetoothplugin` |
| 最低 Android | 8.0 (API 27) |
| Unity 兼容 | 2019.4+ (所有支持 Android 64-bit 的版本) |

---

## 二、安装

将 `unity3dbluetoothplugin-release.aar` 放入 Unity 项目：

```
Assets/Plugins/Android/unity3dbluetoothplugin-release.aar
```

Unity 打包时会自动合并 AAR 中的 `AndroidManifest.xml`（包含 BLE 权限声明），无需手动配置。

> **如果是版本升级**：直接覆盖旧 AAR 文件即可，C# 代码无需修改。

---

## 三、权限

AAR 自带以下权限，Unity 打包时自动合并进最终 APK 的 `AndroidManifest.xml`：

```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

**Android 12+ (API 31+) 运行时**：`BLUETOOTH_SCAN` 和 `BLUETOOTH_CONNECT` 是危险权限，需在 C# 中请求用户授权：

```csharp
#if UNITY_ANDROID && !UNITY_EDITOR
using UnityEngine.Android;

void Start() {
    if (!Permission.HasUserAuthorizedPermission("android.permission.BLUETOOTH_CONNECT")) {
        Permission.RequestUserPermission("android.permission.BLUETOOTH_CONNECT");
    }
    if (!Permission.HasUserAuthorizedPermission("android.permission.BLUETOOTH_SCAN")) {
        Permission.RequestUserPermission("android.permission.BLUETOOTH_SCAN");
    }
}
#endif
```

---

## 四、API 参考

通过 `AndroidJavaClass` 调用静态方法，所有方法都在 `com.example.unity3dbluetoothplugin.BluetoothConnector` 类中。

### 4.1 StartConnection — 连接设备

```csharp
using UnityEngine;

public class BluetoothManager : MonoBehaviour 
{
    private AndroidJavaClass bluetoothConnector;

    void Start() 
    {
        bluetoothConnector = new AndroidJavaClass("com.example.unity3dbluetoothplugin.BluetoothConnector");
    }

    /// 方式 A: 使用默认 UUID 连接
    public void ConnectDefault(string mac) 
    {
        bluetoothConnector.CallStatic("StartConnection", mac);
        // 例: ConnectDefault("28:05:A5:29:06:B6")
    }

    /// 方式 B: 指定 UUID 连接（用于不同固件的设备）
    public void ConnectWithUuid(string mac, string serviceUuid, string charUuid) 
    {
        bluetoothConnector.CallStatic("StartConnection", mac, serviceUuid, charUuid);
        // 例: ConnectWithUuid("AA:BB:CC:DD:EE:01", 
        //       "4fafc201-1fb5-459e-8fcc-c5c9c331914b",
        //       "beb5483e-36e1-4688-b7f5-ea07361b26a8")
    }
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `mac` | string | 蓝牙 MAC 地址，格式 `"28:05:A5:29:06:B6"` |
| `serviceUuid` | string (可选) | BLE Service UUID。省略则用默认值 |
| `charUuid` | string (可选) | BLE Characteristic UUID。省略则用默认值 |

**默认 UUID（ESP32 BLE Arduino 示例固件）**：

| UUID | 值 |
|------|-----|
| Service | `4fafc201-1fb5-459e-8fcc-c5c9c331914b` |
| Characteristic | `beb5483e-36e1-4688-b7f5-ea07361b26a8` |

> 如果 ESP32 固件 UUID 与上述默认值一致，直接使用方式 A 即可，不用每次传 UUID。

### 4.2 WriteData — 发送数据

```csharp
public void SendSpeed(int speed) 
{
    bluetoothConnector.CallStatic("WriteData", speed.ToString());
    // 例: SendSpeed(220)
    // 例: SendSpeed(0)   // 停止
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `speed` | string | 速度值字符串。转换为 UTF-8 字节发送。不添加 `\r\n`。 |

### 4.3 StopConnection — 断开连接

```csharp
public void Disconnect() 
{
    bluetoothConnector.CallStatic("StopConnection");
}
```

### 4.4 Toast — 显示 Toast（可选）

```csharp
public void ShowToast(string message) 
{
    bluetoothConnector.CallStatic("Toast", message);
}
```

---

## 五、回调接收

在场景中创建名为 **`BluetoothManager`** 的 GameObject，挂载以下脚本接收回调：

```csharp
using UnityEngine;
using UnityEngine.UI;

public class BluetoothManager : MonoBehaviour 
{
    [Header("UI")]
    public Text statusText;
    public Text logText;

    private AndroidJavaClass bluetoothConnector;

    void Start() 
    {
        bluetoothConnector = new AndroidJavaClass("com.example.unity3dbluetoothplugin.BluetoothConnector");
    }

    // ===== 连接状态回调 =====
    // 由 Android 插件通过 UnitySendMessage("BluetoothManager", "ConnectionStatus", status) 调用
    public void ConnectionStatus(string status) 
    {
        Debug.Log("[BLE] ConnectionStatus: " + status);

        switch (status) 
        {
            case "connecting":
                // 正在连接中...
                UpdateUI("连接中...", Color.yellow);
                break;

            case "connected":
                // 连接成功，可以发送数据
                UpdateUI("已连接", Color.green);
                break;

            case "disconnected":
                // 连接已断开
                UpdateUI("已断开", Color.gray);
                break;

            case "Device not found":
                // MAC 地址格式无效
                UpdateUI("MAC 地址无效", Color.red);
                break;

            case "service_not_found":
                // BLE Service UUID 不匹配（固件 UUID 与 AAR 默认值不一致）
                UpdateUI("Service UUID 不匹配", Color.red);
                break;

            case "characteristic_not_found":
                // BLE Characteristic UUID 不匹配
                UpdateUI("Characteristic UUID 不匹配", Color.red);
                break;

            case "timeout":
                // 连接超时（设备不在范围内或未开机）
                UpdateUI("连接超时", Color.red);
                break;

            case "gatt_error":
                // Android BLE 栈错误（可重试）
                UpdateUI("BLE 错误", Color.red);
                break;

            case "connect_failed":
                // connectGatt 返回 null
                UpdateUI("连接失败", Color.red);
                break;

            case "permission_denied":
                // 蓝牙权限被拒绝
                UpdateUI("权限不足", Color.red);
                break;

            default:
                UpdateUI(status, Color.white);
                break;
        }
    }

    // ===== 日志回调 =====
    // 由 Android 插件通过 UnitySendMessage("BluetoothManager", "ReadLog", message) 调用
    public void ReadLog(string message) 
    {
        Debug.Log("[BLE] " + message);
        if (logText != null) logText.text += message + "\n";
    }

    private void UpdateUI(string status, Color color) 
    {
        if (statusText != null) 
        {
            statusText.text = status;
            statusText.color = color;
        }
    }
}
```

| 回调方法 | GameObject | 参数 | 触发时机 |
|----------|------------|------|----------|
| `ConnectionStatus(string)` | BluetoothManager | 状态字符串（见下表） | 连接状态变化 |
| `ReadLog(string)` | BluetoothManager | 日志消息 | 错误、警告、调试信息 |

### ConnectionStatus 状态码完整列表

| 状态字符串 | 含义 | 建议处理 |
|------------|------|----------|
| `connecting` | 正在连接 | 显示加载动画 |
| `connected` | 连接成功，可写数据 | 启用发送按钮 |
| `disconnected` | 已断开（正常） | 恢复 UI 初始状态 |
| `Device not found` | MAC 地址格式错误 | 检查 MAC 格式 `XX:XX:XX:XX:XX:XX` |
| `service_not_found` | Service UUID 不匹配 | 使用方式 B 连接，传正确 UUID |
| `characteristic_not_found` | Characteristic UUID 不匹配 | 使用方式 B 连接，传正确 UUID |
| `characteristic_no_write` | Characteristic 无写属性 | 检查 ESP32 固件配置 |
| `timeout` | 连接超时 (15s) | 确认设备开机且在范围内 |
| `gatt_error` | BLE 栈错误 (133) | 可重试连接 |
| `connect_failed` | connectGatt 返回 null | 重试或重启蓝牙 |
| `permission_denied` | 缺少蓝牙权限 | 引导用户授权 |
| `bluetooth_unavailable` | 蓝牙不可用 | 检查设备蓝牙状态 |
| `invalid_uuid` | UUID 格式无效 | 检查 UUID 字符串 |

---

## 六、完整示例

```csharp
using UnityEngine;
using UnityEngine.UI;
using UnityEngine.Android;

public class BleHorseController : MonoBehaviour 
{
    [Header("ESP32 配置")]
    public string deviceMac = "28:05:A5:29:06:B6";
    
    [Header("UI")]
    public Button connectBtn;
    public Button disconnectBtn;
    public Button speed220Btn;
    public Button speed240Btn;
    public Button stopBtn;
    public Text statusText;
    public Text logText;

    private AndroidJavaClass ble;
    private bool isConnected = false;

    void Start() 
    {
        ble = new AndroidJavaClass("com.example.unity3dbluetoothplugin.BluetoothConnector");

        // 绑定按钮
        connectBtn.onClick.AddListener(Connect);
        disconnectBtn.onClick.AddListener(Disconnect);
        speed220Btn.onClick.AddListener(() => Send(220));
        speed240Btn.onClick.AddListener(() => Send(240));
        stopBtn.onClick.AddListener(() => Send(0));

        // 运行时权限 (Android 12+)
#if UNITY_ANDROID && !UNITY_EDITOR
        if (!Permission.HasUserAuthorizedPermission("android.permission.BLUETOOTH_CONNECT")) 
        {
            Permission.RequestUserPermission("android.permission.BLUETOOTH_CONNECT");
        }
#endif
    }

    void Connect() 
    {
        if (isConnected) return;
        
        // 默认 UUID 连接（适合 ESP32 示例固件）
        ble.CallStatic("StartConnection", deviceMac);
        
        // 或指定 UUID 连接（不同固件）：
        // ble.CallStatic("StartConnection", deviceMac, 
        //     "custom-service-uuid", "custom-char-uuid");
    }

    void Disconnect() 
    {
        ble.CallStatic("StopConnection");
    }

    void Send(int speed) 
    {
        if (!isConnected) 
        {
            Debug.LogWarning("未连接，无法发送");
            return;
        }
        ble.CallStatic("WriteData", speed.ToString());
    }

    // ---- 回调 ----

    public void ConnectionStatus(string status) 
    {
        Debug.Log("[BLE] " + status);

        switch (status) 
        {
            case "connected":
                isConnected = true;
                statusText.text = "已连接";
                statusText.color = Color.green;
                break;

            case "disconnected":
            case "timeout":
            case "gatt_error":
                isConnected = false;
                statusText.text = status;
                statusText.color = Color.red;
                break;

            case "connecting":
                statusText.text = "连接中...";
                statusText.color = Color.yellow;
                break;

            default:
                statusText.text = status;
                statusText.color = Color.red;
                break;
        }
    }

    public void ReadLog(string message) 
    {
        Debug.Log("[BLE] " + message);
        logText.text += message + "\n";
    }
}
```

---

## 七、通信协议

```
Unity C#                        Android (Java)                    ESP32
───────                         ──────────────                    ─────
WriteData("220")
  → CallStatic("WriteData","220")
                                  → characteristic.setValue
                                    ("220".getBytes(UTF_8))
                                  → writeCharacteristic()
                                                                    → 收到: 0x32 0x32 0x30
                                                                    → 解析: "220"
                                                                    → 执行动作
```

- 编码: **UTF-8**
- 行尾符: **不添加** `\r` `\n`
- 写入类型: `WRITE_TYPE_DEFAULT` (Write With Response)
- 不需要配对/绑定

---

## 八、ESP32 固件要求

| 项目 | 要求 |
|------|------|
| 蓝牙协议 | BLE (Bluetooth 4.0+) |
| GATT Role | Server (Peripheral) |
| Service UUID | 与 AAR 默认值一致，或通过 StartConnection 参数指定 |
| Characteristic UUID | 与 AAR 默认值一致 |
| Characteristic Property | **WRITE** (必须) |
| 数据格式 | UTF-8 字符串，无换行符 |

当前默认 UUID 对应 ESP32 BLE Arduino 示例：

```cpp
// ESP32 固件中对应的定义
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

BLEService        *pService = pServer->createService(SERVICE_UUID);
BLECharacteristic *pChar    = pService->createCharacteristic(
    CHARACTERISTIC_UUID,
    BLECharacteristic::PROPERTY_WRITE | 
    BLECharacteristic::PROPERTY_READ  | 
    BLECharacteristic::PROPERTY_NOTIFY
);
```

---

## 九、从旧 SPP 版本迁移

如果从旧版 `unity3dbluetoothplugin-release.aar` (SPP/RFCOMM) 升级：

| 变化项 | 说明 |
|--------|------|
| C# 代码 | **无需修改** — API 完全兼容 |
| 连接方式 | `socket.connect()` → `connectGatt()` (异步) |
| 主线程阻塞 | 旧版阻塞 5-12s → 新版不阻塞 |
| 配对弹窗 | 旧版弹出 → 新版不弹 |
| 扫描方法 | 已移除 (`StartScanDevices` 等) |
| ReadData 回调 | 已移除 (BLE 只写不读) |

已移除的方法（如果 C# 中有调用需删除）：
- `StartScanDevices()` / `StopScanDevices()`
- `GetPairedDevices()`

---

## 十、常见问题

**Q: 连接不上，状态显示 `service_not_found`？**  
A: ESP32 固件的 UUID 与 AAR 默认值不一致。使用两个参数的 `StartConnection` 指定正确的 UUID。

**Q: 发送数据后 ESP32 没反应？**  
A: 确认是用字符串发送（如 `"220"` 而不是整数 `220`），ESP32 端按 UTF-8 解析。

**Q: Android 12+ 连接失败？**  
A: 需要在 C# 中运行时请求 `BLUETOOTH_CONNECT` 和 `BLUETOOTH_SCAN` 权限（见第三节）。

**Q: 多台不同固件的 ESP32 怎么切换？**  
A: 每次切换时调用带 UUID 参数的 `StartConnection`，会自动断开旧连接。

**Q: 旧的配对弹窗问题真的解决了吗？**  
A: BLE GATT 连接不需要配对，不触发系统配对 UI。Android 12+ 的唯一弹窗是运行时权限请求（仅首次）。

---

## 十一、文件清单

```
unity3dbluetoothplugin-release.aar    ← Unity 插件
UNITY_SDK_GUIDE.md                   ← 本文档
```
