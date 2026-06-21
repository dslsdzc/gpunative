package com.dslsdzc.gpunative.compat;

import com.dslsdzc.gpunative.rhi.*;
import com.dslsdzc.gpunative.rhi.opengl.GlDevice;
import com.dslsdzc.gpunative.util.MathUtil;

import java.util.*;
import java.util.function.Consumer;

/**
 * Shader compatibility layer.
 * Interfaces with Iris/OptiFine shader packs by providing
 * standard G-Buffer layouts and uniform bindings.
 *
 * This layer translates between GPU Native's internal frame
 * graph and the shader pack's expected passes.
 */
public class ShaderCompat {
    private final GlDevice device;

    // Registered shader pack callbacks
    private final List<PassHook> prePassHooks = new ArrayList<>();
    private final List<PassHook> postPassHooks = new ArrayList<>();

    // Standard uniforms expected by shader packs
    private final Map<String, float[]> uniformBuffer = new HashMap<>();

    // Current shader pack info
    private ShaderPackInfo activePack;
    private boolean initialized;

    public ShaderCompat(GlDevice device) {
        this.device = device;
    }

    /**
     * Initialize the compatibility layer.
     */
    public void initialize() {
        // Allocate common uniform buffers
        initialized = true;
    }

    /**
     * Called each frame to allow shader pack to inject its passes.
     */
    public void onRenderFrame(RhiCommandList cmdList, float deltaTick) {
        if (!initialized || activePack == null) return;

        fireHooks(cmdList, prePassHooks, deltaTick);
        fireHooks(cmdList, postPassHooks, deltaTick);
    }

    /**
     * Load a shader pack.
     * @param packId Identifier for the shader pack
     * @param config Pack configuration data
     * @return true if the pack was loaded successfully
     */
    public boolean loadShaderPack(String packId, Object config) {
        this.activePack = new ShaderPackInfo(packId);
        prePassHooks.clear();
        postPassHooks.clear();
        return true;
    }

    /**
     * Unload the current shader pack, restoring default rendering.
     */
    public void unloadShaderPack() {
        this.activePack = null;
        prePassHooks.clear();
        postPassHooks.clear();
    }

    /**
     * Register a hook to run before a specific pass.
     */
    public void registerPrePassHook(String passName, Consumer<RhiCommandList> callback) {
        prePassHooks.add(new PassHook(passName, callback));
    }

    /**
     * Register a hook to run after a specific pass.
     */
    public void registerPostPassHook(String passName, Consumer<RhiCommandList> callback) {
        postPassHooks.add(new PassHook(passName, callback));
    }

    /**
     * Update a standard uniform value (e.g. projection matrix, light direction).
     */
    public void setUniform(String name, float[] values) {
        uniformBuffer.put(name, values);
    }

    /**
     * Retrieve a uniform buffer for shader binding.
     */
    public float[] getUniform(String name) {
        return uniformBuffer.get(name);
    }

    /**
     * Populate the OptiFine/Iris-compatible G-Buffer uniform values.
     * Called once per frame before rendering.
     */
    public void populateStandardUniforms(float[] viewProj, float[] modelView,
                                          float[] projection, float[] modelViewProjection,
                                          float tickDelta, float sunAngle,
                                          float[] sunPosition, float[] fogColor) {
        setUniform("gbufferProjection", projection);
        setUniform("gbufferModelView", modelView);
        setUniform("gbufferProjectionInverse", MathUtil.mat4Invert(projection));
        setUniform("gbufferModelViewInverse", MathUtil.mat4Invert(modelView));
        setUniform("gbufferPreviousProjection", projection);
        setUniform("gbufferPreviousModelView", modelView);
        setUniform("sunPosition", sunPosition);
        setUniform("fogColor", fogColor);
        setUniform("sunAngle", new float[]{ sunAngle });
    }

    /**
     * Get the current shader pack's screen buffer format expectations.
     */
    public GBufferLayout getGBufferLayout() {
        if (activePack == null) {
            return GBufferLayout.STANDARD;
        }
        return activePack.gbufferLayout;
    }

    public boolean hasActivePack() { return activePack != null; }
    public String getActivePackId() { return activePack != null ? activePack.id : null; }

    public void destroy() {
        unloadShaderPack();
        uniformBuffer.clear();
        initialized = false;
    }

    // ---- Internal ----

    private void fireHooks(RhiCommandList cmdList, List<PassHook> hooks, float deltaTick) {
        for (PassHook hook : hooks) {
            hook.callback.accept(cmdList);
        }
    }

    /**
     * G-Buffer layout matching OptiFine / Iris specification.
     */
    public enum GBufferLayout {
        STANDARD(new String[]{ "colortex0", "colortex1", "colortex2", "colortex3", "depthtex0" }),
        PBR(new String[]{ "colortex0", "colortex1", "colortex2", "colortex3", "colortex4", "colortex5", "depthtex0" }),
        DEFERRED(new String[]{ "gcolor", "gdepth", "gnormal", "composite" });

        final String[] targets;
        GBufferLayout(String[] targets) { this.targets = targets; }
    }

    private record PassHook(String passName, Consumer<RhiCommandList> callback) {}

    private static class ShaderPackInfo {
        final String id;
        final GBufferLayout gbufferLayout;
        final Map<String, String> options = new HashMap<>();

        ShaderPackInfo(String id) {
            this.id = id;
            this.gbufferLayout = GBufferLayout.STANDARD;
        }
    }
}
