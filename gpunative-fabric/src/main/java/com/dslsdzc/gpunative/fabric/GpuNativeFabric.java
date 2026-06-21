package com.dslsdzc.gpunative.fabric;

import com.dslsdzc.gpunative.GpuNative;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * GPU Native Fabric entry point.
 * Initializes the rendering engine and hooks into Minecraft's
 * client lifecycle and rendering events.
 */
public class GpuNativeFabric extends GpuNative implements ModInitializer, ClientModInitializer {

    @Override
    public void onInitialize() {
        // Common mod initialization (server-safe)
        MOD_LOGGER.info("GPU Native (Fabric) initializing...");
    }

    @Override
    public void onInitializeClient() {
        // Client-side initialization
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            initialize();
            MOD_LOGGER.info("GPU Native rendering engine initialized on Fabric");
        });

        // Register world render hook - renders LAST so our output is on top
        WorldRenderEvents.LAST.register(context -> {
            if (!isInitialized()) return;

            var camera = context.camera();
            var pose = context.matrixStack().last().pose();
            var proj = context.projectionMatrix();

            float[] viewProj = extractViewProjection(pose, proj);
            float tickDelta = context.tickDelta();

            onRenderFrame(tickDelta, viewProj,
                (float) camera.getPosition().x,
                (float) camera.getPosition().y,
                (float) camera.getPosition().z
            );
        });

        // Prevent vanilla terrain rendering
        WorldRenderEvents.BEFORE_ENTITIES.register(context -> {
            // Signal to our mixin to suppress vanilla rendering
        });

        // Chunk load/unload tracking
        registerChunkEventListeners();

        // Shutdown hook
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            shutdown();
        });
    }

    /**
     * Extract a combined view-projection matrix from Minecraft's camera state.
     */
    private float[] extractViewProjection(org.joml.Matrix4f modelView,
                                           org.joml.Matrix4f projection) {
        // Minecraft 1.20.1 uses JOML
        org.joml.Matrix4f vp = new org.joml.Matrix4f(projection);
        vp.mul(modelView);

        float[] result = new float[16];
        vp.get(result);
        return result;
    }

    private void registerChunkEventListeners() {
        // In a full implementation, register.chunk load/unload callbacks
        // via Fabric API events or mixins
    }

    private static final org.slf4j.Logger MOD_LOGGER =
        org.slf4j.LoggerFactory.getLogger(MOD_ID);
}
