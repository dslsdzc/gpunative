package com.dslsdzc.gpunative.renderer;

import com.dslsdzc.gpunative.rhi.*;
import com.dslsdzc.gpunative.rhi.opengl.GlDevice;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * GPU-side mesh generation and culling pipeline.
 * Dispatches mesh_gen.comp for each loaded chunk, then culling.comp
 * to filter visible chunks before rendering.
 */
public class GpuMeshPipeline {
    private final GlDevice device;
    private final VoxelDataManager voxelData;
    private final boolean hasCompute;

    // Shaders
    private RhiShader meshGenComputeShader;
    private RhiShader cullingComputeShader;
    private RhiShader meshGenShader;    // fallback
    private RhiShader cullingShader;     // fallback

    // GPU pools
    private RhiBuffer vertexPool;
    private RhiBuffer indexPool;
    private RhiBuffer counterBuffer;   // [drawCount, vertexCount, indexCount] uint32
    private RhiBuffer indirectBuffer;   // DrawElementsIndirect command

    // Voxel data SSBO reference (set from VoxelDataManager)
    private RhiBuffer voxelBuffer;

    // Culling resources
    private RhiBuffer frustumBuffer;
    private RhiTexture hiZTexture;

    // CPU-side readback of counters
    private int lastDrawCount;
    private int lastVertexCount;
    private int lastIndexCount;

    private boolean initialized;

    public GpuMeshPipeline(GlDevice device, VoxelDataManager voxelData) {
        this.device = device;
        this.voxelData = voxelData;
        this.hasCompute = device.isExtensionSupported("GL_ARB_compute_shader")
            || device.getInfo().majorVersion() >= 43;
    }

    public void initialize() {
        // Cap total verts to keep vertexPool ~96 MB (safe for GL_MAX_SHADER_STORAGE_BLOCK_SIZE)
        int maxVerts = 2_000_000;
        int maxIndices = 3_000_000;
        long vertexPoolSize = (long) maxVerts * 48L;
        long indexPoolSize = (long) maxIndices * 4L;

        System.out.println("[GpuNative] Allocating vertexPool=" + (vertexPoolSize / (1024*1024))
            + "MB (" + maxVerts + " verts) indexPool=" + (indexPoolSize / (1024*1024))
            + "MB (" + maxIndices + " indices)");

        vertexPool = device.createBuffer(
            RhiBuffer.Type.STORAGE,
            vertexPoolSize,
            RhiBuffer.Usage.DYNAMIC
        );

        indexPool = device.createBuffer(
            RhiBuffer.Type.STORAGE,
            indexPoolSize,
            RhiBuffer.Usage.DYNAMIC
        );

        counterBuffer = device.createBuffer(
            RhiBuffer.Type.STORAGE,
            12L, // 3 uints
            RhiBuffer.Usage.DYNAMIC
        );

        indirectBuffer = device.createBuffer(
            RhiBuffer.Type.INDIRECT,
            20L, // 5 uints for DrawElementsIndirectCommand
            RhiBuffer.Usage.DYNAMIC
        );

        // Frustum data: 6 planes * 4 floats * 4 bytes
        frustumBuffer = device.createBuffer(
            RhiBuffer.Type.UNIFORM,
            96L,
            RhiBuffer.Usage.DYNAMIC
        );

        initialized = true;
    }

    public void loadShaders(String meshGenSource, String cullingSource) {
        if (hasCompute) {
            String meshGenSrc = adaptComputeShaderSource(meshGenSource);
            String cullingSrc = adaptComputeShaderSource(cullingSource);

            // Debug: show adapted source first line
            String firstLine = meshGenSrc.substring(0, Math.min(80, meshGenSrc.indexOf('\n') > 0 ? meshGenSrc.indexOf('\n') : meshGenSrc.length())).replace("\n", "\\n");
            System.out.println("[GpuNative] mesh_gen.comp adapted header: " + firstLine);

            this.meshGenComputeShader = device.createComputeShader(meshGenSrc);
            this.cullingComputeShader = device.createComputeShader(cullingSrc);

            if (!meshGenComputeShader.isCompiled() || meshGenComputeShader.getHandle() == 0) {
                System.err.println("[GpuNative] mesh_gen.comp FAILED: " + meshGenComputeShader.getInfoLog());
                this.meshGenComputeShader = null;
            } else {
                System.out.println("[GpuNative] mesh_gen.comp compiled OK (handle=" + meshGenComputeShader.getHandle() + ")");
            }
            if (!cullingComputeShader.isCompiled() || cullingComputeShader.getHandle() == 0) {
                System.err.println("[GpuNative] culling.comp FAILED: " + cullingComputeShader.getInfoLog());
                this.cullingComputeShader = null;
            } else {
                System.out.println("[GpuNative] culling.comp compiled OK (handle=" + cullingComputeShader.getHandle() + ")");
            }
        } else {
            this.meshGenShader = device.createShader(RhiShader.Type.FRAGMENT, meshGenSource);
            this.cullingShader = device.createShader(RhiShader.Type.FRAGMENT, cullingSource);
        }
    }

    /**
     * Adapt compute shader source to the available GL version.
     * GL 4.3+ : use #version 430 core as-is.
     * GL 3.x + ARB_compute_shader : strip #version, use #version 150 + extensions.
     */
    private String adaptComputeShaderSource(String source) {
        int major = device.getInfo().majorVersion();
        boolean nativeCompute = major >= 43;
        if (nativeCompute) {
            return source;
        }

        // Strip existing #version line (handle both \n and \r\n line endings)
        String cleaned = source.replaceFirst("^\\s*#version\\s+\\d+\\s+core\\s*\\r?\\n", "");
        return "#version 150\n"
             + "#extension GL_ARB_compute_shader : enable\n"
             + "#extension GL_ARB_shader_storage_buffer_object : enable\n"
             + "#extension GL_ARB_shading_language_420pack : enable\n"
             + "#extension GL_ARB_explicit_uniform_location : enable\n"
             + "#extension GL_ARB_gpu_shader5 : enable\n"
             + cleaned;
    }

    /**
     * Reset atomic counters to zero. Called before each frame's mesh gen.
     */
    public void resetCounters() {
        ByteBuffer zeros = ByteBuffer.allocateDirect(12);
        zeros.putInt(0).putInt(0).putInt(0);
        zeros.flip();
        counterBuffer.upload(zeros);
    }

    /**
     * Set the voxel data SSBO reference.
     */
    public void setVoxelBuffer(RhiBuffer buffer) {
        this.voxelBuffer = buffer;
    }

    /**
     * Run mesh generation compute shader for all loaded chunks.
     */
    public void runMeshGeneration(RhiCommandList cmdList) {
        if (!initialized || !hasCompute || meshGenComputeShader == null) return;

        int prog = (int) meshGenComputeShader.getHandle();
        if (prog == 0) {
            System.err.println("[GpuNative] mesh_gen.comp not available"
                + " isCompiled=" + meshGenComputeShader.isCompiled()
                + " infoLog=" + meshGenComputeShader.getInfoLog()
                + " GLversion=" + device.getInfo().version()
                + " major=" + device.getInfo().majorVersion());
            return;
        }
        if (voxelData.getLoadedChunkCount() == 0) return;

        // Bind output pools
        cmdList.setShaderStorageBuffer(0, "VertexPool", vertexPool, 0, vertexPool.getSize());
        cmdList.setShaderStorageBuffer(1, "IndexPool", indexPool, 0, indexPool.getSize());
        cmdList.setShaderStorageBuffer(4, "DrawBuffer", counterBuffer, 0, counterBuffer.getSize());

        // Bind voxel data input
        cmdList.setShaderStorageBuffer(3, "VoxelData", voxelBuffer, 0, voxelBuffer.getSize());

        int groupsPerChunk = (VoxelDataManager.BLOCKS_PER_CHUNK + 63) / 64;

        // Dispatch one set of workgroups per loaded chunk
        voxelData.forEachSlot((slot, cx, cz) -> {
            cmdList.setProgram(prog);
            cmdList.setUniformInt(prog, "u_DataSlot", slot);
            cmdList.setUniformInt(prog, "u_ChunkBaseX", cx * 16);
            cmdList.setUniformInt(prog, "u_ChunkBaseZ", cz * 16);
            cmdList.setUniformInt(prog, "u_TotalSections", VoxelDataManager.MAX_SECTIONS);
            cmdList.dispatchCompute(groupsPerChunk, 1, 1);
        });

        // Memory barrier: make SSBO writes visible for rendering
        cmdList.memoryBarrier(RhiCommandList.MemoryBarrier.SHADER_STORAGE);
    }

    /**
     * Read back mesh gen counters after compute is done.
     */
    public void readBackCounters() {
        ByteBuffer data = counterBuffer.download(0, 12);
        if (data != null && data.remaining() >= 12) {
            IntBuffer ib = data.asIntBuffer();
            lastDrawCount = ib.get(0);
            lastVertexCount = ib.get(1);
            lastIndexCount = ib.get(2);
        } else {
            lastDrawCount = 0;
            lastVertexCount = 0;
            lastIndexCount = 0;
        }
    }

    /**
     * Write an indirect draw command using the current counters.
     */
    public void writeIndirectCommand() {
        ByteBuffer cmd = ByteBuffer.allocateDirect(20);
        cmd.putInt(lastIndexCount);   // indexCount
        cmd.putInt(1);                // instanceCount
        cmd.putInt(0);                // firstIndex
        cmd.putInt(0);                // baseVertex
        cmd.putInt(0);                // firstInstance
        cmd.flip();
        indirectBuffer.upload(cmd);
    }

    // ---- Accessors ----

    public RhiBuffer getVertexPool() { return vertexPool; }
    public RhiBuffer getIndexPool() { return indexPool; }
    public RhiBuffer getCounterBuffer() { return counterBuffer; }
    public RhiBuffer getIndirectBuffer() { return indirectBuffer; }
    public int getLastDrawCount() { return lastDrawCount; }
    public int getLastVertexCount() { return lastVertexCount; }
    public int getLastIndexCount() { return lastIndexCount; }
    public boolean hasComputeShaders() { return hasCompute && meshGenComputeShader != null; }

    public void destroy() {
        if (vertexPool != null) vertexPool.destroy();
        if (indexPool != null) indexPool.destroy();
        if (counterBuffer != null) counterBuffer.destroy();
        if (indirectBuffer != null) indirectBuffer.destroy();
        if (frustumBuffer != null) frustumBuffer.destroy();
        initialized = false;
    }
}
