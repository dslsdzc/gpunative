package com.dslsdzc.gpunative.rhi.opengl;

import com.dslsdzc.gpunative.rhi.*;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL32.*;
import static org.lwjgl.opengl.GL40.*;
import static org.lwjgl.opengl.GL41.*;
import static org.lwjgl.opengl.GL43.*;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class GlDevice implements RhiDevice {
    private final DeviceInfo info;
    private final GLCapabilities caps;
    private final Set<String> extensions = new HashSet<>();
    private final boolean hasComputeShader;
    private final boolean hasIndirectDraw;
    private final boolean hasSSBO;

    // For GL 3.2 indirect draw simulation: stores pending readback callbacks
    private final Map<GlBuffer, Consumer<Integer>> pendingReadbacks = new ConcurrentHashMap<>();

    public GlDevice() {
        this.caps = GL.createCapabilities();
        this.hasComputeShader = caps.GL_ARB_compute_shader || caps.OpenGL43;
        this.hasIndirectDraw = caps.GL_ARB_multi_draw_indirect || caps.OpenGL43;
        this.hasSSBO = caps.GL_ARB_shader_storage_buffer_object || caps.OpenGL43;

        // Gather extensions using the GL 3.0+ core profile-safe API
        int numExt = glGetInteger(GL_NUM_EXTENSIONS);
        for (int i = 0; i < numExt; i++) {
            String ext = glGetStringi(GL_EXTENSIONS, i);
            if (ext != null) {
                extensions.add(ext);
            }
        }

        String versionStr = glGetString(GL_VERSION);
        String renderer = glGetString(GL_RENDERER);
        String vendor = glGetString(GL_VENDOR);

        int major = 3, minor = 2;
        if (versionStr != null) {
            try {
                String[] parts = versionStr.split(" ")[0].split("\\.");
                major = Integer.parseInt(parts[0]);
                minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            } catch (Exception ignored) {}
        }

        this.info = new DeviceInfo(
            renderer != null ? renderer : "Unknown",
            vendor != null ? vendor : "Unknown",
            versionStr != null ? versionStr : "Unknown",
            major, minor,
            Collections.unmodifiableSet(extensions),
            glGetInteger(GL_MAX_TEXTURE_SIZE),
            glGetInteger(GL_MAX_ARRAY_TEXTURE_LAYERS),
            glGetInteger(GL_MAX_UNIFORM_BUFFER_BINDINGS),
            hasSSBO ? glGetInteger(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) : 0,
            glGetInteger(GL_MAX_DRAW_BUFFERS),
            glGetInteger(GL_MAX_COLOR_ATTACHMENTS),
            glGetInteger(GL_MAX_VERTEX_ATTRIBS),
            glGetInteger(GL_MAX_VARYING_COMPONENTS),
            glGetInteger(GL_MAX_VERTEX_UNIFORM_COMPONENTS),
            glGetInteger(GL_MAX_FRAGMENT_UNIFORM_COMPONENTS),
            glGetInteger(GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT),
            hasSSBO ? glGetInteger(GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT) : 256
        );

        // Set default GL state
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glFrontFace(GL_CCW);
        glEnable(GL_TEXTURE_CUBE_MAP_SEAMLESS);

        System.out.println("[GpuNative] GL: " + versionStr + " | " + renderer + " | " + vendor
            + " | compute=" + hasComputeShader + " | ssbo=" + hasSSBO
            + " | extensions=" + extensions.size());
    }

    @Override
    public DeviceInfo getInfo() { return info; }

    @Override
    public boolean isExtensionSupported(String ext) {
        return extensions.contains(ext);
    }

    @Override
    public int getMaxTextureSize() { return info.maxTextureSize(); }

    @Override
    public RhiBuffer createBuffer(RhiBuffer.Type type, long size, RhiBuffer.Usage usage) {
        return new GlBuffer(type, size, usage, null);
    }

    @Override
    public RhiBuffer createBuffer(RhiBuffer.Type type, long size, RhiBuffer.Usage usage, ByteBuffer initialData) {
        return new GlBuffer(type, size, usage, initialData);
    }

    @Override
    public RhiTexture createTexture(RhiTexture.Type type, RhiTexture.Format format,
                                     int width, int height, int depth, int mipLevels, int layers) {
        return new GlTexture(type, format, width, height, depth, mipLevels, layers);
    }

    @Override
    public RhiShader createShader(RhiShader.Type type, String source) {
        if (type == RhiShader.Type.COMPUTE && !hasComputeShader) {
            // Wrap compute shader as a GL compute shader anyway - it will use fragment-based fallback
            // at the application level
        }
        return new GlShader(type, source);
    }

    @Override
    public RhiShader createComputeShader(String source) {
        return createShader(RhiShader.Type.COMPUTE, source);
    }

    @Override
    public RhiPipeline createPipeline(RhiPipeline.Descriptor desc) {
        return new GlPipeline(desc);
    }

    @Override
    public RhiCommandList createCommandList() {
        return new GlCommandList(this);
    }

    @Override
    public RhiFence createFence() {
        return new GlFence();
    }

    @Override
    public void submit(RhiCommandList commandList) {
        if (commandList instanceof GlCommandList glCmdList) {
            glCmdList.execute();
        }
    }

    @Override
    public void submit(RhiCommandList commandList, RhiFence signalFence) {
        submit(commandList);
        if (signalFence instanceof GlFence glFence) {
            glFence.signal();
        }
    }

    @Override
    public void submit(RhiCommandList commandList, RhiFence signalFence, RhiFence... waitFences) {
        // Wait for fences before executing
        for (RhiFence fence : waitFences) {
            if (fence != null) {
                fence.waitFor(500_000_000L); // 500ms timeout
            }
        }

        submit(commandList, signalFence);
    }

    @Override
    public void waitIdle() {
        glFinish();
    }

    @Override
    public void destroy() {
        glFinish();
    }

    void readBackIndirectCount(GlBuffer indirectBuffer, long offset, Consumer<Integer> callback) {
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, (int) indirectBuffer.getHandle());
        ByteBuffer data = ByteBuffer.allocateDirect(4);
        glGetBufferSubData(GL_DRAW_INDIRECT_BUFFER, offset, data);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        int count = data.getInt(0);
        callback.accept(count);
    }
}
