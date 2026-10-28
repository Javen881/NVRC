package com.example.nvrclient;

import android.content.Context;
import android.view.*;

/** Invisible drag control. Touch release/cancel/multitouch always stops movement. */
final class PreviewPtzTouch implements View.OnTouchListener {
    interface Listener { boolean begin();void move(float pan,float tilt);void stop(); }
    private final GestureDetector detector;private final Listener listener;private final float threshold;
    private boolean dragging,blocked;private int last=-1;
    PreviewPtzTouch(Context context,View target,Listener listener){
        this.listener=listener;threshold=18*context.getResources().getDisplayMetrics().density;
        detector=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onSingleTapUp(MotionEvent e){if(!blocked)target.performClick();return true;}
            @Override public void onLongPress(MotionEvent e){if(!dragging&&!blocked)target.performLongClick();}
            @Override public boolean onScroll(MotionEvent first,MotionEvent current,float distanceX,float distanceY){
                if(first==null||blocked)return true;
                float[] direction=direction(current.getX()-first.getX(),current.getY()-first.getY(),threshold);
                int command=PtzDirection.command(direction[0],direction[1],0);
                if(command<0){if(dragging&&last>=0){listener.stop();last=-1;}return true;}
                if(!dragging){if(!listener.begin()){blocked=true;return true;}dragging=true;target.getParent().requestDisallowInterceptTouchEvent(true);}
                if(command!=last){listener.move(direction[0],direction[1]);last=command;}return true;
            }
        });
    }
    static float[] direction(float dx,float dy,float threshold){
        float x=Math.abs(dx),y=Math.abs(dy);if(Math.hypot(x,y)<threshold)return new float[]{0,0};
        float pan=Math.signum(dx)*.5f,tilt=-Math.signum(dy)*.5f;
        if(x>y*2.41421356f)tilt=0;else if(y>x*2.41421356f)pan=0;
        return new float[]{pan,tilt};
    }
    @Override public boolean onTouch(View view,MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){dragging=false;blocked=false;last=-1;}
        if(action==MotionEvent.ACTION_POINTER_DOWN){stop(view);blocked=true;MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);detector.onTouchEvent(cancel);cancel.recycle();return true;}
        if(!blocked)detector.onTouchEvent(event);
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){stop(view);blocked=false;}
        return true;
    }
    private void stop(View view){if(dragging)listener.stop();dragging=false;last=-1;if(view.getParent()!=null)view.getParent().requestDisallowInterceptTouchEvent(false);}
}
