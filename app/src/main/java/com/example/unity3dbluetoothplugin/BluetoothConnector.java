package com.example.unity3dbluetoothplugin;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Unity3D 蓝牙连接器 — BLE GATT 版本
 *
 * 通过 BluetoothGatt (BLE) 与 ESP32 马机座椅通信。
 * 替代原有的 BluetoothSocket (SPP/RFCOMM) 方案，避免系统配对弹窗。
 *
 * 调用链:
 *   Unity C# (BluetoothManager.cs)
 *     → AndroidJavaClass("com.example.unity3dbluetoothplugin.BluetoothConnector")
 *     → BluetoothConnector (本类)
 *     → ESP32 BLE GATT Server
 */
public class BluetoothConnector {

    // ============ 单例 ============
    private static BluetoothConnector mInstance;

    // ============ BLE GATT UUID ============
    // 默认值来自 ESP32 BLE Arduino 示例固件，可通过 setter 覆盖
    private static UUID SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b");
    private static UUID CHARACTERISTIC_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8");

    // ============ 连接状态机 ============
    private enum ConnectionState {
        DISCONNECTED,   // 初始状态 / 已断开
        CONNECTING,     // connectGatt 已调用，等待回调
        DISCOVERING,    // GATT 已连接，正在 discoverServices
        CONNECTED       // Service + Characteristic 就绪，可写入
    }
    private static ConnectionState mConnectionState = ConnectionState.DISCONNECTED;

    // ============ 蓝牙系统组件 ============
    private static Context mAppContext;
    private static BleCallback mCallback;
    private static BluetoothManager mBluetoothManager;
    private static BluetoothAdapter mBluetoothAdapter;
    private static BluetoothLeScanner mBluetoothLeScanner;

    // ============ BLE GATT ============
    private static BluetoothGatt mBluetoothGatt;
    private static BluetoothGattCharacteristic mWriteCharacteristic;

    // ============ Handler & 超时 ============
    private static final Handler mHandler = new Handler(Looper.getMainLooper());
    private static final int CONNECTION_TIMEOUT_MS = 15000;

    // ============ GATT 回调 ============
    private static final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                // 连接成功 → 取消超时 → 发现服务
                mHandler.removeCallbacks(mTimeoutRunnable);
                log("GATT connected, discovering services...");
                mConnectionState = ConnectionState.DISCOVERING;
                // discoverServices 需要在主线程调用（避免部分设备 BLE stack 133 错误）
                mHandler.post(() -> {
                    if (mBluetoothGatt != null) {
                        mBluetoothGatt.discoverServices();
                    }
                });
            } else {
                // 断开连接 (正常断开 / 错误 / 超时)
                mHandler.removeCallbacks(mTimeoutRunnable);
                mConnectionState = ConnectionState.DISCONNECTED;
                cleanupGatt();
                String reason = buildDisconnectReason(status, newState);
                notifyStatus(reason);
                log("GATT disconnected: status=" + status + " newState=" + newState + " → " + reason);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyStatus("service_discovery_failed");
                log("Service discovery failed: status=" + status);
                StopConnection();
                return;
            }

            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                notifyStatus("service_not_found");
                log("Service not found: " + SERVICE_UUID);
                StopConnection();
                return;
            }

            BluetoothGattCharacteristic characteristic = service.getCharacteristic(CHARACTERISTIC_UUID);
            if (characteristic == null) {
                notifyStatus("characteristic_not_found");
                log("Characteristic not found: " + CHARACTERISTIC_UUID);
                StopConnection();
                return;
            }

            // 检查写属性
            int props = characteristic.getProperties();
            if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                log("Write type: WRITE_TYPE_DEFAULT");
            } else if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                log("Write type: WRITE_TYPE_NO_RESPONSE");
            } else {
                notifyStatus("characteristic_no_write");
                log("Characteristic has no write property, props=" + props);
                StopConnection();
                return;
            }

            mWriteCharacteristic = characteristic;
            mConnectionState = ConnectionState.CONNECTED;
            notifyStatus("connected");
            log("Connected successfully. Service and characteristic found.");
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                           BluetoothGattCharacteristic characteristic,
                                           int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Write failed: status=" + status);
            }
            // 成功时不打印（避免日志刷屏）
        }
    };

    // ============ 连接超时 Runnable ============
    private static final Runnable mTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            log("Connection timeout (" + CONNECTION_TIMEOUT_MS + "ms)");
            notifyStatus("timeout");
            if (mBluetoothGatt != null) {
                mBluetoothGatt.disconnect();
                // close() 在 onConnectionStateChange 回调中进行
            }
            mConnectionState = ConnectionState.DISCONNECTED;
        }
    };

    // ============ 辅助方法 ============

    /** 根据 GATT status 构建断开原因字符串 */
    private static String buildDisconnectReason(int status, int newState) {
        if (newState == BluetoothProfile.STATE_DISCONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            return "disconnected";
        }
        // 常见 BLE 错误码映射
        if (status == 133) return "gatt_error";   // BluetoothGatt.GATT_ERROR
        if (status == 8)  return "timeout";        // GATT_CONN_TIMEOUT
        if (status == 19) return "disconnected";    // peer disconnected
        if (status == 22) return "disconnected";    // local terminated
        return "disconnected";
    }

    /** 通过 BleCallback 通知连接状态 */
    private static void notifyStatus(String status) {
        if (mCallback != null) {
            mCallback.onConnectionStatus(status);
        }
    }

    /** 通过 BleCallback 记录日志 */
    private static void log(String msg) {
        if (mCallback != null) {
            mCallback.onLog(msg);
        }
    }

    /** 清理 GATT 资源 */
    private static void cleanupGatt() {
        if (mBluetoothGatt != null) {
            try {
                mBluetoothGatt.close();
            } catch (Exception ignored) {}
            mBluetoothGatt = null;
        }
        mWriteCharacteristic = null;
    }

    // ============ 单例获取 & 初始化 ============

    /**
     * 初始化蓝牙连接器。必须在使用其他方法前调用。
     *
     * @param context  Application Context
     * @param callback 状态/日志回调
     */
    public static void initialize(Context context, BleCallback callback) {
        mAppContext = context.getApplicationContext();
        mCallback = callback;

        mBluetoothManager = (BluetoothManager) mAppContext.getSystemService(Context.BLUETOOTH_SERVICE);
        if (mBluetoothManager != null) {
            mBluetoothAdapter = mBluetoothManager.getAdapter();
        }
        if (mBluetoothAdapter != null) {
            mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
        }

        log("BLE Connector initialized. BT enabled: " +
                (mBluetoothAdapter != null && mBluetoothAdapter.isEnabled()));
    }

    /** Unity C# 通过 AndroidJavaClass.CallStatic<AndroidJavaObject>("getInstance") 调用 */
    public static BluetoothConnector getInstance() {
        if (mInstance == null) {
            mInstance = new BluetoothConnector();
        }
        return mInstance;
    }

    /**
     * 构造函数。
     * 对于测试 App: 需要先调用 initialize()。
     * 对于 Unity AAR (阶段2): 构造函数中通过 UnityPlayer 自动获取 Context。
     */
    public BluetoothConnector() {
        // 尝试从 UnityPlayer 获取 Context（阶段2）
        // 测试 App 中 initialize() 已经提供了 context，这里不需要额外操作
        if (mBluetoothAdapter != null && !mBluetoothAdapter.isEnabled()) {
            // 蓝牙未开启时仅记录，由上层决定如何处理
            log("Bluetooth is not enabled");
        }
    }

    // ============ UUID 配置 (可选) ============

    /** 设置 BLE Service UUID（用于适配不同固件的 ESP32） */
    public static void setServiceUuid(String uuid) {
        SERVICE_UUID = UUID.fromString(uuid);
        log("Service UUID updated: " + uuid);
    }

    /** 设置 BLE Characteristic UUID */
    public static void setCharacteristicUuid(String uuid) {
        CHARACTERISTIC_UUID = UUID.fromString(uuid);
        log("Characteristic UUID updated: " + uuid);
    }

    // ============ 连接管理 ============

    /**
     * Unity 调用: 连接到指定 MAC 地址的 BLE 设备 (ESP32)
     * @param mac 蓝牙 MAC 地址，如 "28:05:A5:29:06:B6"
     */
    @SuppressLint("MissingPermission")
    public static void StartConnection(String mac) {
        if (mCallback == null) {
            return; // 未初始化
        }

        notifyStatus("connecting");
        log("StartConnection: " + mac);

        // 校验 MAC 地址格式
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
            notifyStatus("Device not found");
            log("Invalid MAC address: " + mac);
            return;
        }

        if (mBluetoothAdapter == null) {
            notifyStatus("bluetooth_unavailable");
            log("BluetoothAdapter is null");
            return;
        }

        // 如果已有连接，先断开
        if (mConnectionState != ConnectionState.DISCONNECTED) {
            log("Already connecting/connected, disconnecting first...");
            StopConnection();
            // 短暂等待让 disconnect 生效
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }

        try {
            BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mac);
            mConnectionState = ConnectionState.CONNECTING;
            log("Calling connectGatt(TRANSPORT_LE)...");

            // connectGatt 需要 Context; 异步操作，立即返回
            mBluetoothGatt = device.connectGatt(mAppContext, false, mGattCallback,
                    BluetoothDevice.TRANSPORT_LE);

            if (mBluetoothGatt == null) {
                mConnectionState = ConnectionState.DISCONNECTED;
                notifyStatus("connect_failed");
                log("connectGatt returned null — device unreachable or BLE stack busy");
                return;
            }

            // 启动连接超时检测
            mHandler.removeCallbacks(mTimeoutRunnable);
            mHandler.postDelayed(mTimeoutRunnable, CONNECTION_TIMEOUT_MS);

        } catch (SecurityException e) {
            mConnectionState = ConnectionState.DISCONNECTED;
            notifyStatus("permission_denied");
            log("SecurityException: " + e.getMessage());
        } catch (Exception e) {
            mConnectionState = ConnectionState.DISCONNECTED;
            log("StartConnection Error: " + e);
        }
    }

    /**
     * Unity 调用: 断开 BLE 连接
     */
    @SuppressLint("MissingPermission")
    public static void StopConnection() {
        log("StopConnection");
        mConnectionState = ConnectionState.DISCONNECTED;
        mHandler.removeCallbacks(mTimeoutRunnable);

        if (mBluetoothGatt != null) {
            try {
                mBluetoothGatt.disconnect();
            } catch (Exception ignored) {}
            // close() 在 onConnectionStateChange 回调中或 cleanupGatt 中
            cleanupGatt();
        }
        notifyStatus("disconnected");
    }

    // ============ 数据通信 ============

    /**
     * Unity 调用: 向已连接的 BLE 设备写入速度数据
     * @param speed 速度值字符串 (如 "220", "240", "0"=停止)
     */
    @SuppressLint("MissingPermission")
    public static void WriteData(String speed) {
        if (mConnectionState != ConnectionState.CONNECTED) {
            log("Write skipped: not connected (state=" + mConnectionState + ")");
            return;
        }
        if (mBluetoothGatt == null || mWriteCharacteristic == null) {
            log("Write skipped: gatt or characteristic is null");
            return;
        }

        try {
            byte[] data = speed.getBytes(StandardCharsets.UTF_8);
            mWriteCharacteristic.setValue(data);
            boolean success = mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
            if (!success) {
                log("Write queued (BLE stack busy): " + speed);
            }
        } catch (Exception e) {
            log("WriteData Error: " + e);
        }
    }

    // ============ 工具方法 ============

    /** Unity 调用: 显示 Toast 消息 */
    public static void Toast(String data) {
        if (mAppContext != null) {
            android.widget.Toast.makeText(mAppContext, data, android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
