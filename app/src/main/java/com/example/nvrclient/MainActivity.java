package com.example.nvrclient;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import com.example.nvrclient.protocol.OnvifClient;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final List<Device> devices = new ArrayList<>();
    private DeviceStore store;
    private final int ink = Color.rgb(55, 58, 64), teal = MonitorWidgets.RED;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); store = new DeviceStore(this);
        try { devices.addAll(store.load()); } catch (Exception e) { toast("无法读取设备配置"); }
        home();
    }
    @Override protected void onDestroy() { network.shutdownNow(); super.onDestroy(); }
    private void preview(Device device) {
        startActivity(new android.content.Intent(this, MonitorActivity.class).putExtra("device", devices.indexOf(device)).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP|android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
    private LinearLayout base() {
        LinearLayout root = column(); root.setBackgroundColor(Color.rgb(245, 246, 248));
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
            });
        } else {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets;
            });
        }
        setContentView(root); return root;
    }
    private void home() {
        LinearLayout root = base();
        ScrollView scroll = new ScrollView(this); root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
        root.addView(NavigationTabs.create(this,2,index->{if(index<2)startActivity(new android.content.Intent(this,MonitorActivity.class).putExtra("replay",index==1).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP|android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));}));
        LinearLayout body = column(); body.setPadding(dp(20), dp(20), dp(20), dp(24)); scroll.addView(body);
        text(body, "NVR CLIENT", 13, teal);
        text(body, "摄像机配置", 28, ink);
        text(body, "选择设备进行远程参数配置，或编辑本机连接", 15, Color.DKGRAY);
        button(body,"本地录像 / 导出",v->startActivity(new android.content.Intent(this,RecordingsActivity.class)));
        button(body, "+ 添加设备", v -> edit(null));
        if (devices.isEmpty()) text(body, "还没有设备\n添加 ONVIF 设备自动获取通道，或填写 RTSP 地址开始预览。", 16, Color.DKGRAY);
        for (Device device : devices) {
            LinearLayout card = column(); card.setPadding(dp(16), dp(12), dp(16), dp(12));
            card.setBackground(shape(Color.WHITE)); margins(body, card);
            text(card, device.name, 21, ink); text(card, device.brand + " · " + (device.nativeSdk() ? "SDK 端口 " + device.sdkPort : device.stream.isEmpty() ? "待获取通道" : "已配置视频流"), 13, teal);
            button(card, "实时预览", v -> preview(device));
            button(card,"远程参数配置",v->{if(!device.hikvision()){toast("远程参数配置目前支持海康 SDK 设备");return;}startActivity(new android.content.Intent(this,RemoteConfigActivity.class).putExtra("deviceId",device.id));});
            button(card, "连接设置 / 选择通道", v -> edit(device));
            Button delete = button(card, "删除", v -> new AlertDialog.Builder(this).setMessage("删除设备“" + device.name + "”？")
                .setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> {
                    List<Device> updated = new ArrayList<>(devices); updated.remove(device);
                    if (persist(updated)) home();
                }).show());
            delete.setTextColor(Color.rgb(243, 151, 151));
        }
        text(body, "接入方式：海康 SDK（8000）、大华 NetSDK（37777）、宇视 NetDEV SDK 或 ONVIF / RTSP。", 13, Color.DKGRAY);
        button(body, "远程回放", v -> startActivity(new android.content.Intent(this, MonitorActivity.class).putExtra("replay", true).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP|android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)));
    }
    private void edit(Device original) {
        if (original != null) {
            if (original.hikvision()) editHikvision(original); else if(original.uniview())editUniview(original);else if(original.dahua())editDahua(original);else editStandard(original);
            return;
        }
        new AlertDialog.Builder(this).setTitle("选择接入方式")
            .setItems(new String[]{"海康 SDK（默认 8000 端口）", "大华 NetSDK（默认 37777 端口）", "宇视 NetDEV SDK", "ONVIF / RTSP（通用）"},
                (dialog, which) -> { if (which == 0) editHikvision(null); else if(which==1)editDahua(null);else if(which==2)editUniview(null);else editStandard(null); }).show();
    }
    private void editDahua(Device original){editNativeDevice(original,"大华 NetSDK 设备","dahua-sdk","大华 NetSDK",37777,0,"大华通道从 0 开始；通过 37777 端口登录设备。");}
    private void editNativeDevice(Device original,String title,String connection,String brandName,int defaultPort,int defaultChannel,String help){
        Device source=original==null?new Device():original;ScrollView scroll=new ScrollView(this);LinearLayout form=column();form.setPadding(dp(20),dp(8),dp(20),dp(8));scroll.addView(form);
        EditText name=field(form,"设备名称",source.name,false),host=field(form,"设备 IP / 域名",source.host,false);host.setHint("192.168.1.108");
        EditText port=field(form,"SDK 服务端口",Integer.toString(original==null?defaultPort:source.sdkPort),false);port.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText user=field(form,"账号",source.user,false),password=field(form,"密码",source.password,true);
        EditText channel=field(form,"通道号",Integer.toString(original==null?defaultChannel:source.sdkChannel),false);channel.setInputType(InputType.TYPE_CLASS_NUMBER);
        CheckBox substream=new CheckBox(this);substream.setText("使用子码流（节省带宽）");substream.setChecked(source.sdkStream==1);form.addView(substream);TextView message=text(form,help,13,Color.DKGRAY);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(scroll).setNegativeButton("取消",null).setPositiveButton("保存",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            Device value=new Device();if(original!=null)value.id=original.id;value.connection=connection;value.brand=brandName;value.name=name.getText().toString().trim();value.host=host.getText().toString().trim();value.user=user.getText().toString();value.password=password.getText().toString();
            if(value.name.isEmpty()||value.host.isEmpty()||value.user.isEmpty())throw new IllegalArgumentException("请填写名称、设备地址和账号");java.net.URI address=new java.net.URI("http://"+value.host);if(address.getHost()==null||address.getUserInfo()!=null||address.getPort()!=-1||!address.getRawPath().isEmpty()||address.getQuery()!=null||address.getFragment()!=null)throw new IllegalArgumentException("设备地址只填写 IP / 域名，端口单独填写");
            value.sdkPort=Integer.parseInt(port.getText().toString().trim());value.sdkChannel=Integer.parseInt(channel.getText().toString().trim());value.sdkStream=substream.isChecked()?1:0;if(value.sdkPort<1||value.sdkPort>65535||value.sdkChannel<0||value.sdkChannel>65535)throw new IllegalArgumentException("端口或通道号不在有效范围内");
            List<Device> updated=new ArrayList<>(devices);if(original==null)updated.add(value);else updated.set(devices.indexOf(original),value);if(persist(updated)){dialog.dismiss();toast(brandName+"设备已保存");home();}
        }catch(Exception e){message.setText(e instanceof NumberFormatException?"端口和通道号必须为数字":error(e));}}));dialog.show();
    }
    private void editUniview(Device original) {
        Device source=original==null?new Device():original;
        ScrollView scroll=new ScrollView(this);LinearLayout form=column();form.setPadding(dp(20),dp(8),dp(20),dp(8));scroll.addView(form);
        EditText name=field(form,"设备名称",source.name,false);
        EditText host=field(form,"设备 IP / 域名",source.host,false);host.setHint("192.168.1.64");
        EditText port=field(form,"SDK 服务端口",Integer.toString(original==null?80:source.sdkPort),false);port.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText user=field(form,"账号",source.user,false);EditText password=field(form,"密码",source.password,true);
        EditText channel=field(form,"通道号（通常从 1 开始）",Integer.toString(original==null?1:source.sdkChannel),false);channel.setInputType(InputType.TYPE_CLASS_NUMBER);
        CheckBox substream=new CheckBox(this);substream.setText("使用子码流（节省带宽）");substream.setChecked(source.sdkStream==1);form.addView(substream);
        TextView message=text(form,"通过宇视 NetDEV SDK 直接登录；端口以设备网页或 SDK 服务配置为准。",13,Color.DKGRAY);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("宇视 SDK 设备").setView(scroll).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            Device value=new Device();if(original!=null)value.id=original.id;value.connection="uniview-sdk";value.brand="宇视 NetDEV SDK";
            value.name=name.getText().toString().trim();value.host=host.getText().toString().trim();value.user=user.getText().toString();value.password=password.getText().toString();
            if(value.name.isEmpty()||value.host.isEmpty()||value.user.isEmpty())throw new IllegalArgumentException("请填写名称、设备地址和账号");
            java.net.URI address=new java.net.URI("http://"+value.host);if(address.getHost()==null||address.getUserInfo()!=null||address.getPort()!=-1||!address.getRawPath().isEmpty()||address.getQuery()!=null||address.getFragment()!=null)throw new IllegalArgumentException("设备地址只填写 IP / 域名，端口单独填写");
            value.sdkPort=Integer.parseInt(port.getText().toString().trim());value.sdkChannel=Integer.parseInt(channel.getText().toString().trim());value.sdkStream=substream.isChecked()?1:0;
            if(value.sdkPort<1||value.sdkPort>65535||value.sdkChannel<1||value.sdkChannel>65535)throw new IllegalArgumentException("端口或通道号不在有效范围内");
            List<Device> updated=new ArrayList<>(devices);if(original==null)updated.add(value);else updated.set(devices.indexOf(original),value);
            if(persist(updated)){dialog.dismiss();toast("宇视设备已保存");home();}
        }catch(Exception e){message.setText(e instanceof NumberFormatException?"端口和通道号必须为数字":error(e));}}));dialog.show();
    }
    private void editHikvision(Device original) {
        Device source = original == null ? new Device() : original;
        ScrollView scroll = new ScrollView(this); LinearLayout form = column();
        form.setPadding(dp(20), dp(8), dp(20), dp(8)); scroll.addView(form);
        EditText name = field(form, "设备名称", source.name, false);
        EditText host = field(form, "设备 IP / 域名", source.host, false);
        host.setHint("192.168.1.64");
        EditText port = field(form, "SDK 端口", Integer.toString(source.sdkPort), false);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText user = field(form, "账号", source.user, false);
        EditText password = field(form, "密码", source.password, true);
        EditText channel = field(form, "SDK 通道号（0 = 首个通道）", Integer.toString(source.sdkChannel), false);
        channel.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText voice = field(form, "设备对讲通道号", Integer.toString(source.voiceChannel), false);
        voice.setInputType(InputType.TYPE_CLASS_NUMBER);
        CheckBox cameraTalk=new CheckBox(this);cameraTalk.setText("通过 NVR 对当前摄像机讲话（自动计算对讲通道）");cameraTalk.setChecked(source.voiceToCamera);form.addView(cameraTalk);
        text(form,"对讲编码（优先自动读取；手动值必须与设备一致）",13,ink);
        Spinner audioCodec=new Spinner(this);audioCodec.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"自动读取","G.711 μ-law / 8kHz","G.711 A-law / 8kHz"}));audioCodec.setSelection(Math.max(0,Math.min(2,source.voiceCodec)));form.addView(audioCodec);
        cameraTalk.setOnCheckedChangeListener((button,checked)->voice.setEnabled(!checked));voice.setEnabled(!source.voiceToCamera);
        CheckBox substream = new CheckBox(this); substream.setText("使用子码流（节省带宽）");
        substream.setChecked(source.sdkStream == 1); form.addView(substream);
        TextView message = text(form, "通过海康 SDK 直接登录设备，无需填写 RTSP 地址。NVR 的 SDK 通道号可能不同于界面上的摄像头序号。", 13, Color.DKGRAY);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("海康 SDK 设备")
            .setView(scroll).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                Device value = new Device(); if (original != null) value.id = original.id; value.connection = "hikvision-sdk"; value.brand = "海康威视 SDK";
                value.name = name.getText().toString().trim(); value.host = host.getText().toString().trim();
                if (value.name.isEmpty() || value.host.isEmpty()) throw new IllegalArgumentException("请填写名称和设备 IP / 域名");
                java.net.URI address = new java.net.URI("http://" + value.host);
                if (address.getHost() == null || address.getUserInfo() != null || address.getPort() != -1
                    || !address.getRawPath().isEmpty() || address.getQuery() != null || address.getFragment() != null)
                    throw new IllegalArgumentException("设备地址只填写 IP / 域名，端口单独填写");
                value.sdkPort = Integer.parseInt(port.getText().toString().trim());
                value.sdkChannel = Integer.parseInt(channel.getText().toString().trim());
                value.voiceToCamera=cameraTalk.isChecked();
                value.voiceChannel=value.voiceToCamera?1:Integer.parseInt(voice.getText().toString().trim());
                value.voiceCodec=audioCodec.getSelectedItemPosition();
                if (value.sdkPort < 1 || value.sdkPort > 65535 || value.sdkChannel < 0 || value.sdkChannel > 65535
                    || value.voiceChannel < 1 || value.voiceChannel > 65535) throw new IllegalArgumentException("端口或通道号不在有效范围内");
                value.user = user.getText().toString(); value.password = password.getText().toString();
                if (value.user.isEmpty()) throw new IllegalArgumentException("请填写设备账号");
                value.sdkStream = substream.isChecked() ? 1 : 0;
                List<Device> updated = new ArrayList<>(devices);
                if (original == null) updated.add(value); else updated.set(devices.indexOf(original), value);
                if (persist(updated)) { dialog.dismiss(); toast("设备和对讲配置已保存"); home(); }
            } catch (Exception e) { message.setText(e instanceof NumberFormatException ? "端口和通道号必须为数字" : error(e)); }
        }));
        dialog.show();
    }
    private void editStandard(Device original) {
        Device source = original == null ? new Device() : original;
        ScrollView scroll = new ScrollView(this); LinearLayout form = column(); form.setPadding(dp(20), dp(8), dp(20), dp(8)); scroll.addView(form);
        EditText name = field(form, "设备名称", source.name, false);
        text(form, "设备品牌（仅作标记，当前统一使用标准协议）", 13, ink);
        Spinner brand = new Spinner(this);
        String[] brands = {"通用 ONVIF", "海康威视", "大华", "宇视", "国标网关输出 RTSP"};
        brand.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, brands)); form.addView(brand);
        for (int i = 0; i < brands.length; i++) if (brands[i].equals(source.brand)) brand.setSelection(i);
        EditText endpoint = field(form, "ONVIF 服务地址（可选）", source.endpoint, false);
        endpoint.setHint("http://192.168.1.64/onvif/device_service");
        EditText user = field(form, "账号", source.user, false);
        EditText password = field(form, "密码", source.password, true);
        EditText stream = field(form, "RTSP 视频地址（可自动读取）", source.stream, false);
        stream.setHint("rtsp://192.168.1.64:554/...");
        EditText back = field(form, "对讲 RTSP 地址（留空则同视频）", source.talkStream, false);
        EditText token = field(form, "ONVIF ProfileToken（自动读取）", source.profile, false);
        EditText ptz = field(form, "ONVIF 云台服务地址（自动读取）", source.ptz, false);
        text(form, "ONVIF 账号可能需在设备管理页面单独启用。海康 8000 是 SDK 端口，不能填为 ONVIF 地址。", 13, Color.DKGRAY);
        TextView message = text(form, "配置加密保存在本机。", 13, teal);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(original == null ? "添加设备" : "编辑设备")
            .setView(scroll).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        Button discover = button(form, "连接设备并选择通道", v -> {});
        discover.setOnClickListener(v -> {
            String address = endpoint.getText().toString().trim();
            try { Device.validateUrl(address, false); } catch (Exception e) { message.setText(e.getMessage()); return; }
            String username = user.getText().toString(), secret = password.getText().toString();
            discover.setEnabled(false); message.setText("正在读取通道…");
            network.execute(() -> {
                try {
                    OnvifClient client = new OnvifClient(username, secret);
                    List<OnvifClient.Profile> profiles = client.discover(address);
                    ui.post(() -> {
                        if (!dialog.isShowing() || isDestroyed()) return;
                        String[] names = new String[profiles.size()];
                        for (int i = 0; i < names.length; i++) names[i] = profiles.get(i).name + " · " + profiles.get(i).token;
                        new AlertDialog.Builder(this).setTitle("选择通道 / 码流").setItems(names, (picker, index) -> {
                            message.setText("正在读取视频地址…");
                            network.execute(() -> {
                                try {
                                    OnvifClient.Profile selected = profiles.get(index);
                                    String uri = client.stream(selected); Device.validateUrl(uri, true);
                                    ui.post(() -> {
                                        if (!dialog.isShowing() || isDestroyed()) return;
                                        stream.setText(uri); token.setText(selected.token); ptz.setText(selected.ptz);
                                        message.setText(selected.ptz.isEmpty() ? "视频地址已读取，该通道未提供云台配置" : "视频与云台配置已读取");
                                        discover.setEnabled(true);
                                    });
                                } catch (Exception e) { ui.post(() -> { if (dialog.isShowing()) { message.setText(error(e)); discover.setEnabled(true); } }); }
                            });
                        }).setOnCancelListener(d -> { discover.setEnabled(true); message.setText("已取消选择"); }).show();
                    });
                } catch (Exception e) { ui.post(() -> { if (dialog.isShowing()) { message.setText(error(e)); discover.setEnabled(true); } }); }
            });
        });
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Device value = new Device(); if (original != null) value.id = original.id;
            value.name = name.getText().toString().trim(); value.brand = brands[brand.getSelectedItemPosition()];
            value.endpoint = endpoint.getText().toString().trim(); value.stream = stream.getText().toString().trim();
            value.user = user.getText().toString(); value.password = password.getText().toString();
            value.profile = token.getText().toString().trim(); value.ptz = ptz.getText().toString().trim(); value.talkStream = back.getText().toString().trim();
            try {
                if (value.name.isEmpty()) throw new IllegalArgumentException("请填写设备名称");
                Device.validateUrl(value.stream, true);
                if (!value.endpoint.isEmpty()) Device.validateUrl(value.endpoint, false);
                if (!value.ptz.isEmpty()) Device.validateUrl(value.ptz, false);
                if (!value.talkStream.isEmpty()) Device.validateUrl(value.talkStream, true);
                if (!value.ptz.isEmpty() && value.profile.isEmpty()) throw new IllegalArgumentException("云台控制需要 ProfileToken");
                List<Device> updated = new ArrayList<>(devices);
                if (original == null) updated.add(value); else updated.set(devices.indexOf(original), value);
                if (persist(updated)) { dialog.dismiss(); home(); }
            } catch (Exception e) { message.setText(error(e)); }
        }));
        dialog.show();
    }
    private boolean persist(List<Device> values) {
        try { store.save(values); devices.clear(); devices.addAll(values); return true; }
        catch (Exception e) { toast("设备保存失败，请检查本机存储"); return false; }
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER); return l; }
    private TextView text(LinearLayout parent, String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setPadding(0, dp(6), 0, dp(6));
        if (size >= 20) view.setTypeface(null, Typeface.BOLD); parent.addView(view); return view;
    }
    private Button button(LinearLayout parent, String title, View.OnClickListener action) {
        Button b = new Button(this); b.setText(title); b.setAllCaps(false); b.setTextColor(ink); b.setTextSize(14); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setOnClickListener(action);
        LinearLayout.LayoutParams p = parent.getOrientation() == LinearLayout.HORIZONTAL ? new LinearLayout.LayoutParams(0, dp(48), 1) : new LinearLayout.LayoutParams(-1, dp(48));
        p.setMargins(dp(2), dp(4), dp(2), dp(4)); parent.addView(b, p); return b;
    }
    private EditText field(LinearLayout parent, String label, String value, boolean secret) {
        text(parent, label, 13, ink); EditText e = new EditText(this); e.setSingleLine(true); e.setTextSize(14);
        e.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setText(value); e.setSaveEnabled(false); parent.addView(e, new LinearLayout.LayoutParams(-1, dp(48))); return e;
    }
    private GradientDrawable shape(int color) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(16)); return d; }
    private void margins(LinearLayout parent, View view) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(16), 0, 0); parent.addView(view, p); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private static String error(Exception e) {
        return e instanceof IllegalStateException || e instanceof IllegalArgumentException ? e.getMessage() : "连接失败，请检查网络、服务地址、设备时间和账号权限";
    }
}
