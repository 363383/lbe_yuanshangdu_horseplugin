package com.example.unity3dbluetoothplugin;

import android.bluetooth.BluetoothDevice;
import java.util.HashMap;
import java.util.Map;

/**
 * 蓝牙设备列表适配器
 *
 * 用于设备扫描去重 —— 同一 MAC 地址只记录一次
 * Unity 调用 getPairedDevices() 时使用
 */
public class DeviceListAdapter {

    /** 以 MAC 地址为 key 的设备映射表，确保去重 */
    public Map<String, BluetoothDevice> mDevicesMap;

    public DeviceListAdapter() {
        mDevicesMap = new HashMap<>();
    }

    /**
     * 添加设备 (去重)
     * @param device 蓝牙设备
     * @return true=新增, false=已存在
     */
    public boolean AddDevice(BluetoothDevice device) {
        if (mDevicesMap.get(device.getAddress()) != null) {
            return false;   // 已存在，不重复添加
        }
        mDevicesMap.put(device.getAddress(), device);
        return true;
    }

    /** 获取设备数量 */
    public int getCount() {
        return mDevicesMap.size();
    }

    /** 根据 MAC 地址获取设备 */
    public BluetoothDevice getItem(String address) {
        return mDevicesMap.get(address);
    }

    /** 清空设备列表 */
    public void clearAll() {
        mDevicesMap.clear();
    }
}
