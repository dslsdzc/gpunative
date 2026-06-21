#version 150 core

/* -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
 * Terrain Fragment Shader (G-Buffer)
 *
 * Writes to the G-Buffer for deferred shading:
 *   color0 (Albedo):   base color (diffuse)
 *   color1 (Normal):   world-space normal
 *   color2 (Material): roughness, metalness, ambient occlusion
 *   depth:             fragment depth (automatic via gl_FragDepth)
 * -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-= */

// Block texture atlas
uniform sampler2DArray u_BlockAtlas;

// G-Buffer outputs
out vec4 o_Albedo;
out vec4 o_Normal;
out vec4 o_Material;

// Interpolated inputs
in vec3 v_WorldPos;
in vec3 v_Normal;
in vec2 v_TexCoord;
in vec4 v_Color;
in vec3 v_ViewDir;
in float v_FogFactor;

// Uniforms from shader pack compat
uniform vec4 u_SunColor = vec4(1.0, 0.95, 0.8, 1.0);
uniform vec4 u_AmbientColor = vec4(0.3, 0.4, 0.6, 1.0);
uniform vec3 u_SunDirection = normalize(vec3(0.5, 0.8, 0.3));

// Block material properties (sampled from block ID)
uniform samplerBuffer u_BlockProperties;

void main() {
    // Sample block texture from atlas
    vec4 albedo = texture(u_BlockAtlas, vec3(v_TexCoord, 0.0)) * v_Color;

    // Normalize interpolated normal
    vec3 normal = normalize(v_Normal);

    // Simple directional lighting for G-Buffer preview
    float NdotL = max(dot(normal, u_SunDirection), 0.0);
    vec3 lighting = u_AmbientColor.rgb + u_SunColor.rgb * NdotL;

    // Output G-Buffer
    o_Albedo = vec4(albedo.rgb, 1.0);
    o_Normal = vec4(normal * 0.5 + 0.5, 1.0); // Store in [0, 1] range

    // Material: roughness (r), metalness (g), ambient occlusion (b)
    o_Material = vec4(0.8, 0.0, 1.0, 1.0);

    // Fog
    vec3 fogColor = vec3(0.5, 0.6, 0.7);
    o_Albedo.rgb = mix(o_Albedo.rgb, fogColor, v_FogFactor);
}
