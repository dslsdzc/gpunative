package com.dslsdzc.gpunative.rhi.opengl;

import com.dslsdzc.gpunative.rhi.RhiShader;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL40.*;
import static org.lwjgl.opengl.GL43.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.BufferUtils;

public class GlShader implements RhiShader {
    private final Type type;
    private final String source;
    private final int glShaderId;
    private int glProgramId;
    private boolean shaderCompiled;
    private boolean programLinked;
    private String compileLog;
    private String linkLog;
    private boolean destroyed;

    private final Map<String, Integer> uniformLocations = new HashMap<>();
    private final Map<String, Integer> attributeLocations = new HashMap<>();
    private final Map<String, Integer> uniformBlockIndices = new HashMap<>();
    private final Map<String, Integer> ssboIndices = new HashMap<>();

    GlShader(Type type, String source) {
        this.type = type;
        this.source = source;

        int glShaderType = switch (type) {
            case VERTEX -> GL_VERTEX_SHADER;
            case FRAGMENT -> GL_FRAGMENT_SHADER;
            case GEOMETRY -> GL_GEOMETRY_SHADER;
            case COMPUTE -> GL_COMPUTE_SHADER;
        };

        this.glShaderId = glCreateShader(glShaderType);
        glShaderSource(glShaderId, source);
        glCompileShader(glShaderId);

        this.shaderCompiled = glGetShaderi(glShaderId, GL_COMPILE_STATUS) == GL_TRUE;
        if (!shaderCompiled) {
            this.compileLog = glGetShaderInfoLog(glShaderId);
            this.glProgramId = 0;
            glDeleteShader(glShaderId);
            return;
        }

        // Only create a program for COMPUTE shaders (so getHandle() returns a usable program).
        // VS/FS shaders are linked into a combined program by GlPipeline.
        if (type == Type.COMPUTE) {
            this.glProgramId = glCreateProgram();
            glAttachShader(glProgramId, glShaderId);
            glLinkProgram(glProgramId);

            this.programLinked = glGetProgrami(glProgramId, GL_LINK_STATUS) == GL_TRUE;
            if (!programLinked) {
                this.linkLog = glGetProgramInfoLog(glProgramId);
                glDeleteProgram(glProgramId);
                this.glProgramId = 0;
            } else {
                reflectUniforms();
                reflectAttributes();
                reflectUniformBlocks();
                reflectSSBOs();
            }
        } else {
            this.glProgramId = 0; // No program — pipeline manages this
        }
    }

    private void reflectUniforms() {
        if (glProgramId == 0) return;
        int count = glGetProgrami(glProgramId, GL_ACTIVE_UNIFORMS);
        IntBuffer size = BufferUtils.createIntBuffer(1);
        IntBuffer type = BufferUtils.createIntBuffer(1);

        for (int i = 0; i < count; i++) {
            size.clear(); type.clear();
            String name = glGetActiveUniform(glProgramId, i, size, type);
            if (name != null) {
                int location = glGetUniformLocation(glProgramId, name);
                if (location >= 0) {
                    uniformLocations.put(name, location);
                }
            }
        }
    }

    private void reflectAttributes() {
        if (glProgramId == 0) return;
        int count = glGetProgrami(glProgramId, GL_ACTIVE_ATTRIBUTES);
        IntBuffer size = BufferUtils.createIntBuffer(1);
        IntBuffer type = BufferUtils.createIntBuffer(1);

        for (int i = 0; i < count; i++) {
            size.clear(); type.clear();
            String name = glGetActiveAttrib(glProgramId, i, size, type);
            if (name != null) {
                int location = glGetAttribLocation(glProgramId, name);
                if (location >= 0) {
                    attributeLocations.put(name, location);
                }
            }
        }
    }

    private void reflectUniformBlocks() {
        if (glProgramId == 0) return;
        int count = glGetProgrami(glProgramId, GL_ACTIVE_UNIFORM_BLOCKS);
        for (int i = 0; i < count; i++) {
            String name = glGetActiveUniformBlockName(glProgramId, i);
            uniformBlockIndices.put(name, i);
        }
    }

    private void reflectSSBOs() {
        if (glProgramId == 0) return;
    }

    @Override
    public Type getType() { return type; }

    @Override
    public boolean isCompiled() { return shaderCompiled; }

    @Override
    public String getInfoLog() {
        if (!shaderCompiled) return compileLog != null ? compileLog : "unknown error";
        if (!programLinked) return linkLog != null ? linkLog : "program link failed";
        return "";
    }

    @Override
    public int getUniformLocation(String name) {
        return uniformLocations.getOrDefault(name, -1);
    }

    @Override
    public int getAttributeLocation(String name) {
        return attributeLocations.getOrDefault(name, -1);
    }

    @Override
    public int getShaderStorageBlockIndex(String name) {
        return ssboIndices.getOrDefault(name, -1);
    }

    @Override
    public int getUniformBlockIndex(String name) {
        return uniformBlockIndices.getOrDefault(name, -1);
    }

    @Override
    public void setUniformInt(String name, int value) {
        if (glProgramId == 0) return;
        bind();
        Integer loc = uniformLocations.get(name);
        if (loc != null) glUniform1i(loc, value);
    }

    @Override
    public void setUniformFloat(String name, float value) {
        if (glProgramId == 0) return;
        bind();
        Integer loc = uniformLocations.get(name);
        if (loc != null) glUniform1f(loc, value);
    }

    @Override
    public void setUniformVec2(String name, float x, float y) {
        if (glProgramId == 0) return;
        bind();
        Integer loc = uniformLocations.get(name);
        if (loc != null) glUniform2f(loc, x, y);
    }

    @Override
    public void setUniformVec3(String name, float x, float y, float z) {
        if (glProgramId == 0) return;
        bind();
        Integer loc = uniformLocations.get(name);
        if (loc != null) glUniform3f(loc, x, y, z);
    }

    @Override
    public void setUniformVec4(String name, float x, float y, float z, float w) {
        if (glProgramId == 0) return;
        bind();
        Integer loc = uniformLocations.get(name);
        if (loc != null) glUniform4f(loc, x, y, z, w);
    }

    @Override
    public void setUniformMat4(String name, float[] matrix) {
        if (glProgramId == 0) return;
        bind();
        Integer loc = uniformLocations.get(name);
        if (loc != null) glUniformMatrix4fv(loc, false, matrix);
    }

    @Override
    public void setUniformMat4(String name, FloatBuffer matrix) {
        if (glProgramId == 0) return;
        bind();
        Integer loc = uniformLocations.get(name);
        if (loc != null) glUniformMatrix4fv(loc, false, matrix);
    }

    @Override
    public void bind() {
        if (destroyed || glProgramId == 0) return;
        glUseProgram(glProgramId);
    }

    @Override
    public void unbind() {
        glUseProgram(0);
    }

    @Override
    public long getHandle() {
        return glProgramId;
    }

    /** Returns the raw GL shader object ID (not the program). Used by GlPipeline for linking. */
    public int getRawShaderHandle() {
        return glShaderId;
    }

    @Override
    public void destroy() {
        if (!destroyed) {
            if (glProgramId != 0) {
                glDetachShader(glProgramId, glShaderId);
                glDeleteProgram(glProgramId);
            }
            glDeleteShader(glShaderId);
            destroyed = true;
        }
    }
}
