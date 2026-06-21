package com.dslsdzc.gpunative.fabric.mixin;

import com.dslsdzc.gpunative.GpuNative;
import com.dslsdzc.gpunative.fabric.ChunkDataExtractor;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

/**
 * Mixin into ClientChunkCache to intercept chunk load/unload events
 * and forward them to GPU Native's voxel data manager.
 */
@Mixin(ClientChunkCache.class)
public class ClientChunkCacheMixin {

    /**
     * Called when a chunk is loaded from the network.
     * Extracts block data and passes it to GPU Native.
     */
    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void onChunkLoaded(int x, int z, FriendlyByteBuf buf, CompoundTag tag,
                                Consumer<?> biomeConsumer,
                                CallbackInfoReturnable<LevelChunk> cir) {
        LevelChunk chunk = cir.getReturnValue();
        if (chunk != null) {
            byte[] packed = ChunkDataExtractor.extract(chunk);
            if (packed != null) {
                GpuNative.onChunkLoaded(x, z, packed);
            }
        }
    }

    /**
     * Called when a chunk is unloaded/dropped.
     */
    @Inject(method = "drop", at = @At("HEAD"))
    private void onChunkUnloaded(int x, int z, CallbackInfo ci) {
        GpuNative.onChunkUnloaded(x, z);
    }
}
