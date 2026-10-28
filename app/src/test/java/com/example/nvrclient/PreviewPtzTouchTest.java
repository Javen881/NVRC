package com.example.nvrclient;

import android.app.Activity;
import android.os.SystemClock;
import android.view.*;
import android.widget.FrameLayout;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class PreviewPtzTouchTest {
    @Test public void eightDirectionsAndDeadZone(){
        assertEquals(25,PtzDirection.command(-.5f,.5f,0));assertEquals(26,PtzDirection.command(.5f,.5f,0));assertEquals(27,PtzDirection.command(-.5f,-.5f,0));assertEquals(28,PtzDirection.command(.5f,-.5f,0));
        assertEquals(21,PtzDirection.command(0,.5f,0));assertEquals(22,PtzDirection.command(0,-.5f,0));assertEquals(23,PtzDirection.command(-.5f,0,0));assertEquals(24,PtzDirection.command(.5f,0,0));assertEquals(-1,PtzDirection.command(0,0,0));
        assertArrayEquals(new float[]{0,0},PreviewPtzTouch.direction(2,3,18),.001f);assertArrayEquals(new float[]{.5f,.5f},PreviewPtzTouch.direction(100,-100,18),.001f);assertArrayEquals(new float[]{.5f,0},PreviewPtzTouch.direction(100,5,18),.001f);
    }
    @Test public void dragChangesDirectionAndStopsOnUpCancelOrSecondFinger(){
        try(org.robolectric.android.controller.ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup()){
            FrameLayout parent=new FrameLayout(c.get());View target=new View(c.get());parent.addView(target);c.get().setContentView(parent);List<Integer> commands=new ArrayList<>();
            PreviewPtzTouch touch=new PreviewPtzTouch(c.get(),target,new PreviewPtzTouch.Listener(){public boolean begin(){return true;}public void move(float x,float y){commands.add(PtzDirection.command(x,y,0));}public void stop(){commands.add(0);}});
            long t=SystemClock.uptimeMillis();send(touch,target,t,0,MotionEvent.ACTION_DOWN,200,200);send(touch,target,t,40,MotionEvent.ACTION_MOVE,400,0);send(touch,target,t,60,MotionEvent.ACTION_MOVE,0,0);send(touch,target,t,80,MotionEvent.ACTION_UP,0,0);assertEquals(Arrays.asList(26,25,0),commands);
            commands.clear();send(touch,target,t+100,0,MotionEvent.ACTION_DOWN,200,200);send(touch,target,t+100,40,MotionEvent.ACTION_MOVE,400,400);send(touch,target,t+100,60,MotionEvent.ACTION_CANCEL,400,400);assertEquals(Arrays.asList(28,0),commands);
            commands.clear();send(touch,target,t+200,0,MotionEvent.ACTION_DOWN,200,200);send(touch,target,t+200,40,MotionEvent.ACTION_MOVE,400,200);send(touch,target,t+200,60,MotionEvent.ACTION_POINTER_DOWN,400,200);send(touch,target,t+200,80,MotionEvent.ACTION_MOVE,0,200);send(touch,target,t+200,100,MotionEvent.ACTION_UP,0,200);assertEquals(Arrays.asList(24,0),commands);
        }
    }
    private void send(PreviewPtzTouch touch,View target,long down,long offset,int action,float x,float y){MotionEvent e=MotionEvent.obtain(down,down+offset,action,x,y,0);touch.onTouch(target,e);e.recycle();}
}
