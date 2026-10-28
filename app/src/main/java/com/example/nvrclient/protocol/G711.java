package com.example.nvrclient.protocol;

/** ITU G.711 encoders for mono, 8000 Hz PCM16. */
public final class G711 {
    private G711() {}
    private static final int[] ENDS = {0xff, 0x1ff, 0x3ff, 0x7ff, 0xfff, 0x1fff, 0x3fff, 0x7fff};
    public static byte encode(short sample, boolean alaw) {
        int pcm = sample;
        if (alaw) {
            int mask;
            if (pcm >= 0) mask = 0xd5;
            else { mask = 0x55; pcm = -pcm - 1; }
            int segment = 0;
            while (segment < 8 && pcm > ENDS[segment]) segment++;
            int value = segment >= 8 ? 0x7f : (segment << 4) | ((pcm >> (segment < 2 ? 4 : segment + 3)) & 15);
            return (byte) (value ^ mask);
        }
        int sign = (pcm >> 8) & 0x80;
        if (sign != 0) pcm = -pcm;
        pcm = Math.min(pcm, 32635) + 132;
        int exponent = 7;
        for (int mask = 0x4000; exponent > 0 && (pcm & mask) == 0; mask >>= 1) exponent--;
        return (byte) ~(sign | (exponent << 4) | ((pcm >> (exponent + 3)) & 15));
    }
}
