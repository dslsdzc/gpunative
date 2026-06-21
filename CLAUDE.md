# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

- **Build all**: `./gradlew build`
- **Build a specific module**: `./gradlew :gpunative-core:build` (or `:gpunative-fabric:build`, `:gpunative-neoforge:build`)
- **Run Fabric client**: `./gradlew :gpunative-fabric:runClient`
- **Run NeoForge client**: `./gradlew :gpunative-neoforge:runClient`
- **Clean**: `./gradlew clean`

Java 17 required. Gradle wrapper is committed (`./gradlew`).

## Architecture

GPU Native is a Minecraft 1.20.1 mod that replaces vanilla rendering with a GPU-driven pipeline. Three modules:

### `gpunative-core` — Core engine

- **RHI** (`com.dslsdzc.gpunative.rhi`): Hardware abstraction layer. Interfaces (`RhiDevice`, `RhiBuffer`, `RhiCommandList`, `RhiTexture`, `RhiPipeline`, `RhiShader`, `RhiFence`) with an OpenGL implementation in `rhi.opengl`. Targets GL 3.2 baseline with runtime extension detection for GL 4.3+ features (compute shaders, SSBOs, indirect draw).
- **Renderer** (`com.dslsdzc.gpunative.renderer`): GPU-driven pipeline orchestration.
  - `GpuDrivenRenderer` — main orchestrator: frame phases are (1) compute (upload + mesh gen) → (2) readback → (3) terrain rendering → (4) debug overlay
  - `VoxelDataManager` — CPU-side chunk data store, converts Minecraft packed block format to uint32 SSBO format, uploads dirty chunks per frame
  - `GpuMeshPipeline` — compute shader mesh generation (`mesh_gen.comp`) and culling (`culling.comp`), manages GPU vertex/index pools and counter readback
  - `IndirectDrawBuilder` — indirect draw command buffer management with GL 3.2 fallback
  - `FrameGraph` — render pass dependency graph with topological sort and transient resource allocation
- **Compat** (`com.dslsdzc.gpunative.compat`): `ShaderCompat` — Iris/OptiFine shader pack compatibility layer with G-Buffer layouts and uniform bindings
- **Shaders** (`src/main/resources/shaders/`): GLSL compute shaders for mesh generation (SSBO-based vertex/index output) and culling; fragment/vertex shaders for terrain rendering and post-processing

### `gpunative-fabric` — Fabric loader entry point

- `GpuNativeFabric` extends `GpuNative`, hooks into Fabric API events (`WorldRenderEvents.LAST`) and client lifecycle
- Mixins cancel vanilla terrain rendering (`WorldRendererMixin` cancels `renderChunkLayer`)
- `ChunkDataExtractor` converts `LevelChunk` block data to packed byte arrays (2 bytes/block: block ID + light nibble)
- Conflicts with Sodium, OptiFine/OptiFabric, Canvas, VulkanMod, Iris in `fabric.mod.json`

### `gpunative-neoforge` — NeoForge loader entry point

- `GpuNativeNeoForge` uses NeoForge event bus (`RenderLevelStageEvent.AFTER_SKY`, `ChunkEvent.Load/Unload`)
- Same chunk extraction logic as Fabric variant

### Key flow per frame

1. `voxelData.uploadDirtyChunks()` — upload newly loaded chunk data to SSBO
2. `meshPipeline.runMeshGeneration()` — dispatch `mesh_gen.comp` per chunk (groups=1536 threads/chunk)
3. `device.waitIdle()` + counter readback — sync and read generated vertex/index counts
4. Bind vertex/index pools, set matrices, draw indexed
5. Debug overlay rendered with identity view matrix + translated model matrix

### Slot-based chunk eviction

`VoxelDataManager` uses a circular slot allocator (`AtomicInteger`). When a new chunk arrives and all slots are used, the oldest chunk in that slot is evicted. Max 256 cached chunks by default.
