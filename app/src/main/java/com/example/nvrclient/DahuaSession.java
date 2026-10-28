package com.example.nvrclient;

import android.view.SurfaceHolder;
import com.company.NetSDK.CB_fRealDataCallBackEx;
import com.company.NetSDK.INetSDK;
import com.company.NetSDK.NET_IN_LOGIN_WITH_HIGHLEVEL_SECURITY;
import com.company.NetSDK.NET_IN_REALPLAY_BY_DATA_TYPE;
import com.company.NetSDK.NET_OUT_LOGIN_WITH_HIGHLEVEL_SECURITY;
import com.company.NetSDK.NET_OUT_REALPLAY_BY_DATA_TYPE;
import com.company.NetSDK.SDK_EXTPTZ_ControlType;
import com.company.NetSDK.SDK_PTZ_ControlType;
import com.company.NetSDK.SDK_TALK_CODING_TYPE;
import com.company.NetSDK.SDKDEV_TALKDECODE_INFO;
import com.company.NetSDK.EM_USEDEV_MODE;
import com.company.PlaySDK.IPlaySDK;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native Dahua NetSDK session used for internal device testing. */
final class DahuaSession {
    interface Listener{void state(String message);void ready();}
    private static final Object INIT_LOCK=new Object();private static boolean initialized;
    private final Device device;private final SurfaceHolder surface;private final Listener listener;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();private final AtomicBoolean closed=new AtomicBoolean();
    private volatile long login,real;private volatile int playPort=-1;private volatile boolean recording;
    private CB_fRealDataCallBackEx callback;
    DahuaSession(Device device,SurfaceHolder surface,Listener listener){this.device=device;this.surface=surface;this.listener=listener;}
    void start(){worker.execute(()->{try{
        synchronized(INIT_LOCK){if(!initialized){System.loadLibrary("netsdk");System.loadLibrary("configsdk");System.loadLibrary("play");System.loadLibrary("avnetsdk");if(!INetSDK.Init(null))throw failure("SDK 初始化");INetSDK.SetAutoReconnect(null);initialized=true;}}
        NET_IN_LOGIN_WITH_HIGHLEVEL_SECURITY in=new NET_IN_LOGIN_WITH_HIGHLEVEL_SECURITY();copy(device.host,in.szIP);copy(device.user,in.szUserName);copy(device.password,in.szPassword);in.nPort=device.sdkPort;
        NET_OUT_LOGIN_WITH_HIGHLEVEL_SECURITY out=new NET_OUT_LOGIN_WITH_HIGHLEVEL_SECURITY();login=INetSDK.LoginWithHighLevelSecurity(in,out);if(login==0)throw failure("登录");if(closed.get())return;
        playPort=IPlaySDK.PLAYGetFreePort();if(playPort<0||IPlaySDK.PLAYOpenStream(playPort,null,0,2*1024*1024)==0)throw new IllegalStateException("大华播放库初始化失败");
        if(IPlaySDK.PLAYPlay(playPort,surface.getSurface())==0)throw new IllegalStateException("大华视频画面初始化失败");final int port=playPort;
        callback=(handle,type,data,size,user)->{if(type==0&&data!=null&&size>0)IPlaySDK.PLAYInputData(port,data,size);};
        NET_IN_REALPLAY_BY_DATA_TYPE preview=new NET_IN_REALPLAY_BY_DATA_TYPE();preview.nChannelID=device.sdkChannel;preview.rType=device.sdkStream==0?com.company.NetSDK.SDK_RealPlayType.SDK_RType_Realplay_0:com.company.NetSDK.SDK_RealPlayType.SDK_RType_Realplay_1;preview.emDataType=0;preview.cbRealData=callback;
        real=INetSDK.RealPlayByDataType(login,preview,new NET_OUT_REALPLAY_BY_DATA_TYPE(),5000);if(real==0)throw failure("打开预览");if(!closed.get())listener.ready();
    }catch(Throwable error){if(!closed.get())listener.state(error.getMessage()==null?"大华 SDK 连接失败":error.getMessage());}});}
    boolean isReady(){return real!=0&&!closed.get();}
    void mute(boolean mute){int port=playPort;if(port>=0){if(mute)IPlaySDK.PLAYStopSoundShare(port);else IPlaySDK.PLAYPlaySoundShare(port);}}
    void move(float pan,float tilt,float zoom){if(login==0)return;int speed=5;boolean ok;
        if(pan!=0&&tilt!=0){int cmd=pan<0?(tilt>0?SDK_EXTPTZ_ControlType.SDK_EXTPTZ_LEFTTOP:SDK_EXTPTZ_ControlType.SDK_EXTPTZ_LEFTDOWN):(tilt>0?SDK_EXTPTZ_ControlType.SDK_EXTPTZ_RIGHTTOP:SDK_EXTPTZ_ControlType.SDK_EXTPTZ_RIGHTDOWN);ok=INetSDK.SDKPTZControlEx(login,device.sdkChannel,cmd,speed,speed,0,false);}
        else{int cmd=zoom>0?SDK_PTZ_ControlType.SDK_PTZ_ZOOM_ADD_CONTROL:zoom<0?SDK_PTZ_ControlType.SDK_PTZ_ZOOM_DEC_CONTROL:pan<0?SDK_PTZ_ControlType.SDK_PTZ_LEFT_CONTROL:pan>0?SDK_PTZ_ControlType.SDK_PTZ_RIGHT_CONTROL:tilt>0?SDK_PTZ_ControlType.SDK_PTZ_UP_CONTROL:SDK_PTZ_ControlType.SDK_PTZ_DOWN_CONTROL;ok=INetSDK.PTZControl(login,cmd,0,speed,false);}if(!ok)throw failure("云台控制");}
    void stopMove(){long handle=login;if(handle!=0)for(int cmd=0;cmd<=5;cmd++)INetSDK.PTZControl(handle,cmd,0,5,true);}
    void record(File file,boolean start){long handle=real;if(handle==0)throw new IllegalStateException("大华预览尚未连接");boolean ok=start?INetSDK.SaveRealData(handle,file.getAbsolutePath()):INetSDK.StopSaveRealData(handle);if(!ok)throw failure(start?"开始录像":"停止录像");recording=start;}
    com.example.nvrclient.protocol.AudioChannel audioChannel(){return new com.example.nvrclient.protocol.AudioChannel(){
        private volatile long talk;
        public void open(){long handle=login;if(handle==0)throw new IllegalStateException("请先连接大华实时预览");SDKDEV_TALKDECODE_INFO format=new SDKDEV_TALKDECODE_INFO();format.encodeType=SDK_TALK_CODING_TYPE.SDK_TALK_G711a;format.nAudioBit=16;format.dwSampleRate=8000;format.nPacketPeriod=20;
            if(!INetSDK.SetDeviceMode(handle,EM_USEDEV_MODE.SDK_TALK_ENCODE_TYPE,format))throw failure("设置对讲编码");INetSDK.SetDeviceMode(handle,EM_USEDEV_MODE.SDK_TALK_CLIENT_MODE,Boolean.TRUE);INetSDK.SetDeviceMode(handle,EM_USEDEV_MODE.SDK_TALK_TALK_CHANNEL,Integer.valueOf(device.sdkChannel));
            talk=INetSDK.StartTalkEx(handle,(voice,data,flag)->{});if(talk==0)throw failure("开启对讲");}
        public void send(short[] samples,int count){long handle=talk;if(handle==0)throw new IllegalStateException("大华对讲尚未建立");byte[] pcm=new byte[count*2];for(int i=0;i<count;i++){pcm[i*2]=(byte)samples[i];pcm[i*2+1]=(byte)(samples[i]>>>8);}byte[] encoded=new byte[count];int length=INetSDK.g711aEncode(pcm,encoded,pcm.length);if(length<=0)throw failure("编码对讲音频");if(length!=encoded.length)encoded=java.util.Arrays.copyOf(encoded,length);if(INetSDK.TalkSendData(handle,encoded)<=0)throw failure("发送对讲音频");}
        public void teardown(){long handle=talk;talk=0;if(handle!=0)INetSDK.StopTalkEx(handle);}
        public void close(){teardown();}
    };}
    void close(){if(!closed.compareAndSet(false,true))return;worker.execute(()->{long r=real;real=0;if(r!=0){if(recording)INetSDK.StopSaveRealData(r);INetSDK.StopRealPlayEx(r);}int p=playPort;playPort=-1;if(p>=0){IPlaySDK.PLAYStop(p);IPlaySDK.PLAYCloseStream(p);IPlaySDK.PLAYReleasePort(p);}long l=login;login=0;if(l!=0)INetSDK.Logout(l);callback=null;});worker.shutdown();}
    private static void copy(String value,byte[] target){byte[] bytes=value.getBytes(StandardCharsets.UTF_8);System.arraycopy(bytes,0,target,0,Math.min(bytes.length,target.length-1));}
    private static IllegalStateException failure(String operation){return new IllegalStateException(operation+"失败（大华错误码 0x"+Integer.toHexString(INetSDK.GetLastError())+"）");}
}
