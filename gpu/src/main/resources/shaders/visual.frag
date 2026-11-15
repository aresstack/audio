#version 330 core

// The effects of the visualizer on the graphics card (OpenGL). visual.hlsl is the same for Direct3D;
// change both together. The texture is one row: red = band of the spectrum (low at the left),
// green = waveform (0.5 is silence).
in vec2 vUv;

uniform sampler2D uTexture;
uniform vec3 uTime;   // seconds, beat flash, loudness
uniform vec3 uLevels; // bass, middle, treble
uniform vec3 uScreen; // width, height, effect: 0 spectrum, 1 tunnel, 2 particles, 3 alchemy

out vec4 fragColor;

const float PI = 3.14159265;

float band(float x) { return texture(uTexture, vec2(clamp(x, 0.0, 1.0), 0.5)).r; }
float wave(float x) { return texture(uTexture, vec2(clamp(x, 0.0, 1.0), 0.5)).g * 2.0 - 1.0; }

// A smooth rainbow along t (cosine palette).
vec3 palette(float t) {
    return 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.33, 0.67)));
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

vec3 spectrumRing(vec2 p, float time, float beat) {
    float r = length(p);
    float a = atan(p.y, p.x);
    float x = abs(a) / PI;                    // mirrored: low at the right, high at the left
    float inner = 0.32 + 0.08 * uLevels.x + 0.05 * beat;
    float bar = band(x) * 0.55;
    float inside = step(inner, r) * step(r, inner + bar);
    float glow = exp(-abs(r - inner - bar) * 30.0) * band(x);
    vec3 color = palette(x * 0.8 + time * 0.05);
    float core = exp(-r * 6.0) * (0.3 + uTime.z);
    return color * (inside * 0.8 + glow) + vec3(core) * palette(time * 0.1);
}

vec3 tunnel(vec2 p, float time, float beat) {
    float r = max(length(p), 0.001);
    float a = atan(p.y, p.x);
    float speed = time * (0.6 + 2.0 * uTime.z);
    float z = 0.35 / r + speed;
    float u = abs(a) / PI;                    // mirrored, so that no seam runs where the angle wraps
    float rings = 0.5 + 0.5 * sin(z * 8.0 + band(u) * 4.0);
    float stripes = 0.5 + 0.5 * sin(a * 12.0 + time + uLevels.y * 6.0);
    vec3 color = palette(z * 0.05 + uLevels.z * 0.5) * rings * (0.6 + 0.4 * stripes);
    color *= smoothstep(0.0, 0.6, r) * (0.7 + band(fract(z * 0.1)) * 1.5);
    return color + beat * 0.25 * palette(time * 0.2);
}

vec3 stars(vec2 p, float time, float beat) {
    vec3 color = vec3(0.0);
    for (int layer = 0; layer < 4; layer++) {
        float depth = fract(float(layer) * 0.25 + time * (0.05 + 0.2 * uLevels.x));
        float scale = mix(18.0, 1.5, depth);
        vec2 q = p * scale;
        vec2 cell = floor(q);
        vec2 f = fract(q) - 0.5;
        float h = hash(cell + float(layer) * 17.0);
        vec2 offset = vec2(hash(cell + 3.1), hash(cell + 7.7)) - 0.5;
        float d = length(f - offset * 0.7);
        float size = 0.02 + 0.06 * band(h) + 0.04 * beat;
        float fade = smoothstep(0.0, 0.3, depth) * smoothstep(1.0, 0.8, depth);
        color += palette(h + time * 0.03) * smoothstep(size, 0.0, d) * fade * step(0.6, h + 0.3 * uTime.z);
    }
    return color;
}

vec3 alchemy(vec2 p, float time, float beat) {
    float a = atan(p.y, p.x) + time * 0.1 + beat * 0.2;
    float r = length(p);
    float segment = 2.0 * PI / 6.0;
    a = abs(mod(a, segment) - segment * 0.5);  // six mirrored pieces
    vec2 q = vec2(cos(a), sin(a)) * r;
    float w = wave(fract(r * 0.8));
    float v = sin(q.x * 6.0 + time + w * 2.0) + sin(q.y * 7.0 - time * 1.3) + sin((q.x + q.y) * 5.0 + time * 0.7);
    v += band(r) * 3.0;
    float lines = exp(-abs(fract(v * 0.5) - 0.5) * 12.0);
    return palette(v * 0.15 + time * 0.04 + uLevels.z) * (0.25 + lines) * (0.6 + uTime.z);
}

void main() {
    float aspect = uScreen.x / max(uScreen.y, 1.0);
    vec2 p = (vUv * 2.0 - 1.0) * vec2(aspect, 1.0);
    p.y = -p.y;
    float time = uTime.x;
    float beat = uTime.y;
    int effect = int(uScreen.z + 0.5);
    vec3 color;
    if (effect == 1) {
        color = tunnel(p, time, beat);
    } else if (effect == 2) {
        color = stars(p, time, beat);
    } else if (effect == 3) {
        color = alchemy(p, time, beat);
    } else {
        color = spectrumRing(p, time, beat);
    }
    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
