package com.example.nvrclient;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import com.example.nvrclient.protocol.Backchannel;

/** A push-to-talk session. Call stop on release, cancel and activity pause. */
final class TalkSession {
    interface Listener { void started(); void ended(String error); }
    private final com.example.nvrclient.protocol.AudioChannel channel;
    private final Listener listener;
    private final Object lock = new Object();
    private volatile boolean stopped;
    private AudioRecord recorder;
    TalkSession(String uri, String user, String password, Listener listener) {
        this(new Backchannel(uri, user, password), listener);
    }
    TalkSession(com.example.nvrclient.protocol.AudioChannel channel, Listener listener) {
        this.channel = channel; this.listener = listener;
    }
    void start() { new Thread(this::run, "nvr-talk").start(); }
    private void run() {
        String error = null;
        try {
            channel.open();
            synchronized (lock) {
                if (stopped) return;
                int sampleRate = channel.sampleRate();
                int minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) throw new IllegalStateException("手机不支持 "+sampleRate/1000+"kHz 麦克风采集");
                recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, channel.frameSamples()*8));
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("无法初始化麦克风");
                recorder.startRecording();
                if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("麦克风未开始录音");
            }
            boolean sent = false;
            short[] frame = new short[channel.frameSamples()];
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (!stopped && System.nanoTime() < deadline) {
                int offset = 0;
                while (!stopped && offset < frame.length) {
                    int count = recorder.read(frame, offset, frame.length - offset, AudioRecord.READ_BLOCKING);
                    if (stopped) break;
                    if (count <= 0) throw new IllegalStateException("麦克风采集失败");
                    offset += count;
                }
                if (!stopped) {channel.send(frame, frame.length);if(!sent){sent=true;listener.started();}}
            }
        } catch (LinkageError e) {
            if (!stopped) error = "海康对讲库加载失败，请检查 SDK 与手机架构";
        } catch (SecurityException e) {
            if (!stopped) error = "麦克风权限已撤销，请重新授权";
        } catch (Exception e) {
            if (!stopped) error = e instanceof IllegalArgumentException || e instanceof IllegalStateException
                ? e.getMessage() : "对讲连接失败，请检查网络、账号和设备音频回传能力";
        } finally {
            synchronized (lock) {
                if (recorder != null) {
                    try { if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop(); } catch (Exception ignored) { }
                    recorder.release(); recorder = null;
                }
            }
            try{channel.teardown();}catch(RuntimeException|LinkageError closeError){if(error==null&&!stopped)error="对讲停止失败，请重连设备";}
            listener.ended(error);
        }
    }
    void stop() {
        stopped = true;
        channel.close();
        synchronized (lock) {
            if (recorder != null) try { recorder.stop(); } catch (Exception ignored) { }
        }
    }
}
