package com.ecarx.doubao.bridge;

import android.util.Log;
import org.json.JSONObject;
import okhttp3.*;

/**
 * 豆包 StartVoiceChat API 客户端
 *
 * 流程:
 * 1. HTTP POST StartVoiceChat -> 获取 WebSocket URL
 * 2. WebSocket 连接 -> 双向音频流
 * 3. 发送麦克风 PCM 音频帧
 * 4. 接收 ASR 文本 + LLM 回复 + TTS 音频帧
 */
public class DoubaoApiClient {

    private static final String TAG = "DoubaoClient";
    private static final String API_BASE = "https://openspeech.bytedance.com/api/v1";

    // 豆包 RTC WebSocket 接入点 (实际URL从StartVoiceChat响应中获取)
    private static final String WSS_HOST = "wss://openspeech.bytedance.com";

    private final ConfigManager config;
    private final OkHttpClient httpClient;
    private WebSocket webSocket;
    private String taskId;

    // 回调接口
    public interface DoubaoCallback {
        void onConnected();
        void onAsrText(String text);              // 识别到的文字
        void onLlmResponse(String text);          // LLM回复文字
        void onTtsAudio(byte[] audioData);         // TTS音频块
        void onError(String message);
        void onDisconnected();
    }

    private DoubaoCallback callback;

    public DoubaoApiClient(ConfigManager config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
            .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS) // 不超时
            .build();
    }

    public void setCallback(DoubaoCallback callback) {
        this.callback = callback;
    }

    /**
     * 启动语音对话会话
     * 调用 StartVoiceChat API 获取 WebSocket 地址并连接
     */
    public void startSession() {
        if (!config.isConfigured()) {
            if (callback != null) callback.onError("未配置 AppId/AccessToken, 请先在配置中填写");
            return;
        }

        // 构建 StartVoiceChat 请求
        JSONObject requestBody = buildStartVoiceChatRequest();
        Log.i(TAG, "启动语音对话: appId=" + config.getAppId());

        RequestBody body = RequestBody.create(
            requestBody.toString(),
            MediaType.parse("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
            .url(API_BASE + "/voicechat/start")
            .post(body)
            .addHeader("Authorization", "Bearer;" + config.getAccessToken())
            .addHeader("Content-Type", "application/json")
            .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) {
                Log.e(TAG, "StartVoiceChat失败: " + e.getMessage());
                // 降级: 直接尝试WebSocket连接
                connectWebSocketDirect();
            }

            @Override
            public void onResponse(Call call, Response response) throws java.io.IOException {
                String respBody = response.body() != null ? response.body().string() : "";
                Log.i(TAG, "StartVoiceChat响应: " + response.code());

                try {
                    JSONObject resp = new JSONObject(respBody);
                    if (resp.has("data")) {
                        JSONObject data = resp.getJSONObject("data");
                        String wsUrl = data.optString("WebSocketUrl", null);
                        taskId = data.optString("TaskId", "");
                        if (wsUrl != null) {
                            connectWebSocket(wsUrl);
                        } else {
                            connectWebSocketDirect();
                        }
                    } else {
                        connectWebSocketDirect();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "解析响应失败, 尝试直连: " + e.getMessage());
                    connectWebSocketDirect();
                }
            }
        });
    }

    /**
     * 构建 StartVoiceChat 请求体
     */
    private JSONObject buildStartVoiceChatRequest() {
        JSONObject body = new JSONObject();
        try {
            body.put("AppId", config.getAppId());
            body.put("RoomId", config.getRoomId());
            body.put("TaskId", "task_" + System.currentTimeMillis());

            // ASR 配置
            JSONObject asrConfig = new JSONObject();
            asrConfig.put("Provider", "volcengine");
            JSONObject asrParams = new JSONObject();
            asrParams.put("language", "zh-CN");
            asrParams.put("sampleRate", config.getSampleRate());
            asrParams.put("enablePunctuation", true);
            asrParams.put("enableItn", true);
            asrConfig.put("ProviderParams", asrParams);
            body.put("ASRConfig", asrConfig);

            // LLM 配置 (doubao-seed 大模型)
            JSONObject llmConfig = new JSONObject();
            llmConfig.put("Mode", "ArkV3");
            llmConfig.put("ModelName", config.getModelName());
            // 如果有火山方舟API Key, 使用自建模型接入点(独立计费)
            // 如果没有, 使用公共方舟模型(折算Tokens计费)
            if (config.hasApiKey()) {
                llmConfig.put("APIKey", config.getApiKey());
                Log.i(TAG, "使用自建方舟模型, APIKey已配置");
            }
            llmConfig.put("MaxTokens", 1024);
            llmConfig.put("Temperature", 0.1);
            llmConfig.put("TopP", 0.3);
            llmConfig.put("ThinkingType", "disabled");
            // 系统提示词 - 车载场景定制
            org.json.JSONArray sysMsgs = new org.json.JSONArray();
            sysMsgs.put(config.getSystemPrompt());
            llmConfig.put("SystemMessages", sysMsgs);
            llmConfig.put("HistoryLength", 10);
            body.put("LLMConfig", llmConfig);

            // TTS 配置 (豆包语音合成)
            JSONObject ttsConfig = new JSONObject();
            ttsConfig.put("Provider", "volcengine");
            JSONObject ttsParams = new JSONObject();
            ttsParams.put("voiceType", config.getVoiceType());
            ttsParams.put("encoding", "pcm");
            ttsParams.put("sampleRate", config.getSampleRate());
            ttsConfig.put("ProviderParams", ttsParams);
            body.put("TTSConfig", ttsConfig);

            // Agent 配置
            JSONObject agentConfig = new JSONObject();
            agentConfig.put("AgentId", "e02_voice_assistant");
            agentConfig.put("UserId", "e02_user");
            body.put("AgentConfig", agentConfig);

            // 音频配置
            JSONObject audioConfig = new JSONObject();
            audioConfig.put("InputAudio", new JSONObject()
                .put("format", "pcm")
                .put("sampleRate", config.getSampleRate())
                .put("channels", config.getChannel())
                .put("bits", 16));
            audioConfig.put("OutputAudio", new JSONObject()
                .put("format", "pcm")
                .put("sampleRate", config.getSampleRate())
                .put("channels", 1)
                .put("bits", 16));
            body.put("AudioConfig", audioConfig);

        } catch (Exception e) {
            Log.e(TAG, "构建请求体失败: " + e.getMessage());
        }
        return body;
    }

    /**
     * 直连 WebSocket (降级方案)
     */
    private void connectWebSocketDirect() {
        String wsUrl = WSS_HOST + "/api/v1/voicechat/ws?appid=" + config.getAppId()
            + "&roomid=" + config.getRoomId() + "&token=" + config.getAccessToken();
        connectWebSocket(wsUrl);
    }

    /**
     * 连接 WebSocket 并开始双向音频流
     */
    private void connectWebSocket(String url) {
        Log.i(TAG, "连接WebSocket: " + url.replaceAll("token=[^&]+", "token=***"));

        Request request = new Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer;" + config.getAccessToken())
            .build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                Log.i(TAG, "WebSocket已连接");
                // 发送开始指令
                sendStartSignal();
                if (callback != null) callback.onConnected();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleTextMessage(text);
            }

            @Override
            public void onMessage(WebSocket webSocket, okio.ByteString bytes) {
                // 二进制消息 = TTS音频数据
                if (callback != null) callback.onTtsAudio(bytes.toByteArray());
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
                Log.i(TAG, "WebSocket关闭: " + code + " " + reason);
                if (callback != null) callback.onDisconnected();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.e(TAG, "WebSocket失败: " + t.getMessage());
                if (callback != null) callback.onError(t.getMessage());
            }
        });
    }

    /**
     * 发送开始信令
     */
    private void sendStartSignal() {
        try {
            JSONObject signal = new JSONObject();
            signal.put("type", "start");
            signal.put("timestamp", System.currentTimeMillis());
            webSocket.send(signal.toString());
        } catch (Exception e) {
            Log.e(TAG, "发送开始信令失败: " + e.getMessage());
        }
    }

    /**
     * 处理文本消息 (ASR结果/LLM回复/控制信令)
     */
    private void handleTextMessage(String text) {
        try {
            JSONObject msg = new JSONObject(text);
            String type = msg.optString("type", "");
            switch (type) {
                case "asr":
                case "transcription":
                    String asrText = msg.optString("text", "");
                    Log.i(TAG, "ASR: " + asrText);
                    if (callback != null && !asrText.isEmpty()) callback.onAsrText(asrText);
                    break;
                case "llm":
                case "response":
                    String llmText = msg.optString("text", "");
                    Log.i(TAG, "LLM: " + llmText);
                    if (callback != null && !llmText.isEmpty()) callback.onLlmResponse(llmText);
                    break;
                case "tts_start":
                    Log.i(TAG, "TTS开始");
                    break;
                case "tts_end":
                    Log.i(TAG, "TTS结束");
                    break;
                case "error":
                    String errMsg = msg.optString("message", "未知错误");
                    Log.e(TAG, "服务端错误: " + errMsg);
                    if (callback != null) callback.onError(errMsg);
                    break;
                default:
                    Log.d(TAG, "其他消息: " + type);
            }
        } catch (Exception e) {
            Log.w(TAG, "解析消息失败: " + text);
        }
    }

    /**
     * 发送音频数据 (PCM 16kHz 16-bit mono)
     * @param audioData PCM音频字节数组
     */
    public void sendAudio(byte[] audioData) {
        if (webSocket != null && audioData != null && audioData.length > 0) {
            webSocket.send(okio.ByteString.of(audioData));
        }
    }

    /**
     * 发送结束信令
     */
    public void sendStopSignal() {
        if (webSocket == null) return;
        try {
            JSONObject signal = new JSONObject();
            signal.put("type", "stop");
            signal.put("timestamp", System.currentTimeMillis());
            webSocket.send(signal.toString());
        } catch (Exception e) {
            Log.e(TAG, "发送停止信令失败: " + e.getMessage());
        }
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        if (webSocket != null) {
            sendStopSignal();
            webSocket.close(1000, "正常关闭");
            webSocket = null;
        }
    }
}
