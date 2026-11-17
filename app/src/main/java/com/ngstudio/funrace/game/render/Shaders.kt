package com.ngstudio.funrace.game.render

/**
 * GLSL ES 3.00 sources. Lighting, water-line effects, fog and grading are shared through
 * [COMMON_FS] so every surface in the playground is lit the same way.
 */
object Shaders {

    private const val HEADER = "#version 300 es\n"

    const val COMMON_FS = """
uniform vec3 uSunDir;
uniform vec3 uSunColor;
uniform vec3 uAmbSky;
uniform vec3 uAmbGround;
uniform vec3 uRim;
uniform vec3 uFogColor;
uniform vec2 uFog;
uniform highp vec3 uCamPosF;
uniform vec4 uGrade;
uniform vec3 uGradeTint;
uniform vec2 uScreen;
uniform mediump sampler2DShadow uShadow;
uniform float uShadowOn;
uniform float uShadowTexel;
uniform highp float uWaterY;
uniform highp float uTimeF;
uniform vec3 uWaterTint;
uniform float uCaustics;

float shadowAt(vec4 ls) {
    if (uShadowOn < 0.5) return 1.0;
    vec3 p = ls.xyz / ls.w * 0.5 + 0.5;
    if (p.x < 0.002 || p.x > 0.998 || p.y < 0.002 || p.y > 0.998 || p.z > 1.0) return 1.0;
    float zb = p.z - 0.0012;
#ifdef SOFT_SHADOW
    float t = uShadowTexel;
    float s = texture(uShadow, vec3(p.xy, zb)) * 2.0;
    s += texture(uShadow, vec3(p.xy + vec2(-t, -t) * 1.2, zb));
    s += texture(uShadow, vec3(p.xy + vec2(t, -t) * 1.2, zb));
    s += texture(uShadow, vec3(p.xy + vec2(-t, t) * 1.2, zb));
    s += texture(uShadow, vec3(p.xy + vec2(t, t) * 1.2, zb));
    return s / 6.0;
#else
    return texture(uShadow, vec3(p.xy, zb));
#endif
}

float caustic(highp vec2 p, highp float t) {
    highp vec2 q = p * 1.35;
    float a = sin(q.x * 1.7 + sin(q.y * 1.3 + t * 1.1) * 1.6 + t * 0.7);
    float b = sin(q.y * 1.9 + sin(q.x * 1.1 - t * 0.9) * 1.7 - t * 0.6);
    float c = abs(a + b) * 0.5;
    return pow(1.0 - clamp(c, 0.0, 1.0), 6.0);
}

vec3 shade(vec3 base, vec3 N, highp vec3 W, vec4 ls, float gloss, float ao) {
    float ndl = dot(N, uSunDir);
    float sh = shadowAt(ls);
    // soft wrapped band: stylised but still reads as real form
    float diff = smoothstep(-0.12, 0.42, ndl);
    float lit = diff * sh;
    vec3 amb = mix(uAmbGround, uAmbSky, N.y * 0.5 + 0.5) * (0.35 + 0.65 * ao);
    vec3 c = base * (amb + uSunColor * lit * (0.55 + 0.45 * ao));
    vec3 V = normalize(uCamPosF - W);
    float nv = clamp(dot(N, V), 0.0, 1.0);
    float rim = pow(1.0 - nv, 3.0);
    c += uRim * rim * (0.25 + 0.75 * sh) * 0.4 * ao;
    if (gloss > 0.01) {
        vec3 H = normalize(uSunDir + V);
        float sp = pow(max(dot(N, H), 0.0), mix(18.0, 110.0, clamp(gloss, 0.0, 1.0)));
        c += uSunColor * sp * gloss * 1.6 * sh;
        // glossy vinyl picks up a little sky
        c += uAmbSky * pow(1.0 - nv, 4.0) * gloss * 0.25;
    }
    // water line: submerged parts tint toward the lagoon, caustics dance near the surface
    highp float dw = uWaterY - W.y;
    if (dw > -1.2 && uCaustics > 0.0) {
        float k = caustic(W.xz + vec2(W.y * 0.4), uTimeF);
        float near = 1.0 - smoothstep(0.0, 1.2, abs(dw));
        float under = smoothstep(-0.05, 0.25, dw);
        c += vec3(0.75, 0.95, 1.0) * k * uCaustics * (near * 0.55 + under * (1.0 - smoothstep(0.0, 6.0, dw)) * 0.8);
        c = mix(c, uWaterTint, clamp(dw * 0.22, 0.0, 0.82) * under);
    }
    return c;
}

float bayer4(vec2 p) {
    vec2 q = mod(floor(p), 4.0);
    float i = q.x + q.y * 4.0;
    // 4x4 ordered dither thresholds
    float v = mod(i * 7.0 + floor(i / 4.0) * 3.0, 16.0);
    return (v + 0.5) / 16.0;
}

vec3 grade(vec3 c) {
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(l), c, uGrade.x);
    c = (c - 0.5) * uGrade.y + 0.5;
    c *= uGradeTint * uGrade.z;
    vec2 q = gl_FragCoord.xy / uScreen - 0.5;
    c *= 1.0 - dot(q, q) * uGrade.w;
    return c;
}

vec3 finish(vec3 c, highp vec3 W) {
    highp float dist = distance(W, uCamPosF);
    float f = smoothstep(uFog.x, uFog.y, dist);
    return grade(mix(c, uFogColor, f * 0.92));
}
"""

    // ------------------------------------------------------------------ instanced lit (props, track, scenery)
    private const val LIT_VS_BODY = """
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec4 aNormal;
layout(location = 2) in vec4 aColor;
layout(location = 9) in vec4 aMat;
layout(location = 3) in vec4 aM0;
layout(location = 4) in vec4 aM1;
layout(location = 5) in vec4 aM2;
layout(location = 6) in vec4 aM3;
layout(location = 7) in vec4 aTintA;
layout(location = 8) in vec4 aTintB;
layout(location = 10) in vec4 aParam;
uniform mat4 uViewProj;
uniform mat4 uLightVP;
uniform highp float uTime;
uniform highp vec3 uCamPos;
uniform float uOutline;
uniform float uSwayAll;
out vec3 vNormal;
out vec4 vColor;
out highp vec3 vWorld;
out vec4 vLight;
out vec3 vMat;
void main() {
    mat4 M = mat4(aM0, aM1, aM2, aM3);
    vec3 s2 = vec3(dot(aM0.xyz, aM0.xyz), dot(aM1.xyz, aM1.xyz), dot(aM2.xyz, aM2.xyz));
    vec3 n = normalize(mat3(M) * (aNormal.xyz / max(s2, vec3(1e-6))));
    vec3 p = aPos;
    float wob = aMat.w;
    if (aParam.z > 0.0 && wob > 0.0) {
        // inflatable jiggle after an impact
        p += aNormal.xyz * sin(uTime * 17.0 + aParam.w + aPos.y * 2.5 + aPos.x * 1.7) * aParam.z * wob * 0.09;
    }
    vec4 w = M * vec4(p, 1.0);
    float sway = aParam.y + uSwayAll;
    if (sway > 0.0 && wob > 0.0) {
        highp float ph = uTime * 1.3 + w.x * 0.07 + w.z * 0.05;
        float h = wob * sway;
        w.x += sin(ph) * h * 0.22;
        w.z += cos(ph * 0.77) * h * 0.16;
    }
#ifdef OUTLINE
    if (aTintA.a < 0.0) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); return; }
    float dist = length(uCamPos - w.xyz);
    w.xyz += n * uOutline * clamp(dist / 10.0, 0.6, 2.4);
#endif
    float sel = floor(aMat.x * 5.0 + 0.5);
    vec3 base = aColor.rgb;
    if (sel == 1.0) base *= aTintA.rgb; else if (sel == 2.0) base *= aTintB.rgb;
    vWorld = w.xyz;
    vNormal = n;
    vColor = vec4(base, aColor.a);
    vMat = vec3(aMat.y + aTintB.a, aMat.z + aParam.x, abs(aTintA.a));
    vLight = uLightVP * vec4(w.xyz + n * 0.05, 1.0);
    gl_Position = uViewProj * w;
}
"""

    private const val LIT_FS_BODY = """
in vec3 vNormal;
in vec4 vColor;
in highp vec3 vWorld;
in vec4 vLight;
in vec3 vMat;
uniform vec3 uOutlineColor;
uniform float uFadeNear;
out vec4 frag;
void main() {
#ifdef FADE
    highp float dcam = distance(vWorld, uCamPosF);
    if (dcam < uFadeNear && bayer4(gl_FragCoord.xy) > (dcam / uFadeNear) * (dcam / uFadeNear)) discard;
#endif
#ifdef OUTLINE
    frag = vec4(grade(uOutlineColor), 1.0);
#else
    vec3 N = normalize(vNormal);
    if (!gl_FrontFacing) N = -N;
    vec3 base = vColor.rgb;
    vec3 col = shade(base, N, vWorld, vLight, vMat.x, vColor.a);
    col = mix(col, base * 1.15, clamp(vMat.y, 0.0, 1.0)) + base * max(vMat.y - 1.0, 0.0);
    frag = vec4(finish(col, vWorld), vMat.z);
#endif
}
"""

    // ------------------------------------------------------------------ skinned characters
    private const val SKIN_VS_BODY = """
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec4 aNormal;
layout(location = 2) in vec4 aColor;
layout(location = 9) in vec4 aMat;
layout(location = 11) in uvec4 aJoints;
layout(location = 12) in vec4 aWeights;
uniform mat4 uViewProj;
uniform mat4 uLightVP;
uniform mat4 uModel;
uniform mat4 uBones[13];
uniform vec3 uTints[5];
uniform highp vec3 uCamPos;
uniform float uOutline;
out vec3 vNormal;
out vec4 vColor;
out highp vec3 vWorld;
out vec4 vLight;
out vec3 vMat;
void main() {
    mat4 S = uBones[aJoints.x] * aWeights.x + uBones[aJoints.y] * aWeights.y
           + uBones[aJoints.z] * aWeights.z + uBones[aJoints.w] * aWeights.w;
    vec4 lp = S * vec4(aPos, 1.0);
    vec3 ln = mat3(S) * aNormal.xyz;
    vec4 w = uModel * lp;
    vec3 n = normalize(mat3(uModel) * ln);
#ifdef OUTLINE
    float dist = length(uCamPos - w.xyz);
    w.xyz += n * uOutline * clamp(dist / 10.0, 0.6, 2.4);
#endif
    int sel = int(floor(aMat.x * 5.0 + 0.5));
    vec3 base = aColor.rgb;
    if (sel > 0) base *= uTints[sel];
    vWorld = w.xyz;
    vNormal = n;
    vColor = vec4(base, aColor.a);
    vMat = vec3(aMat.y, aMat.z, 1.0);
    vLight = uLightVP * vec4(w.xyz + n * 0.04, 1.0);
    gl_Position = uViewProj * w;
}
"""

    private const val SKIN_FS_BODY = """
in vec3 vNormal;
in vec4 vColor;
in highp vec3 vWorld;
in vec4 vLight;
in vec3 vMat;
uniform vec3 uOutlineColor;
uniform float uFlash;
uniform float uAlpha;
uniform float uFadeNear;
out vec4 frag;
void main() {
    if (uFadeNear > 0.0) {
        highp float dcam = distance(vWorld, uCamPosF);
        if (dcam < uFadeNear && bayer4(gl_FragCoord.xy) > (dcam / uFadeNear) * (dcam / uFadeNear)) discard;
    }
#ifdef OUTLINE
    frag = vec4(grade(uOutlineColor), uAlpha);
#else
    vec3 N = normalize(vNormal);
    vec3 col = shade(vColor.rgb, N, vWorld, vLight, vMat.x, vColor.a);
    col += vColor.rgb * vMat.y;
    col = mix(col, vec3(1.0), uFlash);
    frag = vec4(finish(col, vWorld), uAlpha);
#endif
}
"""

    private const val DEPTH_FS = """
out vec4 frag;
void main() { frag = vec4(1.0); }
"""

    // ------------------------------------------------------------------ telegraph decals on the track
    private const val DECAL_VS = """
layout(location = 0) in vec3 aPos;
layout(location = 2) in vec4 aColor;
layout(location = 9) in vec4 aMat;
uniform mat4 uViewProj;
uniform float uZones[48];
out highp vec3 vWorld;
out vec4 vColor;
out float vPulse;
void main() {
    int z = int(floor(aMat.x * 255.0 + 0.5));
    vPulse = uZones[z];
    vWorld = aPos;
    vColor = aColor;
    gl_Position = uViewProj * vec4(aPos, 1.0);
}
"""

    private const val DECAL_FS = """
in highp vec3 vWorld;
in vec4 vColor;
in float vPulse;
out vec4 frag;
void main() {
    highp float s = fract((vWorld.x + vWorld.z) * 0.9);
    float stripe = smoothstep(0.45, 0.5, s) * (1.0 - smoothstep(0.95, 1.0, s));
    vec3 c = mix(vColor.rgb, vec3(1.0, 0.95, 0.9), stripe * 0.75);
    float a = vColor.a * (0.35 + 0.65 * vPulse);
    if (a < 0.01) discard;
    vec3 col = c * (0.85 + 0.35 * vPulse);
    frag = vec4(finish(col, vWorld) * a, a);
}
"""

    // ------------------------------------------------------------------ lagoon water (and lava / clouds / soda variants)
    private const val WATER_VS = """
layout(location = 0) in vec3 aPos;
uniform mat4 uViewProj;
uniform mat4 uLightVP;
uniform highp vec3 uOrigin;
uniform highp float uTime;
uniform vec4 uWaveA;
uniform vec4 uWaveB;
uniform vec4 uWaveC;
uniform float uWaveAmp;
uniform float uGridHalf;
out highp vec3 vWorld;
out vec3 vNormal;
out vec4 vLight;
out float vCrest;

vec3 gerstner(vec4 wave, highp vec3 p, highp float t, inout vec3 tg, inout vec3 bn) {
    float k = 6.2831853 / wave.w;
    float c = sqrt(9.8 / k);
    vec2 d = normalize(wave.xy);
    highp float f = k * (dot(d, p.xz) - c * t);
    float st = wave.z * uWaveAmp;
    float a = st / k;
    float sf = sin(f); float cf = cos(f);
    tg += vec3(-d.x * d.x * st * sf, d.x * st * cf, -d.x * d.y * st * sf);
    bn += vec3(-d.x * d.y * st * sf, d.y * st * cf, -d.y * d.y * st * sf);
    return vec3(d.x * a * cf, a * sf, d.y * a * cf);
}

void main() {
    highp vec3 p = vec3(aPos.x + uOrigin.x, uOrigin.y, aPos.z + uOrigin.z);
    float edge = max(abs(aPos.x), abs(aPos.z)) / uGridHalf;
    float fade = 1.0 - smoothstep(0.55, 1.0, edge);
    vec3 tg = vec3(1.0, 0.0, 0.0);
    vec3 bn = vec3(0.0, 0.0, 1.0);
    vec3 off = vec3(0.0);
    if (fade > 0.0) {
        off += gerstner(uWaveA, p, uTime, tg, bn);
        off += gerstner(uWaveB, p, uTime, tg, bn);
        off += gerstner(uWaveC, p, uTime, tg, bn);
    }
    p += off * fade;
    vec3 n = normalize(cross(bn, tg));
    n = normalize(mix(vec3(0.0, 1.0, 0.0), n, fade));
    vCrest = clamp(off.y * fade * 2.2, 0.0, 1.0);
    vWorld = p;
    vNormal = n;
    vLight = uLightVP * vec4(p, 1.0);
    gl_Position = uViewProj * vec4(p, 1.0);
}
"""

    private const val WATER_FS = """
in highp vec3 vWorld;
in vec3 vNormal;
in vec4 vLight;
in float vCrest;
uniform highp float uTime;
uniform vec3 uDeep;
uniform vec3 uShallow;
uniform vec3 uFoam;
uniform vec3 uSkyRefl;
uniform sampler2D uShore;
uniform vec4 uShoreRect;
uniform float uSeaStyle;
out vec4 frag;

highp float h21(highp vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float vnoise(highp vec2 p) {
    highp vec2 i = floor(p); highp vec2 f = fract(p);
    vec2 u = vec2(f * f * (3.0 - 2.0 * f));
    float a = h21(i), b = h21(i + vec2(1.0, 0.0)), c = h21(i + vec2(0.0, 1.0)), d = h21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

void main() {
    highp vec2 p = vWorld.xz;
    highp float t = uTime;
    vec2 d1 = vec2(sin(p.x * 0.83 + t * 1.15 + sin(p.y * 0.57)), cos(p.y * 0.77 - t * 0.95 + sin(p.x * 0.49)));
    vec2 d2 = vec2(sin(p.x * 2.6 - p.y * 1.3 + t * 2.4), cos(p.y * 2.8 + p.x * 1.1 - t * 2.1));
    vec3 N = normalize(vNormal + vec3(d1.x * 0.07 + d2.x * 0.035, 0.0, d1.y * 0.07 + d2.y * 0.035));
    vec3 V = normalize(uCamPosF - vWorld);
    float nv = max(dot(N, V), 0.0);
    float fres = pow(1.0 - nv, 4.0);
    vec2 suv = (p - uShoreRect.xy) / uShoreRect.zw;
    float shore = 0.0;
    if (suv.x > 0.0 && suv.x < 1.0 && suv.y > 0.0 && suv.y < 1.0) shore = texture(uShore, suv).r;
    float sh = shadowAt(vLight);
    vec3 col;
    if (uSeaStyle < 0.5) {
        // ---- tropical water
        float shallow = smoothstep(0.05, 0.85, shore);
        vec3 body = mix(uDeep, uShallow, shallow * 0.85 + vCrest * 0.25);
        float sss = pow(max(dot(-V, uSunDir) * 0.5 + 0.5, 0.0), 3.0) * vCrest;
        body += uShallow * sss * 0.35;
        col = body * (0.72 + 0.28 * sh);
        col = mix(col, uSkyRefl, fres * 0.62);
        vec3 R = reflect(-uSunDir, N);
        float sp = pow(max(dot(R, V), 0.0), 220.0) * 3.2 + pow(max(dot(R, V), 0.0), 26.0) * 0.18;
        col += uSunColor * sp * sh;
        // glitter on the waves
        float g = vnoise(p * 3.1 + vec2(t * 0.6, -t * 0.4));
        col += vec3(1.0) * smoothstep(0.86, 0.97, g) * (0.35 + vCrest) * 0.35 * sh;
        // foam: contact band, travelling rings and crest caps
        float n1 = vnoise(p * 1.4 + vec2(t * 0.25, t * 0.18));
        float ring = sin(shore * 26.0 - t * 2.3 + n1 * 2.0) * 0.5 + 0.5;
        float contact = smoothstep(0.78, 0.94, shore + n1 * 0.08);
        float rings = smoothstep(0.38, 0.62, shore) * (1.0 - smoothstep(0.7, 0.8, shore)) * smoothstep(0.72, 0.9, ring);
        float caps = smoothstep(0.55, 0.85, vCrest + n1 * 0.25) * 0.35;
        float foam = clamp(contact + rings * 0.7 + caps, 0.0, 1.0);
        col = mix(col, uFoam * (0.8 + 0.2 * sh), foam * 0.9);
    } else if (uSeaStyle < 1.5) {
        // ---- lava
        float n1 = vnoise(p * 0.35 + vec2(t * 0.05, t * 0.03));
        float n2 = vnoise(p * 1.3 - vec2(t * 0.11, -t * 0.07));
        float crack = smoothstep(0.42, 0.5, abs(n1 - 0.5) * 2.0 + n2 * 0.25);
        vec3 crust = uDeep * (0.6 + 0.4 * n2);
        vec3 glow = mix(uShallow, vec3(1.0, 0.85, 0.35), n2);
        col = mix(glow * (1.2 + 0.3 * sin(t * 2.0 + n1 * 6.0)), crust, crack);
        col += glow * smoothstep(0.6, 1.0, shore) * 0.8;
    } else if (uSeaStyle < 2.5) {
        // ---- sea of clouds
        float n1 = vnoise(p * 0.12 + vec2(t * 0.03, 0.0));
        float n2 = vnoise(p * 0.45 + vec2(-t * 0.05, t * 0.02));
        float puff = n1 * 0.65 + n2 * 0.35;
        col = mix(uDeep, uShallow, smoothstep(0.25, 0.85, puff));
        col *= 0.85 + 0.25 * smoothstep(-0.2, 0.8, N.x * uSunDir.x + N.z * uSunDir.z + 0.5) * sh;
        col = mix(col, uSkyRefl, fres * 0.3);
    } else {
        // ---- fizzy soda
        float shallow = smoothstep(0.05, 0.85, shore);
        col = mix(uDeep, uShallow, shallow * 0.8 + vCrest * 0.2) * (0.75 + 0.25 * sh);
        col = mix(col, uSkyRefl, fres * 0.5);
        vec3 R = reflect(-uSunDir, N);
        col += uSunColor * pow(max(dot(R, V), 0.0), 120.0) * 2.0 * sh;
        highp vec2 bp = p * 2.2 + vec2(0.0, t * 0.8);
        highp vec2 cell = floor(bp);
        float h = h21(cell);
        vec2 f = vec2(fract(bp)) - 0.5;
        float bub = step(0.82, h) * (1.0 - smoothstep(0.08, 0.13, length(f)));
        col = mix(col, uFoam, bub * 0.8 + smoothstep(0.8, 0.95, shore) * 0.8);
    }
    frag = vec4(finish(col, vWorld), 1.0);
}
"""

    // ------------------------------------------------------------------ sky
    private const val SKY_VS = """
layout(location = 0) in vec3 aPos;
out vec2 vNdc;
void main() {
    vNdc = aPos.xy;
    gl_Position = vec4(aPos.xy, 0.99999, 1.0);
}
"""

    private const val SKY_FS = """
in vec2 vNdc;
uniform highp mat4 uInvViewProj;
uniform vec3 uSkyTop;
uniform vec3 uSkyHorizon;
uniform vec3 uSkyGlow;
uniform float uNight;
uniform highp float uTime;
out vec4 frag;
void main() {
    highp vec4 wp = uInvViewProj * vec4(vNdc, 1.0, 1.0);
    highp vec3 dir = normalize(wp.xyz / wp.w - uCamPosF);
    float y = dir.y;
    vec3 col = mix(uSkyHorizon, uSkyTop, pow(clamp(y, 0.0, 1.0), 0.5));
    if (y < 0.0) col = mix(uSkyHorizon, uFogColor, clamp(-y * 6.0, 0.0, 1.0));
    float sd = max(dot(dir, uSunDir), 0.0);
    col += uSkyGlow * pow(sd, 6.0) * 0.45 * (1.0 - clamp(y * 1.5, 0.0, 1.0));
    col += uSunColor * (smoothstep(0.9975, 0.9988, sd) * 2.4 + pow(sd, 90.0) * 0.6 + pow(sd, 12.0) * 0.12);
    // horizon haze band
    col = mix(col, uFogColor, (1.0 - smoothstep(0.0, 0.12, abs(y))) * 0.45);
    if (uNight > 0.0) {
        highp vec3 d = floor(dir * 280.0);
        highp float h = fract(sin(dot(d, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
        col += vec3(step(0.9965, h)) * uNight * smoothstep(0.05, 0.3, y) * (0.55 + 0.45 * sin(uTime * 2.6 + h * 50.0));
    }
    frag = vec4(grade(col), 1.0);
}
"""

    // ------------------------------------------------------------------ billboards / particles
    private const val BB_VS = """
layout(location = 0) in vec3 aCorner;
layout(location = 1) in vec4 aPosSize;
layout(location = 2) in vec4 aColor;
layout(location = 3) in vec4 aUv;
layout(location = 4) in vec2 aRotAspect;
uniform mat4 uViewProj;
uniform vec3 uRight;
uniform vec3 uUp;
uniform float uGround;
out vec2 vUv;
out vec4 vColor;
void main() {
    float c = cos(aRotAspect.x);
    float s = sin(aRotAspect.x);
    vec2 q = vec2(aCorner.x * aRotAspect.y, aCorner.y);
    q = vec2(q.x * c - q.y * s, q.x * s + q.y * c);
    vec3 off = uGround > 0.5 ? vec3(q.x, 0.0, q.y) : (uRight * q.x + uUp * q.y);
    vec3 w = aPosSize.xyz + off * aPosSize.w;
    vec2 t = aCorner.xy + 0.5;
    vUv = vec2(mix(aUv.x, aUv.z, t.x), mix(aUv.w, aUv.y, t.y));
    vColor = aColor;
    gl_Position = uViewProj * vec4(w, 1.0);
}
"""

    private const val BB_FS = """
precision mediump float;
in vec2 vUv;
in vec4 vColor;
uniform sampler2D uTex;
out vec4 frag;
void main() {
    vec4 t = texture(uTex, vUv);
    frag = t * vColor;
}
"""

    private fun fs(defines: String, body: String) = HEADER + defines + "precision mediump float;\nprecision mediump sampler2DShadow;\n" + COMMON_FS + body
    private fun vs(defines: String, body: String) = HEADER + defines + "precision highp float;\n" + body

    private fun soft(s: Boolean) = if (s) "#define SOFT_SHADOW\n" else ""

    fun litVs(outline: Boolean) = vs(if (outline) "#define OUTLINE\n" else "", LIT_VS_BODY)
    fun litFs(outline: Boolean, soft: Boolean, fade: Boolean = false) = fs((if (outline) "#define OUTLINE\n" else "") + (if (fade) "#define FADE\n" else "") + soft(soft), LIT_FS_BODY)
    fun skinVs(outline: Boolean) = vs(if (outline) "#define OUTLINE\n" else "", SKIN_VS_BODY)
    fun skinFs(outline: Boolean, soft: Boolean) = fs((if (outline) "#define OUTLINE\n" else "") + soft(soft), SKIN_FS_BODY)
    fun depthVs() = vs("", LIT_VS_BODY)
    fun skinDepthVs() = vs("", SKIN_VS_BODY)
    fun depthFs() = HEADER + "precision mediump float;\n" + DEPTH_FS
    fun decalVs() = vs("", DECAL_VS)
    fun decalFs() = fs("", DECAL_FS)
    fun waterVs() = vs("", WATER_VS)
    fun waterFs(soft: Boolean) = fs(soft(soft), WATER_FS)
    fun skyVs() = vs("", SKY_VS)
    fun skyFs() = fs("", SKY_FS)
    fun bbVs() = vs("", BB_VS)
    fun bbFs() = HEADER + BB_FS
}
