package com.dslsdzc.gpunative.rhi.opengl;

import java.nio.IntBuffer;

import com.dslsdzc.gpunative.rhi.RhiFence;

import static org.lwjgl.opengl.GL32.*;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL43.*;

import org.lwjgl.BufferUtils;

public class GlFence implements RhiFence {
    private long glSync;
    private boolean destroyed;

    GlFence() {
    }

    void signal() {
        if (destroyed) return;
        if (glSync != 0) {
            glDeleteSync(glSync);
        }
        glSync = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    @Override
    public Status waitFor(long timeoutNanos) {
        if (glSync == 0 || destroyed) return Status.ERROR;

        int result = glClientWaitSync(glSync, 0, timeoutNanos);
        return switch (result) {
            case GL_ALREADY_SIGNALED, GL_CONDITION_SATISFIED -> Status.READY;
            case GL_TIMEOUT_EXPIRED -> Status.NOT_READY;
            default -> Status.ERROR;
        };
    }

    @Override
    public Status getStatus() {
        if (glSync == 0 || destroyed) return Status.ERROR;
        IntBuffer params = BufferUtils.createIntBuffer(1);
        glGetSynci(glSync, GL_SYNC_STATUS, params);
        return params.get(0) == GL_SIGNALED
            ? Status.READY
            : Status.NOT_READY;
    }

    @Override
    public void reset() {
        if (glSync != 0) {
            glDeleteSync(glSync);
            glSync = 0;
        }
    }

    @Override
    public long getHandle() {
        return glSync;
    }

    @Override
    public void destroy() {
        if (!destroyed) {
            if (glSync != 0) {
                glDeleteSync(glSync);
                glSync = 0;
            }
            destroyed = true;
        }
    }
}
