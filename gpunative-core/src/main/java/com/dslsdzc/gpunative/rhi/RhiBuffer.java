package com.dslsdzc.gpunative.rhi;

import java.nio.ByteBuffer;

public interface RhiBuffer {
    enum Type {
        VERTEX,
        INDEX,
        UNIFORM,
        STORAGE,
        INDIRECT
    }

    enum Usage {
        STATIC,
        DYNAMIC,
        STREAM
    }

    Type getType();
    long getSize();
    Usage getUsage();

    void upload(long offset, ByteBuffer data);
    void upload(ByteBuffer data);
    ByteBuffer download(long offset, long size);
    void copyTo(RhiBuffer target, long srcOffset, long dstOffset, long size);
    ByteBuffer mapMemory(long offset, long size, boolean readable, boolean writable);
    void unmapMemory();
    long getHandle();
    void destroy();
}
