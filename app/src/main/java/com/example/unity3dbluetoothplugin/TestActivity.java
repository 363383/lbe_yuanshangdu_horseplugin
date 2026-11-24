package com.example.unity3dbluetoothplugin;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * BLE GATT 独立测试 Activity。
 *
 * 不依赖 Unity，直接测试蓝牙连接、写入、断开流程。
 * 实现 BleCallback 以接收连接状态和日志。
 */
public class TestActivity extends Activity implements BleCallback {

    private static final String TARGET_MAC = "28:05:A5:29:06:B6";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private TextView mStatusView;
    private TextView mLogView;
    private ScrollView mLogScroller;
    private EditText mSpeedInput;
    private Button mConnectBtn;
    private Button mDisconnectBtn;
    private Button mSendBtn;

    private final SimpleDateFormat mTimeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createLayout());

        // 初始化蓝牙连接器
        BluetoothConnector.initialize(getApplicationContext(), this);

        // 请求权限 (Android 12+)
        requestBluetoothPermissions();

        setTitle("BLE Test — " + TARGET_MAC);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        BluetoothConnector.StopConnection();
    }

    // ============ BleCallback 实现 ============

    @Override
    public void onConnectionStatus(String status) {
        runOnUiThread(() -> {
            mStatusView.setText("Status: " + status);
            switch (status) {
                case "connected":
                    mStatusView.setTextColor(Color.parseColor("#2E7D32"));
                    mConnectBtn.setEnabled(false);
                    mDisconnectBtn.setEnabled(true);
                    mSendBtn.setEnabled(true);
                    break;
                case "connecting":
                case "discovering":
                    mStatusView.setTextColor(Color.parseColor("#E65100"));
                    mConnectBtn.setEnabled(false);
                    mDisconnectBtn.setEnabled(true);
                    mSendBtn.setEnabled(false);
                    break;
                case "disconnected":
                    mStatusView.setTextColor(Color.parseColor("#757575"));
                    mConnectBtn.setEnabled(true);
                    mDisconnectBtn.setEnabled(false);
                    mSendBtn.setEnabled(false);
                    break;
                default:
                    mStatusView.setTextColor(Color.parseColor("#C62828"));
                    mConnectBtn.setEnabled(true);
                    mDisconnectBtn.setEnabled(false);
                    mSendBtn.setEnabled(false);
                    break;
            }
        });
    }

    @Override
    public void onLog(String message) {
        runOnUiThread(() -> {
            String timestamp = mTimeFormat.format(new Date());
            mLogView.append("[" + timestamp + "] " + message + "\n");
            // 自动滚动到底部
            mLogScroller.post(() -> mLogScroller.fullScroll(View.FOCUS_DOWN));
        });
    }

    // ============ 权限 ============

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                }, PERMISSION_REQUEST_CODE);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION
                }, PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean granted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            if (!granted) {
                Toast.makeText(this, "Bluetooth permissions required for BLE", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ============ UI 布局 ============

    @SuppressLint("SetTextI18n")
    private LinearLayout createLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        // --- 标题 ---
        TextView title = new TextView(this);
        title.setText("BLE GATT Test — ESP32 vrgao001_BLE");
        title.setTextSize(18);
        title.setTextColor(Color.BLACK);
        title.setPadding(0, 0, 0, 16);
        root.addView(title);

        // --- 连接按钮行 ---
        LinearLayout connRow = new LinearLayout(this);
        connRow.setOrientation(LinearLayout.HORIZONTAL);
        connRow.setPadding(0, 0, 0, 16);

        mConnectBtn = new Button(this);
        mConnectBtn.setText("Connect");
        mConnectBtn.setOnClickListener(v -> BluetoothConnector.StartConnection(TARGET_MAC));
        connRow.addView(mConnectBtn);

        mDisconnectBtn = new Button(this);
        mDisconnectBtn.setText("Disconnect");
        mDisconnectBtn.setEnabled(false);
        mDisconnectBtn.setOnClickListener(v -> BluetoothConnector.StopConnection());
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dp.setMarginStart(16);
        mDisconnectBtn.setLayoutParams(dp);
        connRow.addView(mDisconnectBtn);

        root.addView(connRow);

        // --- 速度发送行 ---
        LinearLayout sendRow = new LinearLayout(this);
        sendRow.setOrientation(LinearLayout.HORIZONTAL);
        sendRow.setPadding(0, 0, 0, 8);

        mSpeedInput = new EditText(this);
        mSpeedInput.setHint("Speed (e.g. 220)");
        mSpeedInput.setText("220");
        mSpeedInput.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        sendRow.addView(mSpeedInput);

        mSendBtn = new Button(this);
        mSendBtn.setText("Send");
        mSendBtn.setEnabled(false);
        mSendBtn.setOnClickListener(v -> {
            String speed = mSpeedInput.getText().toString().trim();
            if (!speed.isEmpty()) {
                BluetoothConnector.WriteData(speed);
                onLog("Sent: " + speed);
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMarginStart(16);
        mSendBtn.setLayoutParams(sp);
        sendRow.addView(mSendBtn);

        root.addView(sendRow);

        // --- 预设速度按钮 ---
        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setPadding(0, 0, 0, 16);

        for (String val : new String[]{"220", "240", "0"}) {
            Button btn = new Button(this);
            btn.setText(val);
            btn.setOnClickListener(v -> {
                String s = ((Button) v).getText().toString();
                mSpeedInput.setText(s);
                if (mSendBtn.isEnabled()) {
                    BluetoothConnector.WriteData(s);
                    onLog("Sent: " + s);
                }
            });
            presetRow.addView(btn);

            LinearLayout.LayoutParams bsp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bsp.setMarginEnd(16);
            btn.setLayoutParams(bsp);
        }
        root.addView(presetRow);

        // --- 状态显示 ---
        mStatusView = new TextView(this);
        mStatusView.setText("Status: disconnected");
        mStatusView.setTextSize(16);
        mStatusView.setTextColor(Color.parseColor("#757575"));
        mStatusView.setPadding(0, 0, 0, 8);
        root.addView(mStatusView);

        // --- 日志标题 ---
        TextView logTitle = new TextView(this);
        logTitle.setText("── Log ──");
        logTitle.setTextSize(14);
        logTitle.setTextColor(Color.DKGRAY);
        logTitle.setPadding(0, 0, 0, 4);
        root.addView(logTitle);

        // --- 日志区域 (可滚动) ---
        mLogScroller = new ScrollView(this);
        mLogScroller.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        mLogScroller.setBackgroundColor(Color.parseColor("#F5F5F5"));

        mLogView = new TextView(this);
        mLogView.setPadding(16, 8, 16, 8);
        mLogView.setTextSize(12);
        mLogView.setTextColor(Color.DKGRAY);
        mLogView.setMovementMethod(new ScrollingMovementMethod());
        mLogView.setGravity(Gravity.BOTTOM);
        mLogScroller.addView(mLogView);

        root.addView(mLogScroller);

        return root;
    }
}
