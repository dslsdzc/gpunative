package com.dslsdzc.gpunative.rhi.opengl;

import com.dslsdzc.gpunative.rhi.RhiPipeline;
import com.dslsdzc.gpunative.rhi.RhiShader;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

public class GlPipeline implements RhiPipeline {
    private final Descriptor descriptor;
    private final int glProgram;
    private boolean destroyed;

    GlPipeline(Descriptor descriptor) {
        this.descriptor = descriptor;

        int program = glCreateProgram();

        if (descriptor.vertexShader != null) {
            GlShader vs = (GlShader) descriptor.vertexShader;
            glAttachShader(program, vs.getRawShaderHandle());
        }
        if (descriptor.fragmentShader != null) {
            GlShader fs = (GlShader) descriptor.fragmentShader;
            glAttachShader(program, fs.getRawShaderHandle());
        }
        if (descriptor.geometryShader != null) {
            GlShader gs = (GlShader) descriptor.geometryShader;
            glAttachShader(program, gs.getRawShaderHandle());
        }

        glLinkProgram(program);

        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new RuntimeException("Failed to link pipeline program: " + log);
        }

        this.glProgram = program;
    }

    @Override
    public Descriptor getDescriptor() { return descriptor; }

    @Override
    public void bind() {
        if (destroyed) return;

        glUseProgram(glProgram);

        // Depth state
        glDepthMask(descriptor.depthWrite);
        if (descriptor.depthTest) {
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(toGlCompare(descriptor.depthCompare));
        } else {
            glDisable(GL_DEPTH_TEST);
        }

        // Cull state
        switch (descriptor.cullMode) {
            case NONE -> glDisable(GL_CULL_FACE);
            case BACK -> {
                glEnable(GL_CULL_FACE);
                glCullFace(GL_BACK);
            }
            case FRONT -> {
                glEnable(GL_CULL_FACE);
                glCullFace(GL_FRONT);
            }
            case FRONT_AND_BACK -> {
                glEnable(GL_CULL_FACE);
                glCullFace(GL_FRONT_AND_BACK);
            }
        }

        // Blend state
        if (descriptor.blendEnabled) {
            glEnable(GL_BLEND);
            glBlendFuncSeparate(
                toGlBlend(descriptor.srcColorBlend), toGlBlend(descriptor.dstColorBlend),
                toGlBlend(descriptor.srcAlphaBlend), toGlBlend(descriptor.dstAlphaBlend)
            );
        } else {
            glDisable(GL_BLEND);
        }

        if (descriptor.lineWidth != 1.0f) {
            glLineWidth(descriptor.lineWidth);
        }

        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
    }

    @Override
    public void unbind() {
        glUseProgram(0);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);
    }

    @Override
    public long getHandle() {
        return glProgram;
    }

    /** The combined GL program ID. Used for setting uniforms directly. */
    public int getGlProgram() {
        return glProgram;
    }

    @Override
    public void destroy() {
        if (!destroyed) {
            glDeleteProgram(glProgram);
            destroyed = true;
        }
    }

    static int toGlPrimitive(PrimitiveType type) {
        return switch (type) {
            case TRIANGLES -> GL_TRIANGLES;
            case TRIANGLE_STRIP -> GL_TRIANGLE_STRIP;
            case LINES -> GL_LINES;
            case LINE_STRIP -> GL_LINE_STRIP;
            case POINTS -> GL_POINTS;
        };
    }

    private static int toGlCompare(CompareOp op) {
        return switch (op) {
            case NEVER -> GL_NEVER;
            case LESS -> GL_LESS;
            case EQUAL -> GL_EQUAL;
            case LEQUAL -> GL_LEQUAL;
            case GREATER -> GL_GREATER;
            case NOTEQUAL -> GL_NOTEQUAL;
            case GEQUAL -> GL_GEQUAL;
            case ALWAYS -> GL_ALWAYS;
        };
    }

    private static int toGlBlend(BlendFactor factor) {
        return switch (factor) {
            case ZERO -> GL_ZERO;
            case ONE -> GL_ONE;
            case SRC_COLOR -> GL_SRC_COLOR;
            case ONE_MINUS_SRC_COLOR -> GL_ONE_MINUS_SRC_COLOR;
            case SRC_ALPHA -> GL_SRC_ALPHA;
            case ONE_MINUS_SRC_ALPHA -> GL_ONE_MINUS_SRC_ALPHA;
            case DST_COLOR -> GL_DST_COLOR;
            case ONE_MINUS_DST_COLOR -> GL_ONE_MINUS_DST_COLOR;
            case DST_ALPHA -> GL_DST_ALPHA;
            case ONE_MINUS_DST_ALPHA -> GL_ONE_MINUS_DST_ALPHA;
        };
    }
}
