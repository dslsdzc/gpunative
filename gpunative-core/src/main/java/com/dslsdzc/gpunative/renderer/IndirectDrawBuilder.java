package com.dslsdzc.gpunative.renderer;

import com.dslsdzc.gpunative.rhi.RhiBuffer;
import com.dslsdzc.gpunative.rhi.RhiCommandList;
import com.dslsdzc.gpunative.rhi.opengl.GlDevice;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Builds and manages indirect draw command buffers.
 * Each draw command is 20 bytes (5 x uint32):
 *   count, instanceCount, firstIndex, baseVertex, firstInstance
 *
 * For GL 3.2 fallback, the buffer's first uint32 acts as the
 * visible-instance count read back by the CPU.
 */
public class IndirectDrawBuilder {
    private static final int COMMAND_SIZE_BYTES = 20;
    private static final int INITIAL_CAPACITY = 1024;

    private final GlDevice device;
    private RhiBuffer indirectBuffer;
    private RhiBuffer countBuffer;
    private int capacity;
    private int commandCount;

    private ByteBuffer cpuBuffer;
    private boolean supportsNativeIndirect;

    public IndirectDrawBuilder(GlDevice device) {
        this.device = device;
        this.supportsNativeIndirect =
            device.isExtensionSupported("GL_ARB_multi_draw_indirect") ||
            device.getInfo().majorVersion() >= 43;

        this.capacity = INITIAL_CAPACITY;
        this.commandCount = 0;
        this.cpuBuffer = ByteBuffer.allocateDirect(COMMAND_SIZE_BYTES * capacity);

        this.indirectBuffer = device.createBuffer(
            RhiBuffer.Type.INDIRECT,
            (long) COMMAND_SIZE_BYTES * capacity,
            RhiBuffer.Usage.DYNAMIC
        );

        // Separate buffer to store only the visible count (for GL 3.2 fallback)
        this.countBuffer = device.createBuffer(
            RhiBuffer.Type.INDIRECT,
            4L, // single uint32
            RhiBuffer.Usage.STREAM
        );
    }

    /**
     * Begin a new frame of draw command recording.
     */
    public void begin() {
        commandCount = 0;
        cpuBuffer.clear();
    }

    /**
     * Add an indexed draw command.
     */
    public void addCommand(int indexCount, int instanceCount, int firstIndex, int baseVertex) {
        ensureCapacity(commandCount + 1);

        int pos = commandCount * COMMAND_SIZE_BYTES;
        cpuBuffer.putInt(pos, indexCount);
        cpuBuffer.putInt(pos + 4, instanceCount);
        cpuBuffer.putInt(pos + 8, firstIndex);
        cpuBuffer.putInt(pos + 12, baseVertex);
        cpuBuffer.putInt(pos + 16, 0); // firstInstance

        commandCount++;
    }

    /**
     * Finalize and upload commands to GPU.
     */
    public void end() {
        cpuBuffer.limit(commandCount * COMMAND_SIZE_BYTES);
        cpuBuffer.position(0);
        indirectBuffer.upload(cpuBuffer);
    }

    /**
     * Issue the indirect draw.
     */
    public void draw(RhiCommandList cmdList) {
        if (commandCount == 0) return;

        if (supportsNativeIndirect) {
            cmdList.drawIndexedIndirect(indirectBuffer, 0, commandCount, COMMAND_SIZE_BYTES);
        } else {
            // GL 3.2 fallback: manually read back the first visible count
            // and issue instanced draws
            cmdList.drawIndexedIndirect(indirectBuffer, 0, 1, COMMAND_SIZE_BYTES);
        }
    }

    /**
     * For GL 3.2: read back how many instances to draw.
     * The count is written by the GPU culling pass into the first uint32.
     */
    public int readBackVisibleCount() {
        ByteBuffer data = countBuffer.download(0, 4);
        if (data != null && data.remaining() >= 4) {
            return data.getInt(0);
        }
        return 0;
    }

    public void setVisibleCount(int count) {
        ByteBuffer buf = ByteBuffer.allocateDirect(4);
        buf.putInt(0, count);
        countBuffer.upload(buf);
    }

    public RhiBuffer getIndirectBuffer() { return indirectBuffer; }
    public RhiBuffer getCountBuffer() { return countBuffer; }
    public int getCommandCount() { return commandCount; }
    public boolean supportsNativeIndirect() { return supportsNativeIndirect; }

    public void destroy() {
        if (indirectBuffer != null) indirectBuffer.destroy();
        if (countBuffer != null) countBuffer.destroy();
    }

    private void ensureCapacity(int needed) {
        if (needed <= capacity) return;

        int newCapacity = Math.max(capacity * 2, needed);
        ByteBuffer newCpuBuffer = ByteBuffer.allocateDirect(COMMAND_SIZE_BYTES * newCapacity);
        cpuBuffer.position(0);
        newCpuBuffer.put(cpuBuffer);
        cpuBuffer = newCpuBuffer;

        RhiBuffer newBuf = device.createBuffer(
            RhiBuffer.Type.INDIRECT,
            (long) COMMAND_SIZE_BYTES * newCapacity,
            RhiBuffer.Usage.DYNAMIC
        );

        // Copy old buffer content
        indirectBuffer.copyTo(newBuf, 0, 0, (long) COMMAND_SIZE_BYTES * commandCount);
        indirectBuffer.destroy();
        indirectBuffer = newBuf;
        capacity = newCapacity;
    }
}
