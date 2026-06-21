#version 150 core

/* -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
 * Post-Processing Shader
 *
 * Combines lighting result with optional bloom, tone mapping,
 * and color grading. The final output is written to the screen.
 * -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-= */

uniform sampler2D u_LightTexture;
uniform sampler2D u_DepthTexture;

uniform float u_Exposure = 1.0;
uniform float u_Gamma = 2.2;
uniform float u_BloomStrength = 0.3;

in vec2 v_TexCoord;

out vec4 o_FinalColor;

// Tone mapping: ACES Filmic
vec3 acesToneMap(vec3 color) {
    float a = 2.51;
    float b = 0.03;
    float c = 2.43;
    float d = 0.59;
    float e = 0.14;
    return clamp((color * (a * color + b)) / (color * (c * color + d) + e), 0.0, 1.0);
}

// Reinhard tone mapping (alternative)
vec3 reinhardToneMap(vec3 color) {
    return color / (color + vec3(1.0));
}

// Simple bloom: sample bright areas and blur
vec3 bloomPass(sampler2D tex, vec2 uv) {
    vec2 texelSize = 1.0 / vec2(textureSize(tex, 0));
    vec3 bloom = vec3(0.0);

    // Simple 5-tap Gaussian
    const float[5] weights = float[5](0.227, 0.1946, 0.1216, 0.054, 0.016);
    const vec2[5] offsets = vec2[5](
        vec2(0.0, 0.0),
        vec2(1.0, 0.0), vec2(-1.0, 0.0),
        vec2(0.0, 1.0), vec2(0.0, -1.0)
    );

    for (int i = 0; i < 5; i++) {
        vec2 sampleUv = uv + offsets[i] * texelSize * 2.0;
        vec3 color = texture(tex, sampleUv).rgb;
        float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
        if (luminance > 0.8) {
            bloom += color * weights[i];
        }
    }

    return bloom;
}

void main() {
    vec3 color = texture(u_LightTexture, v_TexCoord).rgb;

    // Apply exposure
    color *= u_Exposure;

    // Bloom
    vec3 bloom = bloomPass(u_LightTexture, v_TexCoord);
    color += bloom * u_BloomStrength;

    // Tone mapping (ACES)
    color = acesToneMap(color);

    // Gamma correction
    color = pow(color, vec3(1.0 / u_Gamma));

    // Vignette
    vec2 center = v_TexCoord - 0.5;
    float vignette = 1.0 - dot(center, center) * 0.5;
    color *= vignette;

    o_FinalColor = vec4(color, 1.0);
}
