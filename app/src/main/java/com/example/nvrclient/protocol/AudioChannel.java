package com.example.nvrclient.protocol;
public interface AudioChannel extends AutoCloseable {
    void open() throws Exception;
    default int sampleRate() { return 8000; }
    default int frameSamples() { return 160; }
    void send(short[] samples, int count) throws Exception;
    void teardown();
    @Override void close();
}
