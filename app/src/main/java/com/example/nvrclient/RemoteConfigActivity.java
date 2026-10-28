package com.example.nvrclient;

import android.app.*;
import android.os.*;
import android.content.Intent;
import android.graphics.Color;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class RemoteConfigActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private HikRemoteConfig client;private HikRemoteConfig.Snapshot loaded;
    private LinearLayout form;private TextView status;private Spinner sections;private Button save,reload;
    private final Map<String,Supplier<String>> inputs=new LinkedHashMap<>();
    private boolean busy;private Bundle draft;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);draft=state==null?null:state.getBundle("draft");
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(MonitorWidgets.BG);setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        TextView heading=new TextView(this);heading.setText("‹  远程参数配置");heading.setTextSize(20);heading.setTextColor(Color.WHITE);heading.setBackgroundColor(MonitorWidgets.RED);heading.setPadding(dp(16),dp(16),dp(16),dp(16));root.addView(heading);heading.setOnClickListener(v->finish());
        TextView scope=new TextView(this);scope.setText("图像、码流和文字针对所选通道；信息、时间和网络针对登录设备。");scope.setTextSize(12);scope.setPadding(dp(16),dp(8),dp(16),0);root.addView(scope);
        status=new TextView(this);status.setTextColor(MonitorWidgets.INK);status.setPadding(dp(16),dp(10),dp(16),dp(10));root.addView(status);
        sections=new Spinner(this);String[] names=Arrays.stream(HikRemoteConfig.Section.values()).map(s->s.title).toArray(String[]::new);sections.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));root.addView(sections);
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(16),0,dp(16),dp(12));scroll.addView(form);
        LinearLayout actions=new LinearLayout(this);root.addView(actions);reload=new Button(this);reload.setText("重新读取");actions.addView(reload,new LinearLayout.LayoutParams(0,dp(52),1));save=new Button(this);save.setText("保存到摄像机");save.setTextColor(MonitorWidgets.RED);actions.addView(save,new LinearLayout.LayoutParams(0,dp(52),1));save.setEnabled(false);
        reload.setOnClickListener(v->{draft=null;read();});save.setOnClickListener(v->save());
        try{
            Device selected=null;String id=getIntent().getStringExtra("deviceId");for(Device d:new DeviceStore(this).load())if(d.id.equals(id)){selected=d;break;}
            if(selected==null||!selected.hikvision()){status.setText("请在摄像机 Tab 中选择已添加的海康 SDK 设备，再打开远程参数配置。");sections.setEnabled(false);reload.setEnabled(false);return;}
            client=new HikRemoteConfig(selected);heading.setText("‹  远程配置 · "+selected.name);
            sections.setSelection(state==null?0:Math.min(HikRemoteConfig.Section.values().length-1,state.getInt("section",0)));
            sections.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int index,long id){read();}});
        }catch(Exception error){status.setText("读取本机设备列表失败，请返回摄像机 Tab 检查连接配置。");sections.setEnabled(false);reload.setEnabled(false);}
    }
    private HikRemoteConfig.Section section(){return HikRemoteConfig.Section.values()[sections.getSelectedItemPosition()];}
    private void busy(boolean value){busy=value;sections.setEnabled(!value&&client!=null);reload.setEnabled(!value&&client!=null);save.setEnabled(!value&&client!=null&&loaded!=null&&loaded.editable());for(int i=0;i<form.getChildCount();i++)form.getChildAt(i).setEnabled(!value);}
    private void read(){
        if(client==null||busy)return;HikRemoteConfig.Section section=section();loaded=null;inputs.clear();form.removeAllViews();busy(true);status.setText("正在读取摄像机 · "+section.title+"…");
        worker.execute(()->{try{HikRemoteConfig.Snapshot result=client.read(section);ui.post(()->{if(isDestroyed())return;render(result);busy(false);status.setText(section==HikRemoteConfig.Section.NETWORK?"设备网络信息（只读）":result.editable()?"已读取设备实际参数；修改后点击保存到摄像机。":"已读取设备基本信息");});}catch(RuntimeException|LinkageError error){ui.post(()->{if(isDestroyed())return;busy(false);save.setEnabled(false);status.setText(message(error));});}});
    }
    void render(HikRemoteConfig.Snapshot snapshot){
        loaded=snapshot;inputs.clear();form.removeAllViews();
        for(HikRemoteConfig.Field field:snapshot.fields){
            TextView label=new TextView(this);label.setText(field.label);label.setTextColor(MonitorWidgets.INK);label.setTextSize(13);label.setPadding(0,dp(12),0,dp(4));form.addView(label);
            String value=draft!=null&&draft.containsKey(field.id)?draft.getString(field.id):field.value;
            if(!field.editable()){TextView text=new TextView(this);text.setText(field.value);text.setTextIsSelectable(true);text.setTextSize(16);form.addView(text);continue;}
            if(field.options!=null){
                Spinner spinner=new Spinner(this);List<String> keys=new ArrayList<>(field.options.keySet());spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new ArrayList<>(field.options.values())));spinner.setSelection(Math.max(0,keys.indexOf(value)));spinner.setContentDescription(field.label);form.addView(spinner,new LinearLayout.LayoutParams(-1,dp(48)));inputs.put(field.id,()->keys.get(spinner.getSelectedItemPosition()));
            }else{
                EditText edit=new EditText(this);edit.setText(value);edit.setSingleLine(true);edit.setTextSize(16);edit.setSaveEnabled(false);edit.setContentDescription(field.label);if(field.numeric)edit.setInputType(InputType.TYPE_CLASS_NUMBER);form.addView(edit,new LinearLayout.LayoutParams(-1,dp(48)));inputs.put(field.id,()->edit.getText().toString().trim());
            }
        }
        draft=null;save.setVisibility(snapshot.editable()?View.VISIBLE:View.GONE);
        if(snapshot.section==HikRemoteConfig.Section.MAIN||snapshot.section==HikRemoteConfig.Section.SUB){TextView note=new TextView(this);note.setText("设备支持的取值因型号而异。不支持的组合会返回错误；视频编码变更后预览可能重连。这里的码流音频不控制语音对讲，请到“语音对讲”页设置。");form.addView(note);}
        if(snapshot.section==HikRemoteConfig.Section.TALK){TextView note=new TextView(this);note.setText("这是设备的独立语音对讲编码。部分型号固定使用 G.722 并拒绝修改，这是正常限制；客户端会自动读取并匹配当前编码，无需强制保存。");form.addView(note);}
        if(snapshot.section==HikRemoteConfig.Section.TIME){TextView note=new TextView(this);note.setText("填写设备所在时区的本地时间。此处不更改时区和 NTP 设置。");form.addView(note);}
    }
    private void save(){
        if(busy||loaded==null||client==null)return;Map<String,String> changes=new LinkedHashMap<>();for(Map.Entry<String,Supplier<String>> item:inputs.entrySet()){String value=item.getValue().get();if(!value.equals(loaded.field(item.getKey()).value))changes.put(item.getKey(),value);}
        if(changes.isEmpty()){status.setText("没有修改，无需下发");return;}HikRemoteConfig.Section section=loaded.section;busy(true);status.setText("正在保存到摄像机并回读校验…");
        worker.execute(()->{try{HikRemoteConfig.Snapshot result=client.save(section,changes);String differences=differences(changes,result);boolean verified=differences.isEmpty();
            if(section==HikRemoteConfig.Section.TALK&&verified)syncTalkCodec(result);
            ui.post(()->{if(isDestroyed())return;render(result);busy(false);status.setText(verified?"已保存到摄像机，回读校验一致":section==HikRemoteConfig.Section.TALK?"设备接受了写入，但当前回读尚未生效：\n"+differences+"\n该型号可能需要重启摄像机。":"设备接受了写入，但设备实际采用了不同值：\n"+differences);});
        }catch(RuntimeException|LinkageError error){ui.post(()->{if(isDestroyed())return;busy(false);if(!(error instanceof IllegalArgumentException))save.setEnabled(false);String text=message(error);if(section==HikRemoteConfig.Section.TALK&&text.contains("17"))text="设备不接受语音编码参数，说明该型号使用固定对讲编码。无需修改；客户端会自动匹配设备当前的 G.722/G.711 格式。";status.setText(text);});}});
    }
    private static String differences(Map<String,String> changes,HikRemoteConfig.Snapshot result){
        StringBuilder text=new StringBuilder();
        for(Map.Entry<String,String> entry:changes.entrySet()){
            HikRemoteConfig.Field field=result.field(entry.getKey());String actual=field.value;
            if(entry.getValue().equals(actual))continue;
            String requested=field.options==null?entry.getValue():field.options.getOrDefault(entry.getValue(),entry.getValue());
            String shown=field.options==null?actual:field.options.getOrDefault(actual,actual);
            if(text.length()>0)text.append('\n');text.append(field.label).append("：设定 ").append(requested).append("，实际 ").append(shown);
        }
        return text.toString();
    }
    private void syncTalkCodec(HikRemoteConfig.Snapshot result){
        int codec=Integer.parseInt(result.field("talkCodec").value);if(codec!=1&&codec!=2)return;
        try{
            DeviceStore store=new DeviceStore(this);List<Device> values=store.load();String id=getIntent().getStringExtra("deviceId");
            for(Device device:values)if(device.id.equals(id)){device.voiceCodec=codec;break;}store.save(values);
        }catch(Exception ignored){ui.post(()->status.append("\n设备已保存，但本机对讲编码同步失败；请在连接设置中手动选择相同编码。"));}
    }
    private String message(Throwable error){return error instanceof LinkageError?"海康原生库加载失败，请使用支持的 ARM 手机":error.getMessage()==null?"远程配置失败，请重试":error.getMessage();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putInt("section",sections.getSelectedItemPosition());if(!busy){Bundle values=new Bundle();for(Map.Entry<String,Supplier<String>> e:inputs.entrySet())values.putString(e.getKey(),e.getValue().get());state.putBundle("draft",values);}}
    @Override protected void onDestroy(){if(client!=null)worker.execute(()->{try{client.close();}catch(RuntimeException|LinkageError ignored){}});worker.shutdown();super.onDestroy();}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
