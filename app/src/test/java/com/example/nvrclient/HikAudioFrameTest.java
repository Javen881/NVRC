package com.example.nvrclient;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class HikAudioFrameTest {
    @Test public void convertsPcm16ToLittleEndianForG722Encoder() {
        byte[] result=HikSession.pcm16le(new short[]{0,1,-1,(short)0x1234},4);
        assertArrayEquals(new byte[]{0,0,1,0,-1,-1,0x34,0x12},result);
    }

    @Test public void g722FrameHasExpectedPcmByteCount() {
        assertEquals(1280,HikSession.pcm16le(new short[640],640).length);
    }
}
