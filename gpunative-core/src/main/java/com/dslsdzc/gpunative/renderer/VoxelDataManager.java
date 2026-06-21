package com.dslsdzc.gpunative.renderer;

import com.dslsdzc.gpunative.rhi.RhiBuffer;
import com.dslsdzc.gpunative.rhi.RhiCommandList;
import com.dslsdzc.gpunative.rhi.opengl.GlDevice;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages voxel data on the CPU side, uploads to GPU SSBO.
 * Each chunk stores packed block data: 4 bytes per block = block ID (0 = air).
 * Chunk layout: 24 sections x 16x16x16 blocks = 98,304 blocks = 393,216 bytes per chunk.
 */
public class VoxelDataManager {
    static final int CHUNK_SIZE = 16;
    static final int SECTION_HEIGHT = 16;
    static final int MAX_SECTIONS = 24; // -64 to 320
    static final int BLOCKS_PER_SECTION = CHUNK_SIZE * CHUNK_SIZE * SECTION_HEIGHT;
    static final int BLOCKS_PER_CHUNK = BLOCKS_PER_SECTION * MAX_SECTIONS;
    static final int BYTES_PER_BLOCK = 4; // uint32 per block
    static final int BYTES_PER_CHUNK = BLOCKS_PER_CHUNK * BYTES_PER_BLOCK;

    private final GlDevice device;
    private final int maxCachedChunks;

    // Key: packed long (chunkX << 32 | (chunkZ & 0xFFFFFFFFL))
    private final ConcurrentHashMap<Long, ChunkData> chunks = new ConcurrentHashMap<>();

    // Sequential chunk slot allocator
    private final AtomicInteger nextSlot = new AtomicInteger(0);
    private final Map<Integer, Long> slotToKey = new ConcurrentHashMap<>(); // slot -> chunk key

    // GPU SSBO holding all chunk data
    private RhiBuffer voxelBuffer;
    private boolean initialized;

    // Reusable upload buffer to avoid per-frame direct buffer allocation
    private ByteBuffer uploadBuffer;

    public VoxelDataManager(GlDevice device, int maxCachedChunks) {
        this.device = device;
        this.maxCachedChunks = maxCachedChunks;
    }

    public void initialize() {
        voxelBuffer = device.createBuffer(
            RhiBuffer.Type.STORAGE,
            (long) BYTES_PER_CHUNK * maxCachedChunks,
            RhiBuffer.Usage.DYNAMIC
        );
        initialized = true;
    }

    public void onChunkLoaded(int chunkX, int chunkZ, byte[] packedData) {
        if (!initialized) return;

        long key = packKey(chunkX, chunkZ);
        ChunkData existing = chunks.get(key);
        if (existing != null) {
            existing.packedData = packedData;
            existing.dirty = true;
            return;
        }

        int slot = Math.floorMod(nextSlot.getAndIncrement(), maxCachedChunks);
        // Evict old chunk at this slot if any
        Long oldKey = slotToKey.put(slot, key);
        if (oldKey != null) {
            ChunkData old = chunks.remove(oldKey);
            if (old != null) old.evict();
        }

        ChunkData data = new ChunkData(chunkX, chunkZ, packedData, slot, true);
        chunks.put(key, data);
    }

    public void onChunkUnloaded(int chunkX, int chunkZ) {
        long key = packKey(chunkX, chunkZ);
        ChunkData data = chunks.remove(key);
        if (data != null) {
            slotToKey.remove(data.slot, key);
            data.evict();
        }
    }

    /**
     * Upload all dirty chunks to the GPU SSBO.
     * Called at the start of each frame.
     */
    public void uploadDirtyChunks(RhiCommandList cmdList) {
        if (!initialized) return;

        if (uploadBuffer == null) {
            uploadBuffer = ByteBuffer.allocateDirect(BYTES_PER_CHUNK);
        }

        for (Map.Entry<Long, ChunkData> entry : chunks.entrySet()) {
            ChunkData data = entry.getValue();
            if (data.dirty && data.packedData != null) {
                uploadBuffer.clear();
                int srcLenBytes = data.packedData.length;
                int blocks = Math.min(srcLenBytes / 2, BLOCKS_PER_CHUNK);
                for (int i = 0; i < blocks; i++) {
                    int lo = data.packedData[i * 2] & 0xFF;
                    int hi = data.packedData[i * 2 + 1] & 0xFF;
                    int blockId = lo | (hi << 8);
                    uploadBuffer.putInt(blockId);
                }
                // Pad remaining blocks as air (0)
                for (int i = blocks; i < BLOCKS_PER_CHUNK; i++) {
                    uploadBuffer.putInt(0);
                }
                uploadBuffer.flip();

                long offset = (long) data.slot * BYTES_PER_CHUNK;
                voxelBuffer.upload(offset, uploadBuffer);
                data.dirty = false;
            }
        }
    }

    public int getChunkSlot(int chunkX, int chunkZ) {
        long key = packKey(chunkX, chunkZ);
        ChunkData data = chunks.get(key);
        return data != null ? data.slot : -1;
    }

    /**
     * Iterate all loaded chunks with their slot and coordinates.
     */
    public void forEachSlot(ChunkSlotVisitor visitor) {
        for (Map.Entry<Long, ChunkData> entry : chunks.entrySet()) {
            ChunkData data = entry.getValue();
            visitor.visit(data.slot, data.chunkX, data.chunkZ);
        }
    }

    @FunctionalInterface
    public interface ChunkSlotVisitor {
        void visit(int slot, int chunkX, int chunkZ);
    }

    public RhiBuffer getVoxelBuffer() { return voxelBuffer; }
    public int getMaxCachedChunks() { return maxCachedChunks; }
    public int getLoadedChunkCount() { return chunks.size(); }

    /**
     * Debug: dump first non-air block ID from the first loaded chunk.
     * Returns -1 if no chunks or all air.
     */
    public int debugFirstBlock() {
        for (Map.Entry<Long, ChunkData> entry : chunks.entrySet()) {
            ChunkData data = entry.getValue();
            if (data.packedData != null) {
                for (int i = 0; i < BLOCKS_PER_CHUNK; i++) {
                    int lo = data.packedData[i * 2] & 0xFF;
                    int hi = data.packedData[i * 2 + 1] & 0xFF;
                    int blockId = lo | (hi << 8);
                    if (blockId != 0) return blockId;
                }
            }
        }
        return -1;
    }

    public void destroy() {
        if (voxelBuffer != null) voxelBuffer.destroy();
        chunks.clear();
        slotToKey.clear();
        initialized = false;
    }

    private static long packKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    static class ChunkData {
        final int chunkX;
        final int chunkZ;
        final int slot;
        byte[] packedData;
        boolean dirty;

        ChunkData(int chunkX, int chunkZ, byte[] packedData, int slot, boolean dirty) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.packedData = packedData;
            this.slot = slot;
            this.dirty = dirty;
        }

        void evict() {
            this.packedData = null;
        }
    }
}
