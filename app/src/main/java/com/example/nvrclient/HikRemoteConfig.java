package com.example.nvrclient;

import com.hikvision.netsdk.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.function.*;

/** Remote parameters: read fresh, patch only user edits, write, then read back. */
final class HikRemoteConfig implements AutoCloseable {
    enum Section {
        INFO("设备信息"), IMAGE("图像"), OSD("画面文字"), MAIN("主码流"), SUB("子码流"), TALK("语音对讲"), TIME("设备时间"), NETWORK("网络信息");
        final String title;Section(String title){this.title=title;}
    }
    interface Bridge {
        int login(Device device,NET_DVR_DEVICEINFO_V30 info);
        boolean get(int login,int command,int channel,NET_DVR_CONFIG config);
        boolean set(int login,int command,int channel,NET_DVR_CONFIG config);
        int error();void logout(int login);
    }
    static final class NativeBridge implements Bridge {
        private HCNetSDK sdk;
        public int login(Device d,NET_DVR_DEVICEINFO_V30 info){sdk=HikSession.sdk();return sdk.NET_DVR_Login_V30(d.host,d.sdkPort,d.user,d.password,info);}
        public boolean get(int l,int c,int ch,NET_DVR_CONFIG data){return sdk.NET_DVR_GetDVRConfig(l,c,ch,data);}
        public boolean set(int l,int c,int ch,NET_DVR_CONFIG data){return sdk.NET_DVR_SetDVRConfig(l,c,ch,data);}
        public int error(){return sdk.NET_DVR_GetLastError();}
        public void logout(int l){sdk.NET_DVR_Logout_V30(l);}
    }
    static final class Field {
        final String id,label,value;final LinkedHashMap<String,String> options;final boolean numeric;final Consumer<String> setter;
        Field(String id,String label,String value,boolean numeric,LinkedHashMap<String,String> options,Consumer<String> setter){this.id=id;this.label=label;this.value=value;this.numeric=numeric;this.options=options;this.setter=setter;}
        boolean editable(){return setter!=null;}
    }
    static final class Snapshot {
        final Section section;final List<Field> fields=new ArrayList<>();final NET_DVR_CONFIG config;final int command,channel;
        Snapshot(Section section,NET_DVR_CONFIG config,int command,int channel){this.section=section;this.config=config;this.command=command;this.channel=channel;}
        Field field(String id){for(Field f:fields)if(f.id.equals(id))return f;throw new IllegalArgumentException("未知参数："+id);}
        boolean editable(){return command!=0;}
    }
    private static final Charset DEVICE_TEXT=Charset.forName("GB18030");
    private final Device device;private final Bridge bridge;private int login=-1,channel;private boolean closed;
    HikRemoteConfig(Device device){this(device,new NativeBridge());}
    HikRemoteConfig(Device device,Bridge bridge){this.device=device;this.bridge=bridge;}
    private void connect(){
        if(closed)throw new IllegalStateException("远程配置会话已关闭");if(login>=0)return;
        NET_DVR_DEVICEINFO_V30 info=new NET_DVR_DEVICEINFO_V30();login=bridge.login(device,info);if(login<0)throw failure("登录设备");
        channel=device.sdkChannel>0?device.sdkChannel:(info.byChanNum&255)>0?info.byStartChan&255:info.byStartDChan&255;
        if(channel<=0)throw new IllegalStateException("无法确定摄像机通道，请先设置 SDK 通道号");
    }
    private IllegalStateException failure(String action){int code=bridge.error();return new IllegalStateException(action+"失败（SDK "+code+"）："+(code==23?"设备不支持此配置项":code==2?"账号没有远程配置权限":code==1?"账号或密码错误":code==17?"设备不接受该参数或取值":code==7?"无法连接设备，请检查网络":"请检查设备能力、网络和参数范围"));}
    private <T extends NET_DVR_CONFIG>T get(int command,int ch,T config){if(!bridge.get(login,command,ch,config))throw failure("读取参数");return config;}
    Snapshot read(Section section){
        connect();Snapshot s;
        switch(section){
            case INFO:{
                NET_DVR_DEVICECFG_V40 c=new NET_DVR_DEVICECFG_V40();
                if(bridge.get(login,HCNetSDK.NET_DVR_GET_DEVICECFG_V40,0,c)){
                    s=new Snapshot(section,c,0,0);ro(s,"型号",decode(c.byDevTypeName));ro(s,"设备名称",decode(c.sDVRName));ro(s,"序列号",decode(c.sSerialNumber));
                    ro(s,"主控版本",version(c.dwSoftwareVersion)+" / "+date(c.dwSoftwareBuildDate));ro(s,"编码版本",version(c.dwDSPSoftwareVersion)+" / "+date(c.dwDSPSoftwareBuildDate));
                    ro(s,"通道数",c.byChanNum&255);ro(s,"硬盘数",c.byDiskNum&255);ro(s,"报警输入",c.byAlarmInPortNum&255);ro(s,"报警输出",c.byAlarmOutPortNum&255);
                }else{
                    int code=bridge.error();if(code!=23&&code!=17)throw failure("读取设备信息");
                    NET_DVR_DEVICECFG c30=get(HCNetSDK.NET_DVR_GET_DEVICECFG,0,new NET_DVR_DEVICECFG());s=new Snapshot(section,c30,0,0);
                    ro(s,"设备名称",decode(c30.sDVRName));ro(s,"序列号",decode(c30.sSerialNumber));ro(s,"设备类型编号",c30.byDVRType&255);
                    ro(s,"主控版本",version(c30.dwSoftwareVersion)+" / "+date(c30.dwSoftwareBuildDate));ro(s,"编码版本",version(c30.dwDSPSoftwareVersion)+" / "+date(c30.dwDSPSoftwareBuildDate));
                    ro(s,"通道数",(c30.byChanNum&255)+(c30.byIPChanNum&255));ro(s,"硬盘数",c30.byDiskNum&255);
                }
                ro(s,"当前配置通道",channel);return s;
            }
            case IMAGE:{
                NET_DVR_CAMERAPARAMCFG_EX c=get(HCNetSDK.NET_DVR_GET_CCDPARAMCFG_EX,channel,new NET_DVR_CAMERAPARAMCFG_EX());s=new Snapshot(section,c,HCNetSDK.NET_DVR_SET_CCDPARAMCFG_EX,channel);
                number(s,"brightness","亮度（0–100）",c.struVideoEffect.byBrightnessLevel&255,0,100,n->c.struVideoEffect.byBrightnessLevel=(byte)n);
                number(s,"contrast","对比度（0–100）",c.struVideoEffect.byContrastLevel&255,0,100,n->c.struVideoEffect.byContrastLevel=(byte)n);
                number(s,"saturation","饱和度（0–100）",c.struVideoEffect.bySaturationLevel&255,0,100,n->c.struVideoEffect.bySaturationLevel=(byte)n);
                number(s,"sharpness","锐度（0–100）",c.struVideoEffect.bySharpnessLevel&255,0,100,n->{c.struVideoEffect.bySharpnessLevel=(byte)n;c.struVideoEffect.byEnableFunc|=8;});
                choice(s,"mirror","镜像 / 翻转",c.byMirror&255,n->c.byMirror=(byte)n,0,"关闭",1,"左右镜像",2,"上下翻转",3,"上下左右翻转");
                choice(s,"daynight","日夜模式",c.struDayNight.byDayNightFilterType&255,n->c.struDayNight.byDayNightFilterType=(byte)n,0,"白天",1,"夜晚",2,"自动（光敏）",5,"自动（视频亮度）");
                choice(s,"frequency","电源频率",c.byPowerLineFrequencyMode&255,n->c.byPowerLineFrequencyMode=(byte)n,0,"50Hz",1,"60Hz");return s;
            }
            case OSD:{
                NET_DVR_PICCFG_V30 c=get(HCNetSDK.NET_DVR_GET_PICCFG_V30,channel,new NET_DVR_PICCFG_V30());s=new Snapshot(section,c,HCNetSDK.NET_DVR_SET_PICCFG_V30,channel);
                s.fields.add(new Field("name","摄像机通道名称",decode(c.sChanName),false,null,value->{byte[] bytes=value.getBytes(DEVICE_TEXT);if(bytes.length>=c.sChanName.length)throw new IllegalArgumentException("通道名称太长（最多 "+(c.sChanName.length-1)+" 字节）");Arrays.fill(c.sChanName,(byte)0);System.arraycopy(bytes,0,c.sChanName,0,bytes.length);}));
                choice(s,"showName","显示通道名称",c.dwShowChanName,n->c.dwShowChanName=n,0,"关闭",1,"开启");
                choice(s,"showTime","显示日期时间",c.dwShowOsd,n->c.dwShowOsd=n,0,"关闭",1,"开启");
                choice(s,"week","显示星期",c.byDispWeek&255,n->c.byDispWeek=(byte)n,0,"关闭",1,"开启");return s;
            }
            case MAIN:case SUB:{
                NET_DVR_COMPRESSIONCFG_V30 c=get(HCNetSDK.NET_DVR_GET_COMPRESSCFG_V30,channel,new NET_DVR_COMPRESSIONCFG_V30());s=new Snapshot(section,c,HCNetSDK.NET_DVR_SET_COMPRESSCFG_V30,channel);
                NET_DVR_COMPRESSION_INFO_V30 v=section==Section.MAIN?c.struNormHighRecordPara:c.struNetPara;
                choice(s,"stream","流类型",v.byStreamType&255,n->v.byStreamType=(byte)n,0,"仅视频",1,"视频和音频");
                choice(s,"videoCodec","视频编码",v.byVideoEncType&255,n->v.byVideoEncType=(byte)n,1,"H.264",10,"H.265");
                choice(s,"audioCodec","码流音频编码（与对讲编码可能不同）",v.byAudioEncType&255,n->v.byAudioEncType=(byte)n,0,"G.722",1,"G.711 μ-law",2,"G.711 A-law",7,"AAC",8,"PCM");
                choice(s,"resolution","分辨率",v.byResolution&255,n->v.byResolution=(byte)n,1,"352×288",3,"704×576 / D1",19,"1280×720",20,"1280×960",27,"1920×1080",30,"2048×1536",56,"2304×1296");
                choice(s,"bitrateType","码率控制",v.byBitrateType&255,n->v.byBitrateType=(byte)n,0,"变码率 VBR",1,"定码率 CBR");
                choice(s,"bitrate","最大码率",v.dwVideoBitrate,n->v.dwVideoBitrate=n,11,"256 kbps",15,"512 kbps",19,"1024 kbps",23,"2048 kbps",24,"3072 kbps",25,"4096 kbps",26,"8192 kbps");
                choice(s,"fps","帧率",v.dwVideoFrameRate,n->v.dwVideoFrameRate=n,0,"全帧率",10,"10 fps",14,"15 fps",13,"20 fps",31,"24 fps",17,"25 fps",18,"30 fps");
                number(s,"iframe","I 帧间隔",v.wIntervalFrameI,1,1000,n->v.wIntervalFrameI=n);return s;
            }
            case TALK:{
                NET_DVR_COMPRESSION_AUDIO c=new NET_DVR_COMPRESSION_AUDIO();int configChannel=0;
                if(!bridge.get(login,HCNetSDK.NET_DVR_GET_COMPRESSCFG_AUD,configChannel,c)){
                    configChannel=1;c=new NET_DVR_COMPRESSION_AUDIO();
                    if(!bridge.get(login,HCNetSDK.NET_DVR_GET_COMPRESSCFG_AUD,configChannel,c))throw failure("读取语音对讲参数");
                }
                final NET_DVR_COMPRESSION_AUDIO audio=c;
                // The Android 6.1.9.5 jar omits the public constant, while the
                // bundled SDK guide defines SET_COMPRESSCFG_AUD as command 1059.
                s=new Snapshot(section,audio,1059,configChannel);
                choice(s,"talkCodec","对讲编码",audio.byAudioEncType&255,n->audio.byAudioEncType=(byte)n,
                    0,"G.722 / 16kHz",1,"G.711 μ-law / 8kHz",2,"G.711 A-law / 8kHz");
                choice(s,"talkRate","对讲采样率",audio.byAudioSamplingRate&255,n->audio.byAudioSamplingRate=(byte)n,
                    0,"设备默认",1,"16kHz",5,"8kHz");
                ro(s,"提示","G.711 请配 8kHz；G.722 请配 16kHz。部分设备保存后必须重启才生效。");return s;
            }
            case TIME:{
                NET_DVR_TIME c=get(HCNetSDK.NET_DVR_GET_TIMECFG,0,new NET_DVR_TIME());s=new Snapshot(section,c,HCNetSDK.NET_DVR_SET_TIMECFG,0);
                String time=String.format(Locale.ROOT,"%04d-%02d-%02d %02d:%02d:%02d",c.dwYear,c.dwMonth,c.dwDay,c.dwHour,c.dwMinute,c.dwSecond);
                s.fields.add(new Field("time","设备本地时间（yyyy-MM-dd HH:mm:ss）",time,false,null,value->{java.time.LocalDateTime t=parseTime(value);c.dwYear=t.getYear();c.dwMonth=t.getMonthValue();c.dwDay=t.getDayOfMonth();c.dwHour=t.getHour();c.dwMinute=t.getMinute();c.dwSecond=t.getSecond();}));return s;
            }
            case NETWORK:{
                NET_DVR_NETCFG_V30 c=get(HCNetSDK.NET_DVR_GET_NETCFG_V30,0,new NET_DVR_NETCFG_V30());s=new Snapshot(section,c,0,0);
                ro(s,"DHCP",c.byUseDhcp==0?"关闭":"开启");for(int i=0;i<c.struEtherNet.length;i++){NET_DVR_ETHERNET_V30 e=c.struEtherNet[i];String ip=decode(e.struDVRIP.sIpV4);if(ip.isEmpty()||ip.equals("0.0.0.0"))continue;ro(s,"网卡 "+(i+1)+" IP",ip);ro(s,"子网掩码",decode(e.struDVRIPMask.sIpV4));ro(s,"设备端口",e.wDVRPort);}
                ro(s,"网关",decode(c.struGatewayIpAddr.sIpV4));ro(s,"DNS 1",decode(c.struDnsServer1IpAddr.sIpV4));ro(s,"DNS 2",decode(c.struDnsServer2IpAddr.sIpV4));ro(s,"HTTP 端口",c.wHttpPortNo);return s;
            }
            default:throw new IllegalArgumentException("未知配置页");
        }
    }
    Snapshot save(Section section,Map<String,String> changes){
        if(changes.isEmpty())return read(section);
        Snapshot fresh=read(section);if(!fresh.editable())throw new IllegalArgumentException("当前页只读");
        for(Map.Entry<String,String> change:changes.entrySet()){Field field=fresh.field(change.getKey());if(!field.editable())throw new IllegalArgumentException("参数不可修改");field.setter.accept(change.getValue());}
        if(section==Section.TALK){
            NET_DVR_COMPRESSION_AUDIO audio=(NET_DVR_COMPRESSION_AUDIO)fresh.config;int codec=audio.byAudioEncType&255,rate=audio.byAudioSamplingRate&255;
            if((codec==1||codec==2)&&rate!=0&&rate!=5)throw new IllegalArgumentException("G.711 对讲必须使用 8kHz（或设备默认）采样率");
            if(codec==0&&rate!=0&&rate!=1)throw new IllegalArgumentException("G.722 对讲必须使用 16kHz（或设备默认）采样率");
        }
        if(!bridge.set(login,fresh.command,fresh.channel,fresh.config))throw failure("保存到设备");
        try{
            Snapshot actual=read(section);
            // Some firmware acknowledges SetDVRConfig before the new value is
            // visible to GetDVRConfig. Retry briefly before reporting a mismatch.
            for(int attempt=0;attempt<2&&!matches(changes,actual);attempt++){
                try{Thread.sleep(attempt==0?700:1300);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}
                actual=read(section);
            }
            return actual;
        }catch(RuntimeException error){throw new IllegalStateException("设备已接受写入，但回读校验失败；请重新读取后确认，勿重复提交。"+error.getMessage());}
    }
    private static boolean matches(Map<String,String> changes,Snapshot actual){for(Map.Entry<String,String> change:changes.entrySet())if(!change.getValue().equals(actual.field(change.getKey()).value))return false;return true;}
    static java.time.LocalDateTime parseTime(String value){try{return java.time.LocalDateTime.parse(value,java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(java.time.format.ResolverStyle.STRICT));}catch(java.time.DateTimeException error){throw new IllegalArgumentException("请输入有效日期时间，例如 2026-10-27 12:30:00");}}
    private static void ro(Snapshot s,String label,Object value){s.fields.add(new Field(label,label,String.valueOf(value),false,null,null));}
    private static void number(Snapshot s,String id,String label,int value,int min,int max,IntConsumer setter){s.fields.add(new Field(id,label,Integer.toString(value),true,null,text->{int n;try{n=Integer.parseInt(text);}catch(NumberFormatException e){throw new IllegalArgumentException(label+" 必须是整数");}if(n<min||n>max)throw new IllegalArgumentException(label+" 范围为 "+min+"–"+max);setter.accept(n);}));}
    private static void choice(Snapshot s,String id,String label,int current,IntConsumer setter,Object...options){LinkedHashMap<String,String> values=new LinkedHashMap<>();for(int i=0;i<options.length;i+=2)values.put(options[i].toString(),options[i+1].toString());String value=Integer.toString(current);if(!values.containsKey(value))values.put(value,"保持设备当前值（"+value+"）");s.fields.add(new Field(id,label,value,false,values,text->{if(!values.containsKey(text))throw new IllegalArgumentException("不支持的选项");setter.accept(Integer.parseInt(text));}));}
    static String decode(byte[] bytes){int n=0;while(n<bytes.length&&bytes[n]!=0)n++;return new String(bytes,0,n,DEVICE_TEXT);}
    private static String version(int v){return String.format(Locale.ROOT,"V%d.%d.%d",v>>>24,(v>>>16)&255,v&65535);}
    private static String date(int d){return String.format(Locale.ROOT,"%04d-%02d-%02d",((d>>>16)<100?2000+(d>>>16):d>>>16),(d>>>8)&255,d&255);}
    @Override public void close(){closed=true;if(login>=0){bridge.logout(login);login=-1;}}
}
