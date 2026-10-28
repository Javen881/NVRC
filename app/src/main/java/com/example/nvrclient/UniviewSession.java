package com.example.nvrclient;

import com.sdk.NETDEV_DEVICE_INFO_S;
import com.sdk.NETDEV_PREVIEWINFO_S;
import com.sdk.NETDEV_AUDIO_SAMPLE_PARAM_S;
import com.sdk.NetDEVSDK;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lifecycle wrapper around Uniview's official Android NetDEV SDK. */
final class UniviewSession {
    interface Listener{void state(String message);void ready();}
    private static final Object INIT_LOCK=new Object();
    private static boolean initialized;
    private final Device device;private final UniviewRenderView view;private final Listener listener;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();private final AtomicBoolean closed=new AtomicBoolean();
    private volatile long user,play;private volatile boolean recording;
    UniviewSession(Device device,UniviewRenderView view,Listener listener){this.device=device;this.view=view;this.listener=listener;}
    void start(){worker.execute(()->{try{
        synchronized(INIT_LOCK){if(!initialized){if(NetDEVSDK.initialize()!=1)throw failure("播放器初始化");if(new NetDEVSDK().NETDEV_Init()!=1)throw failure("SDK 初始化");initialized=true;}}
        if(closed.get())return;NETDEV_DEVICE_INFO_S info=new NETDEV_DEVICE_INFO_S();user=NetDEVSDK.NETDEV_Login(device.host,device.sdkPort,device.user,device.password,info);
        if(user==0)throw failure("登录");if(closed.get())return;
        NETDEV_PREVIEWINFO_S preview=new NETDEV_PREVIEWINFO_S();preview.dwChannelID=device.sdkChannel;preview.dwStreamIndex=device.sdkStream;preview.dwLinkMode=1;
        play=NetDEVSDK.NETDEV_RealPlay(user,preview,view.window());if(play==0)throw failure("打开预览");
        if(!closed.get())listener.ready();
    }catch(Throwable e){if(!closed.get())listener.state(message(e));}});}
    boolean isReady(){return play!=0&&!closed.get();}
    void move(float pan,float tilt,float zoom){long handle=play;if(handle==0)return;int command=command(pan,tilt,zoom);if(NetDEVSDK.NETDEV_PTZControl(handle,command,5)!=1)throw failure("云台控制");}
    void stopMove(){long handle=play;if(handle!=0)NetDEVSDK.NETDEV_PTZControl(handle,NetDEVSDK.NETDEV_VOD_PTZ_CMD_E.NETDEV_PTZ_ALLSTOP,5);}
    void record(File file,boolean start){long handle=play;if(handle==0)throw new IllegalStateException("宇视预览尚未连接");int result=start?NetDEVSDK.NETDEV_SaveRealData(handle,file.getAbsolutePath(),0):NetDEVSDK.NETDEV_StopSaveRealData(handle);if(result!=1)throw failure(start?"开始录像":"停止录像");recording=start;}
    boolean recording(){return recording;}
    com.example.nvrclient.protocol.AudioChannel audioChannel(){return new com.example.nvrclient.protocol.AudioChannel(){
        private volatile long voice;
        public void open(){long login=user;if(login==0)throw new IllegalStateException("请先连接宇视实时预览");voice=NetDEVSDK.NETDEV_Android_StartInputVoiceSrv(login,device.sdkChannel);if(voice==0)throw failure("开启对讲");}
        public void send(short[] samples,int count){long handle=voice;if(handle==0)throw new IllegalStateException("宇视对讲尚未建立");byte[] pcm=new byte[count*2];for(int i=0;i<count;i++){pcm[i*2]=(byte)samples[i];pcm[i*2+1]=(byte)(samples[i]>>>8);}NETDEV_AUDIO_SAMPLE_PARAM_S param=new NETDEV_AUDIO_SAMPLE_PARAM_S();param.dwChannels=1;param.dwSampleRate=8000;param.enSampleFormat=NETDEV_AUDIO_SAMPLE_PARAM_S.NETDEV_AUDIO_SAMPLE_FORMAT_E.NETDEV_AUDIO_SAMPLE_FMT_S16;if(NetDEVSDK.NETDEV_InputVoiceData(handle,pcm,pcm.length,param)!=1)throw failure("发送对讲音频");}
        public void teardown(){long handle=voice;voice=0;if(handle!=0)NetDEVSDK.NETDEV_Android_StopInputVoiceSrv(handle);}
        public void close(){teardown();}
    };}
    void close(){if(!closed.compareAndSet(false,true))return;worker.execute(()->{long p=play;play=0;if(p!=0){if(recording)NetDEVSDK.NETDEV_StopSaveRealData(p);NetDEVSDK.NETDEV_StopRealPlay(p,view.window());}long u=user;user=0;if(u!=0)NetDEVSDK.NETDEV_Logout(u);});worker.shutdown();}
    private static int command(float pan,float tilt,float zoom){
        if(zoom>0)return NetDEVSDK.NETDEV_VOD_PTZ_CMD_E.NETDEV_PTZ_ZOOMTELE;if(zoom<0)return NetDEVSDK.NETDEV_VOD_PTZ_CMD_E.NETDEV_PTZ_ZOOMWIDE;
        if(pan<0&&tilt>0)return NetDEVSDK.NETDEV_VOD_PTZ_CMD_E.NETDEV_PTZ_LEFTUP;if(pan>0&&tilt>0)return NetDEVSDK.NETDEV_VOD_PTZ_CMD_E.NETDEV_PTZ_RIGHTUP;
        if(pan<0&&tilt<0)return NetDEVSDK.NETDEV_VOD_PTZ_CMD_E.NETDEV_PTZ_LEFTDOWN;if(pan>0&&tilt<0)return NetDEVSDK.NETDEV_VOD_PTZ_CMD_E.NETDEV_PTZ_RIGHTDOWN;
        if(pan<0)return NetDEVSDK.NETDEV_VOD_PTZ_CMD_E.NETDEV_PTZ_PANLEFT;if(pan>0)return NetDEVSDK.NETDEV_VOD_PTZ_CMD_E.NETDEV_PTZ_PANRIGHT;
        return tilt>0?NetDEVSDK.NETDEV_VOD_PTZ_CMD_E.NETDEV_PTZ_TILTUP:NetDEVSDK.NETDEV_VOD_PTZ_CMD_E.NETDEV_PTZ_TILTDOWN;
    }
    private static IllegalStateException failure(String operation){return new IllegalStateException(operation+"失败（宇视错误码 "+NetDEVSDK.NETDEV_GetLastError()+"）");}
    private static String message(Throwable error){String value=error.getMessage();return value==null?"宇视 SDK 连接失败":value;}
}
