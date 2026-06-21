package com.dslsdzc.gpunative.neoforge.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into Minecraft's LevelRenderer for NeoForge.
 *
 * On NeoForge, most rendering interception is done via the
 * RenderLevelStageEvent. This mixin ensures the vanilla
 * terrain section rendering is skipped when GPU Native is active.
 */
@Mixin(LevelRenderer.class)
public class WorldRendererMixin {

    /**
     * Suppress vanilla chunk section rendering.
     * GPU Native uses its own rendering pipeline via RenderLevelStageEvent.
     */
    @Inject(
        method = "renderSectionLayer",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onRenderSectionLayer(CallbackInfo ci) {
        if (com.dslsdzc.gpunative.GpuNative.isInitialized()) {
            ci.cancel();
        }
    }
}
