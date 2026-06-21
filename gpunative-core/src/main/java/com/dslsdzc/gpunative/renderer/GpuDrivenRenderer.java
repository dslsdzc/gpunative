package com.dslsdzc.gpunative.renderer;

import com.dslsdzc.gpunative.compat.ShaderCompat;
import com.dslsdzc.gpunative.rhi.*;
import com.dslsdzc.gpunative.rhi.opengl.GlDevice;
import com.dslsdzc.gpunative.rhi.opengl.GlPipeline;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL20.*;

/**
 * Main GPU-driven renderer.
 * Orchestrates chunk data upload, compute mesh generation, and terrain rendering.
 */
public class GpuDrivenRenderer {
    private final GlDevice device;
    private final VoxelDataManager voxelData;
    private final GpuMeshPipeline meshPipeline;
    private final ShaderCompat shaderCompat;

    // Pipelines
    private RhiPipeline terrainPipeline;   // depth-tested chunk mesh rendering
    private RhiPipeline debugPipeline;     // overlay cube (no depth test)

    // Shader objects (owned here, destroyed with pipelines)
    private RhiShader terrainVertShader;
    private RhiShader terrainFragShader;

    // Fallback white texture for block atlas
    private RhiTexture whiteTexture;

    // Test cube buffers
    private RhiBuffer testVertexBuffer;
    private RhiBuffer testIndexBuffer;

    private int viewportWidth = 1920;
    private int viewportHeight = 1080;
    private boolean ready;
    private boolean terrainShadersLoaded;
    private boolean computeShadersLoaded;

    // Debug: CPU-generated mesh (for testing terrain pipeline without compute)
    private RhiBuffer debugCpuVertexPool;
    private RhiBuffer debugCpuIndexPool;
    private int debugCpuIndexCount;

    public GpuDrivenRenderer(GlDevice device) {
        this.device = device;
        this.voxelData = new VoxelDataManager(device, 256);
        this.meshPipeline = new GpuMeshPipeline(device, voxelData);
        this.shaderCompat = new ShaderCompat(device);
    }

    public void initialize() {
        voxelData.initialize();
        meshPipeline.initialize();
        meshPipeline.setVoxelBuffer(voxelData.getVoxelBuffer());
        shaderCompat.initialize();

        loadShaders();
        createTestMesh();
        createWhiteTexture();

        ready = true;
    }

    /**
     * Main per-frame render call.
     * Two-phase: compute (upload + mesh gen) then render (terrain + debug).
     */
    public void renderFrame(float deltaTick, float[] viewProjection,
                             float camX, float camY, float camZ) {
        if (!ready) return;

        boolean debugPrinted = false;

        // === Phase 1: Compute ===
        meshPipeline.resetCounters();

        RhiCommandList computeList = device.createCommandList();
        computeList.begin();

        voxelData.uploadDirtyChunks(computeList);
        int chunksBefore = voxelData.getLoadedChunkCount();
        meshPipeline.runMeshGeneration(computeList);

        computeList.end();
        device.submit(computeList);
        computeList.destroy();
        device.waitIdle();

        // === Phase 2: Readback ===
        meshPipeline.readBackCounters();

        if (meshPipeline.getLastIndexCount() > 0 || chunksBefore > 0) {
            System.out.println("[GpuNative] Frame: chunks=" + chunksBefore
                + " indices=" + meshPipeline.getLastIndexCount()
                + " verts=" + meshPipeline.getLastVertexCount());
            debugPrinted = true;
        }

        // === Phase 3: Terrain rendering ===
        boolean hasGpuMesh = meshPipeline.getLastIndexCount() > 0;
        boolean hasCpuMesh = !hasGpuMesh && generateDebugCpuMesh();

        if ((hasGpuMesh || hasCpuMesh) && terrainShadersLoaded) {
            RhiCommandList terrainList = device.createCommandList();
            terrainList.begin();

            renderTerrain(terrainList, viewProjection, camX, camY, camZ, hasGpuMesh);

            terrainList.end();
            device.submit(terrainList);
            terrainList.destroy();
        } else if (!debugPrinted) {
            System.out.println("[GpuNative] No terrain to render: indices="
                + meshPipeline.getLastIndexCount() + " chunks=" + chunksBefore);
        }

        // === Phase 4: Debug overlay ===
        RhiCommandList debugList = device.createCommandList();
        debugList.begin();
        renderTestToScreen(debugList, deltaTick, viewProjection, camX, camY, camZ);
        debugList.end();
        device.submit(debugList);
        debugList.destroy();

        // Shader compat frame (no-op without active pack)
        shaderCompat.onRenderFrame(null, deltaTick);
    }

    public void onChunkLoaded(int chunkX, int chunkZ, byte[] packedData) {
        voxelData.onChunkLoaded(chunkX, chunkZ, packedData);
    }

    public void onChunkUnloaded(int chunkX, int chunkZ) {
        voxelData.onChunkUnloaded(chunkX, chunkZ);
    }

    public void onResize(int width, int height) {
        this.viewportWidth = Math.max(width, 1);
        this.viewportHeight = Math.max(height, 1);
    }

    public boolean isReady() { return ready; }
    public GlDevice getDevice() { return device; }
    public ShaderCompat getShaderCompat() { return shaderCompat; }

    public void destroy() {
        ready = false;
        if (terrainPipeline != null) terrainPipeline.destroy();
        if (debugPipeline != null) debugPipeline.destroy();
        if (terrainVertShader != null) terrainVertShader.destroy();
        if (terrainFragShader != null) terrainFragShader.destroy();
        if (whiteTexture != null) whiteTexture.destroy();
        if (testVertexBuffer != null) testVertexBuffer.destroy();
        if (testIndexBuffer != null) testIndexBuffer.destroy();
        if (debugCpuVertexPool != null) debugCpuVertexPool.destroy();
        if (debugCpuIndexPool != null) debugCpuIndexPool.destroy();
        voxelData.destroy();
        meshPipeline.destroy();
        shaderCompat.destroy();
    }

    // ---- Shader Loading ----

    private void loadShaders() {
        loadTerrainShaders();
        loadComputeShaders();
    }

    private void loadTerrainShaders() {
        try {
            String vertSrc = loadShaderSource("shaders/terrain.vert");
            String fragSrc = loadShaderSource("shaders/terrain.frag");

            RhiShader vertShader = device.createShader(RhiShader.Type.VERTEX, vertSrc);
            checkShader("Terrain vertex", vertShader);

            RhiShader fragShader = device.createShader(RhiShader.Type.FRAGMENT, fragSrc);
            checkShader("Terrain fragment", fragShader);

            // Destroy previous shaders if reloading
            if (terrainVertShader != null) terrainVertShader.destroy();
            if (terrainFragShader != null) terrainFragShader.destroy();
            terrainVertShader = vertShader;
            terrainFragShader = fragShader;

            // Debug pipeline: overlay cube (no depth test)
            RhiPipeline.Descriptor debugDesc = new RhiPipeline.Descriptor();
            debugDesc.vertexShader = vertShader;
            debugDesc.fragmentShader = fragShader;
            debugDesc.depthTest = false;
            debugDesc.depthWrite = false;
            debugDesc.cullMode = RhiPipeline.CullMode.BACK;
            debugPipeline = device.createPipeline(debugDesc);

            // Terrain pipeline: full depth test + culling
            RhiPipeline.Descriptor terrainDesc = new RhiPipeline.Descriptor();
            terrainDesc.vertexShader = vertShader;
            terrainDesc.fragmentShader = fragShader;
            terrainDesc.depthTest = true;
            terrainDesc.depthWrite = true;
            terrainDesc.cullMode = RhiPipeline.CullMode.BACK;
            terrainPipeline = device.createPipeline(terrainDesc);

            terrainShadersLoaded = true;
            System.out.println("[GpuNative] Terrain shaders compiled and pipelines created");
        } catch (Exception e) {
            System.err.println("[GpuNative] Failed to load terrain shaders: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void loadComputeShaders() {
        try {
            String meshGenSrc = loadShaderSource("shaders/mesh_gen.comp");
            String cullingSrc = loadShaderSource("shaders/culling.comp");
            meshPipeline.loadShaders(meshGenSrc, cullingSrc);
            computeShadersLoaded = true;
            System.out.println("[GpuNative] Compute shaders loaded (mesh_gen + culling)");
        } catch (Exception e) {
            System.err.println("[GpuNative] Failed to load compute shaders: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void checkShader(String name, RhiShader shader) {
        if (!shader.isCompiled()) {
            System.err.println("[GpuNative] " + name + " compilation failed: " + shader.getInfoLog());
            throw new RuntimeException(name + " shader compilation failed");
        }
    }

    private String loadShaderSource(String resourcePath) {
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) {
            is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);
        }
        if (is == null) {
            throw new RuntimeException("Shader resource not found: " + resourcePath);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read shader: " + resourcePath, e);
        }
    }

    // ---- CPU Debug Mesh Generation ----

    /**
     * Debug: generate a mesh from the first loaded chunk's voxel data on the CPU.
     * This bypasses the compute shader to verify the data pipeline and rendering work.
     * Returns true if mesh was generated.
     */
    private boolean generateDebugCpuMesh() {
        int firstBlock = voxelData.debugFirstBlock();
        System.out.println("[GpuNative] Debug: first non-air block ID = " + firstBlock);

        if (firstBlock < 0) {
            System.out.println("[GpuNative] Debug: no block data found in any chunk");
            return false;
        }

        // Generate a simple 2-triangle quad at world origin
        float[] verts = {
            -0.5f, -0.5f,  0.5f,  0, 0, 1,  0, 0,  1, 0, 0, 1,
             0.5f, -0.5f,  0.5f,  0, 0, 1,  1, 0,  0, 1, 0, 1,
             0.5f,  0.5f,  0.5f,  0, 0, 1,  1, 1,  0, 0, 1, 1,
            -0.5f,  0.5f,  0.5f,  0, 0, 1,  0, 1,  1, 1, 0, 1,
        };

        int[] indices = { 0, 1, 2, 0, 2, 3 };

        ByteBuffer vbBuf = BufferUtils.createByteBuffer(verts.length * 4);
        for (float v : verts) vbBuf.putFloat(v);
        vbBuf.flip();

        ByteBuffer ibBuf = BufferUtils.createByteBuffer(indices.length * 4);
        for (int i : indices) ibBuf.putInt(i);
        ibBuf.flip();

        if (debugCpuVertexPool != null) debugCpuVertexPool.destroy();
        if (debugCpuIndexPool != null) debugCpuIndexPool.destroy();

        debugCpuVertexPool = device.createBuffer(
            RhiBuffer.Type.VERTEX, (long) verts.length * 4,
            RhiBuffer.Usage.STATIC, vbBuf
        );

        debugCpuIndexPool = device.createBuffer(
            RhiBuffer.Type.INDEX, (long) indices.length * 4,
            RhiBuffer.Usage.STATIC, ibBuf
        );

        debugCpuIndexCount = indices.length;
        System.out.println("[GpuNative] Debug: created CPU mesh with " + indices.length + " indices");
        return true;
    }

    // ---- Terrain Rendering ----

    private void renderTerrain(RhiCommandList cmdList, float[] viewProjection,
                               float camX, float camY, float camZ, boolean useGpuMesh) {
        if (!terrainShadersLoaded || terrainPipeline == null) return;

        cmdList.setViewport(0, 0, viewportWidth, viewportHeight);
        cmdList.clearDefaultDepth(1.0f);
        cmdList.setPipeline(terrainPipeline);

        int prog = ((GlPipeline) terrainPipeline).getGlProgram();

        // Use Minecraft's combined view-projection matrix directly.
        // Set u_ProjectionMatrix = viewProjection, u_ViewMatrix = identity, u_ModelMatrix = identity
        // So: gl_Position = viewProjection * identity * identity * pos = viewProjection * pos ✓
        float[] identityMatrix = createTranslation(0, 0, 0);

        cmdList.setUniformMat4(prog, "u_ProjectionMatrix", viewProjection);
        cmdList.setUniformMat4(prog, "u_ViewMatrix", identityMatrix);
        cmdList.setUniformMat4(prog, "u_ModelMatrix", identityMatrix);
        cmdList.setUniformVec3(prog, "u_CameraPos", camX, camY, camZ);

        // Bind white fallback texture for block atlas
        if (whiteTexture != null) {
            cmdList.bindTexture(0, whiteTexture);
        }

        if (useGpuMesh) {
            // Bind GPU-generated vertex/index pools (STORAGE buffers)
            cmdList.setVertexBuffer(0, meshPipeline.getVertexPool(), 0);
            cmdList.setIndexBuffer(meshPipeline.getIndexPool(), true);

            int indexCount = meshPipeline.getLastIndexCount();
            if (indexCount > 0) {
                cmdList.drawIndexed(indexCount, 1, 0, 0, 0);
            }
        } else if (debugCpuIndexCount > 0) {
            // Use CPU-generated fallback mesh
            cmdList.setVertexBuffer(0, debugCpuVertexPool, 0);
            cmdList.setIndexBuffer(debugCpuIndexPool, true);
            cmdList.drawIndexed(debugCpuIndexCount, 1, 0, 0, 0);
        }
    }

    // ---- Test Mesh Creation ----

    private void createTestMesh() {
        float[] verts = {
            // Front face (+Z)
            -0.5f, -0.5f,  0.5f,  0, 0, 1,  0, 0,  1, 0, 0, 1,
             0.5f, -0.5f,  0.5f,  0, 0, 1,  1, 0,  0, 1, 0, 1,
             0.5f,  0.5f,  0.5f,  0, 0, 1,  1, 1,  0, 0, 1, 1,
            -0.5f,  0.5f,  0.5f,  0, 0, 1,  0, 1,  1, 1, 0, 1,
            // Back face (-Z)
            -0.5f, -0.5f, -0.5f,  0, 0,-1,  0, 0,  1, 0, 0, 1,
            -0.5f,  0.5f, -0.5f,  0, 0,-1,  1, 0,  0, 1, 0, 1,
             0.5f,  0.5f, -0.5f,  0, 0,-1,  1, 1,  0, 0, 1, 1,
             0.5f, -0.5f, -0.5f,  0, 0,-1,  0, 1,  1, 1, 0, 1,
            // Right face (+X)
             0.5f, -0.5f,  0.5f,  1, 0, 0,  0, 0,  1, 0, 0, 1,
             0.5f, -0.5f, -0.5f,  1, 0, 0,  1, 0,  0, 1, 0, 1,
             0.5f,  0.5f, -0.5f,  1, 0, 0,  1, 1,  0, 0, 1, 1,
             0.5f,  0.5f,  0.5f,  1, 0, 0,  0, 1,  1, 1, 0, 1,
            // Left face (-X)
            -0.5f, -0.5f,  0.5f, -1, 0, 0,  0, 0,  1, 0, 0, 1,
            -0.5f,  0.5f,  0.5f, -1, 0, 0,  1, 0,  0, 1, 0, 1,
            -0.5f,  0.5f, -0.5f, -1, 0, 0,  1, 1,  0, 0, 1, 1,
            -0.5f, -0.5f, -0.5f, -1, 0, 0,  0, 1,  1, 1, 0, 1,
            // Top face (+Y)
            -0.5f,  0.5f,  0.5f,  0, 1, 0,  0, 0,  1, 0, 0, 1,
             0.5f,  0.5f,  0.5f,  0, 1, 0,  1, 0,  0, 1, 0, 1,
             0.5f,  0.5f, -0.5f,  0, 1, 0,  1, 1,  0, 0, 1, 1,
            -0.5f,  0.5f, -0.5f,  0, 1, 0,  0, 1,  1, 1, 0, 1,
            // Bottom face (-Y)
            -0.5f, -0.5f,  0.5f,  0,-1, 0,  0, 0,  1, 0, 0, 1,
            -0.5f, -0.5f, -0.5f,  0,-1, 0,  1, 0,  0, 1, 0, 1,
             0.5f, -0.5f, -0.5f,  0,-1, 0,  1, 1,  0, 0, 1, 1,
             0.5f, -0.5f,  0.5f,  0,-1, 0,  0, 1,  1, 1, 0, 1,
        };

        int[] indices = {
            0,1,2, 0,2,3,
            4,5,6, 4,6,7,
            8,9,10, 8,10,11,
            12,13,14, 12,14,15,
            16,17,18, 16,18,19,
            20,21,22, 20,22,23,
        };

        ByteBuffer vbBuf = BufferUtils.createByteBuffer(verts.length * 4);
        for (float v : verts) vbBuf.putFloat(v);
        vbBuf.flip();

        ByteBuffer ibBuf = BufferUtils.createByteBuffer(indices.length * 4);
        for (int i : indices) ibBuf.putInt(i);
        ibBuf.flip();

        testVertexBuffer = device.createBuffer(
            RhiBuffer.Type.VERTEX, (long) verts.length * 4,
            RhiBuffer.Usage.STATIC, vbBuf
        );

        testIndexBuffer = device.createBuffer(
            RhiBuffer.Type.INDEX, (long) indices.length * 4,
            RhiBuffer.Usage.STATIC, ibBuf
        );
    }

    private void createWhiteTexture() {
        whiteTexture = device.createTexture(
            RhiTexture.Type.TEXTURE_2D, RhiTexture.Format.RGBA8_UNORM,
            1, 1, 1, 1, 1
        );
        ByteBuffer white = ByteBuffer.allocateDirect(4);
        white.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255);
        white.flip();
        whiteTexture.upload(0, 0, 0, 1, 1, white);
    }

    // ---- Debug Overlay ----

    private void renderTestToScreen(RhiCommandList cmdList, float deltaTick, float[] viewProjection,
                                     float camX, float camY, float camZ) {
        if (!terrainShadersLoaded || debugPipeline == null) return;

        cmdList.setViewport(0, 0, viewportWidth, viewportHeight);
        cmdList.clearDefaultDepth(1.0f);
        cmdList.setPipeline(debugPipeline);

        int prog = ((GlPipeline) debugPipeline).getGlProgram();

        // Use Minecraft's view-projection directly (same approach as renderTerrain)
        float[] identityMatrix = createTranslation(0, 0, 0);

        // Place cube 3 units in front of the camera
        float dx = (float) Math.sin(deltaTick * 0.5f);
        float dz = (float) Math.cos(deltaTick * 0.5f);
        float[] modelMatrix = createTranslation(camX + dx * 3.0f, camY + 0.5f, camZ + dz * 3.0f);

        cmdList.setUniformMat4(prog, "u_ProjectionMatrix", viewProjection);
        cmdList.setUniformMat4(prog, "u_ViewMatrix", identityMatrix);
        cmdList.setUniformMat4(prog, "u_ModelMatrix", modelMatrix);
        cmdList.setUniformVec3(prog, "u_CameraPos", camX, camY, camZ);

        // Bind white fallback texture for block atlas
        if (whiteTexture != null) {
            cmdList.bindTexture(0, whiteTexture);
        }

        cmdList.setVertexBuffer(0, testVertexBuffer, 0);
        cmdList.setIndexBuffer(testIndexBuffer, true);

        cmdList.drawIndexed(36, 1, 0, 0, 0);
    }

    // ---- Math Helpers ----

    private float[] createTranslation(float x, float y, float z) {
        return new float[]{
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            x, y, z, 1
        };
    }

    private float[] createLookAt(float eyeX, float eyeY, float eyeZ,
                                  float centerX, float centerY, float centerZ,
                                  float upX, float upY, float upZ) {
        float[] f = { centerX - eyeX, centerY - eyeY, centerZ - eyeZ };
        float fLen = (float) Math.sqrt(f[0]*f[0] + f[1]*f[1] + f[2]*f[2]);
        if (fLen > 0) { f[0] /= fLen; f[1] /= fLen; f[2] /= fLen; }

        float[] s = { f[1]*upZ - f[2]*upY, f[2]*upX - f[0]*upZ, f[0]*upY - f[1]*upX };
        float sLen = (float) Math.sqrt(s[0]*s[0] + s[1]*s[1] + s[2]*s[2]);
        if (sLen > 0) { s[0] /= sLen; s[1] /= sLen; s[2] /= sLen; }

        float[] u = { s[1]*f[2] - s[2]*f[1], s[2]*f[0] - s[0]*f[2], s[0]*f[1] - s[1]*f[0] };

        return new float[]{
            s[0], u[0], -f[0], 0,
            s[1], u[1], -f[1], 0,
            s[2], u[2], -f[2], 0,
            -s[0]*eyeX - s[1]*eyeY - s[2]*eyeZ,
            -u[0]*eyeX - u[1]*eyeY - u[2]*eyeZ,
            f[0]*eyeX + f[1]*eyeY + f[2]*eyeZ,
            1
        };
    }

    private float[] createPerspective(float fovY, float aspect, float zNear, float zFar) {
        float f = (float) (1.0 / Math.tan(fovY / 2.0));
        float rangeInv = 1.0f / (zNear - zFar);
        return new float[]{
            f / aspect, 0, 0, 0,
            0, f, 0, 0,
            0, 0, (zFar + zNear) * rangeInv, -1,
            0, 0, 2 * zFar * zNear * rangeInv, 0
        };
    }
}
