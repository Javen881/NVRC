package com.example.nvrclient;

import com.hikvision.netsdk.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class HikRemoteConfigTest {
    static final class FakeBridge implements HikRemoteConfig.Bridge {
        int reads,writes,logout,lastCommand,lastChannel;boolean supported=true,accept=true,failReadback;
        NET_DVR_CONFIG saved;int brightness=40,talkCodec=1,talkRate=5;
        public int login(Device d,NET_DVR_DEVICEINFO_V30 info){info.byChanNum=1;info.byStartChan=1;return 9;}
        public boolean get(int l,int c,int ch,NET_DVR_CONFIG target){reads++;if(!supported||(failReadback&&writes>0))return false;
            if(target instanceof NET_DVR_CAMERAPARAMCFG_EX){NET_DVR_CAMERAPARAMCFG_EX p=(NET_DVR_CAMERAPARAMCFG_EX)target;p.struVideoEffect.byBrightnessLevel=(byte)brightness;p.struVideoEffect.byContrastLevel=(byte)(reads>1?66:50);p.byMirror=2;p.byRes2[0]=73;}
            if(target instanceof NET_DVR_COMPRESSIONCFG_V30){NET_DVR_COMPRESSIONCFG_V30 p=(NET_DVR_COMPRESSIONCFG_V30)target;p.struNormHighRecordPara.byVideoEncType=1;p.struNetPara.byVideoEncType=10;p.struEventRecordPara.dwVideoBitrate=777;}
            if(target instanceof NET_DVR_COMPRESSION_AUDIO){NET_DVR_COMPRESSION_AUDIO p=(NET_DVR_COMPRESSION_AUDIO)target;p.byAudioEncType=(byte)talkCodec;p.byAudioSamplingRate=(byte)talkRate;}
            return true;}
        public boolean set(int l,int c,int ch,NET_DVR_CONFIG config){writes++;lastCommand=c;lastChannel=ch;saved=config;if(config instanceof NET_DVR_CAMERAPARAMCFG_EX)brightness=((NET_DVR_CAMERAPARAMCFG_EX)config).struVideoEffect.byBrightnessLevel&255;if(config instanceof NET_DVR_COMPRESSION_AUDIO){talkCodec=((NET_DVR_COMPRESSION_AUDIO)config).byAudioEncType&255;talkRate=((NET_DVR_COMPRESSION_AUDIO)config).byAudioSamplingRate&255;}return accept;}
        public int error(){return supported?2:23;}public void logout(int l){logout++;}
    }
    @Test public void imagePatchReadsFreshPreservesOtherParametersAndReadsBack(){
        FakeBridge bridge=new FakeBridge();Device device=new Device();device.sdkChannel=4;HikRemoteConfig client=new HikRemoteConfig(device,bridge);
        assertEquals("40",client.read(HikRemoteConfig.Section.IMAGE).field("brightness").value);
        HikRemoteConfig.Snapshot result=client.save(HikRemoteConfig.Section.IMAGE,Collections.singletonMap("brightness","61"));
        assertEquals(3,bridge.reads);assertEquals(1,bridge.writes);assertEquals(4,bridge.lastChannel);assertEquals(HCNetSDK.NET_DVR_SET_CCDPARAMCFG_EX,bridge.lastCommand);
        NET_DVR_CAMERAPARAMCFG_EX written=(NET_DVR_CAMERAPARAMCFG_EX)bridge.saved;
        assertEquals(66,written.struVideoEffect.byContrastLevel&255);assertEquals(2,written.byMirror);assertEquals(73,written.byRes2[0]);assertEquals("61",result.field("brightness").value);
        client.close();assertEquals(1,bridge.logout);
    }
    @Test public void invalidValueAndUnsupportedParameterNeverWrite(){
        FakeBridge bridge=new FakeBridge();HikRemoteConfig client=new HikRemoteConfig(new Device(),bridge);
        assertThrows(IllegalArgumentException.class,()->client.save(HikRemoteConfig.Section.IMAGE,Collections.singletonMap("brightness","101")));assertEquals(0,bridge.writes);
        assertThrows(IllegalArgumentException.class,()->client.save(HikRemoteConfig.Section.IMAGE,Collections.singletonMap("wrong","1")));assertEquals(0,bridge.writes);
        bridge.supported=false;assertTrue(assertThrows(IllegalStateException.class,()->client.read(HikRemoteConfig.Section.IMAGE)).getMessage().contains("SDK 23"));assertEquals(0,bridge.writes);
        client.close();assertThrows(IllegalStateException.class,()->client.read(HikRemoteConfig.Section.IMAGE));
    }
    @Test public void mainStreamChangeDoesNotOverwriteSubstreamOrEventSettings(){
        FakeBridge bridge=new FakeBridge();HikRemoteConfig client=new HikRemoteConfig(new Device(),bridge);
        client.save(HikRemoteConfig.Section.MAIN,Collections.singletonMap("videoCodec","10"));NET_DVR_COMPRESSIONCFG_V30 c=(NET_DVR_COMPRESSIONCFG_V30)bridge.saved;
        assertEquals(10,c.struNormHighRecordPara.byVideoEncType);assertEquals(10,c.struNetPara.byVideoEncType);assertEquals(777,c.struEventRecordPara.dwVideoBitrate);client.close();
    }
    @Test public void writeFailureAndReadbackFailureAreDistinguished(){
        FakeBridge bridge=new FakeBridge();HikRemoteConfig client=new HikRemoteConfig(new Device(),bridge);bridge.accept=false;
        assertTrue(assertThrows(IllegalStateException.class,()->client.save(HikRemoteConfig.Section.IMAGE,Collections.singletonMap("brightness","55"))).getMessage().contains("保存到设备失败"));
        bridge.accept=true;bridge.writes=0;bridge.failReadback=true;
        assertTrue(assertThrows(IllegalStateException.class,()->client.save(HikRemoteConfig.Section.IMAGE,Collections.singletonMap("brightness","55"))).getMessage().contains("设备已接受写入，但回读校验失败"));client.close();
    }
    @Test public void invalidDateAndReadOnlyPageCannotBeWritten(){
        assertThrows(IllegalArgumentException.class,()->HikRemoteConfig.parseTime("2026-02-30 12:00:00"));assertEquals(29,HikRemoteConfig.parseTime("2024-02-29 12:00:00").getDayOfMonth());
        FakeBridge bridge=new FakeBridge();HikRemoteConfig client=new HikRemoteConfig(new Device(),bridge);
        assertThrows(IllegalArgumentException.class,()->client.save(HikRemoteConfig.Section.NETWORK,Collections.singletonMap("DHCP","1")));assertEquals(0,bridge.writes);client.close();
    }
    @Test public void talkCodecUsesDedicatedCommandAndValidatesSampleRate(){
        FakeBridge bridge=new FakeBridge();HikRemoteConfig client=new HikRemoteConfig(new Device(),bridge);
        Map<String,String> changes=new LinkedHashMap<>();changes.put("talkCodec","2");changes.put("talkRate","5");
        HikRemoteConfig.Snapshot result=client.save(HikRemoteConfig.Section.TALK,changes);
        assertEquals(1059,bridge.lastCommand);assertEquals("2",result.field("talkCodec").value);assertEquals("5",result.field("talkRate").value);
        changes.put("talkRate","1");assertThrows(IllegalArgumentException.class,()->client.save(HikRemoteConfig.Section.TALK,changes));client.close();
    }
}
