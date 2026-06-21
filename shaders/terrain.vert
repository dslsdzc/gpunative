#version 150 core

/* -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
 * Terrain Vertex Shader
 *
 * Handles vertex transformation for the G-Buffer geometry pass.
 * Vertex data comes from the GPU-generated vertex pool (instanced
 * drawing or indirect drawing).
 *
 * Supports instanced rendering where gl_InstanceID selects the
 * chunk section and its associated transform data.
 * -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-= */

// Standard uniforms
uniform mat4 u_ProjectionMatrix;
uniform mat4 u_ViewMatrix;
uniform mat4 u_ModelMatrix;
uniform vec3 u_CameraPos;

// Per-instance data (sampled from texture buffer when using instanced fallback)
uniform samplerBuffer u_InstanceData;

// Vertex attributes
in vec3 a_Position;
in vec3 a_Normal;
in vec2 a_TexCoord;
in vec4 a_Color;

// Outputs to fragment shader
out vec3 v_WorldPos;
out vec3 v_Normal;
out vec2 v_TexCoord;
out vec4 v_Color;
out vec3 v_ViewDir;
out float v_FogFactor;

const float FOG_START = 0.75;
const float FOG_END = 1.0;

void main() {
    vec3 worldPos = a_Position;

    // If using instancing, apply per-instance translation
    // (the instance data contains chunk offset)
    if (gl_InstanceID > 0) {
        vec3 instanceOffset = texelFetchBuffer(u_InstanceData, gl_InstanceID * 4).xyz;
        worldPos += instanceOffset;
    }

    v_WorldPos = worldPos;
    v_Normal = normalize(a_Normal);
    v_TexCoord = a_TexCoord;
    v_Color = a_Color;
    v_ViewDir = normalize(u_CameraPos - worldPos);

    // Fog calculation
    vec4 viewPos = u_ViewMatrix * vec4(worldPos, 1.0);
    float dist = abs(viewPos.z);
    v_FogFactor = clamp((dist - FOG_START) / (FOG_END - FOG_START), 0.0, 1.0);

    gl_Position = u_ProjectionMatrix * u_ViewMatrix * vec4(worldPos, 1.0);
}
