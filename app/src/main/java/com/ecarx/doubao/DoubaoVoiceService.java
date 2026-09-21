package com.ecarx.doubao.bridge;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * 豆包语音核心服务
 *
 * 职责:
 * 1. 管理音频采集 -> 豆包API -> TTS播放 的完整链路
 * 2. 弱网降级: 网络不可用时回退原系统
 * 3. 前台服务: 保持麦克风采集不被系统杀死
 *
 * 触发方式:
 * - LSPosed Hook 唤醒词回调 -> 发送广播启动本服务
 * - Push-to-Talk 按钮 (测试用)
 * - 广播: com.ecarx.doubao.ACTION_START_VOICE / ACTION_STOP_VOICE
 */
public class DoubaoVoiceService extends Service {

    private static final String TAG = "DoubaoService";

    // 广播Action
    public static final String ACTION_START = "com.ecarx.doubao.ACTION_START_VOICE";
    public static final String ACTION_STOP = "com.ecarx.doubao.ACTION_STOP_VOICE";

    // 状态
    private enum State { IDLE, CONNECTING, LISTENING, PROCESSING, PLAYING }
    private State state = State.IDLE;

    // 组件
    private ConfigManager config;
    private DoubaoApiClient apiClient;
    private AudioCapture audioCapture;
    private AudioPlayer audioPlayer;
    private boolean useFallback = false;

    @Override
    public void onCreate() {
        super.onCreate();
        config = new ConfigManager(this);
        config.loadFromFile();
        apiClient = new DoubaoApiClient(config);
        audioCapture = new AudioCapture();
        audioPlayer = new AudioPlayer();

        // 设置豆包API回调
        apiClient.setCallback(new DoubaoApiClient.DoubaoCallback() {
            @Override
            public void onConnected() {
                Log.i(TAG, "豆包已连接, 开始音频采集");
                state = State.LISTENING;
                audioCapture.setCallback(data -> apiClient.sendAudio(data));
                audioCapture.start();
            }

            @Override
            public void onAsrText(String text) {
                Log.i(TAG, "用户说: " + text);
                // 可在这里判断是否需要车控指令路由
                // 如果是车控(开空调/导航) -> 可走GKAICarControl
                // 如果是对话 -> 豆包LLM处理
            }

            @Override
            public void onLlmResponse(String text) {
                Log.i(TAG, "豆包回复: " + text);
                state = State.PLAYING;
            }

            @Override
            public void onTtsAudio(byte[] audioData) {
                if (audioPlayer != null) {
                    audioPlayer.writeAudio(audioData);
                }
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "豆包错误: " + message);
                if (config.isFallbackEnabled()) {
                    Log.i(TAG, "降级到原系统");
                    useFallback = true;
                }
                cleanup();
            }

            @Override
            public void onDisconnected() {
                Log.i(TAG, "豆包断开");
                cleanup();
            }
        });

        Log.i(TAG, "服务创建完成, 配置: " + (config.isConfigured() ? "OK" : "未配置"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(action)) {
            startVoiceSession();
        } else if (ACTION_STOP.equals(action)) {
            stopVoiceSession();
        }
        return START_NOT_STICKY;
    }

    /**
     * 启动语音对话
     */
    private void startVoiceSession() {
        if (state != State.IDLE) {
            Log.w(TAG, "已在工作中, 忽略重复启动 state=" + state);
            return;
        }

        // 检查配置
        if (!config.isConfigured()) {
            Log.e(TAG, "未配置火山引擎AppId/Token, 无法启动");
            return;
        }

        // 检查网络
        if (!NetworkUtils.isNetworkAvailable(this)) {
            Log.w(TAG, "网络不可用, 降级到原系统");
            useFallback = true;
            return;
        }

        if (!NetworkUtils.isDoubaoReachable()) {
            Log.w(TAG, "豆包服务不可达, 降级到原系统");
            useFallback = true;
            return;
        }

        // 启动豆包会话
        state = State.CONNECTING;
        Log.i(TAG, "启动豆包语音对话...");
        apiClient.startSession();

        // 启动播放器 (预初始化)
        audioPlayer.start();
    }

    /**
     * 停止语音对话
     */
    private void stopVoiceSession() {
        Log.i(TAG, "停止语音对话");
        // 停止音频采集, 发送结束信令
        audioCapture.stop();
        apiClient.sendStopSignal();
        // 等待TTS播放完毕
        audioPlayer.flush();
        cleanup();
    }

    /**
     * 清理资源
     */
    private void cleanup() {
        audioCapture.stop();
        audioPlayer.stop();
        state = State.IDLE;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        cleanup();
        apiClient.disconnect();
        super.onDestroy();
        Log.i(TAG, "服务销毁");
    }
}
