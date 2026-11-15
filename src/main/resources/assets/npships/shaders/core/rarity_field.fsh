#version 150
uniform float Fields[320];
uniform int FieldCount;
uniform float MaximumPressure;
uniform sampler2D SceneDepth;
uniform mat4 InverseViewProjection;
out vec4 fragColor;
void main() {
    vec2 resolution = vec2(textureSize(SceneDepth, 0));
    vec2 uv = gl_FragCoord.xy / resolution;
    float depth = texelFetch(SceneDepth, ivec2(gl_FragCoord.xy), 0).r;
    if (depth >= 1.0 || depth < 0.0) discard;
    vec4 reconstructed = InverseViewProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    if (abs(reconstructed.w) < 1e-12) discard;
    vec3 position = reconstructed.xyz / reconstructed.w;
    float pressure = 0.0;
    bool covered = false;
    for (int i = 0; i < FieldCount; ++i) {
        int k = i * 5;
        vec3 center = vec3(Fields[k], Fields[k + 1], Fields[k + 2]);
        vec3 delta = position - center;
        float radius = Fields[k + 3];
        float squareDistance = dot(delta, delta);
        if (squareDistance > radius * radius) continue;
        covered = true;
        pressure = max(pressure, Fields[k + 4]);
    }
    if (!covered) discard;
    // Field coverage itself gives the exact spherical boundary, without an extra edge band.
    // A neutral tint shows extent even with no pressure; only the warm shift encodes MAX H.
    float level = MaximumPressure > 0.0 ? clamp(pressure / MaximumPressure, 0.0, 1.0) : 0.0;
    vec3 cyan = vec3(0.0, 0.85, 1.0);
    vec3 green = vec3(0.15, 1.0, 0.2);
    vec3 yellow = vec3(1.0, 0.95, 0.0);
    vec3 orange = vec3(1.0, 0.5, 0.0);
    vec3 red = vec3(1.0, 0.1, 0.0);
    vec3 color = level < 0.25 ? mix(cyan, green, level * 4.0)
        : level < 0.5 ? mix(green, yellow, (level - 0.25) * 4.0)
        : level < 0.75 ? mix(yellow, orange, (level - 0.5) * 4.0)
        : mix(orange, red, (level - 0.75) * 4.0);
    fragColor = vec4(color, 0.32 + 0.12 * level);
}
