package com.example.unity3dbluetoothplugin;

/**
 * BLE 状态回调接口。
 */
public interface BleCallback {
    void onConnectionStatus(String status);
    void onLog(String message);
}
