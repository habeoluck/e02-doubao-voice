package com.ecarx.doubao.bridge;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

/**
 * 音频采集器 - 从麦克风采集 PCM 16kHz 16-bit 单声道音频
 *
 * E02 车机 audioconfig.json 配置:
 *   sampleRate=16000, channels=1(单声道模式), encoding=PCM_16BIT
 */
public class AudioCapture {

    private static final String TAG = "AudioCapture";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    // 每帧 20ms = 16000 * 2 * 0.02 = 640 bytes
    private static final int FRAME_SIZE_MS = 20;
    private static final int FRAME_BYTES = SAMPLE_RATE * 2 * FRAME_SIZE_MS / 1000;

    private AudioRecord audioRecord;
    private boolean isCapturing = false;
    private AudioCallback callback;

    public interface AudioCallback {
        void onAudioData(byte[] data);
    }

    public void setCallback(AudioCallback callback) {
        this.callback = callback;
    }

    /**
     * 开始采集
     */
    public boolean start() {
        if (isCapturing) {
            Log.w(TAG, "已在采集中");
            return true;
        }

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING);
        int bufferSize = Math.max(minBuf, FRAME_BYTES * 2);

        try {
            // MIC = 车机主麦克风
            // 注意: E02 可能有多个声区(4声道), 这里用单声道采集
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败");
                return false;
            }

            audioRecord.startRecording();
            isCapturing = true;
            Log.i(TAG, "音频采集启动: " + SAMPLE_RATE + "Hz, 帧大小=" + FRAME_BYTES);

            // 采集线程
            new Thread(this::captureLoop, "AudioCapture").start();
            return true;

        } catch (SecurityException e) {
            Log.e(TAG, "无麦克风权限: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "启动失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 采集循环
     */
    private void captureLoop() {
        byte[] buffer = new byte[FRAME_BYTES];
        while (isCapturing) {
            int read = audioRecord.read(buffer, 0, FRAME_BYTES);
            if (read > 0 && callback != null) {
                byte[] chunk = new byte[read];
                System.arraycopy(buffer, 0, chunk, 0, read);
                callback.onAudioData(chunk);
            }
        }
    }

    /**
     * 停止采集
     */
    public void stop() {
        isCapturing = false;
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.w(TAG, "停止异常: " + e.getMessage());
            }
            audioRecord = null;
        }
        Log.i(TAG, "音频采集停止");
    }

    public boolean isCapturing() {
        return isCapturing;
    }
}
