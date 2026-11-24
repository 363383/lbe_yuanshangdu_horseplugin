下面是一份可以直接交给其他 AI 或开发人员实现的 **实施方案（Plan）**。

---

# Plan：将 Unity Android 蓝牙插件从 Bluetooth SPP 改为 BLE GATT

## 一、项目背景

当前 Unity 项目使用 Android Java 插件 `unity3dbluetoothplugin-release.aar` 与 ESP32 通信。

目前插件采用 **Bluetooth Classic SPP（RFCOMM）**：

```text
Unity
    ↓
BluetoothConnector.java
    ↓
BluetoothSocket (RFCOMM)
    ↓
ESP32
```

连接代码为：

```java
BluetoothSocket socket =
device.createRfcommSocketToServiceRecord(
UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
);

socket.connect();
```

由于 Android Classic Bluetooth 在首次连接未配对设备时，会弹出系统 Pairing Dialog，影响 VR 体验。

---

## 二、现状分析

经过验证：

### ESP32 已支持 BLE

使用 BLE 调试助手验证：

设备名称：

```
vrgao001_BLE
```

MAC：

```
28:05:A5:29:06:B6
```

BLE Service：

```
4fafc201-1fb5-459e-8fcc-c5c9c331914b
```

Characteristic：

```
beb5483e-36e1-4688-b7f5-ea07361b26a8
```

Properties：

```
READ
WRITE
NOTIFY
```

使用 BLE 调试助手：

写入：

```
220
```

设备立即动作。

说明：

> ESP32 已具备完整 BLE GATT 控制能力。

因此：

**无需修改 ESP32 固件。**

---

## 三、目标

替换 Android 蓝牙插件：

由：

```
BluetoothSocket (SPP)
```

改为：

```
BluetoothGatt (BLE)
```

要求：

Unity 上层代码尽量保持不变。

---

# 四、Unity 层要求

下面接口必须保持一致。

目前 Unity 调用：

```csharp
BluetoothConnector.CallStatic(
    "StartConnection",
    mac);
```

发送：

```csharp
BluetoothConnector.CallStatic(
    "WriteData",
    speed.ToString());
```

断开：

```csharp
BluetoothConnector.CallStatic(
    "StopConnection");
```

要求：

**Java Plugin 保持完全相同接口。**

即：

```java
StartConnection(String mac)

WriteData(String speed)

StopConnection()
```

Unity C# 无需修改。

---

# 五、BLE 通信参数

Service UUID

```
4fafc201-1fb5-459e-8fcc-c5c9c331914b
```

Characteristic UUID

```
beb5483e-36e1-4688-b7f5-ea07361b26a8
```

Characteristic Properties

```
READ

WRITE

NOTIFY
```

实际只需要：

```
WRITE
```

---

# 六、StartConnection()

修改实现：

由：

```
BluetoothSocket.connect()
```

改：

```
BluetoothDevice.connectGatt()
```

流程：

```
StartConnection(mac)

↓

BluetoothAdapter.getRemoteDevice(mac)

↓

connectGatt()

↓

onConnectionStateChange()

↓

discoverServices()

↓

onServicesDiscovered()

↓

保存 Characteristic

↓

Unity 回调：

ConnectionStatus("connected")
```

要求：

仍保持：

```
ConnectionStatus("connecting")

ConnectionStatus("connected")

ConnectionStatus("disconnected")
```

与旧插件一致。

---

# 七、WriteData()

当前：

```
OutputStream.write(speed.getBytes())
```

改：

向 Characteristic 写入 UTF-8 字符串。

例如：

```
220
```

发送：

```
0x32

0x32

0x30
```

不要添加：

```
\r

\n
```

因为 BLE 调试助手直接发送字符串即可控制设备。

推荐：

Android 13+

```
BluetoothGatt.writeCharacteristic(
characteristic,
speed.getBytes(StandardCharsets.UTF_8),
WRITE_TYPE_DEFAULT
)
```

如果设备支持：

```
WRITE WITHOUT RESPONSE
```

则：

```
WRITE_TYPE_NO_RESPONSE
```

优先。

---

# 八、StopConnection()

当前：

```
socket.close()
```

改：

```
bluetoothGatt.disconnect()

bluetoothGatt.close()
```

释放资源。

---

# 九、删除内容

全部删除：

```
BluetoothSocket

InputStream

OutputStream

BufferedReader

Read Thread

SPP UUID

00001101-0000-1000-8000-00805F9B34FB
```

这些全部属于 Classic Bluetooth。

BLE 不需要。

---

# 十、扫描

无需实现 BLE Scan。

原因：

Unity 已知道：

```
MAC
```

直接：

```
getRemoteDevice(mac)

↓

connectGatt()
```

即可。

因此：

```
StartScanDevices()

StopScanDevices()

GetPairedDevices()
```

可以：

保持接口；

内部空实现；

或删除（如果 Unity 未调用）。

---

# 十一、Android 权限

保持：

```
BLUETOOTH

BLUETOOTH_ADMIN

BLUETOOTH_SCAN

BLUETOOTH_CONNECT
```

无需新增权限。

---

# 十二、Unity 回调保持兼容

仍使用：

```
UnityPlayer.UnitySendMessage()
```

回调对象：

```
BluetoothManager
```

保持：

```
ConnectionStatus()

ReadLog()
```

即可。

无需修改 Unity。

---

# 十三、兼容要求

支持：

```
Android 8+

Android 9

Android 10

Android 11

Android 12

Android 13

Android 14

Android 15
```

优先使用 Android 当前推荐 BLE API。

避免继续使用已经 Deprecated 的旧写法。

---

# 十四、最终交付物

需要提供：

```
BluetoothConnector.java
```

重新实现。

以及：

```
unity3dbluetoothplugin-release.aar
```

重新编译。

---

# 十五、目标效果

最终流程：

```
Unity

↓

StartConnection(mac)

↓

BluetoothGatt.connect()

↓

Connected

↓

WriteData("220")

↓

ESP32 收到：

220

↓

马机动作
```

整个过程中：

* 不使用 Bluetooth Classic（SPP）
* 不使用 RFCOMM
* 不使用 `BluetoothSocket`
* 不触发 Android 系统蓝牙配对弹窗
* Unity 上层接口保持不变，仅替换 Android AAR 实现。
