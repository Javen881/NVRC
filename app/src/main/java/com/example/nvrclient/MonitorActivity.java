package com.example.nvrclient;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;
import com.example.nvrclient.protocol.OnvifClient;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Reference-inspired live and remote playback workspaces, sharing the same selected pane. */
public final class MonitorActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService ptz = Executors.newSingleThreadScheduledExecutor();
    private final Slot[] slots = new Slot[16];
    private List<Device> devices = new ArrayList<>();
    private LibVLC vlc;
    private LinearLayout root, stage, grid, panel, toolbar, splits, headerRow, subRow, tabs;
    private String[] restoringIds;
    private TextView title, state, streamButton;
    private MonitorWidgets.Timeline timeline;
    private MonitorWidgets.Action mic, sound, record, pause;
    private TalkSession talk;
    private final Runnable recordingStatus=new Runnable(){public void run(){
        if(!foreground)return;
        if(record!=null){record.active(ScreenRecordService.running||slots[selected].recording);record.label(ScreenRecordService.running?"停止录屏":slots[selected].recording?"停止录像":"录屏");}
        ui.postDelayed(this,500);
    }};
    private ScheduledFuture<?> moving;
    private Slot movingSlot;
    private int layout = 1, selected;
    private boolean replay, foreground, fullscreen;
    private long chosenTime = System.currentTimeMillis()-3600000;
    private int initialDevice = -1;
    private volatile int movementRevision;
    private final int red=MonitorWidgets.RED, ink=MonitorWidgets.INK;
    private static final class Slot {
        Device device; FrameLayout tile; View border; TextView fullscreenButton; TextView label, hint; SurfaceView surface; VLCVideoLayout video; UniviewRenderView univiewView;
        HikSession hik; DahuaSession dahua; UniviewSession uniview; MediaPlayer player; int revision; boolean muted=true, paused, recording, recordBusy; int speed=1;
        long time; String nativeRecordPath; List<long[]> ranges = new ArrayList<>();
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        for(int i=0;i<slots.length;i++)slots[i]=new Slot();
        replay=getIntent().getBooleanExtra("replay",false); layout=replay?4:1; initialDevice=getIntent().getIntExtra("device",-1);
        if(saved!=null){fullscreen=saved.getBoolean("fullscreen");replay=saved.getBoolean("replay");layout=saved.getInt("layout",1);selected=saved.getInt("selected");chosenTime=saved.getLong("time",chosenTime);restoringIds=saved.getStringArray("devices");initialDevice=-1;long[] times=saved.getLongArray("slotTimes");if(times!=null)for(int i=0;i<Math.min(16,times.length);i++)slots[i].time=times[i];}
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::back);
        build();
    }
    private void build() {
        stopControls(); for(Slot s:slots)release(s);
        root=column();root.setBackgroundColor(MonitorWidgets.BG);
        if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(b.left,b.top,b.right,b.bottom);return insets;});
        else root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        setContentView(root);
        LinearLayout header=row();headerRow=header;header.setGravity(Gravity.CENTER_VERTICAL);header.setBackgroundColor(red);root.addView(header,new LinearLayout.LayoutParams(-1,dp(56)));
        TextView menu=headerButton("☰","菜单");header.addView(menu,new LinearLayout.LayoutParams(dp(56),-1));menu.setOnClickListener(v->menu());
        title=text(replay?"远程回放":"实时预览",20,Color.WHITE);title.setGravity(Gravity.CENTER);header.addView(title,new LinearLayout.LayoutParams(0,-1,1));
        TextView add=headerButton("⊞","选择设备");header.addView(add,new LinearLayout.LayoutParams(dp(56),-1));add.setOnClickListener(v->choose());
        LinearLayout sub=row();subRow=sub;sub.setGravity(Gravity.CENTER_VERTICAL);sub.setPadding(dp(16),0,dp(16),0);root.addView(sub,new LinearLayout.LayoutParams(-1,dp(42)));
        TextView mode=text(replay?"设备录像":"滑动画面控制云台",13,ink);sub.addView(mode,new LinearLayout.LayoutParams(0,-2,1));
        streamButton=text("码流 ▾",13,red);streamButton.setPadding(dp(8),0,dp(12),0);streamButton.setGravity(Gravity.CENTER);streamButton.setContentDescription("选择预览码流");sub.addView(streamButton,new LinearLayout.LayoutParams(-2,-1));streamButton.setVisibility(replay?View.GONE:View.VISIBLE);streamButton.setOnClickListener(v->chooseStream());
        TextView deviceButton=text("远程配置  ›",13,red);sub.addView(deviceButton);deviceButton.setOnClickListener(v->{Device device=slots[selected].device;if(device==null){manage();return;}if(!device.hikvision()){toast("远程参数配置目前支持海康 SDK 设备");return;}stopControls();startActivity(new Intent(this,RemoteConfigActivity.class).putExtra("deviceId",device.id));});
        stage=column();stage.setGravity(Gravity.CENTER);root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        grid=column();stage.addView(grid,new LinearLayout.LayoutParams(-1,dp(260)));
        stage.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            int height=fullscreen?b-t:Math.min(b-t,Math.round((r-l)*.92f));
            if(height>0&&grid.getLayoutParams().height!=height){grid.getLayoutParams().height=height;grid.requestLayout();}
        });
        int across=(int)Math.sqrt(layout);selected=Math.min(selected,layout-1);
        for(int y=0;y<across;y++) {
            LinearLayout line=row();grid.addView(line,new LinearLayout.LayoutParams(-1,0,1));
            for(int x=0;x<across;x++){int index=y*across+x;Slot s=slots[index];s.tile=new FrameLayout(this);line.addView(s.tile,new LinearLayout.LayoutParams(0,-1,1));makeTile(index);}
        }
        state=text("点击画面添加设备",12,0xff8a8e96);state.setGravity(Gravity.CENTER);root.addView(state,new LinearLayout.LayoutParams(-1,dp(30)));
        panel=column();panel.setPadding(dp(16),dp(4),dp(16),dp(4));panel.setBackgroundColor(Color.WHITE);panel.setVisibility(View.GONE);root.addView(panel);
        TextView ptzTitle=text("云台控制 · 按住移动，松手停止",12,ink);panel.addView(ptzTitle);
        LinearLayout top=row();panel.addView(top);move(top,"↖",-.5f,.5f,0);move(top,"↑",0,.5f,0);move(top,"↗",.5f,.5f,0);
        LinearLayout middle=row();panel.addView(middle);move(middle,"←",-.5f,0,0);MonitorWidgets.Action stop=action(middle,"close","停止",this::stopMove);stop.getLayoutParams().height=dp(40);move(middle,"→",.5f,0,0);
        LinearLayout bottom=row();panel.addView(bottom);move(bottom,"↙",-.5f,-.5f,0);move(bottom,"↓",0,-.5f,0);move(bottom,"↘",.5f,-.5f,0);
        LinearLayout zoom=row();panel.addView(zoom);move(zoom,"−",0,0,-.5f);move(zoom,"+",0,0,.5f);
        if(replay){
            timeline=new MonitorWidgets.Timeline(this);timeline.center=chosenTime;timeline.listener=time->{chosenTime=time;Slot s=slots[selected];s.time=time;restart(s);};
            root.addView(timeline,new LinearLayout.LayoutParams(-1,dp(88)));
        }else timeline=null;
        splits=row();splits.setGravity(Gravity.CENTER_VERTICAL);splits.setBackgroundColor(Color.WHITE);root.addView(splits,new LinearLayout.LayoutParams(-1,dp(replay?40:56)));
        for(int count:new int[]{1,4,9,16}){
            TextView item=text(Integer.toString(count),18,count==layout?red:ink);item.setGravity(Gravity.CENTER);item.setContentDescription(count+"画面");
            splits.addView(item,new LinearLayout.LayoutParams(0,-1,1));item.setOnClickListener(v->{layout=count;build();});
        }
        toolbar=row();toolbar.setBackgroundColor(0xffeceef1);root.addView(toolbar,new LinearLayout.LayoutParams(-1,dp(64)));
        action(toolbar,"camera","截屏",this::capture);
        record=action(toolbar,"record","录屏",this::recordOptions);
        if(replay){
            pause=action(toolbar,"pause","暂停/继续",()->{Slot s=slots[selected];if(s.hik!=null){s.paused=!s.paused;s.hik.pause(s.paused);pause.active(s.paused);}});
            action(toolbar,"speed","快进",()->new AlertDialog.Builder(this).setTitle("快进速度").setItems(new String[]{"1× 正常播放","2× 快进","4× 快进","8× 快进"},(d,w)->{Slot s=slots[selected];if(s.hik!=null&&s.hik.isReady()){int speed=1<<w;int revision=s.revision;s.hik.speed(speed,()->{if(s.revision==revision){s.speed=speed;if(s==slots[selected])state.setText("回放速度 "+speed+"×");}});}else toast("请先连接海康录像回放");}).show());
            action(toolbar,"date","日期",this::date);
            mic=null;sound=null;
        }else{
            action(toolbar,"ptz","云台",()->{stopControls();panel.setVisibility(panel.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);});
            mic=action(toolbar,"mic","对讲",()->{if(talk==null)startTalk();else stopTalk();});
            sound=action(toolbar,"sound","声音",()->{Slot s=slots[selected];s.muted=!s.muted;volume(s);sound.active(!s.muted);});
        }
        action(toolbar,"close","关闭",()->{stopControls();Slot s=slots[selected];release(s);s.device=null;makeTile(selected);updateSelected();});
        grid.setOnLongClickListener(v->{toggleFullscreen();return true;});
        tabs=NavigationTabs.create(this,replay?1:0,index->{if(index==2)manage();else switchMode(index==1);});root.addView(tabs);
        updateSelected();applyFullscreen();if(foreground)for(int i=0;i<layout;i++)start(slots[i]);
    }
    private void makeTile(int index) {
        Slot s=slots[index];s.tile.removeAllViews();s.tile.setBackgroundColor(index%2==0?0xff353638:0xff414245);
        s.tile.setPadding(dp(1),dp(1),dp(1),dp(1));s.surface=null;s.video=null;s.univiewView=null;
        if(s.device!=null && (s.device.nativeSdk()||!replay)) {
            if(s.device.hikvision()||s.device.dahua()){
                s.surface=new SurfaceView(this);s.tile.addView(s.surface,new FrameLayout.LayoutParams(-1,-1));
                SurfaceView surface=s.surface;
                surface.getHolder().addCallback(new SurfaceHolder.Callback(){
                    public void surfaceCreated(SurfaceHolder holder){if(s.surface==surface)start(s);}
                    public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){if(s.surface==surface&&s.hik!=null)s.hik.resize(holder);}
                    public void surfaceDestroyed(SurfaceHolder holder){if(s.surface==surface){if(index==selected)stopControls();release(s);}}
                });
            }else if(s.device.uniview()){
                s.univiewView=new UniviewRenderView(this,index+1);s.tile.addView(s.univiewView,new FrameLayout.LayoutParams(-1,-1));
            }else{s.video=new VLCVideoLayout(this);s.tile.addView(s.video,new FrameLayout.LayoutParams(-1,-1));}
        }
        s.hint=text(s.device==null?"⊕":"连接中…",s.device==null?36:12,0xffc7c9cd);s.hint.setGravity(Gravity.CENTER);s.tile.addView(s.hint,new FrameLayout.LayoutParams(-1,-1));
        View tap=new View(this);s.tile.addView(tap,new FrameLayout.LayoutParams(-1,-1));
        tap.setOnClickListener(v->{if(selected!=index)stopControls();selected=index;updateSelected();if(s.device==null)choose();});
        tap.setOnLongClickListener(v->{selected=index;stopControls();choose();return true;});
        if(!replay)tap.setOnTouchListener(new PreviewPtzTouch(this,tap,new PreviewPtzTouch.Listener(){
            public boolean begin(){
                if(!foreground||s.device==null){toast("请先添加设备并连接预览");return false;}
                stopControls();selected=index;updateSelected();
                if(s.device.hikvision()&&(s.hik==null||!s.hik.isReady())){toast("请等待实时预览连接");return false;}
                if(s.device.dahua()&&(s.dahua==null||!s.dahua.isReady())){toast("请等待大华实时预览连接");return false;}
                if(s.device.uniview()&&(s.uniview==null||!s.uniview.isReady())){toast("请等待宇视实时预览连接");return false;}
                if(!s.device.nativeSdk()&&s.device.ptz.isEmpty()){toast("该通道未配置 ONVIF 云台能力");return false;}
                return true;
            }
            public void move(float pan,float tilt){startMove(pan,tilt,0);}
            public void stop(){stopMove();}
        }));
        s.label=text(s.device==null?"窗口 "+(index+1):s.device.name,10,Color.WHITE);s.label.setPadding(dp(6),0,dp(4),0);s.label.setGravity(Gravity.CENTER_VERTICAL);s.label.setBackgroundColor(0xb0202020);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,dp(22),Gravity.BOTTOM);s.tile.addView(s.label,lp);
        View border=new View(this);s.border=border;GradientDrawable outline=new GradientDrawable();outline.setColor(Color.TRANSPARENT);outline.setStroke(dp(1),index==selected?0xffdbbd59:0xffe4e5e7);border.setBackground(outline);s.tile.addView(border,new FrameLayout.LayoutParams(-1,-1));
        s.fullscreenButton=text("⛶ 全屏",12,Color.WHITE);s.fullscreenButton.setGravity(Gravity.CENTER);s.fullscreenButton.setBackgroundColor(0xb0202020);
        s.fullscreenButton.setContentDescription("全屏");FrameLayout.LayoutParams full=new FrameLayout.LayoutParams(dp(86),dp(44),Gravity.TOP|Gravity.RIGHT);full.setMargins(dp(6),dp(6),dp(6),0);s.tile.addView(s.fullscreenButton,full);
        s.fullscreenButton.setOnClickListener(v->{if(selected!=index){stopControls();selected=index;updateSelected();}toggleFullscreen();});
        updateOverlay(index);
    }
    private void updateSelected(){
        if(streamButton!=null){Device d=slots[selected].device;streamButton.setText(d!=null&&d.nativeSdk()?(d.sdkStream==0?"主码流 ▾":"子码流 ▾"):"码流 ▾");}
        for(int i=0;i<layout;i++){Slot s=slots[i];View border=s.border;GradientDrawable d=new GradientDrawable();d.setColor(Color.TRANSPARENT);d.setStroke(dp(1),i==selected?0xffdbbd59:0xffd1d3d6);border.setBackground(d);updateOverlay(i);if(i!=selected){s.muted=true;volume(s);}}
        Slot s=slots[selected];if(state!=null)state.setText(s.device==null?"窗口 "+(selected+1)+" · 点击 + 添加设备":s.device.name+" · "+(replay?"远程回放":"实时预览"));
        if(timeline!=null){timeline.center=s.time>0?s.time:chosenTime;timeline.setRanges(s.ranges);}
        if(sound!=null)sound.active(!s.muted);if(record!=null){record.active(s.recording);record.setEnabled(true);}
    }
    private void chooseStream(){
        Slot slot=slots[selected];if(replay)return;if(slot.device==null){toast("请先选择预览设备");return;}
        if(slot.recording||slot.recordBusy){toast("请先停止设备录像，再切换码流");return;}
        Device device=slot.device;int revision=slot.revision;
        if(device.nativeSdk()){
            new AlertDialog.Builder(this).setTitle("选择预览码流").setSingleChoiceItems(new String[]{"主码流（清晰度优先）","子码流（流畅度优先）"},device.sdkStream,(dialog,index)->{
                dialog.dismiss();if(slot.revision!=revision||!foreground)return;
                try{Device value=Device.from(device.json());value.sdkStream=index;applyStream(slot,value);}catch(Exception e){toast("切换码流失败："+e.getMessage());}
            }).setNegativeButton("取消",null).show();return;
        }
        if(device.endpoint.isEmpty()){
            EditText address=new EditText(this);address.setSingleLine(true);address.setText(device.stream);
            AlertDialog picker=new AlertDialog.Builder(this).setTitle("切换 RTSP 视频流").setMessage("填写设备对应主码流或子码流的 RTSP 地址").setView(address).setNegativeButton("取消",null).setPositiveButton("切换",null).create();
            picker.setOnShowListener(d->picker.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{if(slot.revision!=revision||!foreground){picker.dismiss();return;}String uri=address.getText().toString().trim();Device.validateUrl(uri,true);Device value=Device.from(device.json());value.stream=uri;applyStream(slot,value);picker.dismiss();}catch(Exception e){address.setError(e.getMessage());}}));picker.show();return;
        }
        state.setText("正在读取设备码流…");io.execute(()->{try{
            OnvifClient client=new OnvifClient(device.user,device.password);List<OnvifClient.Profile> profiles=client.discover(device.endpoint);
            ui.post(()->{if(!foreground||slot.revision!=revision||isDestroyed())return;String[] names=new String[profiles.size()];int checked=-1;for(int i=0;i<names.length;i++){names[i]=profiles.get(i).name+" · "+profiles.get(i).token;if(profiles.get(i).token.equals(device.profile))checked=i;}
                new AlertDialog.Builder(this).setTitle("选择通道 / 码流").setSingleChoiceItems(names,checked,(dialog,index)->{dialog.dismiss();io.execute(()->{try{OnvifClient.Profile profile=profiles.get(index);String uri=client.stream(profile);Device.validateUrl(uri,true);Device value=Device.from(device.json());value.stream=uri;value.profile=profile.token;value.ptz=profile.ptz;ui.post(()->{if(foreground&&slot.revision==revision)applyStream(slot,value);});}catch(Exception e){ui.post(()->{if(foreground&&slot.revision==revision)toast("读取码流失败："+e.getMessage());});}});}).setNegativeButton("取消",null).show();
            });
        }catch(Exception e){ui.post(()->{if(foreground&&slot.revision==revision)state.setText("读取码流失败："+e.getMessage());});}});
    }
    private void applyStream(Slot slot,Device value){
        if(slot.recording||slot.recordBusy){toast("请先停止设备录像，再切换码流");return;}
        try{DeviceStore store=new DeviceStore(this);List<Device> saved=store.load();boolean found=false;for(int i=0;i<saved.size();i++)if(saved.get(i).id.equals(value.id)){saved.set(i,value);found=true;break;}if(!found){toast("设备已删除，请重新选择");return;}store.save(saved);devices=saved;}
        catch(Exception e){toast("保存码流选择失败："+e.getMessage());return;}
        stopControls();release(slot);slot.device=value;if(slot.univiewView!=null)slot.univiewView.onResume();updateSelected();start(slot);
    }
    private void choose(){
        stopControls();try{devices=new DeviceStore(this).load();}catch(Exception e){toast("读取设备失败");return;}
        if(devices.isEmpty()){new AlertDialog.Builder(this).setTitle("尚未添加设备").setMessage("先添加海康 SDK 或 ONVIF / RTSP 设备，再选择视频窗口。")
            .setPositiveButton("添加设备",(d,w)->manage()).setNegativeButton("取消",null).show();return;}
        String[] names=new String[devices.size()+1];for(int i=0;i<devices.size();i++)names[i]=devices.get(i).name+" · "+devices.get(i).brand;names[devices.size()]="＋ 管理 / 添加设备";
        new AlertDialog.Builder(this).setTitle("选择窗口 "+(selected+1)+" 的设备").setItems(names,(d,w)->{
            if(w==devices.size()){manage();return;}Slot s=slots[selected];release(s);s.device=devices.get(w);s.time=chosenTime;makeTile(selected);updateSelected();start(s);
        }).show();
    }
    private void start(Slot s){
        if(!foreground||s.device==null||s.hik!=null||s.dahua!=null||s.uniview!=null||s.player!=null)return;
        if(replay&&!s.device.hikvision()){s.hint.setText("此设备尚未接入录像检索\n请选择海康 SDK 设备");return;}
        if((s.device.hikvision()||s.device.dahua())&&(s.surface==null||!s.surface.getHolder().getSurface().isValid()))return;
        int revision=++s.revision;s.hint.setVisibility(View.VISIBLE);s.hint.setText("正在连接…");getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(s.device.hikvision()){
            s.hik=new HikSession(s.device,new HikSession.Listener(){
                public void state(String message){if(s.revision!=revision)return;if(s==slots[selected])state.setText(message);if(!s.hik.isReady()){s.hint.setVisibility(View.VISIBLE);s.hint.setText(message);}}
                public void ready(){if(s.revision!=revision)return;s.hint.setVisibility(View.GONE);volume(s);}
                public void records(List<long[]> ranges){if(s.revision!=revision)return;s.ranges=ranges;if(s==slots[selected]&&timeline!=null)timeline.setRanges(ranges);}
            });
            long begin=s.time>0?s.time:chosenTime;Calendar end=Calendar.getInstance();end.setTimeInMillis(begin);end.set(Calendar.HOUR_OF_DAY,23);end.set(Calendar.MINUTE,59);end.set(Calendar.SECOND,59);
            s.hik.start(s.surface.getHolder(),replay,begin,Math.min(end.getTimeInMillis(),System.currentTimeMillis()));
        }else if(s.device.dahua()){
            s.dahua=new DahuaSession(s.device,s.surface.getHolder(),new DahuaSession.Listener(){
                public void state(String message){ui.post(()->{if(s.revision==revision){s.hint.setVisibility(View.VISIBLE);s.hint.setText(message);if(s==slots[selected])state.setText(message);}});}
                public void ready(){ui.post(()->{if(s.revision==revision){s.hint.setVisibility(View.GONE);volume(s);if(s==slots[selected])state.setText(s.device.name+" · 大华 SDK 已连接");}});}
            });s.dahua.start();
        }else if(s.device.uniview()){
            s.uniview=new UniviewSession(s.device,s.univiewView,new UniviewSession.Listener(){
                public void state(String message){ui.post(()->{if(s.revision==revision){s.hint.setVisibility(View.VISIBLE);s.hint.setText(message);if(s==slots[selected])state.setText(message);}});}
                public void ready(){ui.post(()->{if(s.revision==revision){s.hint.setVisibility(View.GONE);if(s==slots[selected])state.setText(s.device.name+" · 宇视 SDK 已连接");}});}
            });s.uniview.start();
        }else{
            if(vlc==null)vlc=new LibVLC(this,new ArrayList<>(Arrays.asList("--rtsp-tcp","--network-caching=350")));
            s.player=new MediaPlayer(vlc);s.player.attachViews(s.video,null,false,false);
            s.player.setEventListener(event->{if(s.revision!=revision)return;
                if(event.type==MediaPlayer.Event.Playing){s.hint.setVisibility(View.GONE);volume(s);if(s==slots[selected])state.setText(s.device.name+" · 已连接");}
                else if(event.type==MediaPlayer.Event.EncounteredError||event.type==MediaPlayer.Event.EndReached){s.hint.setVisibility(View.VISIBLE);s.hint.setText("连接中断\n长按窗口重新选择设备");if(s==slots[selected])stopControls();}
            });
            Uri uri=Uri.parse(s.device.stream);if(!s.device.user.isEmpty())uri=uri.buildUpon().encodedAuthority(Uri.encode(s.device.user)+":"+Uri.encode(s.device.password)+"@"+uri.getEncodedAuthority()).build();
            Media media=new Media(vlc,uri);media.setHWDecoderEnabled(true,false);media.addOption(":rtsp-tcp");media.addOption(":network-caching=350");s.player.setMedia(media);media.release();volume(s);s.player.play();
        }
    }
    private void release(Slot s){s.revision++;if(s.hik!=null){s.hik.close();s.hik=null;}if(s.dahua!=null){s.dahua.close();s.dahua=null;}if(s.uniview!=null){s.uniview.close();s.uniview=null;}if(s.univiewView!=null)s.univiewView.onPause();if(s.player!=null){s.player.setEventListener(null);s.player.stop();s.player.detachViews();s.player.release();s.player=null;}s.recording=false;s.recordBusy=false;s.paused=false;s.speed=1;}
    private void restart(Slot s){stopControls();release(s);s.ranges=new ArrayList<>();if(timeline!=null)timeline.setRanges(s.ranges);start(s);}
    private void volume(Slot s){boolean mute=s.muted||(s==slots[selected]&&talk!=null);if(s.player!=null)s.player.setVolume(mute?0:100);if(s.hik!=null)s.hik.mute(mute);if(s.dahua!=null)s.dahua.mute(mute);}
    private void startTalk(){
        Slot s=slots[selected];if(talk!=null||s.device==null||replay||!foreground)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},22);return;}
        final TalkSession[] currentSession = new TalkSession[1];
        TalkSession.Listener listener=new TalkSession.Listener(){
            public void started(){ui.post(()->{if(talk==currentSession[0]&&s==slots[selected]){mic.active(true);mic.label("结束对讲");state.setText("正在对讲 · 点击对讲结束");}});}
            public void ended(String message){ui.post(()->{if(s==slots[selected]&&talk==currentSession[0]){stopTalk();state.setText(message==null?"对讲已结束":message);if(message!=null&&foreground)new AlertDialog.Builder(MonitorActivity.this).setTitle("对讲失败").setMessage(message).setPositiveButton("知道了",null).show();}});}
        };
        talk=s.device.hikvision()?new TalkSession(s.hik==null?unavailableAudio():s.hik.audioChannel(),listener)
            :s.device.dahua()?new TalkSession(s.dahua==null?unavailableAudio():s.dahua.audioChannel(),listener)
            :s.device.uniview()?new TalkSession(s.uniview==null?unavailableAudio():s.uniview.audioChannel(),listener)
            :new TalkSession(s.device.talkStream.isEmpty()?s.device.stream:s.device.talkStream,s.device.user,s.device.password,listener);
        currentSession[0]=talk;TalkSession current=talk;state.setText("正在建立对讲…");volume(s);talk.start();
        ui.postDelayed(()->{if(talk==current){stopTalk();state.setText("单次对讲已满 60 秒");}},60000);
    }
    private com.example.nvrclient.protocol.AudioChannel unavailableAudio(){return new com.example.nvrclient.protocol.AudioChannel(){public void open(){throw new IllegalStateException("请先连接实时预览");}public void send(short[]x,int n){}public void teardown(){}public void close(){}};}
    private void stopTalk(){TalkSession previous=talk;talk=null;if(previous!=null)previous.stop();if(mic!=null){mic.active(false);mic.label("对讲");}volume(slots[selected]);}
    private void move(LinearLayout parent,String name,float pan,float tilt,float zoom){
        MonitorWidgets.Action control=action(parent,name,name,()->{startMove(pan,tilt,zoom);int ticket=movementRevision;ui.postDelayed(()->{if(ticket==movementRevision)stopMove();},350);});control.getLayoutParams().height=dp(40);control.hold(()->startMove(pan,tilt,zoom),this::stopMove);
    }
    private void startMove(float pan,float tilt,float zoom){
        stopMove();Slot s=slots[selected];if(s.device==null||replay||!foreground)return;
        if(!s.device.nativeSdk()&&s.device.ptz.isEmpty()){state.setText("请在设备配置中读取 ONVIF 云台参数");return;}
        movingSlot=s;int revision=s.revision;int ticket=movementRevision;Device d=s.device;
        moving=ptz.scheduleWithFixedDelay(()->{if(s.revision!=revision||ticket!=movementRevision)return;try{
            if(d.hikvision()){HikSession session=s.hik;if(session!=null)session.move(pan,tilt,zoom);}
            else if(d.dahua()){DahuaSession session=s.dahua;if(session!=null)session.move(pan,tilt,zoom);}
            else if(d.uniview()){UniviewSession session=s.uniview;if(session!=null)session.move(pan,tilt,zoom);}
            else new OnvifClient(d.user,d.password).move(d.ptz,d.profile,pan,tilt,zoom);
        }catch(Exception e){ui.post(()->{if(movingSlot==s&&ticket==movementRevision){stopMove();state.setText("云台控制失败，请检查权限和设备能力");}});}},0,500,TimeUnit.MILLISECONDS);
    }
    private void stopMove(){
        movementRevision++;
        if(moving!=null){moving.cancel(false);moving=null;}Slot s=movingSlot;movingSlot=null;if(s==null||s.device==null)return;
        if(s.hik!=null)s.hik.stopMove();else if(s.dahua!=null)s.dahua.stopMove();else if(s.uniview!=null)s.uniview.stopMove();else if(!s.device.nativeSdk()&&!s.device.ptz.isEmpty()){
            Device d=s.device;ptz.execute(()->{try{new OnvifClient(d.user,d.password).stop(d.ptz,d.profile);}catch(Exception e){ui.post(()->{if(foreground)state.setText("停止指令未确认；ONVIF 移动设有 1 秒超时");});}});
        }
    }
    private void stopControls(){stopTalk();stopMove();}
    private void date(){Calendar c=Calendar.getInstance();c.setTimeInMillis(chosenTime);
        new DatePickerDialog(this,(picker,y,m,d)->{c.set(y,m,d);new TimePickerDialog(this,(time,h,min)->{c.set(Calendar.HOUR_OF_DAY,h);c.set(Calendar.MINUTE,min);c.set(Calendar.SECOND,0);
            if(c.getTimeInMillis()>=System.currentTimeMillis()){toast("请选择过去的录像时间");return;}chosenTime=c.getTimeInMillis();Slot s=slots[selected];s.time=chosenTime;timeline.center=chosenTime;timeline.invalidate();restart(s);
        },c.get(Calendar.HOUR_OF_DAY),c.get(Calendar.MINUTE),true).show();},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show();}
    private void capture(){Slot s=slots[selected];if(s.device==null||(s.hik==null&&s.dahua==null&&s.uniview==null&&s.player==null)||(s.hik!=null&&!s.hik.isReady())||(s.dahua!=null&&!s.dahua.isReady())||(s.uniview!=null&&!s.uniview.isReady())||(s.player!=null&&!s.player.isPlaying())){toast("请等待视频画面加载");return;}
        int[] location=new int[2];s.tile.getLocationInWindow(location);Rect rect=new Rect(location[0],location[1],location[0]+s.tile.getWidth(),location[1]+s.tile.getHeight());
        SurfaceView source=findSurface(s.tile);
        Bitmap image=Bitmap.createBitmap(source==null?rect.width():source.getWidth(),source==null?rect.height():source.getHeight(),Bitmap.Config.ARGB_8888);
        PixelCopy.OnPixelCopyFinishedListener finished=result->{if(result!=PixelCopy.SUCCESS){image.recycle();toast("抓图失败，请等待画面加载");return;}io.execute(()->{
            try{String name="NvrClient-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date())+".png";
                if(Build.VERSION.SDK_INT>=29){ContentValues v=new ContentValues();v.put(MediaStore.Images.Media.DISPLAY_NAME,name);v.put(MediaStore.Images.Media.MIME_TYPE,"image/png");v.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/NvrClient");v.put(MediaStore.Images.Media.IS_PENDING,1);
                    Uri uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);if(uri==null)throw new IOException();try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null||!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException();}catch(Exception e){getContentResolver().delete(uri,null,null);throw e;}v.clear();v.put(MediaStore.Images.Media.IS_PENDING,0);getContentResolver().update(uri,v,null,null);
                }else{File file=new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),name);try(OutputStream out=new FileOutputStream(file)){image.compress(Bitmap.CompressFormat.PNG,100,out);}}
                ui.post(()->toast(Build.VERSION.SDK_INT>=29?"截图已保存到相册 NvrClient":"截图已保存到应用图片目录"));
            }catch(Exception e){ui.post(()->toast("截图保存失败"));}finally{image.recycle();}
        });};
        try{if(source!=null)PixelCopy.request(source,image,finished,ui);else PixelCopy.request(getWindow(),rect,image,finished,ui);}catch(IllegalArgumentException e){image.recycle();toast("视频画面尚不可抓取，请稍后重试");}
    }
    private SurfaceView findSurface(View view){
        if(view instanceof SurfaceView)return (SurfaceView)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){SurfaceView child=findSurface(((ViewGroup)view).getChildAt(i));if(child!=null)return child;}
        return null;
    }
    private void recordOptions(){
        if(ScreenRecordService.running){stopService(new Intent(this,ScreenRecordService.class));record.active(false);return;}
        Slot s=slots[selected];if(s.recording){record();return;}
        String nativeLabel=s.recording?"停止设备录像并保存":"保存设备原始录像（设备 SDK）";
        new AlertDialog.Builder(this).setTitle("录屏 / 录像").setItems(new String[]{"录制屏幕（无声 MP4）",nativeLabel,"本地录像 / 导出"},(dialog,which)->{
            if(which==2){startActivity(new Intent(this,RecordingsActivity.class));return;}if(which==1){record();return;}
            stopControls();
            android.media.projection.MediaProjectionManager manager=getSystemService(android.media.projection.MediaProjectionManager.class);
            startActivityForResult(manager.createScreenCaptureIntent(),43);
        }).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=43)return;
        if(result!=RESULT_OK||data==null){toast("已取消录屏");return;}
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        Intent service=new Intent(this,ScreenRecordService.class).putExtra("permission",data).putExtra("width",metrics.widthPixels).putExtra("height",metrics.heightPixels);
        try{startForegroundService(service);}catch(RuntimeException error){toast("无法启动录屏，请返回预览页重新尝试");}
    }
    private void record(){Slot s=slots[selected];if((s.hik==null||!s.hik.isReady())&&(s.dahua==null||!s.dahua.isReady())&&(s.uniview==null||!s.uniview.isReady())){toast("请先连接设备 SDK 视频");return;}
        File dir=getExternalFilesDir(Environment.DIRECTORY_MOVIES);if(dir==null){toast("录像目录不可用");return;}String extension=s.uniview!=null?".mp4":".dav";if(s.hik!=null)extension=".ps";String path=new File(dir,"NvrClient-"+System.currentTimeMillis()+extension).getAbsolutePath();
        if(s.dahua!=null){if(s.recordBusy){toast("正在处理录像，请稍候");return;}s.recordBusy=true;try{boolean starting=!s.recording;if(starting)s.nativeRecordPath=path;s.dahua.record(new File(starting?path:s.nativeRecordPath),starting);s.recording=starting;record.active(s.recording);state.setText(s.recording?"大华原始录像中 · 点击停止录像结束":"大华原始录像已保存 · 本地录像可导出");toast(s.recording?"已开始原始录像":"原始录像已保存："+new File(s.nativeRecordPath).getName());if(!starting)s.nativeRecordPath=null;}catch(Exception e){toast(e.getMessage());}finally{s.recordBusy=false;}return;}
        if(s.uniview!=null){if(s.recordBusy){toast("正在处理录像，请稍候");return;}s.recordBusy=true;try{boolean starting=!s.recording;if(starting)s.nativeRecordPath=path;s.uniview.record(new File(starting?path:s.nativeRecordPath),starting);s.recording=starting;record.active(s.recording);state.setText(s.recording?"宇视原始录像中 · 点击停止录像结束":"宇视原始录像已保存 · 本地录像可导出");toast(s.recording?"已开始原始录像":"原始录像已保存："+new File(s.nativeRecordPath).getName());if(!starting)s.nativeRecordPath=null;}catch(Exception e){toast(e.getMessage());}finally{s.recordBusy=false;}return;}
        if(s.recordBusy){toast("正在处理录像，请稍候");return;}s.recordBusy=true;int revision=s.revision;android.content.Context app=getApplicationContext();
        s.hik.record(path,!s.recording,(active,saved,error)->{
            if(s.revision==revision){s.recordBusy=false;s.recording=active;if(s==slots[selected]){record.active(active);state.setText(error!=null?error:active?"原始录像中 · 点击停止录像结束":"原始录像已保存 · 本地录像可导出");}}
            String message=error!=null?error:active?"已开始原始录像": "原始录像已保存："+new File(saved).getName();
            Toast.makeText(app,message,Toast.LENGTH_LONG).show();
            if(error!=null&&!isFinishing()&&!isDestroyed()&&foreground)new AlertDialog.Builder(this).setTitle("原始录像提示").setMessage(error).setPositiveButton("知道了",null).show();
        });
    }
    private void manage(){stopControls();startActivity(new Intent(this,MainActivity.class));}
    private void menu(){stopControls();new AlertDialog.Builder(this).setTitle("NVR 客户端").setItems(new String[]{"实时预览","远程回放","设备管理","重连当前窗口","全屏 / 退出全屏"},(d,w)->{
        if(w<2){switchMode(w==1);}else if(w==2)manage();else if(w==3)restart(slots[selected]);else toggleFullscreen();}).show();}
    private void switchMode(boolean playback){if(fullscreen){fullscreen=false;applyFullscreen();}if(replay==playback)return;replay=playback;layout=replay?4:1;selected=0;build();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);initialDevice=intent.getIntExtra("device",-1);switchMode(intent.getBooleanExtra("replay",false));}
    private void updateOverlay(int index){Slot s=slots[index];if(s.fullscreenButton==null)return;
        s.fullscreenButton.setText(fullscreen?"⛶ 退出全屏":"⛶ 全屏");s.fullscreenButton.setContentDescription(fullscreen?"退出全屏":"全屏");
    }
    private void toggleFullscreen(){
        stopControls();fullscreen=!fullscreen;
        setRequestedOrientation(fullscreen
            ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        applyFullscreen();
    }
    @Override public void onConfigurationChanged(Configuration configuration){
        super.onConfigurationChanged(configuration);
        ui.post(()->{if(!isDestroyed())applyFullscreen();});
    }
    private void applyFullscreen(){
        int visibility=fullscreen?View.GONE:View.VISIBLE;
        headerRow.setVisibility(visibility);subRow.setVisibility(visibility);state.setVisibility(visibility);toolbar.setVisibility(visibility);splits.setVisibility(visibility);tabs.setVisibility(visibility);
        int across=(int)Math.sqrt(layout);for(int i=0;i<layout;i++){slots[i].tile.setVisibility(!fullscreen||i==selected?View.VISIBLE:View.GONE);updateOverlay(i);}for(int i=0;i<across;i++)grid.getChildAt(i).setVisibility(!fullscreen||i==selected/across?View.VISIBLE:View.GONE);
        grid.getLayoutParams().height=fullscreen?stage.getHeight():Math.min(stage.getHeight(),Math.round(stage.getWidth()*.92f));grid.requestLayout();
        panel.setVisibility(View.GONE);if(timeline!=null)timeline.setVisibility(visibility);
        if(Build.VERSION.SDK_INT>=30){WindowInsetsController controller=getWindow().getInsetsController();if(controller!=null){controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);if(fullscreen)controller.hide(WindowInsets.Type.systemBars());else controller.show(WindowInsets.Type.systemBars());}}
        else getWindow().getDecorView().setSystemUiVisibility(fullscreen?View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }
    @Override protected void onSaveInstanceState(Bundle out){
        super.onSaveInstanceState(out);out.putBoolean("fullscreen",fullscreen);out.putBoolean("replay",replay);out.putInt("layout",layout);out.putInt("selected",selected);out.putLong("time",chosenTime);
        String[] ids=new String[16];long[] times=new long[16];for(int i=0;i<16;i++){ids[i]=slots[i].device==null?"":slots[i].device.id;times[i]=slots[i].time;}out.putStringArray("devices",ids);out.putLongArray("slotTimes",times);
    }
    private void back(){if(fullscreen){toggleFullscreen();}else if(panel.getVisibility()==View.VISIBLE){panel.setVisibility(View.GONE);stopControls();}else finish();}
    @android.annotation.SuppressLint("GestureBackNavigation") @Override public void onBackPressed(){back();}
    @Override protected void onResume(){super.onResume();foreground=true;ui.removeCallbacks(recordingStatus);ui.post(recordingStatus);try{devices=new DeviceStore(this).load();}catch(Exception ignored){}
        if(restoringIds!=null){for(int i=0;i<Math.min(16,restoringIds.length);i++)for(Device d:devices)if(d.id.equals(restoringIds[i])){slots[i].device=d;break;}restoringIds=null;}
        for(int i=0;i<layout;i++){Slot s=slots[i];if(s.device!=null){Device previous=s.device;s.device=null;for(Device d:devices)if(d.id.equals(previous.id)){s.device=d;break;}makeTile(i);}}
        if(initialDevice>=0&&initialDevice<devices.size()){slots[0].device=devices.get(initialDevice);slots[0].time=chosenTime;initialDevice=-1;makeTile(0);updateSelected();}
        updateSelected();for(int i=0;i<layout;i++)start(slots[i]);}
    @Override protected void onPause(){foreground=false;ui.removeCallbacks(recordingStatus);stopControls();for(Slot s:slots)release(s);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);super.onPause();}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(!focus)stopControls();}
    @Override protected void onDestroy(){stopControls();for(Slot s:slots)release(s);ptz.shutdown();io.shutdown();if(vlc!=null)vlc.release();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==22){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED&&foreground&&!replay)startTalk();else toast("未授权麦克风，无法对讲");}}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    private TextView headerButton(String text,String description){TextView v=text(text,26,Color.WHITE);v.setGravity(Gravity.CENTER);v.setContentDescription(description);return v;}
    private MonitorWidgets.Action action(LinearLayout parent,String icon,String label,Runnable run){MonitorWidgets.Action a=new MonitorWidgets.Action(this,icon,label);parent.addView(a,new LinearLayout.LayoutParams(0,dp(64),1));a.setOnClickListener(v->run.run());return a;}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
}
