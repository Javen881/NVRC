package com.example.nvrclient;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Synchronous AAC-LC encoder used by Hikvision raw voice forwarding. */
final class AndroidAacEncoder implements AutoCloseable {
    private final MediaCodec codec;
    private final int sampleRate;
    private long samples;

    AndroidAacEncoder(int sampleRate, int bitRate) throws Exception {
        this.sampleRate=sampleRate;
        MediaFormat format=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,sampleRate,1);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE,bitRate);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,4096);
        codec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
    }

    List<byte[]> encode(short[] pcm,int count) throws Exception {
        int input=codec.dequeueInputBuffer(10000);
        if(input<0)throw new IllegalStateException("AAC 编码器未接受音频帧");
        ByteBuffer buffer=codec.getInputBuffer(input);
        if(buffer==null||buffer.capacity()<count*2)throw new IllegalStateException("AAC 编码输入缓冲区不足");
        buffer.clear();
        for(int i=0;i<count;i++)buffer.put((byte)pcm[i]).put((byte)(pcm[i]>>>8));
        long timestamp=samples*1_000_000L/sampleRate;samples+=count;
        codec.queueInputBuffer(input,0,count*2,timestamp,0);
        ArrayList<byte[]> frames=new ArrayList<>();
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        for(int tries=0;tries<4;tries++){
            int output=codec.dequeueOutputBuffer(info,tries==0?10000:0);
            if(output==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED||output==MediaCodec.INFO_TRY_AGAIN_LATER)continue;
            if(output<0)continue;
            ByteBuffer encoded=codec.getOutputBuffer(output);
            if(encoded!=null&&info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){
                byte[] data=new byte[info.size];encoded.position(info.offset);encoded.limit(info.offset+info.size);encoded.get(data);frames.add(data);
            }
            codec.releaseOutputBuffer(output,false);
        }
        return frames;
    }

    @Override public void close(){try{codec.stop();}finally{codec.release();}}
}
