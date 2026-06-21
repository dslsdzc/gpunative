package com.dslsdzc.gpunative.rhi;

import java.util.Set;

public interface RhiDevice {
    record DeviceInfo(
        String renderer,
        String vendor,
        String version,
        int majorVersion,
        int minorVersion,
        Set<String> extensions,
        int maxTextureSize,
        int maxTextureArrayLayers,
        int maxUniformBufferBindings,
        int maxShaderStorageBufferBindings,
        int maxDrawBuffers,
        int maxColorAttachments,
        int maxVertexAttributes,
        int maxVaryingComponents,
        int maxVertexUniformComponents,
        int maxFragmentUniformComponents,
        int uniformBufferOffsetAlignment,
        int shaderStorageBufferOffsetAlignment
    ) {}

    DeviceInfo getInfo();
    boolean isExtensionSupported(String extension);
    int getMaxTextureSize();

    RhiBuffer createBuffer(RhiBuffer.Type type, long size, RhiBuffer.Usage usage);
    RhiBuffer createBuffer(RhiBuffer.Type type, long size, RhiBuffer.Usage usage, java.nio.ByteBuffer initialData);

    RhiTexture createTexture(RhiTexture.Type type, RhiTexture.Format format, int width, int height, int depth, int mipLevels, int layers);

    RhiShader createShader(RhiShader.Type type, String source);
    RhiShader createComputeShader(String source);
    RhiPipeline createPipeline(RhiPipeline.Descriptor desc);

    RhiCommandList createCommandList();
    RhiFence createFence();

    void submit(RhiCommandList commandList);
    void submit(RhiCommandList commandList, RhiFence signalFence);
    void submit(RhiCommandList commandList, RhiFence signalFence, RhiFence... waitFences);

    void waitIdle();
    void destroy();
}
