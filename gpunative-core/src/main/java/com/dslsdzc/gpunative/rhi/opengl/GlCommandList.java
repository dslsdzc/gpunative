package com.dslsdzc.gpunative.rhi.opengl;

import com.dslsdzc.gpunative.rhi.*;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL32.*;
import static org.lwjgl.opengl.GL40.*;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.GL44.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;

import org.lwjgl.BufferUtils;public class GlCommandList implements RhiCommandList {
    private final GlDevice device;
    private final boolean supportsIndirect;
    private boolean recording;
    private boolean inRenderPass;
    private GlPipeline currentPipeline;
    private int currentFbo;
    private int currentViewportWidth;
    private int currentViewportHeight;
    private int boundVao;
    private long boundVaoBufferHandle;
    private boolean destroyed;

    private final List<Runnable> commandQueue = new ArrayList<>();

    GlCommandList(GlDevice device) {
        this.device = device;
        this.supportsIndirect = device.isExtensionSupported("GL_ARB_multi_draw_indirect")
            || device.getInfo().majorVersion() >= 43;
    }

    @Override
    public void destroy() {
        destroyed = true;
        if (boundVao != 0) {
            int vao = boundVao;
            record(() -> glDeleteVertexArrays(vao));
            boundVao = 0;
        }
    }

    @Override
    public void begin() {
        recording = true;
        commandQueue.clear();
    }

    @Override
    public void end() {
        recording = false;
    }

    void execute() {
        for (Runnable cmd : commandQueue) {
            cmd.run();
        }
        commandQueue.clear();
    }

    private void record(Runnable cmd) {
        if (recording) {
            commandQueue.add(cmd);
        } else {
            cmd.run();
        }
    }

    @Override
    public void beginRenderPass(List<ColorAttachment> colors, DepthAttachment depth, int width, int height) {
        record(() -> {
            if (colors == null || colors.isEmpty()) {
                // Render to default framebuffer (screen)
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                currentFbo = 0;
                glViewport(0, 0, width, height);
                glDepthMask(true);
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                currentViewportWidth = width;
                currentViewportHeight = height;
                inRenderPass = true;
                return;
            }

            currentFbo = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, currentFbo);

            int drawBufferIndex = 0;
            for (RhiCommandList.ColorAttachment attachment : colors) {
                GlTexture glTex = (GlTexture) attachment.texture;
                int attachmentPoint = GL_COLOR_ATTACHMENT0 + drawBufferIndex;
                glTex.bindFramebuffer(attachmentPoint, attachment.mipLevel);

                if (attachment.loadOp == LoadOp.CLEAR) {
                    glClearBufferfv(GL_COLOR, drawBufferIndex,
                        new float[]{ attachment.clearR, attachment.clearG, attachment.clearB, attachment.clearA });
                }
                drawBufferIndex++;
            }

            if (depth != null) {
                GlTexture glDepth = (GlTexture) depth.texture;
                int depthAttach = switch (glDepth.getFormat()) {
                    case DEPTH24_STENCIL8 -> GL_DEPTH_STENCIL_ATTACHMENT;
                    default -> GL_DEPTH_ATTACHMENT;
                };
                glDepth.bindFramebuffer(depthAttach, 0);

                if (depth.loadOp == LoadOp.CLEAR) {
                    glClearBufferfi(GL_DEPTH_STENCIL, 0, depth.clearDepth, depth.clearStencil);
                }
            }

            if (drawBufferIndex > 0) {
                int[] bufs = new int[drawBufferIndex];
                for (int i = 0; i < drawBufferIndex; i++) {
                    bufs[i] = GL_COLOR_ATTACHMENT0 + i;
                }
                glDrawBuffers(bufs);
            } else {
                glDrawBuffer(GL_NONE);
            }

            int fboStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (fboStatus != GL_FRAMEBUFFER_COMPLETE) {
                System.err.println("[GpuNative] Framebuffer incomplete: 0x" + Integer.toHexString(fboStatus));
            }

            glViewport(0, 0, width, height);
            currentViewportWidth = width;
            currentViewportHeight = height;
            inRenderPass = true;
        });
    }

    @Override
    public void endRenderPass() {
        record(() -> {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(currentFbo);
            currentFbo = 0;
            inRenderPass = false;
        });
    }

    @Override
    public void setPipeline(RhiPipeline pipeline) {
        record(() -> {
            currentPipeline = (GlPipeline) pipeline;
            currentPipeline.bind();
        });
    }

    @Override
    public void setVertexBuffer(int binding, RhiBuffer buffer, long offset) {
        record(() -> {
            GlBuffer glBuf = (GlBuffer) buffer;
            if (binding == 0) {
                long bufHandle = glBuf.getHandle();
                if (boundVao == 0 || boundVaoBufferHandle != bufHandle) {
                    if (boundVao != 0) glDeleteVertexArrays(boundVao);
                    setupDefaultVAO(glBuf);
                    boundVaoBufferHandle = bufHandle;
                }
            } else {
                glBindBuffer(GL_ARRAY_BUFFER, (int) glBuf.getHandle());
            }
        });
    }

    private void setupDefaultVAO(GlBuffer vertexBuffer) {
        boundVao = glGenVertexArrays();
        glBindVertexArray(boundVao);
        glBindBuffer(GL_ARRAY_BUFFER, (int) vertexBuffer.getHandle());

        int stride = 48; // pos3 + normal3 + texcoord2 + color4 = 12 floats = 48 bytes
        // Position (location 0) - 3 floats
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);

        // Normal (location 1) - 3 floats
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 12);
        glEnableVertexAttribArray(1);

        // TexCoord (location 2) - 2 floats
        glVertexAttribPointer(2, 2, GL_FLOAT, false, stride, 24);
        glEnableVertexAttribArray(2);

        // Color (location 3) - 4 floats
        glVertexAttribPointer(3, 4, GL_FLOAT, false, stride, 32);
        glEnableVertexAttribArray(3);
    }

    @Override
    public void setIndexBuffer(RhiBuffer buffer, boolean index32) {
        record(() -> {
            GlBuffer glBuf = (GlBuffer) buffer;
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, (int) glBuf.getHandle());
        });
    }

    @Override
    public void bindTexture(int unit, RhiTexture texture) {
        record(() -> {
            GlTexture glTex = (GlTexture) texture;
            glTex.bind(unit);
        });
    }

    @Override
    public void bindTexture(int unit, RhiTexture texture, RhiTexture.SamplerState sampler) {
        record(() -> {
            GlTexture glTex = (GlTexture) texture;
            glTex.setSamplerState(sampler);
            glTex.bind(unit);
        });
    }

    @Override
    public void setProgram(int program) {
        record(() -> glUseProgram(program));
    }

    @Override
    public void setUniformBlock(int binding, String name, RhiBuffer buffer, long offset, long size) {
        record(() -> {
            GlBuffer glBuf = (GlBuffer) buffer;
            if (currentPipeline != null) {
                int handle = (int) currentPipeline.getHandle();
                int blockIndex = glGetUniformBlockIndex(handle, name);
                if (blockIndex != GL_INVALID_INDEX) {
                    glUniformBlockBinding(handle, blockIndex, binding);
                    glBuf.bindBase(GL_UNIFORM_BUFFER, binding);
                }
            }
        });
    }

    @Override
    public void setShaderStorageBuffer(int binding, String name, RhiBuffer buffer, long offset, long size) {
        record(() -> {
            GlBuffer glBuf = (GlBuffer) buffer;
            glBuf.bindBase(GL_SHADER_STORAGE_BUFFER, binding);
        });
    }

    @Override
    public void setUniformInt(int program, String name, int value) {
        record(() -> {
            glUseProgram(program);
            int loc = glGetUniformLocation(program, name);
            if (loc >= 0) glUniform1i(loc, value);
        });
    }

    @Override
    public void setUniformMat4(int program, String name, float[] matrix) {
        record(() -> {
            glUseProgram(program);
            int loc = glGetUniformLocation(program, name);
            if (loc >= 0) glUniformMatrix4fv(loc, false, matrix);
        });
    }

    @Override
    public void setUniformVec3(int program, String name, float x, float y, float z) {
        record(() -> {
            glUseProgram(program);
            int loc = glGetUniformLocation(program, name);
            if (loc >= 0) glUniform3f(loc, x, y, z);
        });
    }

    @Override
    public void pushConstants(int offset, byte[] data) {
        // OpenGL doesn't have Vulkan-style push constants natively.
        // Emulate via uniform upload if needed.
    }

    @Override
    public void setViewport(int x, int y, int width, int height) {
        record(() -> glViewport(x, y, width, height));
    }

    @Override
    public void clearDefaultDepth(float depth) {
        record(() -> {
            glDepthMask(true);
            glClearDepth(depth);
            glClear(GL_DEPTH_BUFFER_BIT);
        });
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        record(() -> {
            if (boundVao == 0) return;
            glBindVertexArray(boundVao);
            glDrawArraysInstanced(GL_TRIANGLES, firstVertex, vertexCount, instanceCount);
        });
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        record(() -> {
            if (boundVao == 0) return;
            glBindVertexArray(boundVao);
            int indexType = GL_UNSIGNED_INT;
            long indexOffset = (long) firstIndex * 4L;
            glDrawElementsInstancedBaseVertex(
                GL_TRIANGLES, indexCount, indexType, indexOffset,
                instanceCount, vertexOffset
            );
        });
    }

    @Override
    public void drawIndirect(RhiBuffer indirectBuffer, long offset, int drawCount, int stride) {
        record(() -> {
            if (boundVao == 0) return;
            if (supportsIndirect) {
                GlBuffer glBuf = (GlBuffer) indirectBuffer;
                glBindBuffer(GL_DRAW_INDIRECT_BUFFER, (int) glBuf.getHandle());
                glBindVertexArray(boundVao);
                glMultiDrawArraysIndirect(GL_TRIANGLES, offset, drawCount, stride);
            } else {
                device.readBackIndirectCount((GlBuffer) indirectBuffer, offset, count -> {
                    if (count > 0) {
                        glBindVertexArray(boundVao);
                        glDrawArraysInstanced(GL_TRIANGLES, 0, 3, count);
                    }
                });
            }
        });
    }

    @Override
    public void drawIndexedIndirect(RhiBuffer indirectBuffer, long offset, int drawCount, int stride) {
        record(() -> {
            if (boundVao == 0) return;
            if (supportsIndirect) {
                GlBuffer glBuf = (GlBuffer) indirectBuffer;
                glBindBuffer(GL_DRAW_INDIRECT_BUFFER, (int) glBuf.getHandle());
                glBindVertexArray(boundVao);
                glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, offset, drawCount, stride);
            } else {
                device.readBackIndirectCount((GlBuffer) indirectBuffer, offset, count -> {
                    if (count > 0) {
                        glBindVertexArray(boundVao);
                        glDrawElementsInstanced(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0, count);
                    }
                });
            }
        });
    }

    @Override
    public void dispatchCompute(int groupX, int groupY, int groupZ) {
        record(() -> {
            if (device.isExtensionSupported("GL_ARB_compute_shader")) {
                glDispatchCompute(groupX, groupY, groupZ);
                memoryBarrier(MemoryBarrier.ALL);
            } else {
                // Simulated compute via fragment shader is handled at a higher level
            }
        });
    }

    @Override
    public void copyBufferToTexture(RhiBuffer src, RhiTexture dst, int mipLevel, int layer, int x, int y, int width, int height) {
        record(() -> {
            GlBuffer glSrc = (GlBuffer) src;
            GlTexture glDst = (GlTexture) dst;

            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, (int) glSrc.getHandle());
            glDst.bind(0);

            int target = GlTexture.getGlTarget(glDst.getType());
            int[] fmt = GlTexture.getGlFormat(glDst.getFormat());
            switch (glDst.getType()) {
                case TEXTURE_2D -> glTexSubImage2D(target, mipLevel, x, y, width, height, fmt[1], fmt[2], 0L);
                case TEXTURE_2D_ARRAY ->
                    glTexSubImage3D(target, mipLevel, x, y, layer, width, height, 1, fmt[1], fmt[2], 0L);
                case TEXTURE_3D ->
                    glTexSubImage3D(target, mipLevel, x, y, 0, width, height, glDst.getDepth(), fmt[1], fmt[2], 0L);
            }

            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        });
    }

    @Override
    public void copyTextureToBuffer(RhiTexture src, int mipLevel, int layer, RhiBuffer dst) {
        record(() -> {
            GlTexture glSrc = (GlTexture) src;
            GlBuffer glDst = (GlBuffer) dst;

            glBindBuffer(GL_PIXEL_PACK_BUFFER, (int) glDst.getHandle());
            glSrc.bind(0);

            int target = GlTexture.getGlTarget(glSrc.getType());
            int[] fmt = GlTexture.getGlFormat(glSrc.getFormat());
            glGetTexImage(target, mipLevel, fmt[1], fmt[2], 0L);

            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        });
    }

    @Override
    public void clearTexture(RhiTexture texture, int mipLevel, float r, float g, float b, float a) {
        record(() -> {
            GlTexture glTex = (GlTexture) texture;
            if (device.isExtensionSupported("GL_ARB_clear_texture")) {
                FloatBuffer color = BufferUtils.createFloatBuffer(4);
                color.put(r).put(g).put(b).put(a).flip();
                glClearTexImage((int) glTex.getHandle(), mipLevel, GL_RGBA, GL_FLOAT, color);
            }
        });
    }

    @Override
    public void clearDepth(RhiTexture texture, float depth) {
        record(() -> {
            GlTexture glTex = (GlTexture) texture;
            glTex.bindFramebuffer(GL_DEPTH_ATTACHMENT, 0);
            glClearBufferfv(GL_DEPTH, 0, new float[]{ depth });
        });
    }

    @Override
    public void memoryBarrier(MemoryBarrier barrier) {
        record(() -> {
            int bits = toGlBarrierBits(barrier);
            if (bits != 0) {
                glMemoryBarrier(bits);
            }
        });
    }

    private int toGlBarrierBits(MemoryBarrier barrier) {
        if (!device.isExtensionSupported("GL_ARB_shader_image_load_store")) return 0;
        return switch (barrier) {
            case TEXTURE_FETCH -> GL_TEXTURE_FETCH_BARRIER_BIT;
            case SHADER_STORAGE -> GL_SHADER_STORAGE_BARRIER_BIT;
            case INDIRECT_COMMAND -> GL_COMMAND_BARRIER_BIT;
            case INDEX_INPUT -> GL_ELEMENT_ARRAY_BARRIER_BIT;
            case VERTEX_ATTRIBUTE -> GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT;
            case TRANSFER -> GL_BUFFER_UPDATE_BARRIER_BIT;
            case ALL -> GL_ALL_BARRIER_BITS;
        };
    }
}
