package com.dslsdzc.gpunative;

import com.dslsdzc.gpunative.renderer.GpuDrivenRenderer;
import com.dslsdzc.gpunative.rhi.opengl.GlDevice;

/**
 * Abstract base class for GPU Native mod entry points.
 * Both Fabric and NeoForge implementations must extend this
 * and call the appropriate init method.
 */
public abstract class GpuNative {
    public static final String MOD_ID = "gpunative";
    public static final String MOD_NAME = "GPU Native";

    private static GpuDrivenRenderer renderer;
    private static GlDevice device;
    private static boolean initialized;

    /**
     * Initialize the GPU Native rendering engine.
     * Called after Minecraft's GL context is created.
     */
    protected static void initialize() {
        if (initialized) return;

        device = new GlDevice();
        renderer = new GpuDrivenRenderer(device);
        renderer.initialize();
        initialized = true;
    }

    /**
     * Called each frame from the render hook.
     * @param deltaTick Partial tick time
     * @param viewProjection The combined view-projection matrix as float[16]
     * @param camX Camera X position
     * @param camY Camera Y position
     * @param camZ Camera Z position
     */
    public static void onRenderFrame(float deltaTick, float[] viewProjection,
                                      float camX, float camY, float camZ) {
        if (renderer != null && renderer.isReady()) {
            renderer.renderFrame(deltaTick, viewProjection, camX, camY, camZ);
        }
    }

    /**
     * Called when a chunk loads with pre-packed voxel data.
     */
    public static void onChunkLoaded(int chunkX, int chunkZ, byte[] packedData) {
        if (renderer != null) {
            renderer.onChunkLoaded(chunkX, chunkZ, packedData);
        }
    }

    /**
     * Called when a chunk unloads.
     */
    public static void onChunkUnloaded(int chunkX, int chunkZ) {
        if (renderer != null) {
            renderer.onChunkUnloaded(chunkX, chunkZ);
        }
    }

    /**
     * Called on window resize.
     */
    public static void onResize(int width, int height) {
        if (renderer != null) {
            renderer.onResize(width, height);
        }
    }

    public static GpuDrivenRenderer getRenderer() { return renderer; }
    public static GlDevice getDevice() { return device; }
    public static boolean isInitialized() { return initialized; }

    /**
     * Cleanup on mod shutdown.
     */
    protected static void shutdown() {
        if (renderer != null) {
            renderer.destroy();
            renderer = null;
        }
        if (device != null) {
            device.destroy();
            device = null;
        }
        initialized = false;
    }
}
