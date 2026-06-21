#version 150 core

/* -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
 * GPU Culling Shader (Fragment Shader, GL 3.2 fallback)
 *
 * Performs frustum culling and distance-based LOD selection
 * by rendering to a visibility mask texture.
 *
 * Each pixel represents one renderable object (chunk section).
 * Output: 1.0 = visible, 0.0 = culled.
 * -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-= */

// Input: bounding box data as texture
uniform samplerBuffer u_BoundsData;
uniform vec3 u_CameraPos;

// Frustum planes: 6 x vec4
uniform vec4 u_FrustumPlanes[6];

// Distance culling parameters
uniform float u_MaxRenderDistance = 256.0;
uniform float u_LODDistance1 = 64.0;
uniform float u_LODDistance2 = 128.0;

in vec2 v_TexCoord;

out float o_Visibility;

// Check AABB against frustum
bool frustumTest(vec3 center, vec3 halfExtents) {
    for (int i = 0; i < 6; i++) {
        vec4 plane = u_FrustumPlanes[i];
        float d = dot(center, plane.xyz) + plane.w;

        float r = dot(halfExtents, abs(plane.xyz));
        if (d + r < 0.0) {
            return false; // Fully outside
        }
    }
    return true;
}

void main() {
    int objectIndex = int(gl_FragCoord.x);

    // Read bounding box from buffer texture
    vec3 bboxMin = texelFetchBuffer(u_BoundsData, objectIndex * 2).xyz;
    vec3 bboxMax = texelFetchBuffer(u_BoundsData, objectIndex * 2 + 1).xyz;

    vec3 center = (bboxMin + bboxMax) * 0.5;
    vec3 halfExt = (bboxMax - bboxMin) * 0.5;

    // Frustum test
    if (!frustumTest(center, halfExt)) {
        o_Visibility = 0.0;
        return;
    }

    // Distance culling
    float dist = length(center - u_CameraPos);
    if (dist > u_MaxRenderDistance) {
        o_Visibility = 0.0;
        return;
    }

    // LOD selection (encoded in output value)
    float lod = 0.0;
    if (dist > u_LODDistance2) lod = 2.0;
    else if (dist > u_LODDistance1) lod = 1.0;

    o_Visibility = 1.0 + lod;
}
