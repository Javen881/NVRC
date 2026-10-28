package com.example.nvrclient;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.hardware.display.*;
import android.media.MediaRecorder;
import android.media.projection.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.widget.Toast;
import java.io.*;

/** User-authorized MP4 screen recording, independent of camera vendor. No microphone capture. */
public final class ScreenRecordService extends Service {
    static final String STOP="com.example.nvrclient.STOP_RECORDING";
    static volatile boolean running;
    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaRecorder recorder;
    private ParcelFileDescriptor descriptor;
    private Uri output;
    private File legacyFile;
    private boolean started, finishing;
    private final MediaProjection.Callback callback=new MediaProjection.Callback(){
        @Override public void onStop(){finishRecording();}
    };
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null||STOP.equals(intent.getAction())){finishRecording();return START_NOT_STICKY;}
        if(running)return START_NOT_STICKY;
        try{
            NotificationManager manager=getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("screen-record","屏幕录制",NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop=PendingIntent.getService(this,0,new Intent(this,ScreenRecordService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification notification=new Notification.Builder(this,"screen-record").setSmallIcon(R.drawable.ic_nvr)
                .setContentTitle("NVR 正在录屏").setContentText("无声 MP4 · 点击停止并保存").setOngoing(true)
                .setContentIntent(stop).addAction(new Notification.Action.Builder(null,"停止并保存",stop).build()).build();
            if(Build.VERSION.SDK_INT>=29)startForeground(31,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(31,notification);
            Intent permission=intent.getParcelableExtra("permission");
            if(permission==null)throw new IOException("缺少录屏授权");
            projection=getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK,permission);
            if(projection==null)throw new IOException("录屏授权失效");
            projection.registerCallback(callback,new Handler(Looper.getMainLooper()));
            String name="NvrClient-screen-"+System.currentTimeMillis()+".mp4";
            recorder=new MediaRecorder();recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            if(Build.VERSION.SDK_INT>=29){
                ContentValues values=new ContentValues();values.put(MediaStore.Video.Media.DISPLAY_NAME,name);values.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/NvrClient");values.put(MediaStore.Video.Media.IS_PENDING,1);
                output=getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values);
                if(output==null)throw new IOException("无法创建录屏文件");
                descriptor=getContentResolver().openFileDescriptor(output,"w");if(descriptor==null)throw new IOException("无法写入录屏文件");
                recorder.setOutputFile(descriptor.getFileDescriptor());
            }else{
                File dir=getExternalFilesDir(Environment.DIRECTORY_MOVIES);if(dir==null)throw new IOException("存储不可用");
                legacyFile=new File(dir,name);recorder.setOutputFile(legacyFile.getAbsolutePath());
            }
            int width=intent.getIntExtra("width",720),height=intent.getIntExtra("height",1280);
            float scale=Math.min(1f,1280f/Math.max(width,height));width=Math.max(2,(int)(width*scale)/2*2);height=Math.max(2,(int)(height*scale)/2*2);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);recorder.setVideoSize(width,height);
            recorder.setVideoFrameRate(24);recorder.setVideoEncodingBitRate(4_000_000);
            recorder.setMaxFileSize(2_000_000_000L);recorder.setMaxDuration(60*60*1000);
            recorder.setOnInfoListener((r,what,extra)->{if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED||what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)finishRecording();});
            recorder.setOnErrorListener((r,what,extra)->finishRecording());recorder.prepare();
            display=projection.createVirtualDisplay("NvrClient",width,height,getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,recorder.getSurface(),null,null);
            if(display==null)throw new IOException("无法创建录屏画面");
            recorder.start();started=true;running=true;
            Toast.makeText(this,"录屏已开始（无声），再次点击录屏可停止",Toast.LENGTH_LONG).show();
        }catch(Exception error){Toast.makeText(this,"录屏启动失败，请重新授权或检查存储空间",Toast.LENGTH_LONG).show();finishRecording();}
        return START_NOT_STICKY;
    }
    private void finishRecording(){
        if(finishing)return;finishing=true;running=false;boolean saved=false;
        if(recorder!=null){if(started)try{recorder.stop();saved=true;}catch(RuntimeException ignored){}try{recorder.release();}catch(RuntimeException ignored){}recorder=null;}
        if(display!=null){display.release();display=null;}
        if(projection!=null){projection.unregisterCallback(callback);projection.stop();projection=null;}
        if(descriptor!=null){try{descriptor.close();}catch(IOException ignored){}descriptor=null;}
        try{
            if(output!=null){if(saved){ContentValues values=new ContentValues();values.put(MediaStore.Video.Media.IS_PENDING,0);getContentResolver().update(output,values,null,null);}else getContentResolver().delete(output,null,null);}
            if(!saved&&legacyFile!=null)legacyFile.delete();
            if(started)Toast.makeText(this,saved?(Build.VERSION.SDK_INT>=29?"录屏已保存到相册 Movies/NvrClient":"录屏已保存到应用 Movies 目录"):"录屏未保存：时长太短或录制中断",Toast.LENGTH_LONG).show();
        }catch(RuntimeException error){Toast.makeText(this,"录屏文件保存失败，请检查存储空间",Toast.LENGTH_LONG).show();}
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    @Override public void onDestroy(){finishRecording();super.onDestroy();}
    @Override public void onTaskRemoved(Intent rootIntent){finishRecording();super.onTaskRemoved(rootIntent);}
}
