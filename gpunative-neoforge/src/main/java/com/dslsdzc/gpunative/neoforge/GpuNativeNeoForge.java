package com.dslsdzc.gpunative.neoforge;

import com.dslsdzc.gpunative.GpuNative;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GPU Native NeoForge entry point.
 * Initializes the rendering engine and hooks into NeoForge's
 * rendering events to replace the vanilla pipeline.
 */
@Mod(GpuNative.MOD_ID)
public class GpuNativeNeoForge extends GpuNative {
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public GpuNativeNeoForge(IEventBus modEventBus) {
        LOGGER.info("GPU Native (NeoForge) initializing...");

        modEventBus.addListener(this::onCommonSetup);
        modEventBus.addListener(this::onClientSetup);

        // Register event handlers on the NeoForge event bus
        NeoForge.EVENT_BUS.register(this);
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        // Server-safe initialization
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            initialize();
            LOGGER.info("GPU Native rendering engine initialized on NeoForge");
        });
    }

    /**
     * Intercept the level rendering stage to inject GPU Native's pipeline.
     * We hook AFTER_SKY to replace the terrain rendering.
     */
    @SubscribeEvent
    public void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!isInitialized()) return;

        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            var camera = event.getCamera();
            var pose = event.getPoseStack().last().pose();
            var proj = event.getProjectionMatrix();

            // Build combined view-projection matrix
            org.joml.Matrix4f vp = new org.joml.Matrix4f(proj);
            vp.mul(pose);

            float[] viewProj = new float[16];
            vp.get(viewProj);

            onRenderFrame(event.getPartialTick(), viewProj,
                (float) camera.getPosition().x,
                (float) camera.getPosition().y,
                (float) camera.getPosition().z
            );
        }
    }

    /**
     * Track chunk load events.
     */
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!isInitialized()) return;
        var pos = event.getChunk().getPos();
        if (event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk levelChunk) {
            byte[] packed = ChunkDataExtractor.extract(levelChunk);
            if (packed != null) {
                onChunkLoaded(pos.x, pos.z, packed);
            }
        }
    }

    /**
     * Track chunk unload events.
     */
    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (!isInitialized()) return;
        var pos = event.getChunk().getPos();
        onChunkUnloaded(pos.x, pos.z);
    }

    /**
     * Handle client disconnect - clean up renderer.
     */
    @SubscribeEvent
    public void onClientDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        // Keep renderer alive; chunks will re-load on next connection
    }
}
