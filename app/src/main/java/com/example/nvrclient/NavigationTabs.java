package com.example.nvrclient;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.IntConsumer;

final class NavigationTabs {
    static LinearLayout create(Context context, int selected, IntConsumer navigate) {
        LinearLayout row=new LinearLayout(context);row.setBackgroundColor(Color.WHITE);
        int height=Math.round(52*context.getResources().getDisplayMetrics().density);
        String[] labels={"预览","回放","摄像机"};
        for(int i=0;i<labels.length;i++){
            final int index=i;TextView tab=new TextView(context);tab.setText(labels[i]);tab.setTextSize(15);
            tab.setContentDescription(labels[i]+" Tab");tab.setGravity(Gravity.CENTER);tab.setSelected(i==selected);
            tab.setTextColor(i==selected?MonitorWidgets.RED:MonitorWidgets.INK);
            tab.setBackgroundColor(i==selected?0xffffecee:Color.WHITE);
            row.addView(tab,new LinearLayout.LayoutParams(0,height,1));tab.setOnClickListener(v->navigate.accept(index));
        }
        return row;
    }
}
