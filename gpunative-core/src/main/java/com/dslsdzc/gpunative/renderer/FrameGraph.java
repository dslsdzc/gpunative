package com.dslsdzc.gpunative.renderer;

import com.dslsdzc.gpunative.rhi.*;
import com.dslsdzc.gpunative.rhi.opengl.GlDevice;

import java.util.*;
import java.util.function.Consumer;

/**
 * A frame graph that defines render pass dependencies and resource lifetimes.
 * Passes are registered once and executed each frame in topological order.
 */
public class FrameGraph {
    private final GlDevice device;
    private final List<PassNode> passes = new ArrayList<>();
    private final Map<String, ResourceNode> resources = new HashMap<>();
    private boolean compiled;
    private List<PassNode> executionOrder;

    public FrameGraph(GlDevice device) {
        this.device = device;
    }

    // ---- Resource Registration ----

    /**
     * Register a transient texture resource.
     */
    public String createTexture(String name, RhiTexture.Type type, RhiTexture.Format format,
                                 int width, int height, int depth, int mipLevels, int layers) {
        ResourceNode node = new ResourceNode(name, ResourceType.TEXTURE);
        node.textureDesc = new TextureDesc(type, format, width, height, depth, mipLevels, layers);

        if (!name.isEmpty()) {
            resources.put(name, node);
        }
        return name;
    }

    /**
     * Import an external (already created) texture.
     */
    public String importTexture(String name, RhiTexture texture) {
        ResourceNode node = new ResourceNode(name, ResourceType.TEXTURE);
        node.texture = texture;
        node.imported = true;
        resources.put(name, node);
        return name;
    }

    /**
     * Register a transient buffer resource.
     */
    public String createBuffer(String name, RhiBuffer.Type type, long size, RhiBuffer.Usage usage) {
        ResourceNode node = new ResourceNode(name, ResourceType.BUFFER);
        node.bufferDesc = new BufferDesc(type, size, usage);
        if (!name.isEmpty()) {
            resources.put(name, node);
        }
        return name;
    }

    // ---- Pass Registration ----

    /**
     * Add a render pass to the frame graph.
     */
    public PassNode addPass(String name, Consumer<PassBuilder> setup) {
        PassNode pass = new PassNode(name);
        PassBuilder builder = new PassBuilder(pass);
        setup.accept(builder);
        passes.add(pass);
        return pass;
    }

    /**
     * Compile the frame graph: validate dependencies, allocate resources,
     * determine execution order via topological sort.
     */
    public void compile(int viewportWidth, int viewportHeight) {
        // Allocate transient resources
        for (ResourceNode res : resources.values()) {
            if (res.imported) continue;
            if (res.type == ResourceType.TEXTURE && res.textureDesc != null) {
                TextureDesc desc = res.textureDesc;
                int w = desc.width > 0 ? desc.width : viewportWidth;
                int h = desc.height > 0 ? desc.height : viewportHeight;
                res.texture = device.createTexture(desc.type, desc.format, w, h, desc.depth, desc.mipLevels, desc.layers);
            } else if (res.type == ResourceType.BUFFER && res.bufferDesc != null) {
                BufferDesc desc = res.bufferDesc;
                res.buffer = device.createBuffer(desc.type, desc.size, desc.usage);
            }
        }

        // Topological sort
        executionOrder = topologicalSort();

        compiled = true;
    }

    /**
     * Execute all passes in order.
     */
    public void execute(RhiCommandList cmdList) {
        if (!compiled) return;

        for (PassNode pass : executionOrder) {
            pass.execute.accept(cmdList);
        }
    }

    /**
     * Get a resource by name (read-only for external use).
     */
    public RhiTexture getTexture(String name) {
        ResourceNode node = resources.get(name);
        return node != null ? node.texture : null;
    }

    public RhiBuffer getBuffer(String name) {
        ResourceNode node = resources.get(name);
        return node != null ? node.buffer : null;
    }

    public void destroy() {
        for (ResourceNode res : resources.values()) {
            if (!res.imported) {
                if (res.texture != null) res.texture.destroy();
                if (res.buffer != null) res.buffer.destroy();
            }
        }
        passes.clear();
        resources.clear();
        compiled = false;
    }

    // ---- Internal ----

    private List<PassNode> topologicalSort() {
        // Build adjacency: edge from A to B means A must run before B
        Map<String, PassNode> passMap = new HashMap<>();
        Map<PassNode, List<PassNode>> inEdges = new HashMap<>();
        Map<PassNode, Integer> outCount = new HashMap<>();

        for (PassNode pass : passes) {
            passMap.put(pass.name, pass);
            inEdges.putIfAbsent(pass, new ArrayList<>());
            outCount.putIfAbsent(pass, 0);
        }

        for (PassNode pass : passes) {
            for (String read : pass.reads) {
                // Find which pass writes this resource
                for (PassNode other : passes) {
                    if (other.writes.contains(read) && !other.name.equals(pass.name)) {
                        inEdges.computeIfAbsent(pass, k -> new ArrayList<>()).add(other);
                        outCount.merge(other, 1, Integer::sum);
                    }
                }
            }
        }

        // Kahn's algorithm
        Deque<PassNode> queue = new ArrayDeque<>();
        for (PassNode pass : passes) {
            if (outCount.getOrDefault(pass, 0) == 0) {
                queue.add(pass);
            }
        }

        List<PassNode> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            PassNode pass = queue.poll();
            result.add(pass);

            for (PassNode dependent : inEdges.getOrDefault(pass, Collections.emptyList())) {
                int remaining = outCount.merge(dependent, -1, Integer::sum);
                if (remaining <= 0) {
                    queue.add(dependent);
                }
            }
        }

        return result;
    }

    // ---- Builder ----

    public class PassBuilder {
        private final PassNode pass;

        PassBuilder(PassNode pass) { this.pass = pass; }

        public PassBuilder read(String resourceName) {
            pass.reads.add(resourceName);
            return this;
        }

        public PassBuilder write(String resourceName) {
            pass.writes.add(resourceName);
            return this;
        }

        public PassBuilder executes(Consumer<RhiCommandList> callback) {
            pass.execute = callback;
            return this;
        }

        public PassBuilder setViewport(int x, int y, int width, int height) {
            pass.viewportX = x;
            pass.viewportY = y;
            pass.viewportWidth = width;
            pass.viewportHeight = height;
            return this;
        }
    }

    // ---- Internal Classes ----

    enum ResourceType { TEXTURE, BUFFER }

    record TextureDesc(RhiTexture.Type type, RhiTexture.Format format,
                        int width, int height, int depth, int mipLevels, int layers) {}
    record BufferDesc(RhiBuffer.Type type, long size, RhiBuffer.Usage usage) {}

    static class ResourceNode {
        final String name;
        final ResourceType type;
        TextureDesc textureDesc;
        BufferDesc bufferDesc;
        RhiTexture texture;
        RhiBuffer buffer;
        boolean imported;

        ResourceNode(String name, ResourceType type) {
            this.name = name;
            this.type = type;
        }
    }

    public static class PassNode {
        final String name;
        final Set<String> reads = new HashSet<>();
        final Set<String> writes = new HashSet<>();
        Consumer<RhiCommandList> execute;
        int viewportX, viewportY, viewportWidth, viewportHeight;

        PassNode(String name) { this.name = name; }
    }
}
