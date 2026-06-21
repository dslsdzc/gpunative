### 核心 API 对比

下面是两个平台核心 API 的简要对比：

| 类别 | Fabric API | NeoForge API |
| :--- | :--- | :--- |
| **模组入口** | 实现 `net.fabricmc.api.ModInitializer` 接口，并在 `fabric.mod.json` 中配置。 | 使用 `@Mod` 注解标记主类，并在 `neoforge.mods.toml` 中配置。 |
| **事件系统** | 使用 `fabric-api-base` 中的 `Event` 类。**轻量级，可自定义事件回调接口**。 | 使用功能强大的 `@SubscribeEvent` 注解。**内置事件总线** (`NeoForge.EVENT_BUS` 和 `IEventBus`)，生命周期管理更完整。 |
| **Mixin 注入** | 通过 **Fabric Loom** 插件和 `fabric.mod.json` 配置。常用 `@Inject` 和 `@ModifyVariable` 等。 | 通过 **NeoGradle** 插件和 `neoforge.mods.toml` 配置。同样使用 `@Inject` 和 `@ModifyVariable` 等。**API 更完善，可减少手动 Mixin 的需求**。 |
| **渲染钩子** | **核心渲染事件 API** (`fabric-rendering-v1`): 提供 `WorldRenderer` 事件，如 `BeforeWorldRender` 和 `AfterWorldRender`。<br>**自定义模型加载 API** (`fabric-model-loading-api-v1`): 用于加载自定义模型。 | **客户端事件总线**: 专注于监听渲染事件，例如 `RenderLevelStageEvent` 来**在特定渲染阶段注册回调**。<br>**内置 API**: 提供了丰富的 API 来管理实体渲染器 (`EntityRenderersEvent`)、模型加载器等。 |

###关键 API 详解

以下是在项目中最可能用到的几个核心模块：

*   **模组入口**：一切功能的起点。
    *   **Fabric**：必须在主类实现 `net.fabricmc.api.ModInitializer` 接口，并将入口点（`entrypoints`）声明在 `fabric.mod.json` 文件。
    *   **NeoForge**：需要在主类上使用 `@Mod` 注解，注解值需与 `neoforge.mods.toml` 中的 `modId` 严格一致。模组构造函数的参数 `IEventBus` 可用于直接订阅模组总线上的事件。

*   **事件系统**：对接游戏逻辑和保证模组兼容的核心机制。
    *   **Fabric**：高度灵活，可自定义事件。需要创建事件接口，通过 `EventFactory.createArrayBacked` 实现，然后在 Mixin 中触发。
    *   **NeoForge**：通过 `@SubscribeEvent` 注解将方法标记为事件处理器，监听预定义的游戏事件。

*   **Mixin 注入**：**这是你替换 Minecraft 原版渲染器的核心手段**。通过注入，你可以修改或完全替代原版的渲染逻辑，将绘制命令导向你的 `gpunative` 后端。
    *   **Fabric**：Mixin 注入是主要方式。例如，你需要精准地 `@Inject` 到 `WorldRenderer` 类的 `render` 方法头部，以“劫持”渲染流程。
    *   **NeoForge**：同样支持 Mixin，但更鼓励优先使用其内置的 API（如渲染事件）。你可以通过 `RenderLevelStageEvent` 等事件找到切入渲染管线的位置。

*   **渲染钩子**：接入渲染流程的具体“操作点”，是实现光影兼容的关键。
    *   **Fabric**：提供 `fabric-rendering-v1` 等 API，包含 `WorldRenderer` 相关的钩子，用于在游戏渲染世界之前或之后插入你的渲染逻辑。
    *   **NeoForge**：通过事件总线提供 `RenderLevelStageEvent` 等事件。你需要在事件处理器中检查渲染阶段（如 `Stage.AFTER_SKY`），并在该阶段执行你的自定义渲染命令。

###总结与建议

跨平台开发时，为保持`gpunative-core`模块的纯净，你可以在其中定义好内部的抽象层（比如一个 `IRenderer` 接口），然后创建 Fabric 和 NeoForge 的专属模块来分别实现这些接口，从而隔离平台差异。

在具体实现上，可以遵循以下思路：
1.  **优先使用 API，而非 Mixin**：尤其是使用 NeoForge 时，其 API 覆盖面更广，能有效减少手动 Mixin 带来的兼容性风险。
2.  **通过事件接入渲染**：在渲染阶段，优先利用 Fabric 的 `WorldRenderer` 事件或 NeoForge 的 `RenderLevelStageEvent` 来接管渲染流程。
3.  **谨慎使用 Mixin**：当 API 无法满足需求（比如需要完全接管某个类的核心方法）时，再使用 Mixin。建议将 Mixin 注入点设计得尽可能“窄”和“高”，只修改最顶层的调度方法，以降低冲突风险。
