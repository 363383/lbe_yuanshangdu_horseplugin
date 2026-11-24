package com.example.unity3dbluetoothplugin;

import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.*;
import android.os.Handler;
import android.widget.Toast;

import com.unity3d.player.UnityPlayer;

import java.io.*;
import java.util.*;

/**
 * Unity3D 蓝牙连接器 —— 元上都VR 骑马座椅蓝牙通信插件
 *
 * 通过 Bluetooth SPP (RFCOMM) 与物理马机座椅通信
 *
 * @author YuanShangDu VR Team
 *
 * 调用链:
 *   Unity C# (BluetoothManager.cs)
 *     → AndroidJavaClass("com.example.unity3dbluetoothplugin.BluetoothConnector")
 *     → BluetoothConnector (本类)
 *     → 物理蓝牙设备 (马机座椅)
 */
public class BluetoothConnector {

    // ============ 单例 ============
    private static BluetoothConnector mInstance;

    // ============ SPP UUID (标准串口服务) ============
    private static final UUID mUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // ============ 蓝牙系统组件 ============
    private static BluetoothManager mBluetoothManager;
    private static BluetoothAdapter mBluetoothAdapter;
    private static BluetoothLeScanner mBluetoothLeScanner;
    private static DeviceListAdapter mDeviceListAdapter;
    private static boolean scanning;

    // ============ Handler ============
    private static final Handler handler = new Handler();

    // ============ 蓝牙连接 ============
    private static BluetoothSocket skt;          // RFCOMM Socket
    private static InputStream inputStream;      // 输入流 (接收数据)
    private static OutputStream outputStream;    // 输出流 (发送速度数据)
    private static BufferedReader reader;        // 缓冲读取器

    // ============ 数据读取线程 ============
    // 匿名内部类 $2: 持续从 inputStream 读取数据的 Runnable
    private static final Runnable ReadData = new Runnable() {
        @Override
        public void run() {
            while (skt != null && skt.isConnected() && !ReadDatathread.isInterrupted()) {
                try {
                    if (inputStream.available() > 0) {
                        // 读取一行数据并发送回 Unity
                        String data = reader.readLine();
                        UnityPlayer.UnitySendMessage("BluetoothManager", "ReadData", data);
                    }
                } catch (IOException e) {
                    UnityPlayer.UnitySendMessage("BluetoothManager", "ReadLog",
                            "inputStream Error:" + e.toString());
                }
            }
            // 连接断开
            UnityPlayer.UnitySendMessage("BluetoothManager", "ConnectionStatus", "disconnected");
        }
    };

    private static final Thread ReadDatathread = new Thread(ReadData);

    // ============ 广播接收器 ============
    // 匿名内部类 $1: 蓝牙设备发现广播
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                // 发现新设备
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (mDeviceListAdapter.AddDevice(device)) {
                    String name = device.getName() != null ? device.getName() : "null";
                    String address = device.getAddress();
                    // 通知 Unity: 新设备 (MAC+名称)
                    UnityPlayer.UnitySendMessage("BluetoothManager", "NewDeviceFound",
                            address + "+" + name);
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                // 扫描开始
                UnityPlayer.UnitySendMessage("BluetoothManager", "ScanStatus", "started");
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                // 扫描结束
                UnityPlayer.UnitySendMessage("BluetoothManager", "ScanStatus", "stopped");
            }
        }
    };

    // ============ 单例获取 ============
    /** Unity C# 通过 AndroidJavaClass.CallStatic<AndroidJavaObject>("getInstance") 调用 */
    public static BluetoothConnector getInstance() {
        if (mInstance == null) {
            mInstance = new BluetoothConnector();
        }
        return mInstance;
    }

    // ============ 构造函数: 初始化蓝牙 ============
    public BluetoothConnector() {
        // 获取当前 Unity Activity 的 ApplicationContext
        Context appContext = UnityPlayer.currentActivity.getApplication().getApplicationContext();

        // 获取 BluetoothManager 和 BluetoothAdapter
        mBluetoothManager = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();

        // 如果蓝牙未开启，尝试启用
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            UnityPlayer.currentActivity.startActivityForResult(enableBtIntent, 1);
        }
        if (!mBluetoothAdapter.isEnabled()) {
            mBluetoothAdapter.enable();
        }

        // 获取 BLE 扫描器 (未使用，保留)
        mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();

        // 初始化设备列表
        mDeviceListAdapter = new DeviceListAdapter();

        // 注册蓝牙广播接收器
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        appContext.registerReceiver(mReceiver, filter);
    }

    // ============ 设备扫描 ============
    /** Unity 调用: 开始扫描附近蓝牙设备 */
    private static void StartScanDevices() {
        if (mBluetoothAdapter.isDiscovering()) {
            mBluetoothAdapter.cancelDiscovery();
        }
        mDeviceListAdapter.clearAll();
        mBluetoothAdapter.startDiscovery();
    }

    /** Unity 调用: 停止扫描 */
    private static void StopScanDevices() {
        if (mBluetoothAdapter.isDiscovering()) {
            mBluetoothAdapter.cancelDiscovery();
            UnityPlayer.UnitySendMessage("BluetoothManager", "ScanStatus", "stopped");
        }
    }

    // ============ 获取已配对设备 ============
    /** Unity 调用: 返回已配对的蓝牙设备列表，格式: ["MAC+Name", ...] */
    public static String[] GetPairedDevices() {
        try {
            ArrayList<String> deviceList = new ArrayList<>();
            Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();

            for (BluetoothDevice device : pairedDevices) {
                deviceList.add(device.getAddress() + "+" + device.getName());
            }

            return deviceList.toArray(new String[0]);
        } catch (Exception e) {
            UnityPlayer.UnitySendMessage("BluetoothManager", "ReadLog", e.toString());
            return null;
        }
    }

    // ============ 连接管理 ============
    /**
     * Unity 调用: 连接到指定 MAC 地址的蓝牙设备 (马机座椅)
     * @param mac 蓝牙 MAC 地址，如 "28:05:A5:29:06:B6"
     */
    public static void StartConnection(String mac) {
        UnityPlayer.UnitySendMessage("BluetoothManager", "ConnectionStatus", "connecting");

        // 校验 MAC 地址格式
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
            UnityPlayer.UnitySendMessage("BluetoothManager", "ConnectionStatus", "Device not found");
            return;
        }

        try {
            // 获取远程设备
            BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mac);

            // 通过 RFCOMM (SPP) 创建 Socket 连接
            skt = device.createRfcommSocketToServiceRecord(mUUID);

            // 停止扫描 (避免干扰连接)
            mBluetoothAdapter.cancelDiscovery();

            // 建立连接
            skt.connect();

            // 获取输入/输出流
            inputStream = skt.getInputStream();
            outputStream = skt.getOutputStream();

            // 创建缓冲读取器用于异步读取数据
            reader = new BufferedReader(new InputStreamReader(inputStream));

            // 启动数据读取线程
            ReadDatathread.start();

            // 通知 Unity 连接成功
            UnityPlayer.UnitySendMessage("BluetoothManager", "ConnectionStatus", "connected");

        } catch (Exception e) {
            UnityPlayer.UnitySendMessage("BluetoothManager", "ReadLog",
                    "StartConnection Error:" + e.toString());
        }
    }

    /** Unity 调用: 断开蓝牙连接 */
    public static void StopConnection() {
        try {
            ReadDatathread.interrupt();
            if (inputStream != null) inputStream.close();
            if (outputStream != null) outputStream.close();
            if (skt != null) skt.close();
        } catch (IOException e) {
            UnityPlayer.UnitySendMessage("BluetoothManager", "ReadLog",
                    "StopConnection Error:" + e.toString());
        }
    }

    // ============ 数据通信 ============
    /**
     * Unity 调用: 向已连接的蓝牙设备写入速度数据
     * @param speed 速度值字符串 (如 "220", "240", "0"=停止)
     */
    public static void WriteData(String speed) {
        try {
            if (skt != null && skt.isConnected()) {
                outputStream.write(speed.getBytes());
            }
        } catch (IOException e) {
            UnityPlayer.UnitySendMessage("BluetoothManager", "ReadLog",
                    "WriteData Error:" + e.toString());
        }
    }

    // ============ 工具方法 ============
    /** Unity 调用: 显示 Android Toast 消息 */
    public static void Toast(String data) {
        Toast.makeText(
            UnityPlayer.currentActivity.getApplicationContext(),
            data,
            Toast.LENGTH_SHORT
        ).show();
    }
}
