package com.example.nvrclient;

import android.content.Context;
import android.opengl.GLSurfaceView;
import com.sdk.NetDEVSDK;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** OpenGL target used by the official NetDEV decoder. */
final class UniviewRenderView extends GLSurfaceView {
    private final int window;
    UniviewRenderView(Context context,int window){
        super(context);this.window=window;
        setEGLContextClientVersion(2);setPreserveEGLContextOnPause(true);
        setRenderer(new Renderer(){
            public void onSurfaceCreated(GL10 gl,EGLConfig config){NetDEVSDK.initializeRenderer(window);}
            public void onSurfaceChanged(GL10 gl,int width,int height){NetDEVSDK.setRendererViewport(width,height);}
            public void onDrawFrame(GL10 gl){NetDEVSDK.rendererRender(window);}
        });
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }
    int window(){return window;}
}
