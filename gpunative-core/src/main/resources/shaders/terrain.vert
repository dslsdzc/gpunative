#version 150 core
#extension GL_ARB_explicit_attrib_location : enable

uniform mat4 u_ProjectionMatrix;
uniform mat4 u_ViewMatrix;
uniform mat4 u_ModelMatrix;
uniform vec3 u_CameraPos;

layout(location = 0) in vec3 a_Position;
layout(location = 1) in vec3 a_Normal;
layout(location = 2) in vec2 a_TexCoord;
layout(location = 3) in vec4 a_Color;

out vec3 v_WorldPos;
out vec3 v_Normal;
out vec2 v_TexCoord;
out vec4 v_Color;
out vec3 v_ViewDir;
out float v_FogFactor;

const float FOG_START = 0.75;
const float FOG_END = 1.0;

void main() {
    vec4 worldPos = u_ModelMatrix * vec4(a_Position, 1.0);
    v_WorldPos = worldPos.xyz;
    v_Normal = normalize(mat3(u_ModelMatrix) * a_Normal);
    v_TexCoord = a_TexCoord;
    v_Color = a_Color;
    v_ViewDir = normalize(u_CameraPos - worldPos.xyz);

    vec4 viewPos = u_ViewMatrix * worldPos;
    float dist = length(viewPos);
    v_FogFactor = clamp((dist - FOG_START) / (FOG_END - FOG_START), 0.0, 1.0);

    gl_Position = u_ProjectionMatrix * u_ViewMatrix * worldPos;
}
