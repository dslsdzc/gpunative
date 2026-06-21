#version 150 core

/* -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
 * Mesh Generation Shader (Fragment Shader, GL 3.2 fallback)
 *
 * Simulates compute-style mesh generation by rendering to a
 * large output texture. Each output pixel represents one vertex
 * attribute. Multiple Render Targets (MRT) allow writing position,
 * normal, texcoord, and color in a single pass.
 *
 * The input is a voxel texture array containing block IDs
 * and light values for each chunk section.
 *
 * Output layout:
 *   color0: position.xyz + block_id
 *   color1: normal.xyz + padding
 *   color2: texcoord.xy + light.xy
 *   color3: vertex_id (for index buffer generation)
 * -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-= */

uniform sampler2DArray u_VoxelData;
uniform ivec3 u_ChunkOffset;
uniform int u_SectionIndex;

in vec2 v_TexCoord;

out vec4 o_Position;
out vec4 o_Normal;
out vec4 o_TexCoordLight;
out vec4 o_VertexId;

// Block type constants
const int AIR = 0;
const int SOLID_OPAQUE = 1;
const int SOLID_TRANSPARENT = 2;
const int LIQUID = 3;

// Lookup table: face normal directions
const vec3[6] FACE_NORMALS = vec3[6](
    vec3(0.0, 0.0, -1.0),  // -Z
    vec3(0.0, 0.0,  1.0),  // +Z
    vec3(-1.0, 0.0, 0.0),  // -X
    vec3(1.0, 0.0, 0.0),   // +X
    vec3(0.0, -1.0, 0.0),  // -Y
    vec3(0.0,  1.0, 0.0)   // +Y
);

// Texture coordinates per face
const vec2[4] FACE_UV = vec2[4](
    vec2(0.0, 0.0),
    vec2(1.0, 0.0),
    vec2(1.0, 1.0),
    vec2(0.0, 1.0)
);

int getBlockId(ivec3 pos) {
    vec4 data = texelFetch(u_VoxelData, ivec3(pos.x, pos.y + u_SectionIndex * 16, 0), 0);
    return int(data.r * 255.0);
}

int getBlockLight(ivec3 pos) {
    vec4 data = texelFetch(u_VoxelData, ivec3(pos.x, pos.y + u_SectionIndex * 16, 0), 0);
    return int(data.g * 255.0);
}

bool isFaceVisible(ivec3 localPos, ivec3 faceOffset) {
    ivec3 neighbor = localPos + faceOffset;
    if (neighbor.x < 0 || neighbor.x >= 16 ||
        neighbor.y < 0 || neighbor.y >= 16 ||
        neighbor.z < 0 || neighbor.z >= 16) {
        return true; // chunk boundary - visible
    }
    int neighborId = getBlockId(neighbor);
    return neighborId == AIR || neighborId == LIQUID;
}

void main() {
    // Unpack: each invocation processes one potential vertex
    // The draw ID determines the block and face being processed
    int vertexIndex = int(gl_FragCoord.x) +
                      int(gl_FragCoord.y) * int(gl_FragCoord.z);

    ivec3 localPos = ivec3(
        vertexIndex % 16,
        (vertexIndex / 16) % 16,
        (vertexIndex / 256) % 16
    );

    int blockId = getBlockId(localPos);
    if (blockId == AIR) {
        discard;
        return;
    }

    vec3 worldPos = vec3(u_ChunkOffset) + vec3(localPos);

    // Generate vertices for visible faces
    // For each face, check visibility and emit 4 vertices (a quad)
    int quadIndex = vertexIndex % 6;
    int faceIndex = quadIndex; // Simplified - each block gets 1 quad max

    if (faceIndex < 6) {
        ivec3 faceOff = ivec3(FACE_NORMALS[faceIndex]);
        if (isFaceVisible(localPos, faceOff)) {
            int vertInQuad = vertexIndex % 4;
            vec2 uv = FACE_UV[vertInQuad];

            // Vertex position = block center + face offset + UV offset
            vec3 pos = worldPos + vec3(0.5) + FACE_NORMALS[faceIndex] * 0.5;
            pos += vec3(
                (uv.x - 0.5) * (1.0 - abs(FACE_NORMALS[faceIndex].x)),
                (uv.y - 0.5) * (1.0 - abs(FACE_NORMALS[faceIndex].y)),
                0.0
            );

            o_Position = vec4(pos, float(blockId));
            o_Normal = vec4(FACE_NORMALS[faceIndex], 0.0);
            o_TexCoordLight = vec4(uv, float(getBlockLight(localPos)) / 15.0, 0.0);
            o_VertexId = vec4(vertexIndex, quadIndex, faceIndex, 1.0);
        } else {
            discard;
        }
    } else {
        discard;
    }
}
