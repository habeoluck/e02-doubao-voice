package com.ecarx.doubao.bridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 配置界面
 *
 * 功能:
 * 1. 输入火山引擎 AppId / AccessToken
 * 2. 测试连接 (Push-to-Talk 模式)
 * 3. 查看状态
 */
public class MainActivity extends Activity {

    private EditText etAppId, etAccessToken;
    private TextView tvStatus;
    private Button btnSave, btnTest, btnStartVoice;
    private ConfigManager config;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 简单布局 (代码构建, 不依赖XML)
        setContentView(createLayout());

        config = new ConfigManager(this);
        config.loadFromFile();

        // 填充已有配置
        etAppId.setText(config.getAppId());
        etAccessToken.setText(config.getAccessToken());
        updateStatus();
    }

    private View createLayout() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        // 标题
        TextView tvTitle = new TextView(this);
        tvTitle.setText("E02 豆包语音桥接");
        tvTitle.setTextSize(20);
        tvTitle.setPadding(0, 0, 0, 24);
        layout.addView(tvTitle);

        // AppId
        layout.addView(createLabel("火山引擎 AppId:"));
        etAppId = new EditText(this);
        etAppId.setHint("在火山引擎控制台获取");
        layout.addView(etAppId);

        // AccessToken
        layout.addView(createLabel("Access Token:"));
        etAccessToken = new EditText(this);
        etAccessToken.setHint("在火山引擎控制台获取");
        layout.addView(etAccessToken);

        // 按钮
        android.widget.LinearLayout btnRow = new android.widget.LinearLayout(this);
        btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, 24, 0, 24);

        btnSave = new Button(this);
        btnSave.setText("保存配置");
        btnSave.setOnClickListener(v -> saveConfig());
        btnRow.addView(btnBtn(btnSave));

        btnTest = new Button(this);
        btnTest.setText("测试网络");
        btnTest.setOnClickListener(v -> testNetwork());
        btnRow.addView(btnBtn(btnTest));

        btnStartVoice = new Button(this);
        btnStartVoice.setText("Push-to-Talk测试");
        btnStartVoice.setOnClickListener(v -> startVoiceTest());
        btnRow.addView(btnBtn(btnStartVoice));

        layout.addView(btnRow);

        // 状态
        layout.addView(createLabel("状态:"));
        tvStatus = new TextView(this);
        tvStatus.setPadding(0, 8, 0, 0);
        tvStatus.setTextSize(13);
        layout.addView(tvStatus);

        return layout;
    }

    private View createLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setPadding(0, 16, 0, 4);
        return tv;
    }

    private Button btnBtn(Button b) {
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(8, 0, 8, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void saveConfig() {
        config.saveConfig(
            etAppId.getText().toString().trim(),
            etAccessToken.getText().toString().trim()
        );
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        updateStatus();
    }

    private void testNetwork() {
        new Thread(() -> {
            boolean net = NetworkUtils.isNetworkAvailable(this);
            boolean reach = NetworkUtils.isDoubaoReachable();
            boolean weak = NetworkUtils.isWeakNetwork();
            runOnUiThread(() -> {
                String msg = "网络: " + (net ? "OK" : "不可用") + "\n"
                    + "豆包可达: " + (reach ? "OK" : "不可达") + "\n"
                    + "弱网: " + (weak ? "是" : "否");
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                tvStatus.setText(msg);
            });
        }).start();
    }

    private void startVoiceTest() {
        // Push-to-Talk 测试: 启动语音服务
        Intent intent = new Intent(this, DoubaoVoiceService.class);
        intent.setAction(DoubaoVoiceService.ACTION_START);
        startService(intent);
        Toast.makeText(this, "语音对话已启动 (按返回键结束)", Toast.LENGTH_SHORT).show();
    }

    private void updateStatus() {
        String status = "配置: " + (config.isConfigured() ? "已配置" : "未配置") + "\n"
            + "唤醒词: 你好吉利 (DSP检测)\n"
            + "识别: 豆包云端ASR 2.0\n"
            + "LLM: " + config.getModelName() + "\n"
            + "TTS: " + config.getVoiceType() + "\n"
            + "降级: " + (config.isFallbackEnabled() ? "启用" : "禁用");
        tvStatus.setText(status);
    }
}
