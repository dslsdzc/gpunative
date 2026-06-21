package com.dslsdzc.gpunative.rhi;

import java.util.List;

public interface RhiCommandList {
    enum LoadOp { LOAD, CLEAR, DONT_CARE }
    enum StoreOp { STORE, DONT_CARE }

    final class ColorAttachment {
        public RhiTexture texture;
        public int mipLevel;
        public int layer;
        public LoadOp loadOp = LoadOp.CLEAR;
        public StoreOp storeOp = StoreOp.STORE;
        public float clearR, clearG, clearB, clearA;
    }

    final class DepthAttachment {
        public RhiTexture texture;
        public LoadOp loadOp = LoadOp.CLEAR;
        public StoreOp storeOp = StoreOp.STORE;
        public float clearDepth = 1.0f;
        public int clearStencil;
    }

    void begin();
    void end();

    void beginRenderPass(List<ColorAttachment> colors, DepthAttachment depth, int width, int height);
    void endRenderPass();

    void setPipeline(RhiPipeline pipeline);
    void setVertexBuffer(int binding, RhiBuffer buffer, long offset);
    void setIndexBuffer(RhiBuffer buffer, boolean index32);
    void bindTexture(int unit, RhiTexture texture);
    void bindTexture(int unit, RhiTexture texture, RhiTexture.SamplerState sampler);

    void setProgram(int program);

    void setUniformBlock(int binding, String name, RhiBuffer buffer, long offset, long size);
    void setShaderStorageBuffer(int binding, String name, RhiBuffer buffer, long offset, long size);

    void pushConstants(int offset, byte[] data);

    void setUniformInt(int program, String name, int value);
    void setUniformMat4(int program, String name, float[] matrix);
    void setUniformVec3(int program, String name, float x, float y, float z);

    void setViewport(int x, int y, int width, int height);
    void clearDefaultDepth(float depth);

    void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance);
    void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance);
    void drawIndirect(RhiBuffer indirectBuffer, long offset, int drawCount, int stride);
    void drawIndexedIndirect(RhiBuffer indirectBuffer, long offset, int drawCount, int stride);

    void dispatchCompute(int groupX, int groupY, int groupZ);

    void copyBufferToTexture(RhiBuffer src, RhiTexture dst, int mipLevel, int layer, int x, int y, int width, int height);
    void copyTextureToBuffer(RhiTexture src, int mipLevel, int layer, RhiBuffer dst);

    void clearTexture(RhiTexture texture, int mipLevel, float r, float g, float b, float a);
    void clearDepth(RhiTexture texture, float depth);

    void memoryBarrier(MemoryBarrier barrier);

    default void destroy() {}

    enum MemoryBarrier {
        TEXTURE_FETCH,
        SHADER_STORAGE,
        INDIRECT_COMMAND,
        INDEX_INPUT,
        VERTEX_ATTRIBUTE,
        TRANSFER,
        ALL
    }
}
