# GPU Native

A Minecraft 1.20.1 mod that replaces vanilla rendering with a GPU-driven pipeline. Implements compute-shader-based mesh generation, indirect draw, and a frame-graph-driven renderer for maximum performance.

> **Status:** Experimental — not yet feature-complete. Works with Fabric and NeoForge loaders.

---

## Features

- **GPU-Driven Rendering Pipeline** — bypasses the vanilla CPU-bound chunk rebuild pipeline entirely
- **Compute Shader Mesh Generation** — chunk meshes are generated on-GPU via GLSL compute shaders (`mesh_gen.comp`)
- **Indirect Draw** — GPU-generated vertex/index data rendered via `glMultiDrawElementsIndirect`
- **Slot-Based Chunk Eviction** — circular `AtomicInteger` allocator with max 256 cached chunks
- **Frame Graph** — render pass dependency graph with topological sort and transient resource management
- **Shader Compat Layer** — preliminary Iris/OptiFine G-Buffer layout and uniform bindings
- **Fabric & NeoForge** — both loaders supported via multi-module Gradle project

## Architecture

```
gpunative-core     — Core engine (RHI, Renderer, Compat, Shaders)
gpunative-fabric   — Fabric loader entry point + mixins
gpunative-neoforge — NeoForge loader entry point + event bus hooks
```

### Frame Loop

1. **Upload** — dirty chunk data uploaded to SSBO
2. **Compute** — `mesh_gen.comp` dispatched per chunk (1536 threads/chunk)
3. **Readback** — sync + counter readback for vertex/index counts
4. **Render** — bind vertex/index pools, set matrices, indirect indexed draw
5. **Debug** — overlay rendered with identity view matrix

### RHI Abstraction

Hardware abstraction layer under `com.dslsdzc.gpunative.rhi` targeting GL 3.2 baseline with runtime extension detection for GL 4.3+ features (compute shaders, SSBOs, indirect draw). OpenGL implementation in `rhi.opengl`.

## Getting Started

### Prerequisites

- Java 17+
- Minecraft 1.20.1
- A GPU that supports OpenGL 4.3+ (compute shaders, SSBOs, indirect draw)

### Build

```bash
./gradlew build
```

### Run

```bash
# Fabric
./gradlew :gpunative-fabric:runClient

# NeoForge
./gradlew :gpunative-neoforge:runClient
```

### Clean

```bash
./gradlew clean
```

## Project Structure

```
├── gpunative-core/           # Core engine
│   └── src/main/
│       ├── java/com/dslsdzc/gpunative/
│       │   ├── rhi/          # Hardware abstraction layer
│       │   ├── renderer/     # GPU-driven pipeline
│       │   └── compat/       # Shader compatibility layer
│       └── resources/shaders/ # GLSL compute/vertex/fragment shaders
├── gpunative-fabric/         # Fabric loader mod
├── gpunative-neoforge/       # NeoForge loader mod
├── shaders/                  # Shared shader sources
├── build.gradle              # Root build script
└── settings.gradle           # Multi-module settings
```

## Compatibility

The mod conflicts with other rendering mods (declared in `fabric.mod.json`):
- Sodium
- OptiFine / OptiFabric
- Canvas
- VulkanMod
- Iris

## License

See [LICENSE](LICENSE).
