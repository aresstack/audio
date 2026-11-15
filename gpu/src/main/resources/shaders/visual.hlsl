// The effects of the visualizer on the graphics card (Direct3D 11): the same as visual.vert and
// visual.frag for OpenGL; change both together. The texture is one row: red = band of the spectrum
// (low at the left), green = waveform (0.5 is silence).

cbuffer Uniforms : register(b0) {
    float3 uTime;   // seconds, beat flash, loudness
    float3 uLevels; // bass, middle, treble
    float3 uScreen; // width, height, effect: 0 spectrum, 1 tunnel, 2 particles, 3 alchemy
};

Texture2D uTexture : register(t0);
SamplerState uSampler : register(s0);

struct VertexInput {
    float2 position : ATTRIBUTE0;
    float2 uv : ATTRIBUTE1;
};

struct VertexOutput {
    float4 position : SV_Position;
    float2 uv : TEXCOORD0;
};

static const float PI = 3.14159265;

VertexOutput vertexMain(VertexInput input) {
    VertexOutput output;
    output.position = float4(input.position, 0.0, 1.0);
    output.uv = input.uv;
    return output;
}

float band(float x) { return uTexture.SampleLevel(uSampler, float2(saturate(x), 0.5), 0).r; }
float wave(float x) { return uTexture.SampleLevel(uSampler, float2(saturate(x), 0.5), 0).g * 2.0 - 1.0; }

float3 palette(float t) {
    return 0.5 + 0.5 * cos(6.28318 * (t + float3(0.0, 0.33, 0.67)));
}

float hash(float2 p) {
    return frac(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
}

// GLSL's mod: never negative for a positive divisor.
float glslMod(float x, float y) {
    return x - y * floor(x / y);
}

float3 spectrumRing(float2 p, float time, float beat) {
    float r = length(p);
    float a = atan2(p.y, p.x);
    float x = abs(a) / PI;
    float inner = 0.32 + 0.08 * uLevels.x + 0.05 * beat;
    float bar = band(x) * 0.55;
    float inside = step(inner, r) * step(r, inner + bar);
    float glow = exp(-abs(r - inner - bar) * 30.0) * band(x);
    float3 color = palette(x * 0.8 + time * 0.05);
    float core = exp(-r * 6.0) * (0.3 + uTime.z);
    return color * (inside * 0.8 + glow) + core * palette(time * 0.1);
}

float3 tunnel(float2 p, float time, float beat) {
    float r = max(length(p), 0.001);
    float a = atan2(p.y, p.x);
    float speed = time * (0.6 + 2.0 * uTime.z);
    float z = 0.35 / r + speed;
    float u = abs(a) / PI;                    // mirrored, so that no seam runs where the angle wraps
    float rings = 0.5 + 0.5 * sin(z * 8.0 + band(u) * 4.0);
    float stripes = 0.5 + 0.5 * sin(a * 12.0 + time + uLevels.y * 6.0);
    float3 color = palette(z * 0.05 + uLevels.z * 0.5) * rings * (0.6 + 0.4 * stripes);
    color *= smoothstep(0.0, 0.6, r) * (0.7 + band(frac(z * 0.1)) * 1.5);
    return color + beat * 0.25 * palette(time * 0.2);
}

float3 stars(float2 p, float time, float beat) {
    float3 color = float3(0.0, 0.0, 0.0);
    [unroll] for (int layer = 0; layer < 4; layer++) {
        float depth = frac(layer * 0.25 + time * (0.05 + 0.2 * uLevels.x));
        float scale = lerp(18.0, 1.5, depth);
        float2 q = p * scale;
        float2 cell = floor(q);
        float2 f = frac(q) - 0.5;
        float h = hash(cell + layer * 17.0);
        float2 offset = float2(hash(cell + 3.1), hash(cell + 7.7)) - 0.5;
        float d = length(f - offset * 0.7);
        float size = 0.02 + 0.06 * band(h) + 0.04 * beat;
        float fade = smoothstep(0.0, 0.3, depth) * smoothstep(1.0, 0.8, depth);
        color += palette(h + time * 0.03) * smoothstep(size, 0.0, d) * fade * step(0.6, h + 0.3 * uTime.z);
    }
    return color;
}

float3 alchemy(float2 p, float time, float beat) {
    float a = atan2(p.y, p.x) + time * 0.1 + beat * 0.2;
    float r = length(p);
    float segment = 2.0 * PI / 6.0;
    a = abs(glslMod(a, segment) - segment * 0.5);
    float2 q = float2(cos(a), sin(a)) * r;
    float w = wave(frac(r * 0.8));
    float v = sin(q.x * 6.0 + time + w * 2.0) + sin(q.y * 7.0 - time * 1.3) + sin((q.x + q.y) * 5.0 + time * 0.7);
    v += band(r) * 3.0;
    float lines = exp(-abs(frac(v * 0.5) - 0.5) * 12.0);
    return palette(v * 0.15 + time * 0.04 + uLevels.z) * (0.25 + lines) * (0.6 + uTime.z);
}

float4 pixelMain(VertexOutput input) : SV_Target {
    float aspect = uScreen.x / max(uScreen.y, 1.0);
    float2 p = (input.uv * 2.0 - 1.0) * float2(aspect, 1.0);
    p.y = -p.y;
    float time = uTime.x;
    float beat = uTime.y;
    int effect = (int) (uScreen.z + 0.5);
    float3 color;
    if (effect == 1) {
        color = tunnel(p, time, beat);
    } else if (effect == 2) {
        color = stars(p, time, beat);
    } else if (effect == 3) {
        color = alchemy(p, time, beat);
    } else {
        color = spectrumRing(p, time, beat);
    }
    return float4(saturate(color), 1.0);
}
