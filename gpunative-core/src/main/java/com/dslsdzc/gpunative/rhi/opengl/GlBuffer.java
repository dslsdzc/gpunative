package com.dslsdzc.gpunative.rhi.opengl;

import com.dslsdzc.gpunative.rhi.RhiBuffer;

import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.GL44.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

public class GlBuffer implements RhiBuffer {
    private final Type type;
    private final long size;
    private final Usage usage;
    private final int glTarget;
    private final int glUsage;
    private int glBufferId;
    private boolean destroyed;

    GlBuffer(Type type, long size, Usage usage, ByteBuffer initialData) {
        this.type = type;
        this.size = size;
        this.usage = usage;
        this.glTarget = getGlTarget(type);
        this.glUsage = getGlUsage(usage);

        this.glBufferId = glGenBuffers();
        glBindBuffer(glTarget, glBufferId);
        glBufferData(glTarget, size, glUsage);

        if (initialData != null) {
            glBufferSubData(glTarget, 0, initialData);
        }
        glBindBuffer(glTarget, 0);
    }

    @Override
    public Type getType() { return type; }

    @Override
    public long getSize() { return size; }

    @Override
    public Usage getUsage() { return usage; }

    @Override
    public void upload(long offset, ByteBuffer data) {
        checkNotDestroyed();
        glBindBuffer(glTarget, glBufferId);
        glBufferSubData(glTarget, offset, data);
        glBindBuffer(glTarget, 0);
    }

    @Override
    public void upload(ByteBuffer data) {
        upload(0, data);
    }

    @Override
    public ByteBuffer download(long offset, long size) {
        checkNotDestroyed();
        glBindBuffer(glTarget, glBufferId);

        ByteBuffer data = glMapBufferRange(glTarget, offset, size, GL_MAP_READ_BIT);
        if (data == null) {
            data = ByteBuffer.allocateDirect((int) size);
            glGetBufferSubData(glTarget, offset, data);
        } else {
            ByteBuffer result = ByteBuffer.allocateDirect(data.remaining());
            result.put(data);
            result.flip();
            glUnmapBuffer(glTarget);
            return result;
        }

        glBindBuffer(glTarget, 0);
        return data;
    }

    @Override
    public void copyTo(RhiBuffer target, long srcOffset, long dstOffset, long size) {
        checkNotDestroyed();
        if (!(target instanceof GlBuffer glTarget)) {
            throw new IllegalArgumentException("Target must be a GlBuffer");
        }

        glBindBuffer(GL_COPY_READ_BUFFER, this.glBufferId);
        glBindBuffer(GL_COPY_WRITE_BUFFER, glTarget.glBufferId);
        glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, srcOffset, dstOffset, size);
        glBindBuffer(GL_COPY_READ_BUFFER, 0);
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
    }

    @Override
    public ByteBuffer mapMemory(long offset, long size, boolean readable, boolean writable) {
        checkNotDestroyed();
        int access = 0;
        if (readable) access |= GL_MAP_READ_BIT;
        if (writable) access |= GL_MAP_WRITE_BIT;
        glBindBuffer(glTarget, glBufferId);
        ByteBuffer mapped = glMapBufferRange(glTarget, offset, size, access);
        glBindBuffer(glTarget, 0);
        return mapped;
    }

    @Override
    public void unmapMemory() {
        checkNotDestroyed();
        glBindBuffer(glTarget, glBufferId);
        glUnmapBuffer(glTarget);
        glBindBuffer(glTarget, 0);
    }

    @Override
    public long getHandle() {
        return glBufferId;
    }

    @Override
    public void destroy() {
        if (!destroyed && glBufferId != 0) {
            glDeleteBuffers(glBufferId);
            glBufferId = 0;
            destroyed = true;
        }
    }

    void bind(int target) {
        checkNotDestroyed();
        glBindBuffer(target, glBufferId);
    }

    void bindBase(int target, int index) {
        checkNotDestroyed();
        glBindBufferBase(target, index, glBufferId);
    }

    void bindRange(int target, int index, long offset, long size) {
        checkNotDestroyed();
        glBindBufferRange(target, index, glBufferId, offset, size);
    }

    private void checkNotDestroyed() {
        if (destroyed) throw new IllegalStateException("Buffer is destroyed");
    }

    static int getGlTarget(Type type) {
        return switch (type) {
            case VERTEX -> GL_ARRAY_BUFFER;
            case INDEX -> GL_ELEMENT_ARRAY_BUFFER;
            case UNIFORM -> GL_UNIFORM_BUFFER;
            case STORAGE -> GL_SHADER_STORAGE_BUFFER;
            case INDIRECT -> GL_DRAW_INDIRECT_BUFFER;
        };
    }

    static int getGlUsage(Usage usage) {
        return switch (usage) {
            case STATIC -> GL_STATIC_DRAW;
            case DYNAMIC -> GL_DYNAMIC_DRAW;
            case STREAM -> GL_STREAM_DRAW;
        };
    }
}
