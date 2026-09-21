package com.ecarx.doubao.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/**
 * 配置管理器 - 读取 doubao_config.json 和 SharedPreferences
 *
 * 配置优先级: SharedPreferences > /data/local/tmp/doubao_config.json > 默认值
 */
public class ConfigManager {

    private static final String PREF_NAME = "doubao_config";
    private static final String CONFIG_PATH = "/data/local/tmp/doubao_config.json";
    private static final String TAG = "DoubaoConfig";

    // 默认配置
    private static final String DEFAULT_APP_ID = "YOUR_APP_ID";
    private static final String DEFAULT_ACCESS_TOKEN = "YOUR_ACCESS_TOKEN";
    private static final int DEFAULT_SAMPLE_RATE = 16000;
    private static final int DEFAULT_CHANNEL = 1;
    private static final int DEFAULT_BITS = 16;

    private final SharedPreferences prefs;

    public ConfigManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 从 /data/local/tmp/doubao_config.json 加载配置
     */
    public void loadFromFile() {
        try {
            java.io.File file = new java.io.File(CONFIG_PATH);
            if (!file.exists()) {
                android.util.Log.w(TAG, "配置文件不存在: " + CONFIG_PATH);
                return;
            }
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            byte[] buffer = new byte[(int) file.length()];
            fis.read(buffer);
            fis.close();
            String json = new String(buffer, "UTF-8");
            JSONObject obj = new JSONObject(json);

            SharedPreferences.Editor editor = prefs.edit();
            if (obj.has("appId")) editor.putString("appId", obj.getString("appId"));
            if (obj.has("accessToken")) editor.putString("accessToken", obj.getString("accessToken"));
            if (obj.has("roomId")) editor.putString("roomId", obj.optString("roomId", generateRoomId()));
            if (obj.has("systemPrompt")) editor.putString("systemPrompt",
                obj.optString("systemPrompt", "你是E02车机的语音助手，请简洁友好地回答用户问题。"));
            if (obj.has("modelName")) editor.putString("modelName",
                obj.optString("modelName", "doubao-seed-2-0-lite-260428"));
            if (obj.has("voiceType")) editor.putString("voiceType",
                obj.optString("voiceType", "BV700_V2_streaming"));
            if (obj.has("apiKey")) editor.putString("apiKey", obj.getString("apiKey"));
            if (obj.has("enableFallback")) editor.putBoolean("enableFallback",
                obj.optBoolean("enableFallback", true));
            editor.putString("roomId", obj.optString("roomId", generateRoomId()));
            editor.apply();
            android.util.Log.i(TAG, "配置加载完成: appId=" + obj.optString("appId", "?"));
        } catch (Exception e) {
            android.util.Log.e(TAG, "加载配置失败: " + e.getMessage());
        }
    }

    public String getAppId() {
        return prefs.getString("appId", DEFAULT_APP_ID);
    }

    public String getAccessToken() {
        return prefs.getString("accessToken", DEFAULT_ACCESS_TOKEN);
    }

    public String getApiKey() {
        return prefs.getString("apiKey", "");
    }

    public boolean hasApiKey() {
        String key = getApiKey();
        return key != null && !key.isEmpty();
    }

    public String getRoomId() {
        String roomId = prefs.getString("roomId", null);
        if (roomId == null) {
            roomId = generateRoomId();
            prefs.edit().putString("roomId", roomId).apply();
        }
        return roomId;
    }

    public String getSystemPrompt() {
        return prefs.getString("systemPrompt",
            "你是E02车机的语音助手，请简洁友好地回答用户问题。");
    }

    public String getModelName() {
        return prefs.getString("modelName", "doubao-seed-2-0-lite-260428");
    }

    public String getVoiceType() {
        return prefs.getString("voiceType", "BV700_V2_streaming");
    }

    public int getSampleRate() {
        return DEFAULT_SAMPLE_RATE;
    }

    public int getChannel() {
        return DEFAULT_CHANNEL;
    }

    public boolean isFallbackEnabled() {
        return prefs.getBoolean("enableFallback", true);
    }

    public boolean isConfigured() {
        String appId = getAppId();
        return appId != null && !appId.isEmpty() && !appId.equals(DEFAULT_APP_ID);
    }

    /**
     * 保存配置 (从UI界面)
     */
    public void saveConfig(String appId, String accessToken) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("appId", appId);
        editor.putString("accessToken", accessToken);
        editor.apply();
        android.util.Log.i(TAG, "配置已保存: appId=" + appId);
    }

    private String generateRoomId() {
        return "e02_" + System.currentTimeMillis();
    }
}
