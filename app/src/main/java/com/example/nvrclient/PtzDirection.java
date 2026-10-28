package com.example.nvrclient;

import com.hikvision.netsdk.PTZCommand;

final class PtzDirection {
    static int command(float pan,float tilt,float zoom){
        if(zoom>0)return PTZCommand.ZOOM_IN;
        if(zoom<0)return PTZCommand.ZOOM_OUT;
        if(tilt>0)return pan<0?PTZCommand.UP_LEFT:pan>0?PTZCommand.UP_RIGHT:PTZCommand.TILT_UP;
        if(tilt<0)return pan<0?PTZCommand.DOWN_LEFT:pan>0?PTZCommand.DOWN_RIGHT:PTZCommand.TILT_DOWN;
        return pan<0?PTZCommand.PAN_LEFT:pan>0?PTZCommand.PAN_RIGHT:-1;
    }
}
