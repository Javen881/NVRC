package com.example.nvrclient;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, qualifiers="zh-rCN-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MonitorUiTest {
    @Test public void liveAndPlaybackNavigationAndLayouts() throws Exception {
        try(ActivityController<MonitorActivity> controller=Robolectric.buildActivity(MonitorActivity.class).setup()) {
            MonitorActivity activity=controller.get();
            View root=activity.getWindow().getDecorView();
            layout(root);
            assertNotNull(find(root,"实时预览")); assertNotNull(find(root,"对讲")); assertNotNull(find(root,"云台"));
            assertNotNull(find(root,"窗口 1"));
            assertNotNull(find(root,"截屏"));
            find(root,"录屏").performClick();
            AlertDialog recording=ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("录制屏幕（无声 MP4）",recording.getListView().getAdapter().getItem(0));
            recording.dismiss();
            activity.onActivityResult(43,android.app.Activity.RESULT_CANCELED,null);
            assertFalse(ScreenRecordService.running);
            assertNotNull(find(root,"摄像机 Tab"));
            assertNull(find(root,"云台向上"));assertFalse(find(root,"↑").isShown());
            find(root,"全屏").performClick();layout(root);
            assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,activity.getRequestedOrientation());
            assertEquals(View.GONE,((View)find(root,"预览 Tab").getParent()).getVisibility());
            assertEquals(View.VISIBLE,find(root,"退出全屏").getVisibility());
            save(root,"live-fullscreen.png");
            find(root,"退出全屏").performClick();layout(root);
            assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,activity.getRequestedOrientation());
            assertEquals(View.VISIBLE,((View)find(root,"预览 Tab").getParent()).getVisibility());
            save(root,"live-single.png");
            find(root,"4画面").performClick();layout(root);
            assertNotNull(find(root,"窗口 4"));assertNull(find(root,"窗口 5"));
            find(root,"全屏").performClick();layout(root);assertFalse(find(root,"窗口 2").isShown());
            activity.onBackPressed();layout(root);assertTrue(find(root,"窗口 2").isShown());
            save(root,"live-four.png");
            find(root,"16画面").performClick();layout(root);assertNotNull(find(root,"窗口 16"));
            find(root,"云台").performClick();layout(root);assertNotNull(find(root,"云台控制 · 按住移动，松手停止"));assertNotNull(find(root,"↖"));assertNotNull(find(root,"↘"));
            find(root,"菜单").performClick();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);
            dialog.getListView().performItemClick(null,1,1);dialog.dismiss();layout(root);
            assertNotNull(find(root,"远程回放"));assertNotNull(find(root,"日期"));assertNotNull(find(root,"快进"));
            assertNull(find(root,"对讲"));assertNotNull(find(root,"窗口 4"));
            find(root,"快进").performClick();
            AlertDialog speed=ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("8× 快进",speed.getListView().getAdapter().getItem(3));
            speed.getListView().performItemClick(null,3,3);speed.dismiss();
            assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains("请先连接"));
            save(root,"remote-playback.png");
            find(root,"预览 Tab").performClick();layout(root);assertNotNull(find(root,"实时预览"));
            find(root,"回放 Tab").performClick();layout(root);assertNotNull(find(root,"远程回放"));
            find(root,"摄像机 Tab").performClick();assertEquals(MainActivity.class.getName(),Shadows.shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
            controller.pause().stop().start().resume();layout(root);
            assertNotNull(find(root,"远程回放"));
        }
    }
    @Test public void cameraConfigurationAndLibrary()throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            View root=controller.get().getWindow().getDecorView();layout(root);assertNotNull(find(root,"摄像机配置"));assertTrue(find(root,"摄像机 Tab").isSelected());
            find(root,"+ 添加设备").performClick();AlertDialog picker=ShadowAlertDialog.getLatestAlertDialog();picker.getListView().performItemClick(null,0,0);picker.dismiss();
            AlertDialog config=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(find(config.getWindow().getDecorView(),"对讲编码（优先自动读取；手动值必须与设备一致）"));config.dismiss();
            save(root,"camera-config.png");find(root,"本地录像 / 导出").performClick();assertEquals(RecordingsActivity.class.getName(),Shadows.shadowOf(controller.get()).getNextStartedActivity().getComponent().getClassName());
        }
        try(ActivityController<RecordingsActivity> controller=Robolectric.buildActivity(RecordingsActivity.class).setup()){
            View root=controller.get().getWindow().getDecorView();layout(root);assertNotNull(find(root,"暂无本地录像"));
        }
    }
    @Test public void remoteParametersRenderAsEditableDeviceFields()throws Exception {
        try(ActivityController<RemoteConfigActivity> controller=Robolectric.buildActivity(RemoteConfigActivity.class).setup()){
            HikRemoteConfig client=new HikRemoteConfig(new Device(),new HikRemoteConfigTest.FakeBridge());
            controller.get().render(client.read(HikRemoteConfig.Section.IMAGE));View root=controller.get().getWindow().getDecorView();layout(root);
            assertNotNull(find(root,"亮度（0–100）"));assertNotNull(find(root,"镜像 / 翻转"));assertNotNull(find(root,"保存到摄像机"));save(root,"remote-image-config.png");client.close();
        }
    }
    @Test public void voiceSettingsPersist()throws Exception {
        Device device=new Device();device.connection="hikvision-sdk";device.voiceCodec=2;device.voiceToCamera=true;device.voiceChannel=3;
        Device restored=Device.from(device.json());assertEquals(device.id,restored.id);assertTrue(restored.voiceToCamera);assertEquals(2,restored.voiceCodec);assertEquals(3,restored.voiceChannel);
        Device old=Device.from(new org.json.JSONObject());assertEquals(0,old.voiceCodec);assertFalse(old.voiceToCamera);
    }
    @Test public void recordingWithoutConnectionReportsFailure()throws Exception {
        HikSession session=new HikSession(new Device(),new HikSession.Listener(){public void state(String s){}public void ready(){}public void records(java.util.List<long[]> r){}});
        java.util.concurrent.atomic.AtomicReference<String> error=new java.util.concurrent.atomic.AtomicReference<>();
        session.record("/invalid/path.ps",true,(active,path,problem)->{assertFalse(active);error.set(problem);});
        java.lang.reflect.Field field=HikSession.class.getDeclaredField("worker");field.setAccessible(true);
        ((java.util.concurrent.ScheduledExecutorService)field.get(session)).submit(()->{}).get(2,java.util.concurrent.TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(error.get().contains("视频未连接"));
        session.close();error.set(null);session.record("/invalid/path.ps",true,(active,path,problem)->error.set(problem));
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(error.get().contains("已关闭"));
    }
    private void layout(View root) {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));
        root.layout(0,0,360,800);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private View find(View v,String value) {
        if(value.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;
        if(v instanceof TextView&&value.contentEquals(((TextView)v).getText()))return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),value);if(found!=null)return found;}
        return null;
    }
    private void save(View view,String name)throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(360,800,Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));
        File dir=new File("build/ui-previews");assertTrue(dir.isDirectory()||dir.mkdirs());
        try(FileOutputStream out=new FileOutputStream(new File(dir,name))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}
        bitmap.recycle();
    }
}
