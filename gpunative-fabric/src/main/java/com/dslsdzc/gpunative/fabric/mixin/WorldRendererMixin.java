package com.dslsdzc.gpunative.fabric.mixin;

import com.dslsdzc.gpunative.GpuNative;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into Minecraft's LevelRenderer to intercept
 * and replace the terrain rendering pipeline.
 *
 * Cancels vanilla chunk rendering so our GPU-driven pipeline
 * takes over. Fabric API's WorldRenderEvents still fire
 * since they inject at different points in renderLevel.
 */
@Mixin(net.minecraft.client.renderer.LevelRenderer.class)
public class WorldRendererMixin {

    /**
     * Cancel vanilla terrain chunk rendering.
     * renderChunkLayer is called for each RenderType layer
     * (solid, cutout, translucent). We cancel all of them
     * since our GPU pipeline handles everything.
     */
    @Inject(method = "renderChunkLayer", at = @At("HEAD"), cancellable = true)
    private void onRenderChunkLayer(CallbackInfo ci) {
        if (GpuNative.isInitialized()) {
            ci.cancel();
        }
    }
}
