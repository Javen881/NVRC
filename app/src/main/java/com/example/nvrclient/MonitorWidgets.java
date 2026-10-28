package com.example.nvrclient;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.*;

final class MonitorWidgets {
    static final int RED = Color.rgb(199, 39, 49), INK = Color.rgb(55, 58, 64), BG = Color.rgb(245, 246, 248);
    static final class Action extends View {
        private final Paint p = new Paint(3);
        private final String icon;
        private String label;
        private boolean active, holding, finishing, light;
        private Runnable down, up;
        Action(Context context, String icon, String label) {
            super(context); this.icon = icon; this.label = label; setContentDescription(label); setClickable(true); setFocusable(true);
        }
        void light(){light=true;invalidate();}
        void label(String value) { if (!label.equals(value)) { label=value; setContentDescription(value); invalidate(); } }
        void active(boolean value) { active = value; invalidate(); }
        void hold(Runnable press, Runnable release) { down = press; up = release; }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (down == null) return super.onTouchEvent(e);
            if (!isEnabled()) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: holding = true; setPressed(true); getParent().requestDisallowInterceptTouchEvent(true); down.run(); break;
                case MotionEvent.ACTION_MOVE:
                    if (e.getX() >= 0 && e.getX() < getWidth() && e.getY() >= 0 && e.getY() < getHeight()) break;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_UP:
                    if (holding) { holding = false; setPressed(false); up.run(); getParent().requestDisallowInterceptTouchEvent(false); }
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) { finishing = true; performClick(); finishing = false; }
                    break;
            }
            return true;
        }
        @Override public boolean performClick() { if (finishing) return true; return super.performClick(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            p.setColor(active ? RED : isEnabled() ? (light ? Color.WHITE : INK) : 0xff7c8086); p.setStrokeWidth(1.6f); p.setStyle(Paint.Style.STROKE);
            canvas.save(); canvas.translate(getWidth()/2f - 12*d, 10*d); canvas.scale(d, d);
            if (isPressed()) { p.setStyle(Paint.Style.FILL); p.setColor(0x18c72731); canvas.drawCircle(12, 12, 22, p); p.setStyle(Paint.Style.STROKE); p.setColor(RED); }
            switch (icon) {
                case "camera": canvas.drawRoundRect(1,5,23,21,2,2,p); canvas.drawRect(7,2,16,5,p); canvas.drawCircle(12,13,5,p); break;
                case "record": canvas.drawRoundRect(1,4,17,21,2,2,p); path(canvas,18,9,24,5,24,20,18,16); canvas.drawCircle(9,12,3,p); break;
                case "mic": canvas.drawRoundRect(8,1,16,16,4,4,p); canvas.drawArc(4,6,20,20,0,180,false,p); canvas.drawLine(12,20,12,24,p); canvas.drawLine(7,24,17,24,p); break;
                case "ptz": canvas.drawCircle(12,12,5,p); path(canvas,8,3,12,0,16,3); path(canvas,8,21,12,24,16,21); path(canvas,3,8,0,12,3,16); path(canvas,21,8,24,12,21,16); break;
                case "sound": path(canvas,2,8,7,8,13,3,13,22,7,17,2,17,2,8); canvas.drawArc(10,4,24,21,-65,130,false,p); break;
                case "close": canvas.drawRoundRect(2,2,19,19,2,2,p); canvas.drawLine(14,14,24,24,p); canvas.drawLine(24,14,14,24,p); break;
                case "menu": for(int y=5;y<=21;y+=7)canvas.drawLine(2,y,22,y,p); break;
                case "grid": for(int x=2;x<24;x+=12)for(int y=2;y<24;y+=12)canvas.drawRect(x,y,x+8,y+8,p); break;
                case "play": path(canvas,6,2,22,12,6,22,6,2); break;
                case "pause": canvas.drawRect(5,2,9,22,p); canvas.drawRect(16,2,20,22,p); break;
                case "cut": canvas.drawCircle(5,20,4,p); canvas.drawCircle(19,20,4,p); canvas.drawLine(7,16,20,1,p); canvas.drawLine(17,16,4,1,p); break;
                case "date": canvas.drawRoundRect(1,4,23,23,2,2,p); canvas.drawLine(1,10,23,10,p); canvas.drawLine(7,0,7,7,p); canvas.drawLine(17,0,17,7,p); break;
                case "add": canvas.drawCircle(12,12,11,p); canvas.drawLine(6,12,18,12,p); canvas.drawLine(12,6,12,18,p); break;
                case "back": path(canvas,17,2,7,12,17,22); break;
                case "speed": canvas.drawRect(1,2,23,23,p); p.setStyle(Paint.Style.FILL); p.setTextSize(12); p.setTextAlign(Paint.Align.CENTER); canvas.drawText("×",12,17,p); break;
                default: p.setStyle(Paint.Style.FILL); p.setTextSize(18); p.setTextAlign(Paint.Align.CENTER); canvas.drawText(icon,12,19,p);
            }
            canvas.restore(); p.setStyle(Paint.Style.FILL); p.setTextSize(10.5f*d); p.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(label,getWidth()/2f,getHeight()-8*d,p);
        }
        private void path(Canvas c,float...xy) { Path path=new Path();path.moveTo(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)path.lineTo(xy[i],xy[i+1]);c.drawPath(path,p); }
    }
    static final class Timeline extends View {
        interface Listener { void seek(long time); }
        private final Paint p = new Paint(3);
        private final SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd  HH:mm:ss",Locale.CHINA);
        private final SimpleDateFormat hour = new SimpleDateFormat("HH:mm",Locale.CHINA);
        private List<long[]> ranges = new ArrayList<>();
        long center = System.currentTimeMillis()-3600000;
        private final long span = 4*3600000L;
        private float downX;
        private long downTime;
        Listener listener;
        Timeline(Context c) { super(c); setBackgroundColor(Color.WHITE); setContentDescription("录像时间轴，左右拖动定位时间"); setClickable(true); }
        void setRanges(List<long[]> value) { ranges = value; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float d=getResources().getDisplayMetrics().density,w=getWidth();
            p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setColor(INK);p.setTextSize(14*d);
            c.drawText(date.format(new Date(center)),w/2,23*d,p);
            long left=center-span/2;
            for(long[] range:ranges) {p.setColor(0xff64b994);c.drawRect((range[0]-left)*w/span,58*d,(range[1]-left)*w/span,65*d,p);}
            p.setStrokeWidth(d);p.setTextSize(11*d);
            long step=30*60*1000L;
            for(long tick=Math.floorDiv(left,step)*step;tick<left+span;tick+=step) {
                float x=(tick-left)*w/span;p.setColor(0xffd5d7db);c.drawLine(x,49*d,x,70*d,p);p.setColor(0xff72767d);c.drawText(hour.format(new Date(tick)),x,43*d,p);
            }
            p.setColor(0xffd5d7db);c.drawLine(0,70*d,w,70*d,p);
            p.setColor(RED);c.drawLine(w/2,30*d,w/2,81*d,p);Path marker=new Path();marker.moveTo(w/2-4*d,28*d);marker.lineTo(w/2+4*d,28*d);marker.lineTo(w/2,34*d);marker.close();c.drawPath(marker,p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch(e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: downX=e.getX();downTime=center;getParent().requestDisallowInterceptTouchEvent(true);return true;
                case MotionEvent.ACTION_MOVE: center=Math.min(System.currentTimeMillis(),downTime-(long)((e.getX()-downX)*span/Math.max(1,getWidth())));invalidate();return true;
                case MotionEvent.ACTION_UP: getParent().requestDisallowInterceptTouchEvent(false);performClick();if(listener!=null)listener.seek(center);return true;
                case MotionEvent.ACTION_CANCEL: center=downTime;invalidate();getParent().requestDisallowInterceptTouchEvent(false);return true;
            }
            return true;
        }
        @Override public boolean performClick() {super.performClick();return true;}
    }
}
