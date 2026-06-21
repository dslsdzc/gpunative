# GPU Native — 完整设计文档

**目标：** Minecraft 1.20.1，Fabric / NeoForge  
**图形 API：** OpenGL 3.2+（可运行时扩展至更高版本特性）  
**核心理念：** 纯 GPU 驱动渲染管线，原生光影兼容，不与任何现有渲染模组共存

---

## 目录

1. 项目概述
2. 技术路线与核心理念
3. 项目结构
4. 核心模块设计
   - 4.1 渲染硬件抽象层（RHI）
   - 4.2 GPU 驱动渲染器
   - 4.3 体素数据管理（CPU 端）
   - 4.4 帧图（Frame Graph）
   - 4.5 光影兼容层
5. OpenGL 3.2 下的 GPU 驱动模拟策略
   - 5.1 计算着色器模拟（通用计算）
   - 5.2 间接绘制模拟
   - 5.3 网格生成与剔除流水线
6. 构建系统与依赖
7. 发布与兼容性声明
8. 开发路线图（可选，但文档中可省略）

---

## 1. 项目概述

**GPU Native** 是 Minecraft Java 版的独立渲染模组。它完全从零开始构建，不依赖任何现有渲染模组（如 Sodium、OptiFine），旨在提供一个**完全由 GPU 驱动的现代化渲染管线**，并将光影（Shader Pack）作为唯一主动兼容的外部生态。

核心目标：
- 将所有渲染决策（剔除、LOD、网格生成）移至 GPU 端。
- 支持 OpenGL 3.2 作为最低基准，向上可自动利用 OpenGL 4.6+ 特性。
- 同时支持 Fabric 和 NeoForge 模组加载器。
- 不与任何修改原版渲染器的模组共存，但理论上兼容纯内容模组。

---

## 2. 技术路线与核心理念

**纯 GPU 驱动渲染：**  
CPU 仅负责加载和上传原始体素数据（方块 ID、光照值等），GPU 负责生成可见面网格、执行视锥/遮挡/距离剔除、决定 LOD，并通过间接绘制命令自我调度渲染。

**版本策略：**  
以 OpenGL 3.2 为最低要求，利用 OpenGL 的扩展机制，运行时检测并启用更高版本特性（如 `ARB_compute_shader`、`ARB_multi_draw_indirect`、`GL_EXT_mesh_shader`）作为加速路径。保证在老旧硬件上也能运行，性能仅损失可接受的少量开销。

**模组生态定位：**  
- **不与任何渲染修改模组兼容**（Sodium、OptiFine、Canvas 等立即冲突）。
- **光影兼容**：通过自建的光影接口直接对接 Iris（或自研加载器），不依赖 OptiFine。
- **内容模组兼容**：小地图、HUD、WAILA 等理论兼容，但不做主动保证。

---

## 3. 项目结构

```
gpunative/
├── gpunative-core/                    # 核心渲染库（与加载器无关）
│   ├── build.gradle
│   └── src/main/java/gpunative/
│       ├── GpuNative.java             # 模组入口抽象类
│       ├── rhi/                        # 渲染硬件接口定义
│       │   ├── RhiDevice.java
│       │   ├── RhiBuffer.java
│       │   ├── RhiTexture.java
│       │   ├── RhiShader.java
│       │   ├── RhiPipeline.java
│       │   ├── RhiCommandList.java
│       │   └── RhiFence.java
│       ├── rhi/opengl/                 # OpenGL 后端实现
│       │   ├── GlDevice.java
│       │   ├── GlBuffer.java
│       │   ├── GlTexture.java
│       │   ├── GlShader.java
│       │   ├── GlPipeline.java
│       │   ├── GlCommandList.java
│       │   └── GlFence.java
│       ├── renderer/                   # GPU 驱动渲染器
│       │   ├── GpuDrivenRenderer.java
│       │   ├── VoxelDataManager.java  # CPU端体素数据管理
│       │   ├── GpuMeshPipeline.java   # GPU端网格生成与剔除调度
│       │   ├── IndirectDrawBuilder.java
│       │   └── FrameGraph.java        # 帧图
│       ├── compat/                     # 光影兼容层
│       │   └── ShaderCompat.java
│       └── util/
│           └── MathUtil.java
│
├── gpunative-fabric/                  # Fabric 加载器实现
│   ├── build.gradle
│   └── src/main/
│       ├── java/gpunative/fabric/
│       │   ├── GpuNativeFabric.java
│       │   └── mixin/
│       └── resources/
│           ├── fabric.mod.json
│           └── gpunative-fabric.mixins.json
│
├── gpunative-neoforge/                # NeoForge 加载器实现
│   ├── build.gradle
│   └── src/main/
│       ├── java/gpunative/neoforge/
│       │   ├── GpuNativeNeoForge.java
│       │   └── mixin/
│       └── resources/
│           ├── META-INF/neoforge.mods.toml
│           └── gpunative-neoforge.mixins.json
│
├── shaders/                           # OpenGL 着色器源码（兼容 3.2）
│   ├── mesh_gen.frag                  # 用片段着色器模拟计算：网格生成
│   ├── culling.frag                   # 用片段着色器模拟计算：剔除
│   ├── terrain.vert
│   ├── terrain.frag
│   ├── postprocess.frag
│   └── (可选) compute_fallback/       # 当 GL 4.3+ 可用时，真实计算着色器版本
│
├── build.gradle                       # 根构建脚本
├── settings.gradle
└── README.md
```

---

## 4. 核心模块设计

### 4.1 渲染硬件抽象层（RHI）

为可能的多后端（未来 Vulkan）及当前 OpenGL 版本差异提供统一接口。核心接口示例：

```java
public interface RhiDevice {
    RhiBuffer createBuffer(BufferType type, long size);
    RhiTexture createTexture(TextureDescriptor desc);
    RhiShader createShader(ShaderType type, String source);
    RhiPipeline createPipeline(PipelineDescriptor desc);
    RhiCommandList createCommandList();
    void submit(RhiCommandList list);
    // ...
}
```

OpenGL 后端（`GlDevice`）负责将上述调用映射到 OpenGL 3.2 兼容的调用，并根据运行时扩展启用更优路径。

### 4.2 GPU 驱动渲染器

`GpuDrivenRenderer` 持有整个帧的生命周期，其核心循环：

1. **CPU 阶段**：`VoxelDataManager` 加载/卸载区块，将原始体素数据上传至 GPU 纹理或缓冲。
2. **GPU 阶段**（通过一系列计算 Pass）：
   - 面剔除与网格生成（基于体素数据）
   - 视锥体剔除、距离剔除
   - 填充间接绘制命令缓冲区
3. **执行阶段**：`glMultiDrawElementsIndirect`（4.6+）或模拟的实例化绘制（3.2）。
4. **光影阶段**：将 G-Buffer 和帧图信息传递给兼容层，调用光影 Pack 的相应 Pass。

### 4.3 体素数据管理（CPU 端）

`VoxelDataManager` 负责：
- 监听区块加载/卸载事件（通过 Mixin 或加载器事件）。
- 将区块的方块状态调色板和光照数据打包成紧凑格式。
- 上传到 GPU 纹理（`GL_TEXTURE_2D_ARRAY` 或 `GL_TEXTURE_BUFFER`）。
- 维护 GPU 显存中体素数据的 LRU 缓存。

### 4.4 帧图（Frame Graph）

`FrameGraph` 定义渲染 Pass 的依赖关系和资源流转，主要 Pass 节点：

| Pass 名称 | 输入 | 输出 | 说明 |
|:---|:---|:---|:---|
| `VoxelUpload` | CPU 侧区块数据 | 体素纹理 | 上传体素数据 |
| `MeshGen` | 体素纹理 | 顶点/索引缓冲 | GPU 生成可见面网格 |
| `Culling` | 网格包围盒、相机 | 间接绘制命令缓冲 | GPU 剔除 |
| `GBuffer` | 间接绘制命令、网格缓冲 | G-Buffer (颜色、法线、深度) | 延迟渲染几何阶段 |
| `Lighting` | G-Buffer、光照信息 | 光照纹理 | 延迟光照计算 |
| `Translucent` | 透明网格数据 | 混合缓冲区 | 半透明物体绘制 |
| `PostProcess` | 光照纹理、混合缓冲区 | 最终帧缓冲 | 色调映射、泛光等 |
| `Final` | 最终帧缓冲 | 屏幕 | 输出到显示 |

光影兼容层允许外部光影替换或扩展上述部分 Pass。

### 4.5 光影兼容层

`ShaderCompat` 实现与 Iris 的光影管道映射。核心机制：

- **G-Buffer 标准化**：提供与 OptiFine 兼容的 G-Buffer 纹理布局，使旧光影包可直接读取。
- **Pass 钩子**：在每个帧图 Pass 的开始/结束时调用光影注册的回调，允许光影注入自己的着色器。
- **Uniform 桥接**：将原版世界的矩阵、时间、光源等信息转换为光影期望的 Uniform 绑定。
- **扩展计划**：未来考虑定义 `GDSP`（GPU-Driven Shader Pipeline）新规范，与 Iris 合作推动原生支持。

---

## 5. OpenGL 3.2 下的 GPU 驱动模拟策略

### 5.1 计算着色器模拟（通用计算）

在 OpenGL 3.2 中，没有计算着色器。但我们可以利用**片段着色器**和**渲染到纹理**来执行大规模并行计算。

**方法**：

- 将计算任务映射为一个覆盖“输出像素区域”的四边形。
- 使用片段着色器执行内核逻辑，输入数据通过纹理绑定，输出写入帧缓冲附件（可以是多个渲染目标，MRT 在 3.2 中支持）。
- 使用 `glReadPixels` 或 PBO 异步读回少量结果（如剔除计数）。

**性能与限制**：
- 比真正的计算着色器多了一些光栅化和固定功能开销，但对于体素网格生成这类数据并行任务，性能损失通常在 10-25% 之间。
- 输出分辨率受限于最大纹理大小（通常 16384x16384），足以支持极高区块视距的网格生成。

### 5.2 间接绘制模拟

OpenGL 3.2 不支持 `glDrawElementsIndirect`，但可以利用**实例化绘制**和**从 GPU 读回计数**实现类似效果。

**流程**：

1. GPU 剔除 Pass 输出两个值：**可见实例数量**（写入一个 PBO 或纹理的单个纹素）以及**实例变换数据**（写入纹理或 VBO）。
2. 在 CPU 端，使用 `glGetBufferSubData` 读取 PBO，获取可见数量 `N`。
3. 调用 `glDrawElementsInstanced(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0, N)`，并在顶点着色器中利用 `gl_InstanceID` 采样变换纹理，获取每个实例的变换矩阵。

**注意**：CPU 仅读取了一个整数，并不知道具体是哪些物体可见，因此仍然保持了“GPU 驱动”的核心属性。绘制命令的数量是固定的（只有一次调用），性能接近真正的间接绘制。

### 5.3 网格生成与剔除流水线

**网格生成 Pass**（用片段着色器模拟计算）：
- 输入：体素纹理（包含方块 ID 和光照）
- 输出：顶点缓冲纹理（每个像素对应一个顶点的属性）和索引缓冲纹理
- 对于每个区块（16x16x16 或更大），用一次渲染调用覆盖输出区域（例如 256x256 纹素，对应一个区块可能的最大顶点数）。

**剔除 Pass**（同样用片段着色器）：
- 输入：每个网格块的包围盒（纹理）
- 输出：一张可见性掩码纹理 + 计数 PBO
- 使用视锥体方程和 Hi-Z 深度测试进行剔除。

---

## 6. 构建系统与依赖

- **构建工具**：Gradle 8.x（根项目 + 三个子模块）
- **Fabric**：使用 Fabric Loom 插件，生成 `gpunative-fabric.jar`
- **NeoForge**：使用 NeoForge MDK，生成 `gpunative-neoforge.jar`
- **核心库**：`gpunative-core` 作为纯 Java 库，由两个加载器模块引用
- **运行时依赖**：LWJGL 3（Minecraft 1.20.1 自带，提供 OpenGL 绑定）
- **无任何其他第三方库依赖**

---

## 7. 发布与兼容性声明

**发布文件**：
- `gpunative-fabric-<version>.jar`
- `gpunative-neoforge-<version>.jar`

**兼容性规则**：
- **冲突**：任何替换或大幅修改原版渲染器的模组（Sodium、OptiFine、Canvas Renderer、VulkanMod 等）。
- **兼容**：纯内容模组、小地图、HUD、WAILA、JEI 等理论上兼容，但不会针对它们做特殊适配。

---

## 8. 开发注意事项

- **Mixin 管理**：两个加载器需要两套独立的 Mixin 配置，注入点应尽可能少且集中在 `WorldRenderer`、`ChunkRenderDispatcher` 等顶层类。
- **调试**：由于大量逻辑在 GPU 端，必须依赖 RenderDoc 或 NVIDIA Nsight 进行调试。
- **内存**：体素纹理和网格缓冲需仔细管理，实现 LRU 机制防止显存溢出。

