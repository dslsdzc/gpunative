package com.dslsdzc.gpunative.rhi;

import java.nio.ByteBuffer;

public interface RhiTexture {
    enum Type {
        TEXTURE_2D,
        TEXTURE_2D_ARRAY,
        TEXTURE_3D
    }

    enum Format {
        R8_UNORM,
        RGBA8_UNORM,
        RGBA8_SRGB,
        RGBA16F,
        RGB10_A2,
        R32F,
        RG32F,
        RGB32F,
        RGBA32F,
        DEPTH24,
        DEPTH32F,
        DEPTH24_STENCIL8
    }

    Type getType();
    Format getFormat();
    int getWidth();
    int getHeight();
    int getDepth();
    int getMipLevels();
    int getLayers();

    void upload(int mipLevel, int x, int y, int width, int height, ByteBuffer data);
    void upload3D(int mipLevel, int x, int y, int z, int width, int height, int depth, ByteBuffer data);
    void generateMipmaps();
    void setSamplerState(SamplerState state);
    long getHandle();

    interface SamplerState {
        enum Filter { NEAREST, LINEAR, NEAREST_MIPMAP_NEAREST, LINEAR_MIPMAP_LINEAR }
        enum Wrap { REPEAT, CLAMP_TO_EDGE, CLAMP_TO_BORDER, MIRRORED_REPEAT }

        Filter minFilter();
        Filter magFilter();
        Wrap wrapU();
        Wrap wrapV();
        Wrap wrapW();
        float maxAnisotropy();
    }

    void destroy();
}
