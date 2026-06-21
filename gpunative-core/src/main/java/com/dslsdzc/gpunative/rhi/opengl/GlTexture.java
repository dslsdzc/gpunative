package com.dslsdzc.gpunative.rhi.opengl;

import com.dslsdzc.gpunative.rhi.RhiTexture;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL32.*;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.EXTTextureFilterAnisotropic.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

public class GlTexture implements RhiTexture {
    private final Type type;
    private final Format format;
    private final int width;
    private final int height;
    private final int depth;
    private final int mipLevels;
    private final int layers;
    private final int glTarget;
    private final int glInternalFormat;
    private final int glFormat;
    private final int glType;
    private int glTextureId;
    private boolean destroyed;

    private int minFilter = GL_LINEAR;
    private int magFilter = GL_LINEAR;
    private int wrapS = GL_REPEAT;
    private int wrapT = GL_REPEAT;
    private int wrapR = GL_REPEAT;
    private float maxAnisotropy = 1.0f;

    GlTexture(Type type, Format format, int width, int height, int depth, int mipLevels, int layers) {
        this.type = type;
        this.format = format;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.mipLevels = mipLevels;
        this.layers = layers;
        this.glTarget = getGlTarget(type);

        int[] fmt = getGlFormat(format);
        this.glInternalFormat = fmt[0];
        this.glFormat = fmt[1];
        this.glType = fmt[2];

        this.glTextureId = glGenTextures();
        glBindTexture(glTarget, glTextureId);

        switch (type) {
            case TEXTURE_2D -> {
                glTexImage2D(glTarget, 0, glInternalFormat, width, height, 0, glFormat, glType, (ByteBuffer) null);
                if (mipLevels > 1) {
                    for (int i = 1; i < mipLevels; i++) {
                        int mipW = Math.max(1, width >> i);
                        int mipH = Math.max(1, height >> i);
                        glTexImage2D(glTarget, i, glInternalFormat, mipW, mipH, 0, glFormat, glType, (ByteBuffer) null);
                    }
                }
            }
            case TEXTURE_2D_ARRAY -> {
                glTexImage3D(glTarget, 0, glInternalFormat, width, height, layers, 0, glFormat, glType, (ByteBuffer) null);
                for (int i = 1; i < mipLevels; i++) {
                    int mipW = Math.max(1, width >> i);
                    int mipH = Math.max(1, height >> i);
                    glTexImage3D(glTarget, i, glInternalFormat, mipW, mipH, layers, 0, glFormat, glType, (ByteBuffer) null);
                }
            }
            case TEXTURE_3D -> {
                glTexImage3D(glTarget, 0, glInternalFormat, width, height, depth, 0, glFormat, glType, (ByteBuffer) null);
                for (int i = 1; i < mipLevels; i++) {
                    int mipW = Math.max(1, width >> i);
                    int mipH = Math.max(1, height >> i);
                    int mipD = Math.max(1, depth >> i);
                    glTexImage3D(glTarget, i, glInternalFormat, mipW, mipH, mipD, 0, glFormat, glType, (ByteBuffer) null);
                }
            }
        }

        applySamplerState();
        glBindTexture(glTarget, 0);
    }

    @Override
    public Type getType() { return type; }

    @Override
    public Format getFormat() { return format; }

    @Override
    public int getWidth() { return width; }

    @Override
    public int getHeight() { return height; }

    @Override
    public int getDepth() { return depth; }

    @Override
    public int getMipLevels() { return mipLevels; }

    @Override
    public int getLayers() { return layers; }

    @Override
    public void upload(int mipLevel, int x, int y, int width, int height, ByteBuffer data) {
        checkNotDestroyed();
        glBindTexture(glTarget, glTextureId);
        glTexSubImage2D(glTarget, mipLevel, x, y, width, height, glFormat, glType, data);
        glBindTexture(glTarget, 0);
    }

    @Override
    public void upload3D(int mipLevel, int x, int y, int z, int width, int height, int depth, ByteBuffer data) {
        checkNotDestroyed();
        int actualTarget = (type == Type.TEXTURE_2D_ARRAY) ? GL_TEXTURE_2D_ARRAY : glTarget;
        glBindTexture(actualTarget, glTextureId);

        if (type == Type.TEXTURE_2D_ARRAY) {
            glTexSubImage3D(actualTarget, mipLevel, x, y, z, width, height, 1, glFormat, glType, data);
        } else {
            glTexSubImage3D(actualTarget, mipLevel, x, y, z, width, height, depth, glFormat, glType, data);
        }

        glBindTexture(actualTarget, 0);
    }

    @Override
    public void generateMipmaps() {
        checkNotDestroyed();
        glBindTexture(glTarget, glTextureId);
        glGenerateMipmap(glTarget);
        glBindTexture(glTarget, 0);
    }

    @Override
    public void setSamplerState(SamplerState state) {
        this.minFilter = toGlFilter(state.minFilter());
        this.magFilter = toGlFilter(state.magFilter());
        this.wrapS = toGlWrap(state.wrapU());
        this.wrapT = toGlWrap(state.wrapV());
        this.wrapR = toGlWrap(state.wrapW());
        this.maxAnisotropy = state.maxAnisotropy();

        if (glTextureId != 0 && !destroyed) {
            glBindTexture(glTarget, glTextureId);
            applySamplerState();
            glBindTexture(glTarget, 0);
        }
    }

    private void applySamplerState() {
        glTexParameteri(glTarget, GL_TEXTURE_MIN_FILTER, minFilter);
        glTexParameteri(glTarget, GL_TEXTURE_MAG_FILTER, magFilter);
        glTexParameteri(glTarget, GL_TEXTURE_WRAP_S, wrapS);
        glTexParameteri(glTarget, GL_TEXTURE_WRAP_T, wrapT);
        glTexParameteri(glTarget, GL_TEXTURE_WRAP_R, wrapR);

        if (maxAnisotropy > 1.0f) {
            glTexParameterf(glTarget, GL_TEXTURE_MAX_ANISOTROPY_EXT, maxAnisotropy);
        }
    }

    @Override
    public long getHandle() { return glTextureId; }

    void bind(int unit) {
        checkNotDestroyed();
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(glTarget, glTextureId);
    }

    void bind(int unit, int target) {
        checkNotDestroyed();
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(target, glTextureId);
    }

    void bindImage(int unit, int mipLevel, boolean layered, int layer, int access, int format) {
        checkNotDestroyed();
        glBindImageTexture(unit, glTextureId, mipLevel, layered, layer, access, format);
    }

    void bindFramebuffer(int attachment, int mipLevel) {
        checkNotDestroyed();
        switch (type) {
            case TEXTURE_2D -> glFramebufferTexture2D(GL_FRAMEBUFFER, attachment, GL_TEXTURE_2D, glTextureId, mipLevel);
            case TEXTURE_2D_ARRAY -> glFramebufferTextureLayer(GL_FRAMEBUFFER, attachment, glTextureId, mipLevel, 0);
            case TEXTURE_3D -> glFramebufferTextureLayer(GL_FRAMEBUFFER, attachment, glTextureId, mipLevel, 0);
        }
    }

    @Override
    public void destroy() {
        if (!destroyed && glTextureId != 0) {
            glDeleteTextures(glTextureId);
            glTextureId = 0;
            destroyed = true;
        }
    }

    private void checkNotDestroyed() {
        if (destroyed) throw new IllegalStateException("Texture is destroyed");
    }

    static int getGlTarget(Type type) {
        return switch (type) {
            case TEXTURE_2D -> GL_TEXTURE_2D;
            case TEXTURE_2D_ARRAY -> GL_TEXTURE_2D_ARRAY;
            case TEXTURE_3D -> GL_TEXTURE_3D;
        };
    }

    static int[] getGlFormat(Format format) {
        return switch (format) {
            case R8_UNORM -> new int[]{ GL_R8, GL_RED, GL_UNSIGNED_BYTE };
            case RGBA8_UNORM -> new int[]{ GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE };
            case RGBA8_SRGB -> new int[]{ GL_SRGB8_ALPHA8, GL_RGBA, GL_UNSIGNED_BYTE };
            case RGBA16F -> new int[]{ GL_RGBA16F, GL_RGBA, GL_FLOAT };
            case RGB10_A2 -> new int[]{ GL_RGB10_A2, GL_RGBA, GL_UNSIGNED_INT_2_10_10_10_REV };
            case R32F -> new int[]{ GL_R32F, GL_RED, GL_FLOAT };
            case RG32F -> new int[]{ GL_RG32F, GL_RG, GL_FLOAT };
            case RGB32F -> new int[]{ GL_RGB32F, GL_RGB, GL_FLOAT };
            case RGBA32F -> new int[]{ GL_RGBA32F, GL_RGBA, GL_FLOAT };
            case DEPTH24 -> new int[]{ GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT };
            case DEPTH32F -> new int[]{ GL_DEPTH_COMPONENT32F, GL_DEPTH_COMPONENT, GL_FLOAT };
            case DEPTH24_STENCIL8 -> new int[]{ GL_DEPTH24_STENCIL8, GL_DEPTH_STENCIL, GL_UNSIGNED_INT_24_8 };
        };
    }

    static int getGlAccess(boolean readable, boolean writable) {
        if (readable && writable) return GL_READ_WRITE;
        if (readable) return GL_READ_ONLY;
        return GL_WRITE_ONLY;
    }

    private static int toGlFilter(SamplerState.Filter filter) {
        return switch (filter) {
            case NEAREST -> GL_NEAREST;
            case LINEAR -> GL_LINEAR;
            case NEAREST_MIPMAP_NEAREST -> GL_NEAREST_MIPMAP_NEAREST;
            case LINEAR_MIPMAP_LINEAR -> GL_LINEAR_MIPMAP_LINEAR;
        };
    }

    private static int toGlWrap(SamplerState.Wrap wrap) {
        return switch (wrap) {
            case REPEAT -> GL_REPEAT;
            case CLAMP_TO_EDGE -> GL_CLAMP_TO_EDGE;
            case CLAMP_TO_BORDER -> GL_CLAMP_TO_BORDER;
            case MIRRORED_REPEAT -> GL_MIRRORED_REPEAT;
        };
    }
}
