package com.example.nvrclient;

import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import com.hikvision.netsdk.*;
import com.example.nvrclient.protocol.AudioChannel;
import com.example.nvrclient.protocol.G711;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One device login and one preview/playback. All blocking SDK control calls run off the UI thread. */
public final class HikSession {
    interface Listener { void state(String text); void ready(); void records(List<long[]> ranges); }
    public interface SoundApi extends Library {
        int NET_DVR_SaveRealData_V30(int handle, int format, String path);
        int NET_DVR_OpenSound(int handle);
        int NET_DVR_CloseSound();
        int NET_DVR_Volume(int handle, short volume);
        Pointer NET_DVR_InitG722Encoder();
        int NET_DVR_EncodeG722Frame(Pointer encoder, byte[] pcm, byte[] encoded);
        int NET_DVR_ReleaseG722Encoder(Pointer encoder);
    }
    interface RecordListener { void result(boolean active, String path, String error); }
    private static final Set<String> recordingFiles=ConcurrentHashMap.newKeySet();
    static boolean isRecordingFile(java.io.File file){String path=file.getAbsolutePath();if(recordingFiles.contains(path))return true;for(String active:recordingFiles){String stem=active.substring(0,active.length()-3);if(path.startsWith(stem+"_")&&path.endsWith(".ps"))return true;}return false;}
    private String recordPath;
    private RecordListener recordListener;
    private static SoundApi sound;
    private static HikSession soundOwner;
    private static boolean initialized;
    static synchronized HCNetSDK sdk() {
        HCNetSDK sdk = HCNetSDK.getInstance();
        if (!initialized) {
            if (!sdk.NET_DVR_Init()) throw new IllegalStateException("海康 SDK 初始化失败");
            sdk.NET_DVR_SetConnectTime(4000); sdk.NET_DVR_SetReconnect(3000, true);
            initialized = true; // Process-scoped runtime; each session explicitly logs out.
        }
        return sdk;
    }
    private static synchronized SoundApi sound() {
        if (sound == null) sound = (SoundApi) Native.loadLibrary("hcnetsdk", SoundApi.class);
        return sound;
    }
    private final Device device;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Object nativeLock = new Object();
    private volatile boolean closed, ready;
    private HCNetSDK api;
    private int login = -1, handle = -1, channel, command = -1, firstDigitalChannel, firstDigitalVoice;
    private boolean playback, recording, ownsSound;
    private Voice voice;
    private ScheduledFuture<?> stopTimer;
    HikSession(Device device, Listener listener) { this.device = device; this.listener = listener; }
    boolean isReady() { return ready && !closed; }
    private void post(String message) { main.post(() -> { if (!closed) listener.state(message); }); }
    private void submit(Runnable action) {
        if (closed) return;
        try { worker.execute(() -> {
            if (closed) return;
            try { synchronized (nativeLock) { if (!closed) action.run(); } }
            catch (LinkageError e) { post("海康原生库加载失败，请使用兼容的 ARM 手机与 SDK 版本"); }
            catch (RuntimeException e) { post(e.getMessage() == null ? "海康操作失败" : e.getMessage()); }
        }); } catch (RejectedExecutionException ignored) { }
    }
    void start(SurfaceHolder surface, boolean replay, long begin, long end) {
        playback = replay;
        submit(() -> {
            api = sdk();
            NET_DVR_DEVICEINFO_V30 info = new NET_DVR_DEVICEINFO_V30();
            login = api.NET_DVR_Login_V30(device.host, device.sdkPort, device.user, device.password, info);
            if (login < 0) throw failure("海康登录");
            channel = device.sdkChannel > 0 ? device.sdkChannel : (info.byChanNum & 255) > 0 ? info.byStartChan & 255 : info.byStartDChan & 255;
            firstDigitalChannel=info.byStartDChan & 255;firstDigitalVoice=info.byStartDTalkChan & 255;
            if (channel <= 0) throw new IllegalStateException("未取得 SDK 通道号，请在设备配置中指定");
            if (closed || !surface.getSurface().isValid()) return;
            if (replay) {
                NET_DVR_VOD_PARA vod = new NET_DVR_VOD_PARA();
                vod.struIDInfo.dwChannel = channel; vod.struBeginTime = time(begin); vod.struEndTime = time(end);
                vod.hWnd = surface.getSurface(); vod.byStreamType = 0;
                handle = api.NET_DVR_PlayBackByTime_V40(login, vod);
                if (handle < 0) throw failure("录像回放");
                control(HCNetSDK.NET_DVR_PLAYSTART);
                control(HCNetSDK.NET_DVR_PLAYSTOPAUDIO);
            } else {
                NET_DVR_PREVIEWINFO preview = new NET_DVR_PREVIEWINFO(); preview.lChannel = channel;
                preview.dwStreamType = device.sdkStream; preview.dwLinkMode = 0; preview.bBlocked = 1; preview.hHwnd = surface;
                handle = api.NET_DVR_RealPlay_V40(login, preview, null);
                if (handle < 0) throw failure("实时预览");
            }
            if (closed) return;
            ready = true; post((replay ? "回放" : "预览") + "会话已建立 · 通道 " + channel);
            main.post(() -> { if (!closed) listener.ready(); });
            if (replay) { Calendar day = Calendar.getInstance(); day.setTimeInMillis(begin); day.set(Calendar.HOUR_OF_DAY, 0); day.set(Calendar.MINUTE, 0); day.set(Calendar.SECOND, 0); day.set(Calendar.MILLISECOND, 0); search(day.getTimeInMillis(), end); }
        });
    }
    private IllegalStateException failure(String operation) {
        int code = api.NET_DVR_GetLastError();
        String reason = code == 1 ? "账号或密码错误" : code == 2 ? "权限不足" : code == 7 ? "连接失败，检查 SDK 端口与网络"
            : code == 17 ? "参数错误，请检查视频通道和对讲通道" : code == 34 ? "无法创建录像文件" : code == 35 ? "无法打开录像文件" : code == 23 ? "设备不支持此操作" : code == 29 ? "设备对讲通道占用或打开失败" : "设备返回错误";
        return new IllegalStateException(operation + "失败：" + reason + "（" + code + "）");
    }
    void resize(SurfaceHolder surface) { submit(() -> { if (handle >= 0) { if (playback) api.NET_DVR_PlayBackSurfaceChanged(handle, 0, surface); else api.NET_DVR_RealPlaySurfaceChanged(handle, 0, surface); } }); }
    void mute(boolean muted) {
        submit(() -> {
            if (handle < 0) return;
            if (playback) control(muted ? HCNetSDK.NET_DVR_PLAYSTOPAUDIO : HCNetSDK.NET_DVR_PLAYSTARTAUDIO);
            else synchronized (HikSession.class) {
                if (muted) { if (soundOwner == this) { sound().NET_DVR_CloseSound(); soundOwner = null; ownsSound = false; } }
                else {
                    if (sound().NET_DVR_OpenSound(handle) == 0) throw failure("开启声音");
                    if (soundOwner != null) soundOwner.ownsSound = false;
                    soundOwner = this; ownsSound = true;
                }
            }
        });
    }
    void move(float pan, float tilt, float zoom) {
        submit(() -> {
            if (!ready || playback) return;
            int next = PtzDirection.command(pan,tilt,zoom);
            if(next<0){stopNative();return;}
            if (command != next) {
                stopNative(); command = next;
                if (!api.NET_DVR_PTZControlWithSpeed_Other(login, channel, command, 0, 4)) { stopNative(); throw failure("云台控制"); }
            }
            if (stopTimer != null) stopTimer.cancel(false);
            stopTimer = worker.schedule(() -> { synchronized (nativeLock) { try { stopNative(); } catch (RuntimeException e) { post(e.getMessage()); } } }, 1200, TimeUnit.MILLISECONDS);
        });
    }
    void stopMove() { submit(this::stopNative); }
    private void stopNative() {
        if (stopTimer != null) { stopTimer.cancel(false); stopTimer = null; }
        if (command >= 0 && login >= 0) {
            int previous = command;
            if (!api.NET_DVR_PTZControlWithSpeed_Other(login, channel, previous, 1, 4)) {
                // Retry a stop, never retry a movement on failure.
                if (!api.NET_DVR_PTZControlWithSpeed_Other(login, channel, previous, 1, 4)) throw failure("云台停止");
            }
            command = -1;
        }
    }
    AudioChannel audioChannel() { return new Voice(); }
    private final class Voice implements AudioChannel {
        private final AtomicBoolean stopped = new AtomicBoolean();
        private int voiceHandle = -1;
        private boolean alaw, g722, aac;
        private Pointer g722Encoder;
        private AndroidAacEncoder aacEncoder;
        private int talkSampleRate=8000;
        private final VoiceDataCallBack callback = (h, data, length, flag) -> { /* Downlink audio is available through preview. */ };
        @Override public void open() {
            synchronized (nativeLock) {
                if (closed || stopped.get() || !ready || playback) throw new IllegalStateException("请先连接实时预览");
                int talkChannel=device.voiceChannel;
                if(device.voiceToCamera){
                    if(firstDigitalVoice<=0||firstDigitalChannel<=0||channel<firstDigitalChannel)throw new IllegalStateException("设备未提供 NVR 数字对讲通道；直连摄像机请关闭摄像机配置中的 NVR 对讲选项");
                    talkChannel=firstDigitalVoice+channel-firstDigitalChannel;
                }
                NET_DVR_COMPRESSION_AUDIO audio = new NET_DVR_COMPRESSION_AUDIO();
                NET_DVR_AUDIO_CHANNEL audioChannel = new NET_DVR_AUDIO_CHANNEL(); audioChannel.dwChannelNum = talkChannel;
                boolean got = api.NET_DVR_GetCurrentAudioCompress_V50(login, audioChannel, audio);
                if (!got && talkChannel == 1) got = api.NET_DVR_GetCurrentAudioCompress(login, audio);
                int readError = got ? 0 : api.NET_DVR_GetLastError();
                if (!got && device.voiceCodec==0 && readError!=23) throw new IllegalStateException(failure("读取对讲编码（通道 "+talkChannel+"）").getMessage()+"；请确认对讲通道号");
                int codec = device.voiceCodec==0 ? (got ? audio.byAudioEncType & 255 : 0) : device.voiceCodec;
                int rate = device.voiceCodec==0 ? (got ? audio.byAudioSamplingRate & 255 : 1) : 5;
                // Some fixed-codec IPC firmware reports AUTO/INVALID with the default
                // sampling marker although its talk path is the legacy G.722 path.
                boolean fixedG722Placeholder=device.voiceCodec==0&&rate==0&&(codec==0xfe||codec==0xff);
                g722 = codec == 0 || codec == 9 || fixedG722Placeholder;
                aac = codec == 7 || codec == 12;
                if ((!g722 && !aac && codec != 1 && codec != 2) || (g722 ? rate != 0 && rate != 1 : aac ? rate<0||rate>5 : rate != 0 && rate != 5))
                    throw new IllegalStateException("对讲通道 "+talkChannel+" 的音频格式不受支持（编码 "+codec+"，采样率标记 "+rate+"）");
                alaw = codec == 2;
                talkSampleRate=rate==2?32000:rate==3?48000:rate==4?44100:rate==5?8000:16000;
                if (g722) {
                    g722Encoder = sound().NET_DVR_InitG722Encoder();
                    if (g722Encoder == null || Pointer.nativeValue(g722Encoder) == -1L) { g722Encoder=null; throw failure("初始化 G.722 对讲编码器"); }
                } else if(aac) {
                    try{aacEncoder=new AndroidAacEncoder(talkSampleRate,32000);}catch(Exception error){throw new IllegalStateException("手机 AAC 对讲编码器初始化失败");}
                }
                if (stopped.get()) { releaseEncoders(); return; }
                voiceHandle = api.NET_DVR_StartVoiceCom_MR_V30(login, talkChannel, callback);
                if (voiceHandle < 0) { releaseEncoders(); throw failure("开启对讲（通道 "+talkChannel+"，"+(g722?"G.722":aac?"AAC-LC":alaw?"G.711 A-law":"G.711 μ-law")+"）"); }
                voice = this;
                if (stopped.get() || closed) stopNativeVoice();
            }
        }
        @Override public void send(short[] samples, int count) {
            synchronized (nativeLock) {
                if (closed || stopped.get() || voiceHandle < 0) throw new IllegalStateException("对讲已停止");
                byte[] data;
                if (g722) {
                    if (count != 640) throw new IllegalArgumentException("G.722 对讲需要每帧 640 个采样");
                    data = new byte[80];
                    if (sound().NET_DVR_EncodeG722Frame(g722Encoder, pcm16le(samples,count), data) == 0) throw failure("编码 G.722 对讲音频");
                } else if(aac) {
                    try{
                        for(byte[] frame:aacEncoder.encode(samples,count))if(!api.NET_DVR_VoiceComSendData(voiceHandle,frame,frame.length))throw failure("发送 AAC 对讲音频");
                    }catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException("AAC 对讲编码失败");}
                    return;
                } else {
                    data = new byte[count]; for (int i = 0; i < count; i++) data[i] = G711.encode(samples[i], alaw);
                }
                if (!api.NET_DVR_VoiceComSendData(voiceHandle, data, data.length)) throw failure("发送对讲音频");
            }
        }
        @Override public int sampleRate() { return g722 ? 16000 : aac ? talkSampleRate : 8000; }
        @Override public int frameSamples() { return g722 ? 640 : aac ? 1024 : 160; }
        private void releaseG722() { if (g722Encoder != null) { sound().NET_DVR_ReleaseG722Encoder(g722Encoder); g722Encoder=null; } }
        private void releaseEncoders(){releaseG722();if(aacEncoder!=null){aacEncoder.close();aacEncoder=null;}}
        private void stopNativeVoice() {
            if (voiceHandle >= 0) { if(!api.NET_DVR_StopVoiceCom(voiceHandle))post(failure("停止对讲").getMessage()); voiceHandle = -1; }
            releaseEncoders();
            if (voice == this) voice = null;
        }
        @Override public void close() {
            stopped.set(true);
            if (!worker.isShutdown()) try { worker.execute(() -> { synchronized (nativeLock) { stopNativeVoice(); } }); } catch (RejectedExecutionException ignored) { }
        }
        @Override public void teardown() { close(); }
    }
    static byte[] pcm16le(short[] samples,int count){
        byte[] pcm=new byte[count*2];for(int i=0;i<count;i++){pcm[i*2]=(byte)samples[i];pcm[i*2+1]=(byte)(samples[i]>>>8);}return pcm;
    }
    void pause(boolean paused) { submit(() -> { if (ready && playback) control(paused ? HCNetSDK.NET_DVR_PLAYPAUSE : HCNetSDK.NET_DVR_PLAYRESTART); }); }
    void speed(int times, Runnable success) { submit(() -> { if (ready && playback) { control(7); for (int value = 1; value < times; value *= 2) control(HCNetSDK.NET_DVR_PLAYFAST); main.post(() -> { if (!closed) success.run(); }); } }); }
    private void control(int code) { if (!api.NET_DVR_PlayBackControl_V40(handle, code, null, 0, null)) throw failure("回放控制"); }
    void record(String path, boolean start, RecordListener result) {
        if(closed){main.post(()->result.result(false,null,"预览会话已关闭，请重连"));return;}
        try { worker.execute(()->{synchronized(nativeLock){
            try {
                if(!ready||closed)throw new IllegalStateException("视频未连接，请等待画面加载");
                if(start){
                    if(recording)throw new IllegalStateException("当前窗口已经在录像");
                    java.io.File file=new java.io.File(path);java.io.File dir=file.getParentFile();
                    if(dir==null||(!dir.isDirectory()&&!dir.mkdirs()))throw new IllegalStateException("无法创建录像目录");
                    if(dir.getUsableSpace()<16*1024*1024)throw new IllegalStateException("剩余空间不足 16MB");
                    try(java.io.FileOutputStream check=new java.io.FileOutputStream(file)){}catch(java.io.IOException error){throw new IllegalStateException("录像目录不可写");}
                    boolean ok;
                    if(playback)ok=api.NET_DVR_PlayBackSaveData(handle,path);
                    else {
                        // Official Android demo uses the V30 entry point with explicit PS packaging.
                        try {ok=sound().NET_DVR_SaveRealData_V30(handle,1,path)!=0;}catch(UnsatisfiedLinkError missing){ok=api.NET_DVR_SaveRealData(handle,path);}
                        if(!ok&&api.NET_DVR_GetLastError()==23)ok=api.NET_DVR_SaveRealData(handle,path);
                    }
                    if(!ok){IllegalStateException error=failure("开始原始录像");if(file.length()==0)file.delete();throw error;}
                    recording=true;recordPath=path;recordListener=result;recordingFiles.add(path);
                    if(!playback){if(device.sdkStream==1)api.NET_DVR_MakeKeyFrameSub(login,channel);else api.NET_DVR_MakeKeyFrame(login,channel);}
                    main.post(()->result.result(true,path,null));
                }else stopRecording(result);
            }catch(LinkageError error){main.post(()->result.result(recording,recordPath,"原始录像接口加载失败，请确认 SDK 库完整"));}
            catch(RuntimeException error){boolean active=recording;String existing=recordPath;main.post(()->result.result(active,existing,error.getMessage()));}
        }});}catch(RejectedExecutionException stopped){main.post(()->result.result(false,null,"会话已关闭"));}
    }
    private void stopRecording(RecordListener result){
        if(!recording)throw new IllegalStateException("当前窗口未在录像");
        boolean ok=playback?api.NET_DVR_StopPlayBackSave(handle):api.NET_DVR_StopSaveRealData(handle);
        if(!ok)throw failure("停止原始录像");
        recording=false;String saved=recordPath;recordPath=null;recordListener=null;recordingFiles.remove(saved);
        java.io.File file=new java.io.File(saved);String error=null;
        if(file.length()<=40){file.delete();error="没有收到有效录像数据；请录制数秒后重试，或切换主码流";}
        String problem=error;main.post(()->result.result(false,saved,problem));
    }
    private void search(long begin, long end) {
        NET_DVR_FILECOND condition = new NET_DVR_FILECOND(); condition.lChannel = channel;
        condition.dwFileType = 0xff; condition.dwIsLocked = 0xff; condition.struStartTime = time(begin); condition.struStopTime = time(end);
        int finder = api.NET_DVR_FindFile_V30(login, condition);
        if (finder < 0) { post("回放已连接，录像索引查询失败"); return; }
        List<long[]> result = new ArrayList<>(); long deadline = System.nanoTime() + 10_000_000_000L;
        try {
            while (!closed && result.size() < 2000 && System.nanoTime() < deadline) {
                NET_DVR_FINDDATA_V30 entry = new NET_DVR_FINDDATA_V30(); int code = api.NET_DVR_FindNextFile_V30(finder, entry);
                if (code == HCNetSDK.NET_DVR_FILE_SUCCESS) result.add(new long[]{millis(entry.struStartTime), millis(entry.struStopTime)});
                else if (code == HCNetSDK.NET_DVR_ISFINDING) {
                    try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                } else break;
            }
            main.post(() -> { if (!closed) listener.records(result); });
        } finally { api.NET_DVR_FindClose_V30(finder); }
    }
    void close() {
        if (closed) return;
        closed = true; ready = false;
        if (voice != null) voice.stopped.set(true);
        worker.execute(() -> {
            synchronized (nativeLock) {
                if (api == null) return;
                try { stopNative(); } catch (Exception ignored) { }
                if (voice != null) voice.stopNativeVoice();
                synchronized (HikSession.class) {
                    if (soundOwner == this) { try { sound().NET_DVR_CloseSound(); } catch (LinkageError | RuntimeException ignored) { } soundOwner = null; }
                    ownsSound = false;
                }
                if (handle >= 0) {
                    if (recording) { RecordListener callback=recordListener;try{stopRecording(callback);}catch(RuntimeException|LinkageError error){recording=false;recordingFiles.remove(recordPath);main.post(()->callback.result(false,recordPath,"会话结束时录像停止未确认，请检查本地文件"));} }
                    if (playback) api.NET_DVR_StopPlayBack(handle); else api.NET_DVR_StopRealPlay(handle);
                    handle = -1;
                }
                if (login >= 0) { api.NET_DVR_Logout_V30(login); login = -1; }
            }
        });
        worker.shutdown();
    }
    private static NET_DVR_TIME time(long millis) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(millis); NET_DVR_TIME t = new NET_DVR_TIME();
        t.dwYear = c.get(Calendar.YEAR); t.dwMonth = c.get(Calendar.MONTH) + 1; t.dwDay = c.get(Calendar.DAY_OF_MONTH);
        t.dwHour = c.get(Calendar.HOUR_OF_DAY); t.dwMinute = c.get(Calendar.MINUTE); t.dwSecond = c.get(Calendar.SECOND); return t;
    }
    private static long millis(NET_DVR_TIME t) {
        Calendar c = Calendar.getInstance(); c.clear(); c.set(t.dwYear, t.dwMonth - 1, t.dwDay, t.dwHour, t.dwMinute, t.dwSecond); return c.getTimeInMillis();
    }
}
