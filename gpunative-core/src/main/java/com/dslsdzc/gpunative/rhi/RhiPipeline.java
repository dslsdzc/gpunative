package com.dslsdzc.gpunative.rhi;

import java.util.List;

public interface RhiPipeline {
    enum PrimitiveType {
        TRIANGLES,
        TRIANGLE_STRIP,
        LINES,
        LINE_STRIP,
        POINTS
    }

    enum CompareOp {
        NEVER,
        LESS,
        EQUAL,
        LEQUAL,
        GREATER,
        NOTEQUAL,
        GEQUAL,
        ALWAYS
    }

    enum BlendFactor {
        ZERO,
        ONE,
        SRC_COLOR,
        ONE_MINUS_SRC_COLOR,
        SRC_ALPHA,
        ONE_MINUS_SRC_ALPHA,
        DST_COLOR,
        ONE_MINUS_DST_COLOR,
        DST_ALPHA,
        ONE_MINUS_DST_ALPHA
    }

    enum CullMode {
        NONE,
        FRONT,
        BACK,
        FRONT_AND_BACK
    }

    final class Descriptor {
        public RhiShader vertexShader;
        public RhiShader fragmentShader;
        public RhiShader geometryShader;
        public PrimitiveType primitiveType = PrimitiveType.TRIANGLES;
        public CullMode cullMode = CullMode.BACK;
        public boolean depthTest = true;
        public boolean depthWrite = true;
        public CompareOp depthCompare = CompareOp.LEQUAL;
        public boolean blendEnabled = false;
        public BlendFactor srcColorBlend = BlendFactor.SRC_ALPHA;
        public BlendFactor dstColorBlend = BlendFactor.ONE_MINUS_SRC_ALPHA;
        public BlendFactor srcAlphaBlend = BlendFactor.ONE;
        public BlendFactor dstAlphaBlend = BlendFactor.ONE_MINUS_SRC_ALPHA;
        public float lineWidth = 1.0f;
        public int sampleCount = 1;

        public static Descriptor create() {
            return new Descriptor();
        }
    }

    Descriptor getDescriptor();
    void bind();
    void unbind();
    long getHandle();
    void destroy();
}
