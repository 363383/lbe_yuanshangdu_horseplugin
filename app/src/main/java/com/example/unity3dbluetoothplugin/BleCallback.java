package com.example.unity3dbluetoothplugin;

/**
 * BLE 状态回调接口。
 *
 * 解耦上层通知机制：测试 App 用 Log/Toast 实现，Unity AAR 用 UnityPlayer.UnitySendMessage 实现。
 */
public interface BleCallback {

    /**
     * 连接状态变化
     * @param status 状态字符串:
     *   "connecting"       - 正在连接
     *   "connected"        - 已连接 (Service + Characteristic 发现完毕)
     *   "disconnected"     - 已断开
     *   "Device not found" - MAC 地址无效
     *   "service_not_found"      - BLE Service UUID 未找到
     *   "characteristic_not_found" - Characteristic UUID 未找到
     */
    void onConnectionStatus(String status);

    /**
     * 日志/错误消息
     * @param message 日志内容
     */
    void onLog(String message);
}
