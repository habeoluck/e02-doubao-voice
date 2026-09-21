package com.ecarx.doubao.bridge;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 音频播放器 - 播放豆包TTS返回的PCM音频
 *
 * 使用AudioTrack流式播放, 支持分块接收实时播放
 * 输出到车机扬声器 (STREAM_MUSIC)
 */
public class AudioPlayer {

    private static final String TAG = "AudioPlayer";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL = AudioFormat.CHANNEL_OUT_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private AudioTrack audioTrack;
    private boolean isPlaying = false;
    private final LinkedBlockingQueue<byte[]> audioQueue = new LinkedBlockingQueue<>(100);

    // 静音检测: 连续静音帧数
    private int silenceFrames = 0;
    private static final int SILENCE_THRESHOLD = 15; // ~300ms静音认为结束

    /**
     * 开始播放
     */
    public void start() {
        if (isPlaying) return;

        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING);
        audioTrack = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(ENCODING)
                .setChannelMask(CHANNEL)
                .build())
            .setBufferSizeInBytes(Math.max(minBuf, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build();

        audioTrack.play();
        isPlaying = true;
        silenceFrames = 0;

        new Thread(this::playbackLoop, "AudioPlayer").start();
        Log.i(TAG, "播放器启动");
    }

    /**
     * 写入音频数据 (来自豆包TTS)
     */
    public void writeAudio(byte[] data) {
        if (data == null || data.length == 0) return;
        if (!audioQueue.offer(data)) {
            Log.w(TAG, "音频队列满, 丢弃数据");
        }
    }

    /**
     * 播放循环
     */
    private void playbackLoop() {
        while (isPlaying) {
            try {
                byte[] chunk = audioQueue.poll(200, TimeUnit.MILLISECONDS);
                if (chunk != null) {
                    audioTrack.write(chunk, 0, chunk.length);
                    // 简单静音检测 (用于判断TTS是否结束)
                    if (isSilence(chunk)) {
                        silenceFrames++;
                    } else {
                        silenceFrames = 0;
                    }
                } else if (silenceFrames > SILENCE_THRESHOLD) {
                    // 长时间静音, 认为TTS播放完成
                    Log.d(TAG, "检测到TTS结束 (静音)");
                    silenceFrames = 0;
                }
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * 简单静音检测 (基于能量)
     */
    private boolean isSilence(byte[] pcm) {
        if (pcm.length < 2) return true;
        long sum = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            short sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            sum += Math.abs(sample);
        }
        int count = pcm.length / 2;
        long avg = count > 0 ? sum / count : 0;
        return avg < 200; // 阈值, 可调
    }

    /**
     * 停止播放
     */
    public void stop() {
        isPlaying = false;
        audioQueue.clear();
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception e) {
                Log.w(TAG, "停止异常: " + e.getMessage());
            }
            audioTrack = null;
        }
        Log.i(TAG, "播放器停止");
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    /**
     * 等待队列播放完毕
     */
    public void flush() {
        while (!audioQueue.isEmpty() && isPlaying) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                break;
            }
        }
    }
}
