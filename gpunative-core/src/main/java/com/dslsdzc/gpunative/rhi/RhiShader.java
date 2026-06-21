package com.dslsdzc.gpunative.rhi;

import java.util.Map;

public interface RhiShader {
    enum Type {
        VERTEX,
        FRAGMENT,
        GEOMETRY,
        COMPUTE
    }

    Type getType();
    boolean isCompiled();
    String getInfoLog();

    int getUniformLocation(String name);
    int getAttributeLocation(String name);
    int getShaderStorageBlockIndex(String name);
    int getUniformBlockIndex(String name);

    void setUniformInt(String name, int value);
    void setUniformFloat(String name, float value);
    void setUniformVec2(String name, float x, float y);
    void setUniformVec3(String name, float x, float y, float z);
    void setUniformVec4(String name, float x, float y, float z, float w);
    void setUniformMat4(String name, float[] matrix);
    void setUniformMat4(String name, java.nio.FloatBuffer matrix);

    void bind();
    void unbind();
    long getHandle();
    void destroy();
}
