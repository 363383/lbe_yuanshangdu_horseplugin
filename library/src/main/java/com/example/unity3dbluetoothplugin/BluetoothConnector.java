package com.example.unity3dbluetoothplugin;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.*;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Unity3D 蓝牙连接器 — BLE GATT 版本
 *
 * 通过 BluetoothGatt (BLE) 与 ESP32 通信，替代 SPP/RFCOMM。
 * 使用反射调用 UnityPlayer.UnitySendMessage，无需编译时依赖 Unity SDK。
 *
 * Unity C# 调用（与旧版完全相同）:
 *   BluetoothConnector.CallStatic("StartConnection", mac);
 *   BluetoothConnector.CallStatic("WriteData", speed);
 *   BluetoothConnector.CallStatic("StopConnection");
 */
public class BluetoothConnector {

    // ============ 单例 ============
    private static BluetoothConnector mInstance;

    // ============ BLE GATT UUID ============
    private static UUID SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b");
    private static UUID CHARACTERISTIC_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8");

    // ============ 连接状态机 ============
    private enum ConnectionState {
        DISCONNECTED, CONNECTING, DISCOVERING, CONNECTED
    }
    private static ConnectionState mConnectionState = ConnectionState.DISCONNECTED;

    // ============ 蓝牙系统组件 ============
    private static Context mAppContext;
    private static BleCallback mCallback;
    private static BluetoothManager mBluetoothManager;
    private static BluetoothAdapter mBluetoothAdapter;

    // ============ BLE GATT ============
    private static BluetoothGatt mBluetoothGatt;
    private static BluetoothGattCharacteristic mWriteCharacteristic;

    // ============ Handler & 超时 ============
    private static final Handler mHandler = new Handler(Looper.getMainLooper());
    private static final int CONNECTION_TIMEOUT_MS = 15000;

    // ============ Unity 反射 ============
    private static Method sUnitySendMessageMethod;

    /**
     * 通过反射调用 UnityPlayer.UnitySendMessage(gameObject, methodName, message)
     * 不使用直接 import，避免编译时依赖 Unity classes.jar。
     */
    private static void unitySendMessage(String gameObject, String methodName, String message) {
        try {
            if (sUnitySendMessageMethod != null) {
                sUnitySendMessageMethod.invoke(null, gameObject, methodName, message);
            }
        } catch (Exception ignored) {
        }
    }

    /** 初始化 Unity 反射缓存 */
    private static void initUnityReflection() {
        try {
            Class<?> unityPlayerClass = Class.forName("com.unity3d.player.UnityPlayer");
            sUnitySendMessageMethod = unityPlayerClass.getMethod(
                    "UnitySendMessage", String.class, String.class, String.class);
        } catch (Exception e) {
            sUnitySendMessageMethod = null;
        }
    }

    // ============ Unity 回调适配器 ============
    private static class UnityCallback implements BleCallback {
        @Override
        public void onConnectionStatus(String status) {
            unitySendMessage("BluetoothManager", "ConnectionStatus", status);
        }

        @Override
        public void onLog(String message) {
            unitySendMessage("BluetoothManager", "ReadLog", message);
        }
    }

    // ============ GATT 回调 ============
    private static final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                mHandler.removeCallbacks(mTimeoutRunnable);
                log("GATT connected, discovering services...");
                mConnectionState = ConnectionState.DISCOVERING;
                mHandler.post(() -> {
                    if (mBluetoothGatt != null) mBluetoothGatt.discoverServices();
                });
            } else {
                mHandler.removeCallbacks(mTimeoutRunnable);
                mConnectionState = ConnectionState.DISCONNECTED;
                cleanupGatt();
                notifyStatus(buildDisconnectReason(status, newState));
                log("GATT disconnected: status=" + status + " newState=" + newState);
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
            BluetoothGattCharacteristic ch = service.getCharacteristic(CHARACTERISTIC_UUID);
            if (ch == null) {
                notifyStatus("characteristic_not_found");
                log("Characteristic not found: " + CHARACTERISTIC_UUID);
                StopConnection();
                return;
            }
            int props = ch.getProperties();
            if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } else {
                notifyStatus("characteristic_no_write");
                log("Characteristic has no write property, props=" + props);
                StopConnection();
                return;
            }
            mWriteCharacteristic = ch;
            mConnectionState = ConnectionState.CONNECTED;
            notifyStatus("connected");
            log("Connected. Service and characteristic found.");
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                           BluetoothGattCharacteristic ch, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Write failed: status=" + status);
            }
        }
    };

    // ============ 连接超时 ============
    private static final Runnable mTimeoutRunnable = () -> {
        log("Connection timeout (" + CONNECTION_TIMEOUT_MS + "ms)");
        notifyStatus("timeout");
        if (mBluetoothGatt != null) mBluetoothGatt.disconnect();
        mConnectionState = ConnectionState.DISCONNECTED;
    };

    // ============ 辅助方法 ============
    private static String buildDisconnectReason(int status, int newState) {
        if (newState == BluetoothProfile.STATE_DISCONNECTED && status == BluetoothGatt.GATT_SUCCESS)
            return "disconnected";
        if (status == 133) return "gatt_error";
        if (status == 8)  return "timeout";
        if (status == 19) return "disconnected";
        if (status == 22) return "disconnected";
        return "disconnected";
    }

    private static void notifyStatus(String status) {
        if (mCallback != null) mCallback.onConnectionStatus(status);
    }

    private static void log(String msg) {
        if (mCallback != null) mCallback.onLog(msg);
    }

    private static void cleanupGatt() {
        if (mBluetoothGatt != null) {
            try { mBluetoothGatt.close(); } catch (Exception ignored) {}
            mBluetoothGatt = null;
        }
        mWriteCharacteristic = null;
    }

    /** 确保 Context 和蓝牙组件已初始化 */
    private static boolean ensureInitialized() {
        if (mAppContext == null) {
            // 构造函数已尝试从 Unity 初始化；如果仍为 null，说明不在 Unity 环境
            return false;
        }
        return mBluetoothAdapter != null;
    }

    // ============ 单例 & 构造 ============

    /** Unity C#: BluetoothConnector.CallStatic<AndroidJavaObject>("getInstance") */
    public static BluetoothConnector getInstance() {
        if (mInstance == null) {
            mInstance = new BluetoothConnector();
        }
        return mInstance;
    }

    /**
     * 构造函数。自动检测 Unity 环境并通过反射初始化。
     * 如果不在 Unity 中运行（如测试 App），请调用 initialize() 代替。
     */
    public BluetoothConnector() {
        if (mAppContext != null) return; // 已初始化

        // 尝试通过反射从 Unity 获取 Context
        try {
            Class<?> unityPlayerClass = Class.forName("com.unity3d.player.UnityPlayer");
            Activity currentActivity = (Activity) unityPlayerClass.getField("currentActivity").get(null);
            if (currentActivity != null) {
                Context ctx = currentActivity.getApplication().getApplicationContext();
                mAppContext = ctx;
                mCallback = new UnityCallback();
                initUnityReflection();

                mBluetoothManager = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
                if (mBluetoothManager != null) {
                    mBluetoothAdapter = mBluetoothManager.getAdapter();
                }

                // 尝试开启蓝牙（与旧版行为一致）
                if (mBluetoothAdapter != null && !mBluetoothAdapter.isEnabled()) {
                    try {
                        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                        currentActivity.startActivityForResult(enableBtIntent, 1);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            // 不在 Unity 环境，调用方需要手动 initialize()
        }
    }

    /**
     * 非 Unity 环境（如测试 App）的初始化入口。
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
        log("BLE Connector initialized. BT: " +
                (mBluetoothAdapter != null && mBluetoothAdapter.isEnabled()));
    }

    // ============ UUID 配置 ============
    public static void setServiceUuid(String uuid) {
        SERVICE_UUID = UUID.fromString(uuid);
    }
    public static void setCharacteristicUuid(String uuid) {
        CHARACTERISTIC_UUID = UUID.fromString(uuid);
    }

    // ============ 连接管理 ============

    /**
     * Unity 调用: 使用默认 UUID 连接 BLE 设备。
     * @param mac 蓝牙 MAC，如 "28:05:A5:29:06:B6"
     */
    public static void StartConnection(String mac) {
        StartConnection(mac, SERVICE_UUID.toString(), CHARACTERISTIC_UUID.toString());
    }

    /**
     * Unity 调用: 使用指定 UUID 连接 BLE 设备（兼容不同固件的 ESP32）。
     * @param mac           蓝牙 MAC 地址
     * @param serviceUuid    BLE Service UUID 字符串
     * @param charUuid       BLE Characteristic UUID 字符串
     */
    @SuppressLint("MissingPermission")
    public static void StartConnection(String mac, String serviceUuid, String charUuid) {
        if (!ensureInitialized()) {
            log("Not initialized");
            return;
        }
        notifyStatus("connecting");

        // 解析并更新当前 UUID
        UUID svcUuid, chUuid;
        try {
            svcUuid = UUID.fromString(serviceUuid);
            chUuid = UUID.fromString(charUuid);
        } catch (IllegalArgumentException e) {
            log("Invalid UUID format: " + e.getMessage());
            notifyStatus("invalid_uuid");
            return;
        }
        SERVICE_UUID = svcUuid;
        CHARACTERISTIC_UUID = chUuid;
        log("StartConnection: " + mac + " svc=" + serviceUuid + " char=" + charUuid);

        if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
            notifyStatus("Device not found");
            log("Invalid MAC: " + mac);
            return;
        }

        if (mBluetoothAdapter == null) {
            notifyStatus("bluetooth_unavailable");
            log("BluetoothAdapter is null");
            return;
        }

        if (mConnectionState != ConnectionState.DISCONNECTED) {
            log("Already connecting/connected, disconnecting first...");
            StopConnection();
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }

        try {
            BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mac);
            mConnectionState = ConnectionState.CONNECTING;
            log("Calling connectGatt(TRANSPORT_LE)...");

            mBluetoothGatt = device.connectGatt(mAppContext, false, mGattCallback,
                    BluetoothDevice.TRANSPORT_LE);

            if (mBluetoothGatt == null) {
                mConnectionState = ConnectionState.DISCONNECTED;
                notifyStatus("connect_failed");
                log("connectGatt returned null");
                return;
            }

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

    /** Unity 调用: 断开连接 */
    @SuppressLint("MissingPermission")
    public static void StopConnection() {
        log("StopConnection");
        mConnectionState = ConnectionState.DISCONNECTED;
        mHandler.removeCallbacks(mTimeoutRunnable);
        if (mBluetoothGatt != null) {
            try { mBluetoothGatt.disconnect(); } catch (Exception ignored) {}
            cleanupGatt();
        }
        notifyStatus("disconnected");
    }

    // ============ 数据通信 ============

    /**
     * Unity 调用: 写入速度数据
     * @param speed 速度值字符串，如 "220", "240", "0"
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
            boolean ok = mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
            if (!ok) log("Write queued (BLE stack busy): " + speed);
        } catch (Exception e) {
            log("WriteData Error: " + e);
        }
    }

    // ============ 工具方法 ============
    public static void Toast(String data) {
        if (mAppContext != null) {
            android.widget.Toast.makeText(mAppContext, data,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
