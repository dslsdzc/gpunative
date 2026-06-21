#version 150 core

uniform sampler2D u_BlockAtlas;

out vec4 o_Albedo;

in vec3 v_WorldPos;
in vec3 v_Normal;
in vec2 v_TexCoord;
in vec4 v_Color;
in vec3 v_ViewDir;
in float v_FogFactor;

uniform vec4 u_SunColor = vec4(1.0, 0.95, 0.8, 1.0);
uniform vec4 u_AmbientColor = vec4(0.3, 0.4, 0.6, 1.0);
uniform vec3 u_SunDirection;

void main() {
    vec4 albedo = texture(u_BlockAtlas, v_TexCoord) * v_Color;
    vec3 normal = normalize(v_Normal);

    float NdotL = max(dot(normal, u_SunDirection), 0.0);
    vec3 lighting = u_AmbientColor.rgb + u_SunColor.rgb * NdotL;

    o_Albedo = vec4(albedo.rgb * lighting, 1.0);

    vec3 fogColor = vec3(0.5, 0.6, 0.7);
    o_Albedo.rgb = mix(o_Albedo.rgb, fogColor, v_FogFactor);
}
