package com.example.nvrclient;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.Gravity;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Lists original recordings and exports through Android's document picker. */
public final class RecordingsActivity extends Activity {
    private File pending;
    @Override public void onCreate(Bundle state){super.onCreate(state);if(state!=null&&state.getString("file")!=null)pending=new File(state.getString("file"));showFiles();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);if(pending!=null)state.putString("file",pending.getAbsolutePath());}
    private void showFiles(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(16,16,16,16);root.setBackgroundColor(MonitorWidgets.BG);setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(16+insets.getSystemWindowInsetLeft(),16+insets.getSystemWindowInsetTop(),16+insets.getSystemWindowInsetRight(),16+insets.getSystemWindowInsetBottom());return insets;});
        Button back=new Button(this);back.setText("‹ 返回 · 本地录像");root.addView(back);back.setOnClickListener(v->finish());
        TextView help=new TextView(this);help.setText("点击文件导出到手机目录。PS 为设备原始码流，需要支持该格式的播放器。录制中的文件请先停止再导出。");root.addView(help);
        Button refresh=new Button(this);refresh.setText("刷新文件列表");root.addView(refresh);refresh.setOnClickListener(v->showFiles());
        ListView list=new ListView(this);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        File dir=getExternalFilesDir(Environment.DIRECTORY_MOVIES);File[] files=dir==null?null:dir.listFiles(f->f.isFile()&&(f.getName().endsWith(".ps")||f.getName().endsWith(".mp4")));
        if(files==null)files=new File[0];Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
        final File[] items=files;String[] titles=new String[items.length];for(int i=0;i<items.length;i++)titles[i]=items[i].getName()+"\n"+String.format(Locale.CHINA,"%.2f MB",items[i].length()/1048576.0)+(HikSession.isRecordingFile(items[i])?" · 正在录制":"");
        list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,titles));
        if(items.length==0){TextView empty=new TextView(this);empty.setText("暂无本地录像");empty.setGravity(Gravity.CENTER);root.addView(empty);}
        list.setOnItemClickListener((parent,view,index,id)->{
            File file=items[index];if(HikSession.isRecordingFile(file)){toast("请先停止此文件的录像");return;}if(file.length()==0){toast("此文件没有录像数据，请检查设备码流");return;}
            pending=file;startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(file.getName().endsWith(".ps")?"video/mpeg":"video/mp4").putExtra(Intent.EXTRA_TITLE,file.getName()),61);
        });
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=61)return;
        File source=pending;pending=null;if(result!=RESULT_OK||data==null||data.getData()==null||source==null)return;
        android.net.Uri destination=data.getData();android.content.Context app=getApplicationContext();
        new Thread(()->{String message;try(InputStream in=new FileInputStream(source);OutputStream out=app.getContentResolver().openOutputStream(destination,"w")){
            if(out==null)throw new IOException();byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);out.flush();message="录像已导出";
        }catch(Exception error){message="导出失败，原始文件仍保留，请检查存储空间";}String done=message;new Handler(Looper.getMainLooper()).post(()->Toast.makeText(app,done,Toast.LENGTH_LONG).show());},"nvr-export").start();
    }
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
}
