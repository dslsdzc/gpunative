package com.dslsdzc.gpunative.neoforge;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Extracts and packs voxel data from Minecraft chunks.
 * Same format as ChunkDataExtractor in the Fabric module.
 */
public class ChunkDataExtractor {
    private static final int SECTION_SIZE = 16;
    private static final int BLOCKS_PER_SECTION = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    private static final int MAX_SECTIONS = 24;
    public static final int BYTES_PER_BLOCK = 2;
    public static final int BYTES_PER_SECTION = BLOCKS_PER_SECTION * BYTES_PER_BLOCK;
    public static final int BYTES_PER_CHUNK = BYTES_PER_SECTION * MAX_SECTIONS;

    /**
     * Extract and pack block data from a LevelChunk.
     */
    public static byte[] extract(LevelChunk chunk) {
        if (chunk == null) return null;

        byte[] data = new byte[BYTES_PER_CHUNK];
        LevelChunkSection[] sections = chunk.getSections();

        if (sections == null) return data;

        for (int sectionIdx = 0; sectionIdx < sections.length && sectionIdx < MAX_SECTIONS; sectionIdx++) {
            LevelChunkSection section = sections[sectionIdx];
            if (section == null || section.hasOnlyAir()) continue;

            for (int localY = 0; localY < SECTION_SIZE; localY++) {
                for (int localZ = 0; localZ < SECTION_SIZE; localZ++) {
                    for (int localX = 0; localX < SECTION_SIZE; localX++) {
                        BlockState state = section.getBlockState(localX, localY, localZ);
                        if (state.isAir()) continue;

                        int blockId = getBlockId(state);
                        if (blockId == 0) continue;

                        int blockIndex = sectionIdx * BLOCKS_PER_SECTION
                            + localY * (SECTION_SIZE * SECTION_SIZE)
                            + localZ * SECTION_SIZE
                            + localX;
                        int byteOffset = blockIndex * BYTES_PER_BLOCK;

                        data[byteOffset] = (byte) (blockId & 0xFF);
                        data[byteOffset + 1] = (byte) ((blockId >> 8) & 0xFF);
                    }
                }
            }
        }

        return data;
    }

    private static int getBlockId(BlockState state) {
        int rawId = Block.getId(state);
        return rawId & 0xFFFF;
    }
}
