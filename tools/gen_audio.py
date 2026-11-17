#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Splash Dash: Obstacle Party -- procedural audio generator.

Synthesises every sound effect and music loop used by the game from scratch,
using only the Python 3 standard library (no numpy/scipy, no samples, no
downloads, no borrowed melodies).  All randomness comes from fixed seeds, so
every run produces bit-identical files.  The DSP core is adapted from the
studio's SnowRace generator (../SnowRace/tools/gen_audio.py).

Usage (from the project root):
    python3 tools/gen_audio.py                    # generate all + verify + manifest
    python3 tools/gen_audio.py --verify           # only verify existing files
    python3 tools/gen_audio.py sfx_coin music_menu    # regenerate a subset
    python3 tools/gen_audio.py --stems music_race     # print per-stem mix levels

Output:
    app/src/main/res/raw/*.wav   PCM 16-bit, mono, 22050 Hz (plain RIFF/WAVE,
                                 'fmt ' + 'data' chunks only)
    tools/AUDIO_MANIFEST.md      file list with durations and descriptions

Techniques: band-limited wavetables (harmonics below ~9.5 kHz only, so no
aliasing), modal (decaying-partial) steel pan / marimba / xylophone / bells,
FM bells, Karplus-Strong ukulele with fractional-delay tuning and
pick-position comb, van den Doel bubble model for water, stick-slip friction
for creaks and squeaks, TPT state-variable and RBJ biquad filters, a
Freeverb-style reverb, DC blocking, raised-cosine fades.  Loops are rendered
*circularly*: every tail that rings past the loop end (note releases,
echoes, reverb, filter ringing) is folded back onto the start, which is the
exact periodic steady state, so loop points are click- and gap-free by
construction.  Music is mastered to a loudness target (BS.1770-style
K-weighted, gated) with a circular look-ahead peak limiter.
"""

import array
import math
import os
import random
import sys
import time
import wave
import zlib
from itertools import accumulate, repeat
from operator import mul

SR = 22050
TAU = 2.0 * math.pi
PI = math.pi
sin = math.sin
cos = math.cos
exp = math.exp
tanh = math.tanh

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RAW_DIR = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'raw')
MANIFEST_PATH = os.path.join(HERE, 'AUDIO_MANIFEST.md')

SFX_PEAK_DB = -1.0
MUSIC_PEAK_DB = -3.0          # limiter ceiling for music
MUSIC_LUFS = -15.0            # integrated loudness target for music
SIZE_LIMIT = 8 * 1000 * 1000  # bytes, whole res/raw folder


# ----------------------------------------------------------------------------
# basic helpers
# ----------------------------------------------------------------------------

def ns(t):
    """Seconds -> whole samples."""
    return int(round(t * SR))


def hz(m):
    """MIDI note number -> Hz (A4 = 69 = 440 Hz)."""
    return 440.0 * 2.0 ** ((m - 69) / 12.0)


def rng_for(name):
    """Deterministic RNG per sound (crc32, not the randomised str hash)."""
    return random.Random(zlib.crc32(name.encode('utf-8')))


def zeros(n):
    return [0.0] * n


def add_at(dst, src, off=0, gain=1.0):
    """Mix src*gain into dst starting at sample `off` (clipped to dst)."""
    if off < 0:
        src = src[-off:]
        off = 0
    end = min(len(dst), off + len(src))
    if end <= off:
        return dst
    if gain == 1.0:
        dst[off:end] = [a + b for a, b in zip(dst[off:end], src)]
    else:
        dst[off:end] = [a + gain * b for a, b in zip(dst[off:end], src)]
    return dst


def add_wrap(dst, src, off, gain=1.0):
    """Mix src into dst circularly (indices wrap modulo len(dst))."""
    L = len(dst)
    off %= L
    pos = 0
    while pos < len(src):
        chunk = src[pos:pos + (L - off)]
        add_at(dst, chunk, off, gain)
        pos += len(chunk)
        off = 0
    return dst


def vscale(x, g):
    return [v * g for v in x]


def vmul(a, b):
    return [p * q for p, q in zip(a, b)]


def pad_to(x, n):
    return x + [0.0] * (n - len(x)) if len(x) < n else x[:n]


def peak(x):
    return max(max(x), -min(x)) if x else 0.0


def rms(x):
    return math.sqrt(math.fsum(v * v for v in x) / len(x)) if x else 0.0


def sat(x, drive=1.5):
    """Gentle tanh saturation, unity small-signal gain."""
    k = 1.0 / drive
    return [k * tanh(drive * v) for v in x]


# ----------------------------------------------------------------------------
# envelopes
# ----------------------------------------------------------------------------

def env_exp(n, tau):
    """exp(-t/tau) for n samples (tau in seconds), via C-level accumulate."""
    if n <= 0:
        return []
    c = exp(-1.0 / (tau * SR))
    return list(accumulate(repeat(c, n - 1), mul, initial=1.0))


def ramp_up(n):
    """Raised cosine 0 -> 1 over n samples (first value exactly 0)."""
    if n <= 0:
        return []
    k = PI / n
    return [0.5 - 0.5 * cos(k * i) for i in range(n)]


def apply_attack(x, att):
    na = min(len(x), max(1, ns(att)))
    x[:na] = [v * r for v, r in zip(x[:na], ramp_up(na))]
    return x


def env_ad(n, att=0.002, tau=0.1, hold=0.0):
    """Raised-cosine attack, optional hold, exponential decay (n samples)."""
    na = min(n, max(1, ns(att)))
    nh = max(0, min(n - na, ns(hold)))
    out = ramp_up(na) + [1.0] * nh
    out += env_exp(n - len(out), tau)
    return out


def env_adsr(gate, a=0.005, d=0.1, s=0.7, r=0.15):
    """ADSR in seconds; returns gate + r seconds, ending at exactly 0."""
    ng = max(2, ns(gate))
    na = min(ng, max(1, ns(a)))
    out = ramp_up(na)
    m = ng - na
    if m > 0:
        out += [s + (1.0 - s) * e for e in env_exp(m, d)]
    lvl = out[-1]
    nr = max(2, ns(r))
    k = 1.0 / nr
    out += [lvl * exp(-4.0 * i * k) * (1.0 - i * k) for i in range(1, nr + 1)]
    return out


def env_hump(n, peak_at=0.5, p_up=2.0, p_dn=2.0):
    """Smooth 0 -> 1 -> 0 envelope peaking at fraction peak_at of n."""
    k = min(n - 1, max(1, int(n * peak_at)))
    m = n - k
    h = PI / 2.0
    return ([max(0.0, sin(h * i / k)) ** p_up for i in range(k)] +
            [max(0.0, cos(h * (j + 1) / m)) ** p_dn for j in range(m)])


def release_tail(x, start, length):
    """Fade x to zero from sample `start` over `length` samples, truncate."""
    start = max(0, min(start, len(x)))
    length = max(2, length)
    end = min(len(x), start + length)
    k = PI / length
    x[start:end] = [v * (0.5 + 0.5 * cos(k * i)) for i, v in enumerate(x[start:end])]
    del x[end:]
    return x


def end_fade(x, frac=0.12, min_s=0.004):
    """Short raised-cosine fade over the tail so truncated layers never click."""
    n = len(x)
    m = min(n, max(ns(min_s), int(n * frac)))
    if m > 1:
        k = PI / m
        x[n - m:] = [v * (0.5 + 0.5 * cos(k * (i + 1))) for i, v in enumerate(x[n - m:])]
    return x


# ----------------------------------------------------------------------------
# oscillators
# ----------------------------------------------------------------------------

def phases(freqs, ph0=0.0):
    """Running phase (radians) for a per-sample frequency list."""
    k = TAU / SR
    ph = list(accumulate([f * k for f in freqs], initial=ph0))
    ph.pop()
    return ph


def osc_sine(freqs, ph0=0.0):
    return [sin(p) for p in phases(freqs, ph0)]


def tone(n, f, ph0=0.0):
    w = TAU * f / SR
    return [sin(ph0 + w * i) for i in range(n)]


def glide(n, f0, f1, tau):
    """Exponential glide from f0 towards f1 with time constant tau (s)."""
    return [f1 + (f0 - f1) * e for e in env_exp(n, tau)]


def sweep_exp(n, f0, f1):
    """Log-linear sweep f0 -> f1 over n samples."""
    if n <= 1:
        return [f0] * n
    r = (f1 / f0) ** (1.0 / (n - 1))
    return list(accumulate(repeat(r, n - 1), mul, initial=f0))


WT_N = 2048
_WT_CACHE = {}


def _harm(shape, k):
    if shape == 'saw':
        return 1.0 / k
    if shape == 'square':
        return 1.0 / k if k % 2 else 0.0
    if shape == 'tri':
        return ((-1.0) ** ((k - 1) // 2)) / (k * k) if k % 2 else 0.0
    if shape == 'pulse':                    # 30 % duty pulse
        return math.sin(PI * k * 0.3) / k
    if shape == 'soft':                     # mellow saw for pads
        return 1.0 / k ** 1.7
    if shape == 'reed':                     # odd-heavy buzzy reed
        return (1.0 if k % 2 else 0.4) / k
    if shape == 'brass':                    # saw with a slight mid lift
        return (1.0 / k) * (1.0 + 0.6 * math.exp(-((k - 4.0) / 3.0) ** 2))
    raise ValueError(shape)


def wavetable(shape, f_max):
    """Band-limited single-cycle table: only harmonics that stay below
    ~9.5 kHz at f_max (Nyquist is 11.025 kHz) -> alias-free playback."""
    K = max(1, min(160, int(9500.0 / max(f_max, 1.0))))
    key = (shape, K)
    t = _WT_CACHE.get(key)
    if t is None:
        N = WT_N
        acc = [0.0] * N
        for k in range(1, K + 1):
            a = _harm(shape, k)
            if a == 0.0:
                continue
            w = TAU * k / N
            acc = [v + a * sin(w * j) for j, v in enumerate(acc)]
        pk = peak(acc) or 1.0
        T = [v / pk for v in acc]
        T.append(T[0])
        D = [T[i + 1] - T[i] for i in range(N)]
        t = (T, D)
        _WT_CACHE[key] = t
    return t


def osc_wt(table, freqs, ph0=0.0):
    """Linear-interpolated wavetable oscillator; freqs = per-sample Hz list."""
    T, D = table
    N = WT_N
    k = N / SR
    ph = list(accumulate([f * k for f in freqs], initial=ph0 * N))
    ph.pop()
    return [T[(i := int(q := p % N))] + (q - i) * D[i] for p in ph]


def osc_wt_const(table, n, f, ph0=0.0):
    T, D = table
    N = WT_N
    inc = f * N / SR
    base = ph0 * N
    return [T[(i := int(q := (base + inc * j) % N))] + (q - i) * D[i]
            for j in range(n)]


def white(n, rng):
    r = rng.random
    return [2.0 * r() - 1.0 for _ in range(n)]


# ----------------------------------------------------------------------------
# filters
# ----------------------------------------------------------------------------

def rbj(kind, f0, q=0.7071, gain_db=0.0):
    """RBJ audio-EQ-cookbook biquad coefficients (normalised)."""
    f0 = min(max(f0, 5.0), 0.47 * SR)
    w = TAU * f0 / SR
    cw = cos(w)
    sw = sin(w)
    al = sw / (2.0 * q)
    A = 10.0 ** (gain_db / 40.0)
    if kind == 'lp':
        b0 = b2 = (1 - cw) / 2
        b1 = 1 - cw
        a0, a1, a2 = 1 + al, -2 * cw, 1 - al
    elif kind == 'hp':
        b0 = b2 = (1 + cw) / 2
        b1 = -(1 + cw)
        a0, a1, a2 = 1 + al, -2 * cw, 1 - al
    elif kind == 'bp':                      # 0 dB peak gain
        b0, b1, b2 = al, 0.0, -al
        a0, a1, a2 = 1 + al, -2 * cw, 1 - al
    elif kind == 'peak':
        b0, b1, b2 = 1 + al * A, -2 * cw, 1 - al * A
        a0, a1, a2 = 1 + al / A, -2 * cw, 1 - al / A
    elif kind == 'hs':
        sq = 2 * math.sqrt(A) * al
        b0 = A * ((A + 1) + (A - 1) * cw + sq)
        b1 = -2 * A * ((A - 1) + (A + 1) * cw)
        b2 = A * ((A + 1) + (A - 1) * cw - sq)
        a0 = (A + 1) - (A - 1) * cw + sq
        a1 = 2 * ((A - 1) - (A + 1) * cw)
        a2 = (A + 1) - (A - 1) * cw - sq
    elif kind == 'ls':
        sq = 2 * math.sqrt(A) * al
        b0 = A * ((A + 1) - (A - 1) * cw + sq)
        b1 = 2 * A * ((A - 1) - (A + 1) * cw)
        b2 = A * ((A + 1) - (A - 1) * cw - sq)
        a0 = (A + 1) + (A - 1) * cw + sq
        a1 = -2 * ((A - 1) + (A + 1) * cw)
        a2 = (A + 1) + (A - 1) * cw - sq
    else:
        raise ValueError(kind)
    return (b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)


def biquad(x, c):
    """Transposed direct-form II biquad."""
    b0, b1, b2, a1, a2 = c
    y = [0.0] * len(x)
    s1 = s2 = 0.0
    for i, v in enumerate(x):
        o = b0 * v + s1
        s1 = b1 * v - a1 * o + s2
        s2 = b2 * v - a2 * o
        y[i] = o
    return y


def filt(x, kind, f0, q=0.7071, gain_db=0.0):
    return biquad(x, rbj(kind, f0, q, gain_db))


def svf(x, fc, q=0.707, mode='lp', block=16):
    """Zavalishin TPT state-variable filter (stable under fast modulation).
    fc: Hz (float) or per-sample list (re-read every `block` samples).
    mode 'bp' is normalised to 0 dB at the centre frequency."""
    n = len(x)
    y = [0.0] * n
    k = 1.0 / q
    ic1 = ic2 = 0.0
    m = {'lp': 0, 'bp': 1, 'hp': 2}[mode]
    is_list = isinstance(fc, list)
    if not is_list:
        block = max(1, n)
    lim = 0.45 * SR
    tan = math.tan
    pos = 0
    while pos < n:
        f = fc[pos] if is_list else fc
        f = 20.0 if f < 20.0 else (lim if f > lim else f)
        g = tan(PI * f / SR)
        a1 = 1.0 / (1.0 + g * (g + k))
        a2 = g * a1
        a3 = g * a2
        end = pos + block if pos + block < n else n
        for j in range(pos, end):
            v0 = x[j]
            v3 = v0 - ic2
            v1 = a1 * ic1 + a2 * v3
            v2 = ic2 + a2 * ic1 + a3 * v3
            ic1 = 2.0 * v1 - ic1
            ic2 = 2.0 * v2 - ic2
            if m == 0:
                y[j] = v2
            elif m == 1:
                y[j] = k * v1
            else:
                y[j] = v0 - k * v1 - v2
        pos = end
    return y


def onepole_lp(x, fc):
    a = exp(-TAU * fc / SR)
    b = 1.0 - a
    y = [0.0] * len(x)
    s = 0.0
    for i, v in enumerate(x):
        s = b * v + a * s
        y[i] = s
    return y


def dc_block(x, r=0.995):
    """y[n] = x[n] - x[n-1] + r*y[n-1]  (~18 Hz high-pass at 22.05 kHz)."""
    y = [0.0] * len(x)
    x1 = y1 = 0.0
    for i, v in enumerate(x):
        y1 = v - x1 + r * y1
        x1 = v
        y[i] = y1
    return y


# ----------------------------------------------------------------------------
# reverb (Freeverb topology, block-vectorised) and loop folding
# ----------------------------------------------------------------------------

def comb_damped(x, D, g, damp):
    """Feedback comb y[n] = x[n] + g*((1-d)*y[n-D] + d*y[n-D-1]), computed in
    whole blocks of up to D samples (the newest feedback sample is D old)."""
    n = len(x)
    pad = D + 1
    y = [0.0] * (pad + n)
    c0 = g * (1.0 - damp)
    c1 = g * damp
    pos = 0
    while pos < n:
        m = D if n - pos > D else n - pos
        b = pad + pos
        y[b:b + m] = [v + c0 * p + c1 * q for v, p, q in
                      zip(x[pos:pos + m], y[b - D:b - D + m], y[b - D - 1:b - D - 1 + m])]
        pos += m
    del y[:pad]
    return y


def allpass_fv(x, D, g=0.5):
    """Freeverb allpass: buf = x + g*buf[-D]; y = buf[-D] - x (block form)."""
    n = len(x)
    buf = [0.0] * (D + n)
    y = [0.0] * n
    pos = 0
    while pos < n:
        m = D if n - pos > D else n - pos
        xs = x[pos:pos + m]
        bd = buf[pos:pos + m]
        buf[D + pos:D + pos + m] = [v + g * d for v, d in zip(xs, bd)]
        y[pos:pos + m] = [d - v for v, d in zip(xs, bd)]
        pos += m
    return y


COMBS = (557, 593, 641, 677, 709, 743, 779, 811)    # Freeverb tunings / 2
ALLPASSES = (277, 221, 171, 113)


def reverb(x, room=0.86, damp=0.3, predelay=0.02):
    """Mono Freeverb-style reverb; output is len(x) + predelay samples."""
    src = [0.0] * ns(predelay) + x
    acc = None
    for D in COMBS:
        c = comb_damped(src, D, room, damp)
        acc = c if acc is None else [a + b for a, b in zip(acc, c)]
    acc = [v * 0.125 for v in acc]
    for D in ALLPASSES:
        acc = allpass_fv(acc, D, 0.5)
    return acc


def fold(buf, L):
    """Wrap everything past L back onto the start (circular / loop mixing)."""
    out = buf[:L]
    if len(out) < L:
        out += [0.0] * (L - len(out))
    pos = L
    while pos < len(buf):
        seg = buf[pos:pos + L]
        out[:len(seg)] = [a + b for a, b in zip(out, seg)]
        pos += L
    return out


def circular(x, fn, tail):
    """Exact periodic steady-state response of an LTI process `fn` to the
    periodic signal x: run on one period + silence, fold the tail back."""
    return fold(fn(x + [0.0] * tail), len(x))


# ----------------------------------------------------------------------------
# loudness + limiting (music mastering)
# ----------------------------------------------------------------------------

def lufs(x):
    """Integrated loudness, ITU-R BS.1770 style: K-weighting (shelf + RLB
    high-pass re-derived for 22.05 kHz), 400 ms blocks with 75 % overlap,
    absolute gate -70 LUFS and relative gate -10 LU."""
    y = biquad(biquad(x, rbj('hs', 1681.974, 0.7071752, 3.99984)),
               rbj('hp', 38.13547, 0.5003270))
    blk, hop = ns(0.4), ns(0.1)
    if len(y) < blk:
        blk = hop = len(y)
    cs = list(accumulate((v * v for v in y), initial=0.0))
    ms = [(cs[i + blk] - cs[i]) / blk for i in range(0, len(y) - blk + 1, hop)]
    ms = [m for m in ms if m > 0.0 and -0.691 + 10.0 * math.log10(m) > -70.0]
    if not ms:
        return -99.0
    rel = -0.691 + 10.0 * math.log10(sum(ms) / len(ms)) - 10.0
    ms = [m for m in ms if -0.691 + 10.0 * math.log10(m) > rel]
    return -0.691 + 10.0 * math.log10(sum(ms) / len(ms))


def limit_circular(x, ceiling, look=0.0015, release=0.09, stats=None):
    """Look-ahead peak limiter on a periodic signal (all stages wrap around,
    so a loop stays seamless).  Required gain is min-filtered over +-W,
    released with a one-pole (two passes round the circle = steady state),
    then box-smoothed over +-W, which guarantees |y| <= ceiling."""
    n = len(x)
    W = max(1, ns(look))
    m = [1.0] * n
    for i, v in enumerate(x):
        a = v if v >= 0.0 else -v
        if a > ceiling:
            r = ceiling / a
            for k in range(i - W, i + W + 1):
                kk = k % n
                if r < m[kk]:
                    m[kk] = r
    c = exp(-1.0 / (release * SR))
    g = 1.0
    gl = [1.0] * n
    for _ in range(2):
        for i in range(n):
            t = m[i]
            g = t if t < g else t + (g - t) * c
            gl[i] = g
    ext = gl[-W:] + gl + gl[:W]
    cs = list(accumulate(ext, initial=0.0))
    w = 2 * W + 1
    gs = [(cs[i + w] - cs[i]) / w for i in range(n)]
    if stats is not None:
        stats['gr1'] = sum(1 for v in gs if v < 0.891) / n          # > 1 dB reduction
        stats['gr3'] = sum(1 for v in gs if v < 0.708) / n          # > 3 dB reduction
    return [v * s for v, s in zip(x, gs)], min(gs)


# ----------------------------------------------------------------------------
# finishing / file IO
# ----------------------------------------------------------------------------

def fade_in(x, t):
    """Raised-cosine fade-in; the first sample becomes exactly 0."""
    a = min(len(x), max(2, ns(t)))
    for i in range(a):
        x[i] *= 0.5 - 0.5 * cos(PI * i / a)
    return x


def fade_out(x, t):
    """Raised-cosine fade-out; the last sample becomes exactly 0."""
    n = len(x)
    b = min(n, max(2, ns(t)))
    for i in range(b):
        x[n - 1 - i] *= 0.5 - 0.5 * cos(PI * i / b)
    return x


def trim(x, thresh_db=-60.0, pre=0.001, post=0.004, lead=True, tail=True):
    """Remove leading/trailing material quieter than thresh_db re: peak."""
    p = peak(x)
    if p <= 0.0:
        return x
    th = p * 10.0 ** (thresh_db / 20.0)
    first = 0
    if lead:
        while first < len(x) and abs(x[first]) <= th:
            first += 1
    last = len(x) - 1
    if tail:
        while last > first and abs(x[last]) <= th:
            last -= 1
    return x[max(0, first - ns(pre)):min(len(x), last + ns(post) + 1)]


def normalize(x, peak_db):
    p = peak(x)
    if p <= 1e-12:
        return x
    g = 10.0 ** (peak_db / 20.0) / p
    return [v * g for v in x]


def finish_sfx(x, fin=0.003, fout=0.008, lp=None, hp=None):
    """One-shot finishing: trim leading silence, >= 3 ms raised-cosine
    fade-in, DC block (after the fade, so the onset lobe the fade removes
    cannot leave a net offset), optional EQ, trim the silent tail, >= 5 ms
    raised-cosine fade-out, peak-normalise to -1 dBFS."""
    x = fade_in(trim(x, tail=False), max(0.003, fin))
    x = dc_block(x)
    if hp:
        x = filt(x, 'hp', hp)
    if lp:
        x = filt(x, 'lp', lp)
    x = fade_out(trim(x, lead=False), max(0.005, fout))
    return normalize(x, SFX_PEAK_DB)


def finish_loop(x):
    x = circular(x, dc_block, ns(0.4))
    return normalize(x, SFX_PEAK_DB)


def write_wav(path, x):
    data = array.array('h', [32767 if v >= 1.0 else (-32768 if v <= -1.0 else round(v * 32767.0))
                             for v in x])
    if sys.byteorder == 'big':
        data.byteswap()
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(data.tobytes())


def read_wav(path):
    with wave.open(path, 'rb') as w:
        params = (w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes())
        raw = w.readframes(w.getnframes())
    data = array.array('h')
    data.frombytes(raw)
    if sys.byteorder == 'big':
        data.byteswap()
    return params, data


# ----------------------------------------------------------------------------
# shared synthesis building blocks
# ----------------------------------------------------------------------------

def modal(f, dur, modes, att=0.002, rng=None, n=None):
    """Sum of exponentially decaying sine partials.
    modes = [(freq_ratio, amplitude, decay_tau_seconds), ...]."""
    n = n if n is not None else ns(dur)
    out = [0.0] * n
    for ratio, amp, tau in modes:
        fr = f * ratio
        if fr >= 0.45 * SR or amp == 0.0:
            continue
        w = TAU * fr / SR
        ph = rng.random() * TAU if rng else 0.0
        out = [o + amp * e * sin(ph + w * i)
               for i, o, e in zip(range(n), out, env_exp(n, tau))]
    if att > 0.0:
        apply_attack(out, att)
    return end_fade(out)


def fm_bell(f, dur, ratio=1.0, i0=1.5, i_tau=0.15, i_floor=0.2, tau=0.5, att=0.002):
    """Two-operator FM bell/celesta with decaying modulation index."""
    n = ns(dur)
    imax = max(0.0, (9500.0 - f) / (f * ratio) - 1.0)     # Carson's rule
    if i0 + i_floor > imax:
        s = imax / (i0 + i_floor)
        i0 *= s
        i_floor *= s
    wc = TAU * f / SR
    wm = wc * ratio
    out = [a * sin(wc * i + (i_floor + i0 * e) * sin(wm * i))
           for i, a, e in zip(range(n), env_exp(n, tau), env_exp(n, i_tau))]
    return end_fade(apply_attack(out, att))


GLOCK = [(1.0, 1.0, 0.30), (2.756, 0.32, 0.09), (5.404, 0.12, 0.035)]
CELESTA = [(1.0, 1.0, 0.45), (2.0, 0.10, 0.20), (4.0, 0.22, 0.07), (6.0, 0.05, 0.03)]


def chime(f, dur, tau_scale=1.0, modes=GLOCK, att=0.002, rng=None):
    return modal(f, dur, [(r, a, t * tau_scale) for r, a, t in modes], att=att, rng=rng)


def twinkles(buf, rng, count, t0, t1, f_lo, f_hi, amp=0.15, tau=0.035):
    """Sprinkle tiny high sine pings into buf between t0 and t1 seconds."""
    for _ in range(count):
        t = t0 + (t1 - t0) * rng.random()
        f = f_lo * (f_hi / f_lo) ** rng.random()
        p = modal(f, 0, [(1.0, 1.0, tau), (2.0, 0.2, tau * 0.5)], att=0.002,
                  n=ns(tau * 6.0))
        add_at(buf, p, ns(t), amp * (0.55 + 0.45 * rng.random()))
    return buf


def noise_band(n, rng, fc, q=0.8, mode='bp'):
    return svf(white(n, rng), fc, q, mode)


def click(n, rng, fc=3000.0, tau=0.0012, q=0.8):
    """Short filtered noise transient."""
    return svf(vmul(white(n, rng), env_exp(n, tau)), fc, q, 'bp')


def thump(dur, f0, f1, p_tau, a_tau, att=0.002):
    """Sine with an exponential pitch drop (kick/thud body)."""
    n = ns(dur)
    return end_fade(vmul(osc_sine(glide(n, f0, f1, p_tau)), env_ad(n, att, a_tau)))


def blip(dur, f0, f1, p_tau, a_tau, att=0.002, h2=0.0):
    """Pitch-swept sine blip (bubbles, pops); optional 2nd harmonic."""
    n = ns(dur)
    ph = phases(glide(n, f0, f1, p_tau))
    env = env_ad(n, att, a_tau)
    if h2:
        return end_fade([e * (sin(p) + h2 * sin(2.0 * p)) for p, e in zip(ph, env)])
    return end_fade([e * sin(p) for p, e in zip(ph, env)])


def grains(n, rng, count, t_lo, t_hi, shape=1.0, glen=(8, 40), amp_decay=True):
    """Sparse random noise grains (crunch / crackle excitation)."""
    out = [0.0] * n
    r = rng.random
    span = max(1e-6, t_hi - t_lo)
    for _ in range(count):
        u = r() ** shape
        t = t_lo + span * u
        i = ns(t)
        L = rng.randint(glen[0], glen[1])
        a = (0.25 + 0.75 * r()) * ((1.0 - 0.8 * u) if amp_decay else 1.0)
        k = 1.0 / L
        g = [a * (2.0 * r() - 1.0) * (1.0 - j * k) ** 2 for j in range(L)]
        add_at(out, g, i)
    return out


def brass(f, gate, rel=0.12, bright=1.0, vib=0.0, rng=None):
    """Brass-like voice: two detuned band-limited saws, a pitch scoop into the
    note, an envelope-tracking low-pass ("bwah") and optional late vibrato."""
    env = env_adsr(gate, 0.012, 0.09, 0.78, rel)
    n = len(env)
    scoop = env_exp(n, 0.018)
    freqs = [f * (1.0 - 0.02 * s) for s in scoop]          # ~35 cents below
    if vib:
        k = TAU * 5.6 / SR
        on, ramp = ns(0.15), ns(0.2)
        freqs = [v * (1.0 + vib * sin(k * i) * min(1.0, max(0.0, (i - on) / ramp)))
                 for i, v in enumerate(freqs)]
    table = wavetable('brass', f * 1.04)
    p0 = rng.random() if rng else 0.0
    a = osc_wt(table, freqs, p0)
    b = osc_wt(table, [v * 1.004 for v in freqs], (p0 + 0.37) % 1.0)
    fe = env_exp(n, 0.07)
    fc = [f * 1.2 + bright * (900.0 + 3600.0 * x + 1100.0 * e) for x, e in zip(fe, env)]
    s = svf([0.5 * (p + q) for p, q in zip(a, b)], fc, 0.8, 'lp')
    return vmul(s, env)


# ---- water-park specific building blocks -----------------------------------

def bubble(f0, rng, rise=None):
    """van den Doel bubble: a damped sine whose pitch rises as it decays."""
    d = 0.043 * f0 + 0.0014 * f0 ** 1.5               # damping, 1/s
    xi = rise if rise is not None else 0.1 + 0.1 * rng.random()
    k = max(16, ns(min(5.0 / d, 0.16)))
    fr = [f0 * (1.0 + xi * d * i / SR) for i in range(k)]
    return end_fade(vmul(osc_sine(fr, rng.random() * TAU), env_ad(k, 0.0008, 1.0 / d)))


def bubbles(buf, rng, count, t0, t1, f_lo, f_hi, amp=0.3, shape=1.0, wrap=False, rise=None):
    """Scatter `count` bubbles into buf (denser early when shape > 1)."""
    for _ in range(count):
        u = rng.random() ** shape
        t = t0 + (t1 - t0) * u
        f0 = f_lo * (f_hi / f_lo) ** rng.random()
        a = amp * (0.35 + 0.65 * rng.random())
        b = bubble(f0, rng, rise)
        if wrap:
            add_wrap(buf, b, ns(t), a)
        else:
            add_at(buf, b, ns(t), a)
    return buf


def creak(dur, rng, rate0, rate1, res, jitter=0.3):
    """Stick-slip friction: an irregular impulse train (rate0 -> rate1 Hz)
    exciting a few resonant band-passes (wood / rope / hinge body)."""
    n = ns(dur)
    exc = zeros(n)
    t = 0.0
    while True:
        u = t / dur
        rate = rate0 * (rate1 / rate0) ** u
        t += (1.0 + jitter * (2.0 * rng.random() - 1.0)) / rate
        if t >= dur:
            break
        i = ns(t)
        if i + 1 < n:
            a = 0.5 + 0.5 * rng.random()
            exc[i] += a
            exc[i + 1] -= 0.6 * a
    out = zeros(n)
    for f, q, g in res:
        add_at(out, svf(exc, f, q, 'bp'), 0, g)
    return normalize(out, 0.0)


def splash_layer(rng, dur, size=1.0, n_bub=40, drops=12):
    """Something falls into water: slap, wash, spray, plunge, bubbles, drips."""
    n = ns(dur)
    out = zeros(n)
    m = ns(0.06)
    slap = svf(vmul(white(m, rng), env_ad(m, 0.0008, 0.010 + 0.010 * size)), 1100.0, 0.6, 'bp')
    add_at(out, slap, 0, 0.9)
    body = filt(filt(vmul(white(n, rng), env_ad(n, 0.003, 0.05 + 0.09 * size)), 'lp', 3600.0), 'hp', 220.0)
    add_at(out, body, 0, 0.8)
    k = n - ns(0.015)
    spray = filt(vmul(white(k, rng), env_hump(k, 0.10, 1.2, 3.0)), 'hp', 2300.0)
    add_at(out, spray, ns(0.015), 0.22 + 0.12 * size)
    add_at(out, thump(0.25 + 0.15 * size, 170.0, 60.0 + 25.0 * (1.0 - size), 0.05, 0.04 + 0.06 * size),
           0, 0.3 + 0.3 * size)
    for _ in range(n_bub):
        u = rng.random()
        t = 0.02 + dur * 0.7 * u ** 1.8
        f0 = 300.0 * (1800.0 / 300.0) ** rng.random()
        add_at(out, bubble(f0, rng), ns(t), (0.5 - 0.35 * u) * (0.6 + 0.4 * rng.random()))
    for _ in range(drops):
        t = dur * (0.3 + 0.6 * rng.random())
        f0 = 900.0 * 3.0 ** rng.random()
        add_at(out, bubble(f0, rng, 0.25), ns(t), 0.10 + 0.10 * rng.random())
    return out


def squeak_voice(freqs, env, rng, forms=((2500.0, 3.0, 1.0), (1250.0, 2.0, 0.5)),
                 shape='pulse', sine_mix=0.5, rasp=0.0, jit=0.012):
    """Rubber-toy squeak / honk: band-limited source following a pitch
    contour (plus smoothed jitter) through a couple of resonances."""
    n = len(freqs)
    jn = onepole_lp(onepole_lp(white(n, rng), 40.0), 40.0)
    jp = peak(jn) or 1.0
    fr = [f * (1.0 + jit * j / jp) for f, j in zip(freqs, jn)]
    ph = phases(fr)
    src = osc_wt(wavetable(shape, max(fr) * 1.02), fr)
    out = [sine_mix * sin(p) for p in ph]
    for fc, q, g in forms:
        add_at(out, svf(src, fc, q, 'bp'), 0, g)
    if rasp:
        out = [v * (1.0 + rasp * sin(0.5 * p)) for v, p in zip(out, ph)]
    return vmul(out, env)


def boing_voice(dur, f0, f1, wob_hz, wob_depth, tau, fc_lo, fc_hi, rise_tau=0.05):
    """Cartoon spring 'boyoyoing': a buzzy source whose pitch wobbles and
    whose jaw-harp-like formant sweeps with the wobble, both decaying."""
    n = ns(dur)
    base = glide(n, f0, f1, rise_tau)
    kw = TAU * wob_hz / SR
    wob = [sin(kw * i) for i in range(n)]
    dec = env_exp(n, 0.3)
    fr = [b * (1.0 + wob_depth * w * d) for b, w, d in zip(base, wob, dec)]
    src = osc_wt(wavetable('saw', max(f0, f1) * 1.15), fr)
    fd = env_exp(n, 0.22)
    fc = [fc_lo + (fc_hi - fc_lo) * (0.5 + 0.5 * w) * (0.35 + 0.65 * d) for w, d in zip(wob, fd)]
    y = svf(src, fc, 3.2, 'bp')
    add_at(y, osc_sine(fr), 0, 0.45)                    # round body
    return end_fade(vmul(y, env_ad(n, 0.002, tau)))


def ks_pluck(m, ring, t60_base=2.4, passes=2, pick=0.0, seed=0, lp=4500.0):
    """Karplus-Strong pluck.  Loop = z^-D * g*[h0 + h1 z^-1 + h2 z^-2]
    (2-tap damping average merged with a linear fractional delay); the
    fraction is solved so the loop's phase delay at f0 gives exact tuning.
    `pick` (0..0.5) applies a pick-position comb to the excitation."""
    f = hz(m)
    rng = random.Random(1000 + m + 7919 * seed)
    P = SR / f
    D = int(P - 0.5)
    target = P - D
    w0 = TAU * f / SR

    def taps(x):
        return 0.5 * (1.0 - x), 0.5, 0.5 * x

    def pdelay(x):
        h0, h1, h2 = taps(x)
        return math.atan2(h1 * sin(w0) + h2 * sin(2 * w0),
                          h0 + h1 * cos(w0) + h2 * cos(2 * w0)) / w0

    lo, hi = 0.0, 1.0
    for _ in range(40):
        mid = 0.5 * (lo + hi)
        if pdelay(mid) < target:
            lo = mid
        else:
            hi = mid
    h0, h1, h2 = taps(0.5 * (lo + hi))
    mag = abs(complex(h0 + h1 * cos(w0) + h2 * cos(2 * w0),
                      -(h1 * sin(w0) + h2 * sin(2 * w0))))
    t60 = t60_base * min(1.0, (300.0 / f) ** 0.35)
    g = min(0.9995, 10.0 ** (-3.0 * P / (t60 * SR)) / mag)
    c0, c1, c2 = g * h0, g * h1, g * h2
    n = ns(ring)
    L0 = int(P) + 1
    exc = white(L0, rng)
    for _ in range(passes):
        exc = [0.5 * (a + b) for a, b in zip(exc, exc[1:] + exc[:1])]
    if pick:
        d = max(1, int(pick * P))
        exc = [exc[i] - exc[i - d] for i in range(L0)]
    mu = sum(exc) / L0
    exc = [v - mu for v in exc]
    pk = peak(exc) or 1.0
    xin = [v / pk for v in exc] + [0.0] * max(0, n - L0)
    pad = D + 2
    y = [0.0] * (pad + n)
    pos = 0
    while pos < n:
        mm = D if n - pos > D else n - pos
        b = pad + pos
        y[b:b + mm] = [v + c0 * p + c1 * q + c2 * r for v, p, q, r in
                       zip(xin[pos:pos + mm], y[b - D:b - D + mm],
                           y[b - D - 1:b - D - 1 + mm], y[b - D - 2:b - D - 2 + mm])]
        pos += mm
    out = onepole_lp(y[pad:], lp)
    lvl = rms(out[:ns(0.15)]) or 1.0                    # even out random excitations
    out = [v * (0.3 / lvl) for v in out]
    apply_attack(out, 0.0015)
    return end_fade(out, 0.15)


# ---- pitched instruments (also used by the SFX) ------------------------------

def ins_steelpan(m, gate=0.25, bright=1.0):
    """Steel pan (tenor pan) note.  Modal: strong fundamental, an octave
    partial that swells in just after the strike (energy transfer between
    the coupled modes) with a gentle shimmer, decaying 3rd/4th/5th
    partials, a small tension-modulation pitch drop and a soft rubber-tip
    mallet thump."""
    f = hz(m)
    rng = random.Random(5000 + m)
    T1 = min(1.15, 0.9 * (523.25 / f) ** 0.5)
    tail = min(0.75, 2.2 * T1)
    n = ns(gate + tail)
    fr = [f * (1.0 + 0.004 * e) for e in env_exp(n, 0.02)]
    p = phases(fr, rng.random() * TAU)
    e1 = env_exp(n, T1)
    e2 = env_exp(n, 0.55 * T1)
    sw = env_exp(n, 0.012)
    e3 = env_exp(n, 0.28 * T1)
    e4 = env_exp(n, 0.15 * T1)
    e5 = env_exp(n, 0.08 * T1)
    a3 = 0.26 * bright if 3.0 * f < 9500.0 else 0.0
    a4 = 0.13 * bright if 4.0 * f < 9500.0 else 0.0
    a5 = 0.05 * bright if 5.0 * f < 9500.0 else 0.0
    k5 = TAU * 5.2 / SR
    y = [0.5 * (q1 * sin(ph)
                + 0.62 * q2 * (1.0 - 0.7 * s) * (1.0 + 0.1 * sin(k5 * i)) * sin(2.0 * ph + 0.6)
                + a3 * q3 * sin(3.004 * ph) + a4 * q4 * sin(4.01 * ph) + a5 * q5 * sin(5.02 * ph))
         for i, (ph, q1, q2, s, q3, q4, q5) in enumerate(zip(p, e1, e2, sw, e3, e4, e5))]
    k = ns(0.006)
    add_at(y, onepole_lp(vmul(white(k, rng), env_exp(k, 0.0012)), 2400.0), 0, 0.10)
    apply_attack(y, 0.0012)
    return end_fade(y, 0.3)


def ins_marimba(m, gate=0.0):
    """Marimba bar: fundamental, 4th (~3.93x) and 10th (~9.2x) partials,
    lower notes ring longer (resonator tube), soft yarn-mallet thump."""
    f = hz(m)
    rng = random.Random(6000 + m)
    T = max(0.1, min(0.85, 0.55 * (220.0 / f) ** 0.8))
    y = modal(f, min(1.8, 4.0 * T), [(1.0, 1.0, T), (3.93, 0.30, 0.17 * T), (9.2, 0.08, 0.05 * T)],
              att=0.0015, rng=rng)
    k = ns(0.005)
    add_at(y, onepole_lp(vmul(white(k, rng), env_exp(k, 0.0009)), 1800.0), 0, 0.10)
    return end_fade(y, 0.2)


def ins_xylo(m, gate=0.0):
    """Xylophone: bright 1:3 tuned bar, short ring, hard-mallet tick."""
    f = hz(m)
    rng = random.Random(6500 + m)
    T = max(0.07, min(0.45, 0.30 * (523.25 / f) ** 0.6))
    y = modal(f, 4.0 * T, [(1.0, 1.0, T), (3.0, 0.38, 0.3 * T), (6.1, 0.12, 0.12 * T),
                           (10.4, 0.04, 0.05 * T)], att=0.0008, rng=rng)
    k = ns(0.003)
    add_at(y, filt(vmul(white(k, rng), env_exp(k, 0.0005)), 'hp', 2500.0), 0, 0.12)
    return end_fade(y, 0.2)


def ins_celesta(m, gate):
    """FM celesta / music-box bell with a bright 4th-partial 'ting'."""
    f = hz(m)
    ring = gate + 0.5
    y = fm_bell(f, ring, 1.0, 0.9, 0.16, 0.22, tau=0.75, att=0.003)
    add_at(y, modal(f, ring, [(4.0, 0.16, 0.06), (2.0, 0.05, 0.25)], att=0.003), 0)
    return release_tail(y, ns(gate + 0.05), ns(0.45))


def ins_uke(m, gate, var=0):
    """Ukulele string: bright nylon Karplus-Strong with a pick-position comb,
    a small body resonance, damped when the next strum arrives."""
    y = ks_pluck(m, gate + 0.07, t60_base=1.3, passes=1, pick=0.22, seed=var, lp=5200.0)
    y = filt(y, 'peak', 470.0, 1.0, 4.0)
    return release_tail(y, ns(gate), ns(0.06))


def ins_whistle(m, gate):
    """Slide-whistle / tin-whistle lead: near-sine tone with a pitch scoop,
    delayed vibrato, breath noise around the pitch and a little chiff."""
    f = hz(m)
    rng = random.Random(7000 + m)
    env = env_adsr(gate, 0.025, 0.12, 0.82, 0.07)
    n = len(env)
    scoop = env_exp(n, 0.018)
    kv = TAU * 5.6 / SR
    on, ramp = ns(0.14), ns(0.18)
    fr = [f * (1.0 - 0.03 * s) * (1.0 + 0.0065 * sin(kv * i) * min(1.0, max(0.0, (i - on) / ramp)))
          for i, s in enumerate(scoop)]
    tn = [sin(q) + 0.07 * sin(2.0 * q) + 0.025 * sin(3.0 * q) for q in phases(fr)]
    breath = svf(white(n, rng), f, 5.0, 'bp')
    hiss = svf(white(n, rng), 5200.0, 0.8, 'bp')
    y = [e * (t + 0.2 * b + 0.02 * h) for t, b, h, e in zip(tn, breath, hiss, env)]
    k = ns(0.03)
    add_at(y, vmul(svf(white(k, rng), 2.2 * f, 1.5, 'bp'), env_ad(k, 0.002, 0.008)), 0, 0.25)
    return y


def ins_pad(m, gate, rel=0.9):
    """Soft detuned (3 x +-7 cent) mellow-saw pad."""
    f = hz(m)
    rng = random.Random(2000 + m)
    env = env_adsr(gate, 0.35, 0.6, 0.85, rel)
    n = len(env)
    table = wavetable('soft', f * 1.01)
    acc = zeros(n)
    for det in (-0.0042, 0.0, 0.0042):
        add_at(acc, osc_wt_const(table, n, f * (1.0 + det), rng.random()), 0, 1.0 / 3.0)
    return vmul(onepole_lp(acc, 2200.0), env)


def ins_sine_bass(m, gate):
    """Round, slightly plucked bass; 2nd/3rd harmonics keep it audible on
    phone speakers."""
    f = hz(m)
    env = env_adsr(gate, 0.006, 0.25, 0.7, 0.08)
    w = TAU * f / SR
    pl = env_exp(len(env), 0.04)
    return [e * (sin(w * i) + (0.3 + 0.25 * p) * sin(2 * w * i) + (0.1 + 0.15 * p) * sin(3 * w * i))
            for i, (e, p) in enumerate(zip(env, pl))]


def ins_pluck(m, gate, shape='pulse', bright=1.0, rel=0.07):
    """Bright plucky synth: 2 detuned band-limited oscillators + decaying LPF."""
    f = hz(m)
    env = env_adsr(gate, 0.003, 0.2, 0.45, rel)
    n = len(env)
    table = wavetable(shape, f * 1.01)
    a = osc_wt_const(table, n, f * 0.9985, 0.0)
    b = osc_wt_const(table, n, f * 1.0015, 0.33)
    fc = [f * 1.5 + bright * (900.0 + 5200.0 * e) for e in env_exp(n, 0.09)]
    return vmul(svf([0.5 * (p + q) for p, q in zip(a, b)], fc, 0.9, 'lp'), env)


def ins_bass(m, gate, bright=1.0):
    """Driving synth bass: saw+square through an enveloped LPF, plus sine sub."""
    f = hz(m)
    env = env_adsr(gate, 0.003, 0.12, 0.65, 0.035)
    n = len(env)
    saw = osc_wt_const(wavetable('saw', f), n, f, 0.0)
    sq = osc_wt_const(wavetable('square', f), n, f, 0.25)
    fc = [300.0 + 2.0 * f + bright * 2400.0 * e for e in env_exp(n, 0.06)]
    s = svf([0.6 * a + 0.4 * b for a, b in zip(saw, sq)], fc, 1.2, 'lp')
    w = TAU * f / SR
    return [e * (v + 0.5 * sin(w * i)) for i, (v, e) in enumerate(zip(s, env))]


def ins_stab(m, gate, shape='square', cutoff=2600.0, detune=0.0, att=0.003):
    """Short chord stab (detuned saw = staccato 'strings')."""
    f = hz(m)
    rng = random.Random(8000 + m)                       # per-note random phases: chord
    env = env_adsr(gate, att, 0.08, 0.3, 0.05)          # notes never start phase-aligned
    n = len(env)
    table = wavetable(shape, f * 1.01)
    dets = (0.0,) if not detune else (-detune, 0.0, detune)
    acc = zeros(n)
    for k, d in enumerate(dets):
        add_at(acc, osc_wt_const(table, n, f * (1.0 + d), rng.random()), 0, 1.0 / len(dets))
    fc = [cutoff * (0.6 + 0.8 * e) for e in env_exp(n, 0.05)]
    return vmul(svf(acc, fc, 0.8, 'lp'), env)


# ============================================================================
# SOUND EFFECTS
# ============================================================================

# ---- UI -----------------------------------------------------------------------

def sfx_ui_tap():
    rng = rng_for('sfx_ui_tap')
    out = blip(0.075, 480.0, 1250.0, 0.010, 0.017, att=0.0015, h2=0.10)   # rising bubble
    add_at(out, blip(0.04, 260.0, 340.0, 0.01, 0.008), 0, 0.22)            # round body
    add_at(out, click(ns(0.008), rng, 3000.0, 0.0010, 0.9), 0, 0.16)
    return finish_sfx(out, 0.003, 0.01)


def sfx_ui_back():
    rng = rng_for('sfx_ui_back')
    out = blip(0.09, 300.0, 620.0, 0.014, 0.022, att=0.002, h2=0.12)      # lower bubble pop
    add_at(out, blip(0.05, 190.0, 230.0, 0.01, 0.012), 0, 0.25)
    add_at(out, onepole_lp(click(ns(0.01), rng, 1600.0, 0.0012, 0.8), 2600.0), 0, 0.2)
    return finish_sfx(out, 0.003, 0.012)


def sfx_ui_open():
    rng = rng_for('sfx_ui_open')
    n = ns(0.30)
    m = ns(0.2)
    fc = sweep_exp(m, 600.0, 4800.0) + [4800.0] * (n - m)
    out = vscale(vmul(svf(white(n, rng), fc, 1.4, 'bp'), env_hump(n, 0.4, 1.6, 2.4)), 0.45)
    k = ns(0.2)
    add_at(out, end_fade(vmul(osc_sine(glide(k, 330.0, 990.0, 0.035)), env_ad(k, 0.004, 0.05))),
           ns(0.05), 0.8)                                                  # "bloop" up
    add_at(out, bubble(1500.0, rng, 0.15), ns(0.15), 0.25)
    return finish_sfx(out, 0.003, 0.03)


def sfx_ui_close():
    rng = rng_for('sfx_ui_close')
    n = ns(0.26)
    m = ns(0.18)
    fc = sweep_exp(m, 4200.0, 550.0) + [550.0] * (n - m)
    out = vscale(vmul(svf(white(n, rng), fc, 1.3, 'bp'), env_hump(n, 0.3, 1.5, 2.0)), 0.45)
    k = ns(0.17)
    add_at(out, end_fade(vmul(osc_sine(glide(k, 880.0, 290.0, 0.035)), env_ad(k, 0.004, 0.045))),
           ns(0.06), 0.8)                                                  # "bloop" down
    return finish_sfx(out, 0.003, 0.03)


# ---- rewards ------------------------------------------------------------------

def sfx_coin():
    rng = rng_for('sfx_coin')
    out = zeros(ns(0.34))
    n1 = modal(hz(88), 0.09, [(1, 1.0, 0.04), (2, 0.35, 0.022), (3, 0.15, 0.014)])      # E6
    n2 = modal(hz(95), 0.28, [(1, 1.0, 0.12), (2, 0.30, 0.05), (3, 0.12, 0.03),
                              (2.76, 0.16, 0.04), (5.40, 0.07, 0.02)])                  # B6 ring
    add_at(out, n1, 0, 0.75)
    add_at(out, n2, ns(0.055), 0.9)
    add_at(out, click(ns(0.008), rng, 5000.0, 0.0008, 0.9), 0, 0.15)
    twinkles(out, rng, 5, 0.09, 0.26, 3200.0, 7000.0, amp=0.12, tau=0.022)
    return finish_sfx(out, 0.003, 0.03)


def sfx_coin_tick():
    out = modal(hz(100), 0.04, [(1, 1.0, 0.0065), (2, 0.18, 0.004)])   # E7 tick
    return finish_sfx(out, 0.003, 0.008)


def _star(level):
    rng = rng_for('sfx_star%d' % level)
    n = ns({1: 0.5, 2: 0.62, 3: 0.9}[level])
    out = zeros(n)
    seq = {1: (79, 84), 2: (84, 88), 3: (88, 91, 96)}[level]   # G5-C6 / C6-E6 / E6-G6-C7
    for k, m in enumerate(seq):
        last = k == len(seq) - 1
        t = 0.05 * k
        dur = n / SR - t
        add_at(out, chime(hz(m), dur, 0.75 if last else 0.28, CELESTA), ns(t), 0.62 if last else 0.4)
        if last:
            add_at(out, chime(hz(m) * 1.0045, dur, 0.7, CELESTA), ns(t), 0.3)   # beating twin
            add_at(out, ins_steelpan(m - 12, 0.2), ns(t), 0.30)                  # warm pan body
    if level == 3:
        add_at(out, chime(hz(103), 0.6, 0.8), ns(0.13), 0.22)                    # G7 sparkle
        dn = n - ns(0.1)
        k1 = TAU * 11.0 / SR
        shim = vmul(vmul(noise_band(dn, rng, 7000.0, 1.6), [0.6 + 0.4 * sin(k1 * i) for i in range(dn)]),
                    env_hump(dn, 0.3, 1.5, 2.0))
        add_at(out, shim, ns(0.1), 0.07)
    twinkles(out, rng, 3 + 3 * level, 0.07, n / SR - 0.12, hz(seq[-1]) * 1.4, hz(seq[-1]) * 3.2,
             amp=0.09 + 0.02 * level, tau=0.03)
    air = vmul(noise_band(n, rng, 7000.0, 2.0), env_hump(n, 0.3, 2.0, 2.0))
    add_at(out, air, 0, 0.04)
    return finish_sfx(out, 0.003, 0.08)


def sfx_star1():
    return _star(1)


def sfx_star2():
    return _star(2)


def sfx_star3():
    return _star(3)


def sfx_unlock():
    rng = rng_for('sfx_unlock')
    n = ns(1.1)
    out = zeros(n)
    for k, m in enumerate((72, 76, 79, 84)):              # C5 E5 G5 C6 steel-pan run
        last = k == 3
        t = ns(0.075 * k)
        add_at(out, ins_steelpan(m, 0.6 if last else 0.2), t, 0.5)
        add_at(out, chime(hz(m + 12), 0.6 if last else 0.25, 0.6 if last else 0.25), t, 0.16)
    for m in (88, 91, 96):                                # shimmering bell chord on top
        add_at(out, fm_bell(hz(m), 0.75, 1.0, 1.0, 0.08, 0.12, tau=0.3), ns(0.3), 0.13)
    twinkles(out, rng, 10, 0.3, 0.95, 2500.0, 6500.0, amp=0.10, tau=0.035)
    dn = n - ns(0.2)
    add_at(out, vmul(noise_band(dn, rng, 6500.0, 1.6), env_hump(dn, 0.3, 1.5, 2.0)), ns(0.2), 0.05)
    return finish_sfx(out, 0.003, 0.15)


def sfx_chest_open():
    rng = rng_for('sfx_chest_open')
    n = ns(1.45)
    out = zeros(n)
    cr = creak(0.36, rng, 35.0, 120.0, ((480.0, 9.0, 1.0), (1050.0, 8.0, 0.7), (2200.0, 6.0, 0.35)))
    add_at(out, vmul(cr, env_hump(len(cr), 0.6, 1.5, 1.5)), 0, 1.4)     # hinge creak
    tl = 0.36
    add_at(out, thump(0.4, 150.0, 58.0, 0.04, 0.1), ns(tl), 0.8)         # lid lands
    add_at(out, modal(400.0, 0.15, [(1, 1.0, 0.03), (2.31, 0.5, 0.015), (3.9, 0.25, 0.008)]), ns(tl), 0.35)
    for k, m in enumerate((79, 84, 88, 91, 96)):                          # sparkle burst G5..C7
        last = k == 4
        t = ns(tl + 0.03 + 0.05 * k)
        add_at(out, fm_bell(hz(m), 0.9 if last else 0.45, 1.0, 1.2, 0.07, 0.15,
                            tau=0.42 if last else 0.2), t, 0.34 + 0.03 * k)
        add_at(out, ins_steelpan(m - 12, 0.15), t, 0.18)
    dn = n - ns(tl + 0.03)
    k1, k2 = TAU * 13.0 / SR, TAU * 7.3 / SR
    trem = [0.55 + 0.45 * sin(k1 * i) * sin(k2 * i + 1.0) for i in range(dn)]
    dust = vmul(vmul(noise_band(dn, rng, 6800.0, 1.4), trem), env_hump(dn, 0.35, 1.5, 2.0))
    add_at(out, dust, ns(tl + 0.03), 0.10)
    twinkles(out, rng, 14, tl + 0.05, 1.3, 2500.0, 6500.0, amp=0.09, tau=0.04)
    return finish_sfx(out, 0.003, 0.2)


def sfx_wheel_tick():
    rng = rng_for('sfx_wheel_tick')
    out = modal(1850.0, 0.03, [(1, 1.0, 0.0045), (1.68, 0.5, 0.0035), (0.36, 0.55, 0.006)])
    m = ns(0.006)
    add_at(out, svf(vmul(white(m, rng), env_exp(m, 0.0008)), 2500.0, 0.7, 'hp'), 0, 0.35)
    return finish_sfx(out, 0.003, 0.006)


def sfx_wheel_win():
    rng = rng_for('sfx_wheel_win')
    n = ns(1.15)
    out = zeros(n)
    hits = ((0.0, 0.08, (67, 72, 76)), (0.11, 0.08, (69, 72, 77)), (0.22, 0.55, (67, 72, 76, 79)))
    for t, g, chord in hits:
        for m in chord:
            add_at(out, brass(hz(m), g, 0.14 if g > 0.3 else 0.07, 1.0,
                              vib=0.004 if g > 0.3 else 0.0, rng=rng), ns(t), 0.24)
    for t, g, m in ((0.0, 0.1, 48), (0.11, 0.1, 41), (0.22, 0.55, 36)):
        add_at(out, brass(hz(m), g, 0.12, 0.5, rng=rng), ns(t), 0.2)
    for t, m, g in ((0.0, 76, 0.1), (0.11, 77, 0.1), (0.22, 84, 0.5)):     # steel pan on top
        add_at(out, ins_steelpan(m, g), ns(t), 0.38)
    add_at(out, thump(0.45, 130.0, 72.0, 0.05, 0.14), ns(0.22), 0.35)
    cn = ns(0.7)
    add_at(out, vmul(noise_band(cn, rng, 5500.0, 0.6, 'hp'), env_ad(cn, 0.003, 0.22)), ns(0.22), 0.1)
    twinkles(out, rng, 6, 0.3, 0.9, 3000.0, 6500.0, 0.08)
    return finish_sfx(out, 0.003, 0.18)


# ---- race flow --------------------------------------------------------------

def sfx_countdown():
    env = env_adsr(0.12, 0.004, 0.25, 0.85, 0.05)
    w = TAU * 880.0 / SR                                                   # A5
    out = [e * (sin(w * i) + 0.15 * sin(2 * w * i) + 0.05 * sin(3 * w * i))
           for i, e in enumerate(env)]
    add_at(out, ins_marimba(81), 0, 0.25)                                  # woody onset
    return finish_sfx(out, 0.003, 0.012)


def sfx_go():
    rng = rng_for('sfx_go')
    n = ns(1.15)
    out = zeros(n)
    env = env_adsr(0.30, 0.003, 0.25, 0.8, 0.15)                           # A6 beep (octave
    w = TAU * 1760.0 / SR                                                  # above countdown)
    add_at(out, [e * (sin(w * i) + 0.18 * sin(2 * w * i)) for i, e in enumerate(env)], 0, 0.22)
    for m in (57, 64, 69, 73, 76):                                         # big A-major horn hit
        add_at(out, brass(hz(m), 0.42, 0.25, 1.1, vib=0.005, rng=rng), 0, 0.21)
    add_at(out, brass(hz(45), 0.42, 0.2, 0.6, rng=rng), 0, 0.18)
    add_at(out, splash_layer(rng, 1.1, 1.0, 46, 14), ns(0.01), 0.6)        # SPLASH
    k = ns(0.35)
    fc = sweep_exp(k, 700.0, 5000.0)
    add_at(out, vmul(svf(white(k, rng), fc, 1.2, 'bp'), env_hump(k, 0.7, 1.5, 1.2)), 0, 0.25)
    add_at(out, thump(0.4, 120.0, 50.0, 0.04, 0.12), 0, 0.45)
    return finish_sfx(out, 0.003, 0.15)


# ---- footsteps --------------------------------------------------------------

def _vinyl_step(name, f_thud, sq0, sq1, sq_amt, rub_fc):
    rng = rng_for(name)
    n = ns(0.15)
    out = zeros(n)
    add_at(out, thump(0.13, f_thud * 1.7, f_thud, 0.010, 0.032), 0, 1.0)   # inflated "thup"
    add_at(out, modal(1.0, 0.07, [(f_thud * 2.7, 1.0, 0.012), (f_thud * 5.6, 0.4, 0.007)]), 0, 0.4)
    m = ns(0.06)
    add_at(out, svf(vmul(white(m, rng), env_ad(m, 0.001, 0.010)), 600.0, 1.1, 'bp'), 0, 0.55)
    k = ns(0.055)                                                          # vinyl squeak
    kv = TAU * 48.0 / SR
    fr = [v * (1.0 + 0.025 * sin(kv * i)) for i, v in enumerate(sweep_exp(k, sq0, sq1))]
    env = env_hump(k, 0.35, 1.5, 2.0)
    sq = [e * (sin(q) + 0.35 * sin(2 * q) + 0.12 * sin(3 * q)) for q, e in zip(phases(fr), env)]
    add_at(out, sq, ns(0.014), sq_amt)
    add_at(out, vmul(svf(white(k, rng), rub_fc, 2.0, 'bp'), env), ns(0.014), sq_amt * 0.4)
    return finish_sfx(out, 0.003, 0.02)


def sfx_step_vinyl1():
    return _vinyl_step('sfx_step_vinyl1', 95.0, 1150.0, 1500.0, 0.2, 2200.0)


def sfx_step_vinyl2():
    return _vinyl_step('sfx_step_vinyl2', 112.0, 1520.0, 1240.0, 0.18, 2600.0)


def sfx_step_wood():
    rng = rng_for('sfx_step_wood')
    out = modal(1.0, 0.1, [(310.0, 1.0, 0.022), (720.0, 0.55, 0.014), (1350.0, 0.3, 0.008),
                           (2480.0, 0.12, 0.005)])
    add_at(out, thump(0.08, 170.0, 95.0, 0.01, 0.018), 0, 0.5)
    m = ns(0.012)
    add_at(out, svf(vmul(white(m, rng), env_exp(m, 0.0015)), 2200.0, 0.8, 'bp'), 0, 0.35)
    return finish_sfx(out, 0.003, 0.015)


def sfx_step_stone():
    rng = rng_for('sfx_step_stone')
    n = ns(0.1)
    out = vscale(svf(vmul(white(n, rng), env_ad(n, 0.001, 0.014)), 1700.0, 0.7, 'bp'), 0.8)
    add_at(out, svf(grains(n, rng, 30, 0.0, 0.05, 1.4, (4, 14)), 3800.0, 0.9, 'bp'), 0, 0.6)
    add_at(out, thump(0.07, 140.0, 85.0, 0.008, 0.015), 0, 0.6)
    return finish_sfx(out, 0.003, 0.015)


def sfx_step_ice():
    rng = rng_for('sfx_step_ice')
    n = ns(0.11)
    out = filt(svf(grains(n, rng, 26, 0.0, 0.05, 1.5, (3, 12)), 4800.0, 1.0, 'bp'), 'hp', 2000.0)
    add_at(out, modal(1.0, 0.09, [(3150.0, 1.0, 0.018), (4870.0, 0.6, 0.012), (6900.0, 0.3, 0.007)]), 0, 0.18)
    add_at(out, thump(0.06, 160.0, 100.0, 0.008, 0.012), 0, 0.35)
    m = ns(0.006)
    add_at(out, filt(vmul(white(m, rng), env_exp(m, 0.0008)), 'hp', 3000.0), 0, 0.4)
    return finish_sfx(out, 0.003, 0.015)


# ---- hits, bounces, toys ----------------------------------------------------

def sfx_bonk():
    rng = rng_for('sfx_bonk')
    n = ns(0.42)
    out = zeros(n)
    m = ns(0.04)
    add_at(out, svf(vmul(white(m, rng), env_ad(m, 0.0005, 0.007)), 1400.0, 0.7, 'bp'), 0, 1.0)  # thwap
    add_at(out, thump(0.16, 160.0, 80.0, 0.02, 0.045), 0, 0.6)
    fr = glide(n, 520.0, 160.0, 0.05)                                     # rubbery boing drop
    kv = TAU * 15.0 / SR
    fr = [f * (1.0 + 0.04 * sin(kv * i) * (1.0 - o)) for i, (f, o) in enumerate(zip(fr, env_exp(n, 0.03)))]
    env = env_ad(n, 0.002, 0.12)
    add_at(out, end_fade([e * (sin(p) + 0.4 * sin(2.0 * p) + 0.12 * sin(3.0 * p))
                          for p, e in zip(phases(fr), env)]), 0, 0.8)
    return finish_sfx(sat(normalize(out, 0.0), 1.4), 0.003, 0.04)


def sfx_squeak():
    rng = rng_for('sfx_squeak')
    n = ns(0.32)
    h = env_hump(n, 0.35, 1.3, 1.6)
    kw = TAU * 9.0 / SR
    fr = [950.0 * (1.0 + 0.4 * v) * (1.0 + 0.015 * sin(kw * i)) for i, v in enumerate(h)]
    env = env_hump(n, 0.2, 1.0, 1.8)
    out = squeak_voice(fr, env, rng, ((2600.0, 3.0, 1.0), (1300.0, 2.0, 0.5)), 'pulse', 0.6)
    add_at(out, vmul(noise_band(n, rng, 3000.0, 1.0), env), 0, 0.06)     # air through the hole
    return finish_sfx(out, 0.004, 0.03)


def sfx_boing():
    rng = rng_for('sfx_boing')
    out = boing_voice(0.6, 175.0, 300.0, 14.0, 0.08, 0.18, 500.0, 2600.0, 0.06)
    k = ns(0.04)                                                          # spring "twang" chirp
    add_at(out, end_fade(vmul(osc_sine(sweep_exp(k, 3200.0, 900.0)), env_ad(k, 0.001, 0.012))), 0, 0.2)
    add_at(out, thump(0.1, 180.0, 110.0, 0.01, 0.025), 0, 0.35)
    add_at(out, click(ns(0.01), rng, 2000.0, 0.001, 0.8), 0, 0.2)
    return finish_sfx(out, 0.003, 0.05)


def sfx_trampoline():
    rng = rng_for('sfx_trampoline')
    n = ns(0.85)
    out = zeros(n)
    add_at(out, thump(0.4, 80.0, 44.0, 0.04, 0.12), 0, 1.0)              # membrane "whomp"
    m = ns(0.12)
    add_at(out, filt(vmul(white(m, rng), env_ad(m, 0.002, 0.025)), 'lp', 600.0), 0, 0.7)
    add_at(out, boing_voice(0.85, 95.0, 152.0, 8.0, 0.07, 0.28, 300.0, 1400.0, 0.08), ns(0.01), 0.9)
    return finish_sfx(sat(normalize(out, 0.0), 1.3), 0.003, 0.08)


def sfx_duck_quack():
    rng = rng_for('sfx_duck_quack')
    n = ns(0.55)
    out = zeros(n)
    add_at(out, thump(0.12, 160.0, 95.0, 0.015, 0.03), 0, 0.35)          # squeeze
    k = ns(0.48)
    h = env_hump(k, 0.3, 1.2, 1.6)
    kw = TAU * 11.0 / SR
    fr = [(380.0 + 230.0 * v) * (1.0 - 0.15 * (i / k) ** 2) * (1.0 + 0.02 * sin(kw * i))
          for i, v in enumerate(h)]
    env = env_hump(k, 0.16, 0.9, 1.8)
    honk = squeak_voice(fr, env, rng, ((1050.0, 3.0, 1.0), (2400.0, 4.0, 0.6), (650.0, 1.5, 0.3)),
                        'reed', 0.35, rasp=0.28, jit=0.015)
    add_at(out, honk, ns(0.02), 1.0)
    add_at(out, vmul(noise_band(k, rng, 3000.0, 1.0), env), ns(0.02), 0.05)
    return finish_sfx(out, 0.004, 0.04)


# ---- traversal ----------------------------------------------------------------

def sfx_zip_loop():
    """1.5 s seamless zipline: trolley-wheel whine (phase-modulated harmonic
    cluster with whole cycles per loop), inharmonic cable 'zing', hiss,
    rumble and rattles -- all periodic by construction."""
    rng = rng_for('sfx_zip_loop')
    L = ns(1.5)
    out = zeros(L)
    kc, kv = round(640.0 * L / SR), 6
    for h, a in ((1, 1.0), (2, 0.55), (3, 0.3), (4, 0.18), (6, 0.08)):
        w, wv, beta = TAU * kc * h / L, TAU * kv / L, 1.28 * h
        ph0 = rng.random() * TAU
        add_at(out, [sin(ph0 + w * i + beta * sin(wv * i)) for i in range(L)], 0, 0.13 * a)
    for f, a, c, d in ((1480.0, 0.5, 3, 0.4), (2230.0, 0.35, 5, 0.5), (3370.0, 0.25, 4, 0.5), (4410.0, 0.15, 7, 0.6)):
        kf = round(f * L / SR)
        w, wa, ph0 = TAU * kf / L, TAU * c / L, rng.random() * TAU
        add_at(out, [sin(ph0 + w * i) * (1.0 - d + d * (0.5 + 0.5 * sin(wa * i))) for i in range(L)], 0, 0.1 * a)
    fl = [0.75 + 0.15 * sin(TAU * 9 * i / L) + 0.1 * sin(TAU * 23 * i / L + 1.0) for i in range(L)]
    hiss = circular(white(L, rng), lambda x: svf(x, 5200.0, 0.8, 'bp'), ns(0.05))
    add_at(out, vmul(hiss, fl), 0, 0.3)
    rum = circular(white(L, rng), lambda x: filt(filt(x, 'lp', 170.0, 0.8), 'hp', 50.0), ns(0.3))
    add_at(out, vmul(rum, fl), 0, 0.45)
    exc = zeros(L)
    r = rng.random
    t = 0.0
    while True:
        t += -math.log(1.0 - r()) / 45.0
        if t >= 1.5:
            break
        glen = rng.randint(6, 20)
        add_wrap(exc, [(0.3 + 0.7 * r()) * (2.0 * r() - 1.0) * (1.0 - j / glen) ** 2 for j in range(glen)], ns(t))
    add_at(out, circular(exc, lambda x: svf(x, 2600.0, 1.5, 'bp'), ns(0.05)), 0, 0.5)
    out = sat(normalize(out, 0.0), 1.5)          # memoryless -> loop stays seamless
    return finish_loop(out)


def sfx_slide_loop():
    """2 s seamless water-slide rush: filtered water noise with periodic
    swells, spray hiss, and a stream of wrapped bubbles."""
    rng = rng_for('sfx_slide_loop')
    L = ns(2.0)

    def lfo(c, ph=0.0):
        return [sin(TAU * c * i / L + ph) for i in range(L)]

    mod = [0.72 + 0.14 * a + 0.09 * b + 0.05 * c for a, b, c in zip(lfo(2, 0.3), lfo(5, 1.7), lfo(11, 2.9))]
    fl = [0.7 + 0.12 * a + 0.1 * b + 0.08 * c for a, b, c in zip(lfo(13, 0.5), lfo(17, 2.2), lfo(29, 4.0))]
    rush = circular(white(L, rng), lambda x: svf(filt(x, 'lp', 1500.0), 560.0, 0.6, 'bp'), ns(0.2))
    out = vmul(rush, mod)
    hiss = circular(white(L, rng), lambda x: filt(filt(x, 'hp', 2400.0), 'lp', 7000.0), ns(0.1))
    add_at(out, vmul(hiss, fl), 0, 0.22)
    low = circular(white(L, rng), lambda x: filt(filt(x, 'lp', 220.0), 'lp', 220.0), ns(0.4))
    add_at(out, vmul(low, mod), 0, 1.0)
    bub = zeros(L)
    bubbles(bub, rng, 140, 0.0, 2.0, 450.0, 2600.0, 1.0, wrap=True)
    bubbles(bub, rng, 14, 0.0, 2.0, 180.0, 420.0, 1.2, wrap=True)
    add_at(out, bub, 0, 0.32)
    out = sat(normalize(out, 0.0), 1.4)
    return finish_loop(out)


def sfx_rope_creak():
    rng = rng_for('sfx_rope_creak')
    n = ns(0.75)
    out = zeros(n)
    c1 = creak(0.42, rng, 28.0, 70.0, ((340.0, 14.0, 1.0), (820.0, 11.0, 0.6), (1650.0, 8.0, 0.3)))
    add_at(out, vmul(c1, env_hump(len(c1), 0.55, 1.4, 1.6)), 0, 1.0)
    c2 = creak(0.26, rng, 60.0, 38.0, ((390.0, 13.0, 1.0), (900.0, 10.0, 0.5), (1800.0, 8.0, 0.25)))
    add_at(out, vmul(c2, env_hump(len(c2), 0.4, 1.3, 1.8)), ns(0.46), 0.6)
    add_at(out, vmul(noise_band(n, rng, 2500.0, 1.2), env_hump(n, 0.4, 1.5, 1.5)), 0, 0.04)   # fibres
    return finish_sfx(sat(normalize(out, 0.0), 2.5), 0.004, 0.04)


def sfx_climb():
    rng = rng_for('sfx_climb')
    n = ns(0.2)
    out = zeros(n)
    m = ns(0.05)
    add_at(out, vmul(svf(white(m, rng), sweep_exp(m, 1200.0, 3200.0), 1.2, 'bp'), env_hump(m, 0.6, 1.5, 1.5)), 0, 0.35)
    add_at(out, modal(1.0, 0.1, [(820.0, 1.0, 0.016), (1930.0, 0.6, 0.01), (3400.0, 0.3, 0.006)]), ns(0.04), 0.7)
    add_at(out, thump(0.06, 210.0, 130.0, 0.008, 0.014), ns(0.04), 0.5)
    k = ns(0.05)                                                          # rubber grip squeak
    kv = TAU * 40.0 / SR
    fr = [v * (1.0 + 0.02 * sin(kv * i)) for i, v in enumerate(sweep_exp(k, 1700.0, 2100.0))]
    add_at(out, vmul(osc_sine(fr), env_hump(k, 0.4, 1.5, 2.0)), ns(0.065), 0.12)
    return finish_sfx(out, 0.003, 0.02)


# ---- water ------------------------------------------------------------------

def sfx_splash_big():
    rng = rng_for('sfx_splash_big')
    out = splash_layer(rng, 1.15, 1.0, 48, 16)
    bubbles(out, rng, 3, 0.08, 0.25, 150.0, 300.0, 0.5)                   # cavity "glug"
    return finish_sfx(out, 0.003, 0.15)


def sfx_splash_small():
    rng = rng_for('sfx_splash_small')
    out = splash_layer(rng, 0.45, 0.25, 14, 5)
    return finish_sfx(out, 0.003, 0.06)


def sfx_wave_surge():
    rng = rng_for('sfx_wave_surge')
    n = ns(1.25)
    crest = ns(0.6)
    fc = sweep_exp(crest, 180.0, 2400.0) + sweep_exp(n - crest, 2400.0, 900.0)
    out = vmul(svf(white(n, rng), fc, 0.7, 'lp'), env_hump(n, 0.48, 1.8, 1.4))
    k = n - crest + ns(0.05)
    wash = filt(vmul(white(k, rng), env_ad(k, 0.03, 0.25)), 'hp', 1800.0)
    add_at(out, wash, crest - ns(0.05), 0.35)
    add_at(out, thump(0.4, 90.0, 45.0, 0.05, 0.12), crest - ns(0.04), 0.5)
    bubbles(out, rng, 60, 0.55, 1.15, 1200.0, 4000.0, 0.12, shape=1.4)   # fizz
    return finish_sfx(out, 0.006, 0.12)


def sfx_tide_loop():
    """2 s seamless low lagoon rumble: one slow swell per loop, distant
    wash, low glugs and faint fizz (wrapped)."""
    rng = rng_for('sfx_tide_loop')
    L = ns(2.0)
    sw = [0.62 + 0.38 * (0.5 - 0.5 * cos(TAU * i / L)) for i in range(L)]
    sw2 = [0.5 + 0.5 * (0.5 - 0.5 * cos(TAU * i / L - 0.9)) for i in range(L)]
    rum = circular(white(L, rng), lambda x: filt(filt(filt(x, 'lp', 260.0), 'lp', 260.0), 'hp', 40.0), ns(0.4))
    out = vmul(rum, sw)
    wash = circular(white(L, rng), lambda x: svf(x, 700.0, 0.6, 'bp'), ns(0.1))
    add_at(out, vmul(wash, [v * v for v in sw2]), 0, 0.12)
    hiss = circular(white(L, rng), lambda x: filt(x, 'hp', 3000.0), ns(0.05))
    add_at(out, vmul(hiss, [v ** 3 for v in sw2]), 0, 0.02)
    bub = zeros(L)
    bubbles(bub, rng, 10, 0.0, 2.0, 140.0, 380.0, 1.0, wrap=True)
    bubbles(bub, rng, 25, 0.0, 2.0, 900.0, 2200.0, 0.3, wrap=True)
    add_at(out, bub, 0, 0.12)
    out = sat(normalize(out, 0.0), 1.3)
    return finish_loop(out)


def sfx_paddle():
    rng = rng_for('sfx_paddle')
    n = ns(0.8)
    out = zeros(n)
    add_at(out, splash_layer(rng, 0.5, 0.45, 14, 4), 0, 0.8)               # paddle slaps water
    k = ns(0.7)
    kw = TAU * 5.5 / SR
    fc = [700.0 + 500.0 * sin(kw * i) * exp(-i / (0.35 * SR)) + 300.0 * exp(-i / (0.2 * SR)) for i in range(k)]
    add_at(out, vmul(svf(white(k, rng), fc, 1.4, 'lp'), env_hump(k, 0.25, 1.4, 1.8)), ns(0.05), 0.9)   # slosh
    bubbles(out, rng, 18, 0.25, 0.75, 500.0, 2200.0, 0.25)                  # pour-off
    cr = creak(0.2, rng, 45.0, 30.0, ((300.0, 8.0, 1.0), (700.0, 6.0, 0.5)))
    add_at(out, vmul(cr, env_hump(len(cr), 0.5, 1.5, 1.5)), ns(0.02), 0.1)   # axle
    return finish_sfx(out, 0.003, 0.08)


# ---- obstacles --------------------------------------------------------------

def sfx_wind_gust():
    rng = rng_for('sfx_wind_gust')
    n = ns(1.25)
    env = env_hump(n, 0.38, 1.5, 1.7)
    a = ns(0.48)
    fc = sweep_exp(a, 320.0, 1050.0) + sweep_exp(n - a, 1050.0, 420.0)
    k1, k2 = TAU * 17.0 / SR, TAU * 0.9 / SR
    flutter = [1.0 + 0.18 * sin(k1 * i) * sin(k2 * i) for i in range(n)]   # fan-blade chop
    out = svf(white(n, rng), fc, 1.0, 'bp')
    add_at(out, filt(filt(white(n, rng), 'lp', 260.0), 'lp', 260.0), 0, 1.6)
    add_at(out, svf(white(n, rng), [2.3 * v for v in fc], 7.0, 'bp'), 0, 0.25)
    add_at(out, filt(white(n, rng), 'hp', 3500.0), 0, 0.08)
    out = vmul(vmul(out, env), flutter)
    return finish_sfx(out, 0.006, 0.08)


def sfx_cannon_pop():
    rng = rng_for('sfx_cannon_pop')
    n = ns(0.5)
    out = zeros(n)
    add_at(out, thump(0.35, 140.0, 48.0, 0.025, 0.08), 0, 1.0)
    m = ns(0.12)
    add_at(out, filt(vmul(white(m, rng), env_ad(m, 0.002, 0.025)), 'lp', 900.0), 0, 1.2)  # air burst
    add_at(out, blip(0.18, 300.0, 210.0, 0.03, 0.045, h2=0.3), 0, 0.6)                   # "foomp"
    k = ns(0.25)
    add_at(out, svf(vmul(white(k, rng), env_ad(k, 0.01, 0.06)), 1500.0, 0.8, 'bp'), ns(0.02), 0.2)
    return finish_sfx(sat(normalize(out, 0.0), 1.4), 0.003, 0.05)


def sfx_glove_punch():
    rng = rng_for('sfx_glove_punch')
    n = ns(0.55)
    out = zeros(n)
    add_at(out, boing_voice(0.5, 220.0, 520.0, 22.0, 0.06, 0.12, 700.0, 3000.0, 0.03), 0, 0.35)
    m = ns(0.1)
    add_at(out, vmul(svf(white(m, rng), sweep_exp(m, 600.0, 2400.0), 1.0, 'bp'), env_hump(m, 0.7, 1.5, 1.0)), 0, 0.4)
    t = ns(0.085)
    add_at(out, thump(0.25, 150.0, 62.0, 0.02, 0.06), t, 1.0)
    k = ns(0.05)
    add_at(out, svf(vmul(white(k, rng), env_ad(k, 0.0006, 0.01)), 900.0, 0.8, 'bp'), t, 0.8)   # leather
    add_at(out, filt(vmul(white(k, rng), env_ad(k, 0.002, 0.015)), 'lp', 500.0), t, 0.6)
    return finish_sfx(sat(normalize(out, 0.0), 1.3), 0.003, 0.06)


def sfx_press_thump():
    rng = rng_for('sfx_press_thump')
    n = ns(0.65)
    out = zeros(n)
    add_at(out, thump(0.6, 95.0, 42.0, 0.05, 0.16), 0, 1.0)
    m = ns(0.25)
    add_at(out, filt(vmul(white(m, rng), env_ad(m, 0.003, 0.06)), 'lp', 350.0), 0, 1.4)  # padded fwump
    add_at(out, modal(220.0, 0.15, [(1, 1.0, 0.03), (2.1, 0.4, 0.015)]), 0, 0.35)
    k = ns(0.18)
    add_at(out, vmul(noise_band(k, rng, 700.0, 1.0), env_hump(k, 0.3, 1.5, 2.0)), ns(0.03), 0.12)  # air squish
    return finish_sfx(sat(normalize(out, 0.0), 1.5), 0.003, 0.08)


def sfx_trapdoor():
    rng = rng_for('sfx_trapdoor')
    out = zeros(ns(0.3))
    clack = [(720.0, 1.0, 0.02), (1650.0, 0.7, 0.012), (2900.0, 0.4, 0.007), (4300.0, 0.2, 0.004)]
    for t, g in ((0.0, 1.0), (0.045, 0.55)):
        add_at(out, modal(1.0, 0.12, clack), ns(t), 0.6 * g)
        add_at(out, click(ns(0.01), rng, 3000.0, 0.0008, 0.9), ns(t), 0.5 * g)
    add_at(out, thump(0.12, 140.0, 90.0, 0.01, 0.03), 0, 0.4)
    add_at(out, grains(ns(0.3), rng, 6, 0.07, 0.2, 1.0, (6, 18)), 0, 0.15)
    return finish_sfx(out, 0.003, 0.03)


def sfx_gate():
    rng = rng_for('sfx_gate')
    n = ns(0.72)
    out = zeros(n)
    m = ns(0.42)
    add_at(out, vmul(svf(white(m, rng), sweep_exp(m, 400.0, 1600.0), 1.0, 'bp'), env_hump(m, 0.6, 1.5, 1.4)), 0, 0.6)
    add_at(out, vmul(filt(filt(white(m, rng), 'lp', 200.0), 'lp', 200.0), env_hump(m, 0.6, 1.5, 1.4)), 0, 1.2)
    t = ns(0.42)
    add_at(out, thump(0.3, 160.0, 70.0, 0.02, 0.07), t, 0.9)
    add_at(out, modal(1.0, 0.2, [(310.0, 1.0, 0.05), (780.0, 0.5, 0.03), (1450.0, 0.25, 0.015)]), t, 0.5)
    add_at(out, click(ns(0.01), rng, 2500.0, 0.001, 0.8), t, 0.4)
    return finish_sfx(out, 0.004, 0.06)


# ---- feedback stingers ---------------------------------------------------------

def sfx_checkpoint():
    rng = rng_for('sfx_checkpoint')
    n = ns(0.95)
    out = zeros(n)
    for k, m in enumerate((79, 84, 88)):                                   # G5 C6 E6
        last = k == 2
        add_at(out, ins_steelpan(m, 0.5 if last else 0.2), ns(0.07 * k), 0.5)
        add_at(out, chime(hz(m + 12), 0.6 if last else 0.25, 0.8 if last else 0.3), ns(0.07 * k), 0.2)
    k = ns(0.25)
    kf = TAU * 22.0 / SR
    flap = vmul(vmul(noise_band(k, rng, 1400.0, 1.0), [0.5 + 0.5 * sin(kf * i) for i in range(k)]),
                env_hump(k, 0.3, 1.5, 2.0))
    add_at(out, flap, 0, 0.12)                                             # flag flutter
    twinkles(out, rng, 7, 0.15, 0.7, 3000.0, 7000.0, 0.08)
    return finish_sfx(out, 0.003, 0.15)


def sfx_nice():
    rng = rng_for('sfx_nice')
    out = zeros(ns(0.6))
    add_at(out, ins_steelpan(81, 0.1), 0, 0.5)                             # A5 ...
    for m in (86, 90):                                                     # ... D6+F#6
        add_at(out, ins_steelpan(m, 0.3), ns(0.09), 0.38)
    add_at(out, ins_marimba(62), ns(0.09), 0.3)
    add_at(out, chime(hz(98), 0.3, 0.5), ns(0.09), 0.12)
    twinkles(out, rng, 4, 0.12, 0.4, 3500.0, 7000.0, 0.07)
    return finish_sfx(out, 0.003, 0.1)


def sfx_perfect():
    rng = rng_for('sfx_perfect')
    n = ns(1.15)
    out = zeros(n)
    for k, m in enumerate((72, 76, 79, 84)):                               # C5 E5 G5 C6 run
        add_at(out, ins_steelpan(m, 0.12), ns(0.045 * k), 0.38)
    t = ns(0.2)
    for m in (84, 88, 91):                                                 # C6 E6 G6 chord
        add_at(out, ins_steelpan(m, 0.5), t, 0.32)
    for k, m in enumerate((96, 100, 103)):                                 # C7 E7 G7 glock
        add_at(out, chime(hz(m), 0.6, 0.8), t + ns(0.04 * k), 0.16)
    m = ns(0.25)
    add_at(out, vmul(svf(white(m, rng), sweep_exp(m, 800.0, 6000.0), 1.4, 'bp'), env_hump(m, 0.8, 1.5, 1.0)), 0, 0.2)
    dn = n - t
    k1 = TAU * 12.0 / SR
    shim = vmul(vmul(noise_band(dn, rng, 7000.0, 1.5), [0.6 + 0.4 * sin(k1 * i) for i in range(dn)]),
                env_hump(dn, 0.25, 1.5, 2.0))
    add_at(out, shim, t, 0.08)
    twinkles(out, rng, 12, 0.22, 1.0, 3000.0, 7500.0, 0.09, 0.04)
    return finish_sfx(out, 0.003, 0.2)


def sfx_turbo():
    rng = rng_for('sfx_turbo')
    n = ns(0.9)
    m = ns(0.55)
    fc = sweep_exp(m, 300.0, 3800.0) + [3800.0] * (n - m)
    env = env_hump(n, 0.45, 1.4, 1.8)
    out = vmul(svf(white(n, rng), fc, 1.4, 'bp'), env)                     # jet
    add_at(out, vmul(filt(filt(white(n, rng), 'lp', 500.0), 'lp', 500.0), env_ad(n, 0.01, 0.35)), 0, 1.4)
    tf = sweep_exp(m, 110.0, 660.0) + [660.0] * (n - m)
    tonal = svf(osc_wt(wavetable('saw', 700.0), tf), 2400.0, 0.7, 'lp')
    add_at(out, vmul(tonal, env), 0, 0.25)
    add_at(out, vmul(osc_wt(wavetable('saw', 1000.0), [1.5 * v for v in tf]), env), 0, 0.06)
    add_at(out, vmul(svf(grains(n, rng, 70, 0.0, 0.8, 1.0, (4, 12), False), 3000.0, 1.0, 'bp'), env), 0, 0.5)
    add_at(out, thump(0.2, 140.0, 60.0, 0.02, 0.05), 0, 0.5)
    twinkles(out, rng, 4, 0.5, 0.8, 4000.0, 7500.0, 0.06)
    return finish_sfx(out, 0.004, 0.1)


def sfx_whoosh():
    rng = rng_for('sfx_whoosh')
    n = ns(0.32)
    k = ns(0.13)
    fc = sweep_exp(k, 500.0, 2600.0) + sweep_exp(n - k, 2600.0, 850.0)
    env = env_hump(n, 0.42, 1.8, 1.6)
    out = vmul(svf(white(n, rng), fc, 1.0, 'bp'), env)
    add_at(out, vmul(filt(white(n, rng), 'hp', 3500.0), env), 0, 0.12)
    return finish_sfx(out, 0.004, 0.04)


def sfx_pop():
    rng = rng_for('sfx_pop')
    out = blip(0.08, 380.0, 1200.0, 0.009, 0.018, att=0.0015, h2=0.15)
    add_at(out, modal(220.0, 0.03, [(1, 1.0, 0.008)]), 0, 0.3)
    add_at(out, click(ns(0.01), rng, 2500.0, 0.001, 0.7), 0, 0.4)
    return finish_sfx(out, 0.003, 0.012)


def sfx_powerup():
    rng = rng_for('sfx_powerup')
    n = ns(0.62)
    out = zeros(n)
    notes = (79, 83, 86, 91, 95, 98)                                       # G5 B5 D6 G6 B6 D7
    for k, m in enumerate(notes):
        f = hz(m)
        last = k == len(notes) - 1
        q = ns(0.22 if last else 0.09)
        freqs = glide(q, f * 0.965, f, 0.012)
        env = env_ad(q, 0.002, 0.09 if last else 0.035)
        sq = svf(osc_wt(wavetable('square', f), freqs), min(9000.0, f * 4.0), 0.7, 'lp')
        add_at(out, end_fade([e * (0.55 * a + 0.45 * sin(p)) for a, e, p in zip(sq, env, phases(freqs))]),
               ns(0.042 * k), 0.45)
    m = ns(0.3)                                                            # charging sweep under it
    add_at(out, vmul(osc_sine(sweep_exp(m, 200.0, 800.0)), env_hump(m, 0.8, 1.5, 1.0)), 0, 0.25)
    add_at(out, chime(hz(103), 0.3, 0.6), ns(0.25), 0.16)
    twinkles(out, rng, 7, 0.22, 0.5, 3500.0, 7500.0, 0.08)
    return finish_sfx(out, 0.003, 0.08)


def sfx_shield_pop():
    rng = rng_for('sfx_shield_pop')
    n = ns(0.5)
    out = zeros(n)
    m = ns(0.004)
    add_at(out, filt(vmul(white(m, rng), env_exp(m, 0.0006)), 'hp', 2000.0), 0, 0.45)  # film snaps
    add_at(out, modal(1.0, 0.05, [(4200.0, 1.0, 0.006), (2600.0, 0.5, 0.008)]), 0, 0.5)
    add_at(out, blip(0.06, 900.0, 2400.0, 0.006, 0.012), 0, 0.5)
    k = ns(0.12)
    add_at(out, filt(vmul(white(k, rng), env_ad(k, 0.002, 0.02)), 'lp', 1200.0), 0, 0.6)   # soft "fwup"
    k = ns(0.3)
    kt = TAU * 23.0 / SR
    add_at(out, vmul(osc_sine(sweep_exp(k, 3000.0, 900.0)),
                     vmul(env_ad(k, 0.004, 0.08), [0.6 + 0.4 * sin(kt * i) for i in range(k)])), ns(0.01), 0.3)
    twinkles(out, rng, 10, 0.02, 0.32, 2500.0, 6000.0, 0.35, 0.012)       # droplets
    return finish_sfx(out, 0.003, 0.06)


# ---- crowd / results ---------------------------------------------------------

def sfx_cheer():
    rng = rng_for('sfx_cheer')
    n = ns(2.05)
    out = zeros(n)
    a0, s1, s2 = ns(0.18), ns(1.1), ns(0.95)
    cenv = [min(1.0, i / a0) ** 1.5 * max(0.0, 1.0 if i < s1 else 1.0 - (i - s1) / s2) ** 1.3 for i in range(n)]
    for sec in range(6):                                                   # crowd sections: noise
        a1, a2 = 430.0 + 120.0 * rng.random(), 640.0 + 160.0 * rng.random()  # through "ay -> aah"
        b1, b2 = 1750.0 + 350.0 * rng.random(), 1100.0 + 250.0 * rng.random()  # formant glides
        src = white(n, rng)
        sect = svf(src, sweep_exp(n, a1, a2), 3.5, 'bp')
        add_at(sect, svf(src, sweep_exp(n, b1, b2), 4.5, 'bp'), 0, 0.6)
        am = onepole_lp(onepole_lp(white(n, rng), 7.0), 7.0)              # syllabic swells
        ap = peak(am) or 1.0
        add_at(out, vmul(vmul(sect, cenv), [0.65 + 0.35 * v / ap for v in am]), 0, 0.42)
    add_at(out, vmul(svf(white(n, rng), 2800.0, 2.0, 'bp'), cenv), 0, 0.08)   # breathy top
    for _ in range(16):                                                    # individual yells
        t = 0.05 + 1.25 * rng.random()
        k = ns(0.2 + 0.2 * rng.random())
        fc = 600.0 * 2.3 ** rng.random()
        y = vmul(svf(white(k, rng), sweep_exp(k, fc * 0.85, fc * 1.15), 5.0, 'bp'), env_hump(k, 0.3, 1.5, 1.5))
        add_at(out, y, ns(t), 0.25 + 0.2 * rng.random())
    claps = zeros(n)
    r = rng.random
    t = 0.0
    while True:                                                            # applause
        t += -math.log(1.0 - r()) / 170.0
        if t > 1.8:
            break
        k = ns(0.012)
        c = svf(vmul(white(k, rng), env_exp(k, 0.0025 + 0.002 * r())), 900.0 * 2.8 ** r(), 1.0 + r(), 'bp')
        add_at(claps, c, ns(t), 0.4 + 0.6 * r())
    add_at(out, vmul(claps, cenv), 0, 1.1)
    for t0, f0, a in ((0.25, 1900.0, 0.14), (0.9, 2200.0, 0.1)):           # finger whistles
        k = ns(0.45)
        h = env_hump(k, 0.3, 1.0, 1.0)
        fr = [f0 * (1.0 + 0.4 * v - 0.12 * (i / k)) for i, v in enumerate(h)]
        add_at(out, vmul(osc_sine(fr), env_hump(k, 0.15, 1.0, 1.5)), ns(t0), a)
    return finish_sfx(out, 0.006, 0.2)


def sfx_win_fanfare():
    rng = rng_for('sfx_win_fanfare')
    n = ns(2.6)
    out = zeros(n)
    for k, m in enumerate((67, 72, 76, 79)):                               # pan pickup G4 C5 E5 G5
        add_at(out, ins_steelpan(m, 0.12), ns(0.1 * k), 0.42)
    hits = ((0.42, 0.14, (69, 72, 77)), (0.62, 0.14, (71, 74, 79)), (0.84, 1.15, (72, 76, 79, 84)))
    for t, g, chord in hits:                                               # brass F - G - C
        big = g > 0.5
        for m in chord:
            add_at(out, brass(hz(m), g, 0.3 if big else 0.08, 1.0, vib=0.005 if big else 0.0, rng=rng),
                   ns(t), 0.21)
    for t, g, m in ((0.42, 0.14, 41), (0.62, 0.14, 43), (0.84, 1.15, 36)):
        add_at(out, brass(hz(m), g, 0.25, 0.5, rng=rng), ns(t), 0.22)
        add_at(out, thump(0.5, 140.0, 75.0, 0.05, 0.16), ns(t), 0.28 if g < 0.5 else 0.42)
    for k in range(8):                                                     # snare roll into the hit
        m = ns(0.05)
        hit = svf(vmul(white(m, rng), env_exp(m, 0.012)), 1800.0, 0.6, 'hp')
        add_at(out, end_fade(hit), ns(0.62 + 0.0275 * k), 0.10 + 0.03 * k)
    cn = ns(1.5)
    add_at(out, end_fade(vmul(filt(white(cn, rng), 'hp', 4200.0), env_ad(cn, 0.003, 0.55))), ns(0.84), 0.2)
    for k, m in enumerate((84, 88, 91, 96)):                               # pan arpeggio on the hit
        add_at(out, ins_steelpan(m, 0.15), ns(0.84 + 0.045 * k), 0.3)
    for k in range(14):                                                    # pan roll E6/C6, fading
        m = 88 if k % 2 == 0 else 84
        add_at(out, ins_steelpan(m, 0.06), ns(1.04 + 0.065 * k), 0.26 * (1.0 - k / 16.0))
    for k in range(10):                                                    # shaker
        sh = vmul(noise_band(ns(0.06), rng, 5200.0, 1.1), env_hump(ns(0.06), 0.25, 1.5, 3.0))
        add_at(out, sh, ns(0.84 + 0.11 * k), 0.12 * (1.0 - k / 12.0))
    twinkles(out, rng, 14, 1.0, 2.2, 2500.0, 6500.0, 0.08, 0.05)
    return finish_sfx(out, 0.003, 0.4)


def sfx_lose():
    """Gentle muted-trombone 'aww': E4 - D4 - C4 with a droop and wobble."""
    rng = rng_for('sfx_lose')
    n = ns(1.45)
    tg = []
    for i in range(n):
        t = i / SR
        if t < 0.3:
            m = 64.0
        elif t < 0.6:
            m = 62.0
        else:
            m = 60.0 - 1.0 * min(1.0, max(0.0, (t - 0.75) / 0.55))
        tg.append(hz(m))
    a = exp(-1.0 / (0.03 * SR))                     # portamento between notes
    s = tg[0] * 0.97
    f = []
    for v in tg:
        s = v + (s - v) * a
        f.append(s)
    kv = TAU * 5.0 / SR
    f = [v * (1.0 + 0.016 * sin(kv * i) * min(1.0, max(0.0, (i / SR - 0.72) / 0.25))) for i, v in enumerate(f)]
    src = osc_wt(wavetable('brass', hz(64) * 1.03), f, rng.random())
    on = (0.0, 0.3, 0.6)
    fc = []                                         # mute: "wah" opens on each note, then closes
    for i in range(n):
        t = i / SR
        o = on[2] if t >= on[2] else (on[1] if t >= on[1] else on[0])
        d = t - o
        fc.append(420.0 + 950.0 * (1.0 - exp(-d / 0.05)) * exp(-d / (0.5 if o == 0.6 else 0.18)))
    y = svf(src, fc, 2.5, 'bp')
    add_at(y, onepole_lp(src, 700.0), 0, 0.5)
    env = env_adsr(1.15, 0.03, 0.6, 0.7, 0.28)
    art = [1.0 - 0.45 * exp(-((t - 0.3) / 0.03) ** 2) - 0.45 * exp(-((t - 0.6) / 0.03) ** 2)
           for t in (i / SR for i in range(len(env)))]
    out = vmul(vmul(pad_to(y, len(env)), env), art)
    add_at(out, vmul(osc_sine(pad_to(f, len(env))), env), 0, 0.15)
    return finish_sfx(out, 0.006, 0.12, lp=3500.0)


def sfx_error():
    out = zeros(ns(0.26))
    for t, f in ((0.0, 196.0), (0.13, 185.0)):
        env = env_adsr(0.085, 0.004, 0.1, 0.85, 0.025)
        m = len(env)
        src = svf(osc_wt_const(wavetable('reed', f), m, f), 1600.0, 0.7, 'lp')
        add_at(out, vmul(src, env), ns(t), 0.8)
        add_at(out, vmul(tone(m, f * 0.5), env), ns(t), 0.08)
    return finish_sfx(out, 0.003, 0.02)


def sfx_thunder():
    rng = rng_for('sfx_thunder')
    n = ns(2.3)
    out = zeros(n)
    m = ns(0.18)
    add_at(out, filt(vmul(white(m, rng), env_ad(m, 0.003, 0.03)), 'lp', 2200.0), 0, 0.5)   # soft crack
    renv = zeros(n)
    for t0, a, d in ((0.02, 1.0, 0.5), (0.35, 0.8, 0.6), (0.8, 0.6, 0.7), (1.35, 0.4, 0.8)):
        k = min(n - ns(t0), ns(d))
        add_at(renv, env_hump(k, 0.2, 1.5, 1.8), ns(t0), a)
    lfo = [1.0] * n
    for _ in range(4):
        f, ph = 3.0 + 9.0 * rng.random(), rng.random() * TAU
        w = TAU * f / SR
        lfo = [v + 0.12 * sin(w * i + ph) for i, v in enumerate(lfo)]
    renv = vmul(renv, lfo)
    br = onepole_lp(onepole_lp(white(n, rng), 300.0), 300.0)
    add_at(out, vmul(normalize(br, 0.0), renv), 0, 1.0)
    add_at(out, vmul(svf(white(n, rng), 420.0, 0.7, 'bp'), [v ** 1.5 for v in renv]), 0, 0.3)
    add_at(out, thump(0.8, 70.0, 32.0, 0.08, 0.25), ns(0.02), 0.6)
    return finish_sfx(sat(normalize(out, 0.0), 1.3), 0.006, 0.3, lp=3000.0)


# ============================================================================
# MUSIC -- note cache, drum kit, theory helpers, track buffer, mixdown
# ============================================================================

class Voice:
    """Note renderer with a cache: identical (pitch, gate, variant) notes
    render once."""

    def __init__(self, fn, **kw):
        self.fn = fn
        self.kw = kw
        self.cache = {}

    def __call__(self, midi, gate, var=None):
        key = (midi, round(gate, 4), var)
        b = self.cache.get(key)
        if b is None:
            if var is None:
                b = self.fn(midi, gate, **self.kw)
            else:
                b = self.fn(midi, gate, var=var, **self.kw)
            self.cache[key] = b
        return b


def drum_kick(soft=False):
    n = ns(0.32)
    body = vmul(osc_sine(glide(n, 165.0, 52.0, 0.032)), env_ad(n, 0.0015, 0.13 if soft else 0.16))
    m = ns(0.006)
    add_at(body, vmul(filt(white(m, random.Random(7)), 'hp', 2500.0), env_exp(m, 0.0012)), 0,
           0.08 if soft else 0.25)
    out = sat(body, 1.2 if soft else 1.8)
    return end_fade(onepole_lp(out, 1800.0) if soft else out)


def drum_snare(seed=11):
    rng = random.Random(seed)
    n = ns(0.28)
    out = vscale(vmul(osc_sine(glide(n, 230.0, 185.0, 0.01)), env_ad(n, 0.001, 0.045)), 0.5)
    add_at(out, vmul(osc_sine(glide(n, 400.0, 330.0, 0.01)), env_ad(n, 0.001, 0.03)), 0, 0.3)
    nz = filt(filt(white(n, rng), 'hp', 1200.0), 'lp', 9000.0)
    add_at(out, vmul(nz, env_ad(n, 0.001, 0.09)), 0, 0.85)
    return end_fade(out)


def drum_clap(seed=21):
    """Hand clap: three fast noise bursts then a short diffuse tail."""
    rng = random.Random(seed)
    n = ns(0.22)
    exc = zeros(n)
    for t in (0.0, 0.0095, 0.019):
        k = ns(0.009)
        add_at(exc, vmul(white(k, rng), env_ad(k, 0.0003, 0.0022)), ns(t))
    k = n - ns(0.028)
    add_at(exc, vmul(white(k, rng), env_ad(k, 0.0004, 0.045)), ns(0.028), 0.85)
    out = svf(filt(exc, 'hp', 700.0), 1250.0, 0.9, 'bp')
    add_at(out, svf(exc, 2600.0, 1.2, 'bp'), 0, 0.3)
    return end_fade(out)


def drum_hat(seed, decay=0.012, length=0.06):
    rng = random.Random(seed)
    n = ns(length)
    nz = filt(filt(white(n, rng), 'hp', 6500.0, 0.8), 'hp', 6500.0, 0.8)
    return end_fade(vmul(nz, env_ad(n, 0.0008, decay)))


def drum_crash(seed=5):
    rng = random.Random(seed)
    n = ns(1.6)
    out = vmul(filt(white(n, rng), 'hp', 3800.0), env_ad(n, 0.002, 0.45))
    add_at(out, modal(1.0, 1.6, [(3150.0, 0.12, 0.5), (4370.0, 0.1, 0.4), (5530.0, 0.08, 0.35),
                                 (6720.0, 0.06, 0.3)], rng=rng), 0)
    return end_fade(out, 0.2)


def drum_tom(f0, f1, seed):
    rng = random.Random(seed)
    n = ns(0.35)
    body = vmul(osc_sine(glide(n, f0, f1, 0.06)), env_ad(n, 0.0015, 0.13))
    add_at(body, vmul(osc_sine(glide(n, f0 * 1.59, f1 * 1.59, 0.05)), env_ad(n, 0.0015, 0.05)), 0, 0.2)
    m = ns(0.02)
    add_at(body, filt(vmul(white(m, rng), env_exp(m, 0.003)), 'bp', 1500.0, 0.7), 0, 0.3)
    return end_fade(sat(body, 1.3))


def perc_shaker(seed, accent):
    rng = random.Random(seed)
    n = ns(0.07)
    return vmul(svf(white(n, rng), 5200.0, 1.1, 'bp'), env_hump(n, 0.25 if accent else 0.18, 1.5, 3.0))


def perc_bongo(f, seed, slap=0.3, tau=0.06):
    """Bongo/conga head: pitch-dropping sine + 1.5x overtone + slap noise."""
    rng = random.Random(seed)
    n = ns(0.22)
    body = vmul(osc_sine(glide(n, f * 1.18, f, 0.006)), env_ad(n, 0.001, tau))
    add_at(body, vmul(osc_sine(glide(n, f * 1.77, f * 1.5, 0.006)), env_ad(n, 0.001, tau * 0.5)), 0, 0.3)
    m = ns(0.012)
    add_at(body, svf(vmul(white(m, rng), env_exp(m, 0.0018)), 2800.0, 0.9, 'bp'), 0, slap)
    return end_fade(body)


def perc_clave(f=2500.0):
    return end_fade(modal(f, 0.08, [(1.0, 1.0, 0.022), (2.71, 0.12, 0.006)], att=0.0005))


def perc_block(f=1150.0):
    return end_fade(modal(f, 0.1, [(1.0, 1.0, 0.03), (2.7, 0.3, 0.01), (5.1, 0.1, 0.004)], att=0.0005))


def perc_tamb(seed):
    """Tambourine: cluster of tiny inharmonic jingles + hiss."""
    rng = random.Random(seed)
    n = ns(0.2)
    out = zeros(n)
    for j in range(6):
        t = 0.02 * rng.random() ** 1.5
        base = 3200.0 * 1.8 ** rng.random()
        p = modal(base, 0.12, [(1.0, 1.0, 0.03), (1.47, 0.6, 0.025), (2.09, 0.3, 0.015)], att=0.0008, rng=rng)
        add_at(out, p, ns(t), 0.3 + 0.7 * rng.random())
    add_at(out, vmul(filt(white(n, rng), 'hp', 5500.0), env_ad(n, 0.001, 0.035)), 0, 0.5)
    return end_fade(out)


def fx_riser(dur, seed):
    """Reverse-cymbal style noise swell that ends on the downbeat."""
    rng = random.Random(seed)
    n = ns(dur)
    nz = filt(white(n, rng), 'hp', 1500.0)
    m = ns(0.006)
    return [v * (i / n) ** 2.5 * (1.0 if i < n - m else (n - i) / m) for i, v in enumerate(nz)]


PC = {'C': 0, 'C#': 1, 'Db': 1, 'D': 2, 'D#': 3, 'Eb': 3, 'E': 4, 'F': 5, 'F#': 6,
      'Gb': 6, 'G': 7, 'G#': 8, 'Ab': 8, 'A': 9, 'A#': 10, 'Bb': 10, 'B': 11}
QUAL = {'': (0, 4, 7), 'm': (0, 3, 7), 'maj7': (0, 4, 7, 11), 'm7': (0, 3, 7, 10),
        '7': (0, 4, 7, 10)}
DEBUG_STEMS = False


def note_num(s):
    """'F#5' -> MIDI number."""
    return 12 * (int(s[-1]) + 1) + PC[s[:-1]]


def parse_chord(s):
    root = s[:2] if len(s) > 1 and s[1] in '#b' else s[:1]
    return PC[root], QUAL[s[len(root):]]


def place(pc, lo):
    """Lowest MIDI note >= lo having pitch class pc."""
    return lo + (pc - lo) % 12


def parse_bar(s):
    """'C5:1 A4:.5 r:.5 ...' -> [(beat_in_bar, beats, midi|None)]; must fill 4 beats."""
    out = []
    t = 0.0
    for tok in s.split():
        name, d = tok.split(':')
        out.append((t, float(d), None if name == 'r' else note_num(name)))
        t += float(d)
    assert abs(t - 4.0) < 1e-9, s
    return out


def parse_prog(bars):
    """['Fmaj7', 'Am7:2 Dm7:2', ...] -> [(abs_beat, beats, root_pc, intervals)]."""
    out = []
    for b, s in enumerate(bars):
        t = 0.0
        for tok in s.split():
            ch, d = (tok.split(':') + ['4'])[:2]
            root, iv = parse_chord(ch)
            out.append((b * 4 + t, float(d), root, iv))
            t += float(d)
        assert abs(t - 4.0) < 1e-9, s
    return out


def chord_at(prog, beat):
    for b0, d, root, iv in prog:
        if b0 <= beat < b0 + d:
            return root, iv
    return prog[-1][2], prog[-1][3]


def voicing(root, iv, lo):
    return sorted(place((root + i) % 12, lo) for i in iv)


def check_melody(name, melody, prog, scale):
    """Composition sanity check: every melody note is in the key (or a chord
    tone), and every note on a strong beat (1 or 3) is a chord tone."""
    for b, bar in enumerate(melody):
        for t, d, m in parse_bar(bar):
            if m is None:
                continue
            root, iv = chord_at(prog, b * 4 + t)
            ct = {(root + i) % 12 for i in iv}
            assert m % 12 in scale or m % 12 in ct, (name, b + 1, t, m)
            if t in (0.0, 2.0):
                assert m % 12 in ct, ('strong-beat non-chord tone', name, b + 1, t, m)


class Track:
    """Dry bus + reverb-send bus (+ optional named sub-buses), one loop long
    plus a tail that later gets folded back onto the start (so releases and
    echoes wrap seamlessly)."""

    def __init__(self, bpm, bars=16, seed=1, tail=4.0):
        self.bpm = bpm
        self.bars = bars
        self.spb = SR * 60.0 / bpm
        self.L = int(round(self.spb * 4 * bars))
        self.N = self.L + ns(tail)
        self.dry = zeros(self.N)
        self.send = zeros(self.N)
        self.buses = {}
        self.bus_fx = {'drums': drum_glue}
        self.rng = random.Random(seed)
        self.stems = {} if DEBUG_STEMS else None

    def sec(self, beats):
        return beats * 60.0 / self.bpm

    def add(self, buf, beat, gain, send=0.0, jitter=0.0, stem=None, bus=None):
        p = int(round(beat * self.spb))
        if jitter:
            p = max(0, p + int(round((2.0 * self.rng.random() - 1.0) * jitter * SR)))
        if bus is None and stem in DRUM_STEMS:
            bus = 'drums'
        if bus is None:
            dry, snd = self.dry, self.send
        else:
            if bus not in self.buses:
                self.buses[bus] = (zeros(self.N), zeros(self.N))
            dry, snd = self.buses[bus]
        add_at(dry, buf, p, gain)
        if send:
            add_at(snd, buf, p, gain * send)
        if self.stems is not None and stem:
            st = self.stems.get(stem)
            if st is None:
                st = self.stems[stem] = zeros(self.N)
            add_at(st, buf, p, gain)

    def pump(self, depth=0.45, tau=0.11):
        """Side-chain style duck on every beat (periodic in the beat; one
        loop long, applied to the folded bus)."""
        spb = self.spb
        a = ns(0.004)
        out = []
        for i in range(self.L):
            ph = i % spb
            out.append(1.0 - depth * min(1.0, ph / a) * exp(-ph / (tau * SR)))
        return out


DRUM_STEMS = {'kick', 'snare', 'clap', 'hat', 'tom', 'timp', 'crash', 'bongo', 'shaker', 'clave',
              'tamb', 'block'}


def drum_glue(d, knee=0.45):
    """Drum-bus soft clipper (memoryless, so the loop stays seamless):
    returns the per-sample gain that maps d -> c*tanh(d/c), c = knee*peak.
    Shaves the tallest kick/tom transients so the master limiter has
    less to do, leaving the body of the hits untouched."""
    c = knee * (peak(d) or 1.0)
    return [c * tanh(v / c) / v if abs(v) > 1e-12 else 1.0 for v in d]


def mixdown(tr, wet_ratio, room, damp, shelf_db=-3.0):
    """Fold buses -> circular reverb -> circular master EQ/DC block ->
    loudness normalisation to MUSIC_LUFS -> circular look-ahead limiter at
    MUSIC_PEAK_DB.  Every stage is circular, so the loop is seamless."""
    L = tr.L
    dry = fold(tr.dry, L)
    send = fold(tr.send, L)
    tr.dry = tr.send = None
    for name, (d, s) in sorted(tr.buses.items()):     # sub-buses: fold, then a
        d, s = fold(d, L), fold(s, L)                  # periodic gain curve
        fx = tr.bus_fx.get(name)
        if fx:
            env = fx(d)
            d, s = vmul(d, env), vmul(s, env)
        add_at(dry, d)
        add_at(send, s)
    tr.buses = {}
    wet = circular(send, lambda x: reverb(x, room, damp, 0.022), ns(4.5))
    rd, rw = rms(dry), rms(wet)
    if rw > 1e-9:
        wet = vscale(wet, wet_ratio * rd / rw)          # calibrated wet level
    mix = [a + b for a, b in zip(dry, wet)]

    def master(x):
        x = filt(x, 'hs', 6500.0, 0.7071, shelf_db)      # tame the top
        x = filt(x, 'lp', 10000.0, 0.7071)
        x = filt(x, 'hp', 38.0, 0.7071)
        x = filt(x, 'ls', 85.0, 0.7071, -2.5)          # phone-friendly: less sub, less limiting
        return dc_block(x)

    mix = circular(mix, master, ns(1.0))
    if tr.stems is not None:
        ref = rms(mix) or 1.0
        for k, st in sorted(tr.stems.items()):
            print('    stem %-7s %6.1f dB' % (k, 20.0 * math.log10(max(1e-9, rms(fold(st, L)) / ref))))
        print('    wet     %6.1f dB' % (20.0 * math.log10(max(1e-9, rms(wet) / ref))))
    ceiling = 10.0 ** (MUSIC_PEAK_DB / 20.0)
    g = 10.0 ** ((MUSIC_LUFS - lufs(mix)) / 20.0)
    st = {}
    for _ in range(4):
        y, gmin = limit_circular(vscale(mix, g), ceiling, stats=st)
        lv = lufs(y)
        if abs(lv - MUSIC_LUFS) < 0.05:
            break
        g *= 10.0 ** ((MUSIC_LUFS - lv) / 20.0)
    if DEBUG_STEMS:
        print('    master: %.2f LUFS, peak %.2f dBFS, max limiter GR %.1f dB, >1 dB GR %.1f%%, >3 dB GR %.2f%%'
              % (lufs(y), 20.0 * math.log10(peak(y)), -20.0 * math.log10(gmin), 100 * st['gr1'], 100 * st['gr3']))
    return y


# ============================================================================
# MUSIC -- compositions (all melodies and progressions are original)
# ============================================================================

F_MAJ = {5, 7, 9, 10, 0, 2, 4}
C_MAJ = {0, 2, 4, 5, 7, 9, 11}
A_MIN = C_MAJ | {8}                    # + G# (harmonic minor leading tone)
D_MIN = {2, 4, 5, 7, 9, 10, 0, 1}      # + C# (harmonic minor leading tone)
G_MAJ = {7, 9, 11, 0, 2, 4, 6, 1}      # + C# (V/V)

# ---- music_menu: sunny calypso, F major, 100 bpm, 12 bars (28.8 s) -----------

MENU_PROG = ['F', 'Bb', 'C7', 'F', 'Dm7', 'Gm7', 'C7', 'Fmaj7',
             'Bbmaj7', 'C', 'Am7:2 Dm7:2', 'Gm7:2 C7:2']

MENU_MELODY = [                       # steel-pan tune
    'C5:.5 F5:.5 A5:.75 G5:.25 F5:.5 A5:1 r:.5',
    'Bb5:.75 A5:.25 G5:.5 F5:.5 D5:1 r:1',
    'E5:.5 G5:.5 C6:.75 A5:.25 Bb5:.5 G5:.5 E5:1',
    'F5:1.5 C5:.5 F5:1 r:1',
    'D5:.5 F5:.5 A5:.75 G5:.25 F5:.5 E5:.5 D5:1',
    'Bb4:.5 D5:.5 G5:.75 F5:.25 G5:.5 Bb5:1 r:.5',
    'G5:.75 E5:.25 C5:.5 E5:.5 G5:.5 Bb5:.5 A5:.5 G5:.5',
    'A5:1.5 F5:.5 C5:1 r:1',
    'D6:.75 C6:.25 Bb5:.5 A5:.5 F5:1 D5:1',
    'E5:.5 G5:.5 C6:.75 D6:.25 C6:.5 G5:.5 E5:1',
    'C6:.5 A5:.5 E5:.5 G5:.5 F5:.5 A5:.5 D6:1',
    'Bb5:.5 A5:.5 G5:.5 F5:.5 E5:.75 D5:.25 E5:.5 G5:.5',
]


def _approach(next_root, R, scale):
    """Bass approach note into the next chord root (scale tone nearest it)."""
    N = place(next_root, R - 5)
    for c in (N - 1, N + 2, N - 2, N + 1):
        if c % 12 in scale:
            return c
    return N


def music_menu():
    tr = Track(100, 12, seed=101)
    pan, mar, pad, bass = Voice(ins_steelpan), Voice(ins_marimba), Voice(ins_pad), Voice(ins_sine_bass)
    prog = parse_prog(MENU_PROG)
    check_melody('menu', MENU_MELODY, prog, F_MAJ)
    for b, bar in enumerate(MENU_MELODY):                 # steel-pan lead + soft echo
        for t, d, m in parse_bar(bar):
            if m is None:
                continue
            beat = b * 4 + t
            acc = 1.0 if t in (0.0, 2.0) else (0.9 if t == int(t) else 0.8)
            buf = pan(m, tr.sec(d))
            tr.add(buf, beat, 0.6 * acc, send=0.3, jitter=0.002, stem='pan')
            tr.add(buf, beat + 0.75, 0.6 * acc * 0.15, send=0.6, stem='echo')
    comp = ((0.0, 'R', 1.0), (0.75, 'C', 0.8), (1.5, 'C', 0.7), (2.0, 'F', 0.85), (2.75, 'C', 0.8), (3.5, 'C', 0.7))
    for idx, (beat0, beats, root, iv) in enumerate(prog):
        R = place(root, 50)
        F5 = place((root + 7) % 12, 50)
        dyad = voicing(root, iv[1:], 62)[:2]
        for t, kind, v in comp:                           # marimba calypso comping
            if t >= beats:
                continue
            notes = (R,) if kind == 'R' else ((F5,) if kind == 'F' else dyad)
            for m in notes:
                tr.add(mar(m, 0.3), beat0 + t, 0.22 * v * (0.9 + 0.1 * tr.rng.random()), send=0.25,
                       jitter=0.003, stem='marimba')
        for m in voicing(root, iv, 55):                   # soft pad
            tr.add(pad(m, tr.sec(beats) * 0.98), beat0, 0.06, send=0.5, stem='pad')
        Rb = place(root, 36)
        nxt = prog[(idx + 1) % len(prog)][2]
        if beats >= 4:
            pat = ((0, 1.25, Rb), (1.5, 0.5, Rb), (2, 1.0, Rb + 7), (3, 0.5, Rb + 12),
                   (3.5, 0.45, _approach(nxt, Rb, F_MAJ)))
        else:
            pat = ((0, 1.0, Rb), (1.0, 0.5, Rb + 7), (1.5, 0.45, _approach(nxt, Rb, F_MAJ)))
        for t, d, m in pat:                               # round calypso bass
            tr.add(bass(m, tr.sec(d) * 0.9), beat0 + t, 0.24, stem='bass')
    shakers = [perc_shaker(300 + k, k % 2 == 1) for k in range(4)]
    bh, bl = perc_bongo(420.0, 31), perc_bongo(300.0, 32, 0.4, 0.08)
    clave, kick = perc_clave(), drum_kick(soft=True)
    vel8 = (0.35, 0.7, 0.4, 0.75, 0.35, 0.7, 0.4, 0.8)
    bongo = ((0, 'h', .5), (.5, 'h', .3), (1, 'h', .6), (1.5, 'l', .5), (2, 'h', .5), (2.5, 'h', .3),
             (3, 'h', .6), (3.5, 'l', .7))
    for b in range(12):
        base = b * 4
        for k in range(8):
            tr.add(shakers[(b * 8 + k) % 4], base + 0.5 * k, 0.3 * vel8[k], send=0.15, jitter=0.004, stem='shaker')
        for t, w, v in bongo:
            tr.add(bh if w == 'h' else bl, base + t, 0.28 * v, send=0.2, jitter=0.004, stem='bongo')
        for t in ((0, 1.5, 3) if b % 2 == 0 else (1, 2)):  # 3-2 clave
            tr.add(clave, base + t, 0.16, send=0.3, stem='clave')
        for t in (0, 2.5):
            tr.add(kick, base + t, 0.32, stem='kick')
    return mixdown(tr, 0.28, 0.86, 0.35, -3.0)


# ---- music_race: soca-pop race, C major, 135 bpm, 16 bars (28.4 s) ----------

RACE_PROG = ['C', 'G', 'Am', 'F', 'C', 'G', 'F', 'G',
             'F', 'G', 'Em', 'Am', 'F', 'G', 'C:2 Am:2', 'Dm7:2 G7:2']

RACE_MELODY = [                       # steel-pan hook
    'G5:.5 E5:.5 G5:.5 C6:.5 r:.5 C6:.5 B5:.5 C6:.5',
    'D6:.5 B5:.5 G5:.5 D5:.5 r:.5 G5:.5 A5:.5 B5:.5',
    'C6:.5 A5:.5 E5:.5 A5:.5 r:.5 A5:.5 G5:.5 A5:.5',
    'C6:.75 A5:.25 F5:.5 C5:.5 F5:.5 G5:.5 A5:1',
    'G5:.5 E5:.5 G5:.5 C6:.5 r:.5 C6:.5 B5:.5 C6:.5',
    'D6:.5 B5:.5 G5:.5 D5:.5 r:.5 B4:.5 C5:.5 D5:.5',
    'F5:.5 A5:.5 C6:.5 A5:.5 F5:.5 A5:.5 C6:.5 D6:.5',
    'B5:.75 A5:.25 G5:.5 D5:.5 G5:.5 A5:.5 B5:1',
    'A5:1.5 G5:.5 A5:.5 C6:1 A5:.5',
    'B5:1.5 A5:.5 B5:.5 D6:1 B5:.5',
    'G5:.5 B5:.5 E6:1 B5:.5 A5:.5 G5:1',
    'A5:.5 C6:.5 E6:1 C6:.5 B5:.5 A5:1',
    'F6:.75 E6:.25 C6:.5 A5:.5 C6:.5 A5:.5 F5:1',
    'D6:.75 C6:.25 B5:.5 A5:.5 G5:.5 A5:.5 B5:1',
    'E6:.5 D6:.5 C6:.5 G5:.5 A5:.5 C6:.5 E6:1',
    'F5:.5 A5:.5 D6:.5 C6:.5 B5:.5 G5:.5 F5:.5 D5:.5',
]

STRUM = ((0.0, 'D', 1.0), (1.0, 'D', 0.75), (1.5, 'U', 0.55), (2.5, 'U', 0.6), (3.0, 'D', 0.8), (3.5, 'U', 0.55))


def uke_chord(root, iv):
    notes = voicing(root, iv, 60)
    if len(notes) == 3:
        notes.append(notes[0] + 12)
    return notes


def add_strums(tr, uke, prog, bars, gain, stem='uke', send=0.18):
    for b in range(bars):
        for k, (t, dirn, v) in enumerate(STRUM):
            beat = b * 4 + t
            nxt = STRUM[k + 1][0] if k + 1 < len(STRUM) else 4.0
            gate = tr.sec(nxt - t) + 0.02
            notes = uke_chord(*chord_at(prog, beat))
            order = notes if dirn == 'D' else notes[::-1][:3]
            for j, m in enumerate(order):
                tr.add(uke(m, gate, (b + k + j) % 3), beat + tr.bpm / 60.0 * 0.009 * j,
                       gain * v * (1.0 if dirn == 'D' else 0.8), send=send, jitter=0.002, stem=stem)


def music_race():
    tr = Track(135, 16, seed=202)
    pan, uke, xylo = Voice(ins_steelpan), Voice(ins_uke), Voice(ins_xylo)
    bass = Voice(ins_bass, bright=0.75)
    prog = parse_prog(RACE_PROG)
    check_melody('race', RACE_MELODY, prog, C_MAJ)
    for b, bar in enumerate(RACE_MELODY):
        for t, d, m in parse_bar(bar):
            if m is None:
                continue
            beat = b * 4 + t
            acc = 1.0 if t == int(t) else 0.85
            buf = pan(m, tr.sec(d) * 0.9)
            tr.add(buf, beat, 0.55 * acc, send=0.22, jitter=0.0015, stem='pan')
            tr.add(buf, beat + 0.75, 0.55 * 0.13, send=0.5, stem='echo')
            if b >= 8:                                   # xylophone octave double for lift
                tr.add(xylo(m + 12, 0.2), beat, 0.10, send=0.3, stem='xylo')
    add_strums(tr, uke, prog, 16, 0.19)
    BASS = ((0.0, 0.6, 0), (0.75, 0.5, 0), (1.5, 0.4, 7), (2.0, 0.6, 0), (2.75, 0.5, 0), (3.5, 0.4, 12))
    for b in range(16):                                  # soca 3-3-2 bass
        for t, d, iv_ in BASS:
            root, _ = chord_at(prog, b * 4 + t)
            tr.add(bass(place(root, 36) + iv_, tr.sec(d) * 0.85), b * 4 + t, 0.26, stem='bass')
    kick, snare, clap, crash = drum_kick(), drum_snare(), drum_clap(), drum_crash()
    hats = [drum_hat(500 + k) for k in range(4)]
    ohat = drum_hat(510, 0.06, 0.25)
    shakers = [perc_shaker(520 + k, k % 2 == 1) for k in range(4)]
    bh, bl = perc_bongo(440.0, 41, 0.35), perc_bongo(310.0, 42, 0.45, 0.08)
    hv = (0.25, 0.12, 0.6, 0.12)
    for b in range(16):
        base = b * 4
        fill = b in (7, 15)
        for k in range(4):
            tr.add(kick, base + k, 0.62, stem='kick')
        for k in (1, 3):
            if not (fill and k == 3):
                tr.add(snare, base + k, 0.55, send=0.15, stem='snare')
                tr.add(clap, base + k, 1.0, send=0.2, jitter=0.002, stem='clap')
        for s in range(16):
            if s % 4 == 2 and b % 2 == 1 and s == 14:
                tr.add(ohat, base + 3.5, 0.24, send=0.08, stem='hat')
            else:
                tr.add(hats[s % 4], base + 0.25 * s, 0.34 * hv[s % 4], send=0.05, jitter=0.002, stem='hat')
            tr.add(shakers[s % 4], base + 0.25 * s, 0.10 * (1.0 if s % 2 else 0.6), send=0.1, stem='shaker')
        for t, w, v in ((1.75, 'h', 0.5), (2.5, 'l', 0.6), (3.25, 'h', 0.4), (3.5, 'h', 0.55)):
            tr.add(bh if w == 'h' else bl, base + t, 0.22 * v, send=0.15, jitter=0.003, stem='bongo')
    for b in (0, 8):
        tr.add(crash, b * 4, 0.22, send=0.25, stem='crash')
    for bb in (7, 15):
        for t, v in ((3.0, 0.55), (3.25, 0.7), (3.5, 0.85), (3.75, 1.0)):
            tr.add(snare, bb * 4 + t, 0.45 * v, send=0.15, stem='snare')
    return mixdown(tr, 0.18, 0.84, 0.3, -2.5)


# ---- music_race_night: neon synth variant, A minor, 132 bpm, 16 bars (29.1 s)

NIGHT_PROG = ['Am7', 'Fmaj7', 'C', 'G', 'Am7', 'Fmaj7', 'C', 'G',
              'Dm7', 'Em7', 'Fmaj7', 'G', 'Dm7', 'Em7', 'Fmaj7', 'G:2 E7:2']

NIGHT_MELODY = [                      # synth-pluck hook
    'E5:.75 A5:.75 C6:.5 G5:.5 A5:.5 B5:.5 C6:.5',
    'F5:.75 A5:.75 C6:.5 E6:1 r:1',
    'E5:.75 G5:.75 C6:.5 E6:.5 D6:.5 C6:.5 G5:.5',
    'D5:.75 G5:.75 B5:.5 D6:1 r:1',
    'E5:.75 A5:.75 C6:.5 E6:.5 D6:.5 C6:.5 B5:.5',
    'C6:.75 A5:.75 F5:.5 A5:1 r:1',
    'G5:.75 E5:.75 G5:.5 C6:.5 B5:.5 C6:.5 E6:.5',
    'D6:1.5 B5:.5 G5:1 r:1',
    'F5:.5 A5:.5 C6:.5 D6:1 C6:.5 A5:.5 F5:.5',
    'G5:.5 B5:.5 D6:.5 E6:1 D6:.5 B5:.5 G5:.5',
    'A5:.5 C6:.5 E6:.5 F6:1 E6:.5 C6:.5 A5:.5',
    'B5:1.5 A5:.5 G5:1 D5:1',
    'D6:.75 C6:.75 A5:.5 F5:1 A5:1',
    'E6:.75 D6:.75 B5:.5 G5:1 B5:1',
    'C6:.75 E6:.75 F6:.5 E6:.5 C6:.5 A5:1',
    'B5:.5 D6:.5 B5:.5 G5:.5 G#5:.5 B5:.5 D6:.5 E6:.5',
]


def music_race_night():
    tr = Track(132, 16, seed=404)
    lead = Voice(ins_pluck, shape='saw', bright=0.9)
    sub = Voice(ins_pluck, shape='square', bright=0.35, rel=0.05)
    arp = Voice(ins_pluck, shape='square', bright=0.45, rel=0.03)
    pad, bass = Voice(ins_pad), Voice(ins_bass, bright=0.55)
    bell = Voice(ins_celesta)
    prog = parse_prog(NIGHT_PROG)
    check_melody('night', NIGHT_MELODY, prog, A_MIN)
    for b, bar in enumerate(NIGHT_MELODY):
        for t, d, m in parse_bar(bar):
            if m is None:
                continue
            beat = b * 4 + t
            buf = lead(m, tr.sec(d) * 0.8)
            tr.add(buf, beat, 0.52, send=0.3, stem='lead')
            tr.add(sub(m - 12, tr.sec(d) * 0.8), beat, 0.2, send=0.1, stem='lead')
            tr.add(buf, beat + 0.75, 0.52 * 0.28, send=0.6, stem='echo')       # dotted-8th delay
            tr.add(buf, beat + 1.5, 0.52 * 0.11, send=0.7, stem='echo')
    order = (0, 1, 2, 3, 2, 1, 2, 3)
    for beat0, beats, root, iv in prog:
        notes = voicing(root, iv, 64)
        if len(notes) == 3:
            notes.append(notes[0] + 12)
        for k in range(int(beats * 4)):                   # 16th arpeggio
            m = notes[order[k % 8]]
            tr.add(arp(m, tr.sec(0.2)), beat0 + 0.25 * k, 0.085 * (1.0 if k % 4 == 0 else 0.75),
                   send=0.3, stem='arp')
        for m in voicing(root, iv, 55):                   # pumping pad
            tr.add(pad(m, tr.sec(beats) * 0.98), beat0, 0.075, send=0.45, stem='pad', bus='pad')
        R = place(root, 33)
        for k in range(int(beats * 2)):                   # octave 8th bass
            tr.add(bass(R + (12 if k % 2 else 0), tr.sec(0.42)), beat0 + 0.5 * k, 0.22, stem='bass')
    pump = tr.pump(0.5, 0.12)
    tr.bus_fx['pad'] = lambda d: pump
    for b in (0, 4, 8, 12):                               # neon sparkle on phrase starts
        root, iv = chord_at(prog, b * 4)
        for k, m in enumerate(voicing(root, iv, 84)[:3]):
            tr.add(bell(m, 0.3), b * 4 + 0.08 * k, 0.07, send=0.6, stem='bell')
    kick, snare, clap, crash = drum_kick(), drum_snare(13), drum_clap(23), drum_crash(6)
    hats = [drum_hat(900 + k) for k in range(4)]
    ohat = drum_hat(910, 0.05, 0.22)
    toms = (drum_tom(240.0, 180.0, 51), drum_tom(180.0, 135.0, 52), drum_tom(130.0, 98.0, 53))
    riser = fx_riser(tr.sec(2.0), 61)
    hv = (0.35, 0.18, 0.28, 0.18)
    for b in range(16):
        base = b * 4
        for k in range(4):
            tr.add(kick, base + k, 0.5, stem='kick')
        for k in (1, 3):
            tr.add(snare, base + k, 0.45, send=0.35, stem='snare')
            tr.add(clap, base + k, 0.75, send=0.3, stem='clap')
        for s in range(16):
            if s % 4 == 2:
                tr.add(ohat, base + 0.25 * s, 0.16, send=0.1, stem='hat')
            else:
                tr.add(hats[s % 4], base + 0.25 * s, 0.32 * hv[s % 4], send=0.05, stem='hat')
    for b in (0, 8):
        tr.add(crash, b * 4, 0.2, send=0.3, stem='crash')
    for b in (7, 15):
        tr.add(riser, b * 4 + 2.0, 0.07, send=0.3, stem='riser')
    for k, (t, tom) in enumerate(((3.0, 0), (3.25, 0), (3.5, 1), (3.75, 2))):
        tr.add(toms[tom], 15 * 4 + t, 0.32, send=0.2, stem='tom')
    return mixdown(tr, 0.22, 0.86, 0.28, -2.5)


# ---- music_boss: tense-but-fun chase, D minor, 150 bpm, 16 bars (25.6 s) -----

BOSS_PROG = ['Dm', 'Dm:2 Gm:2', 'Bb', 'C', 'Dm', 'Dm:2 Gm:2', 'Gm', 'A7',
             'Bb', 'C', 'Dm', 'Dm', 'Gm', 'A7', 'Dm:2 Bb:2', 'A7']

BOSS_MELODY = [                       # minor riff
    'D5:.5 A4:.5 D5:.5 F5:.5 A5:.5 G5:.5 F5:.5 E5:.5',
    'F5:.5 E5:.5 D5:.5 A4:.5 Bb4:1 A4:1',
    'Bb4:.5 D5:.5 F5:.5 D5:.5 F5:.75 G5:.25 F5:.5 D5:.5',
    'E5:.5 G5:.5 C6:.5 G5:.5 E5:.5 D5:.5 C5:.5 E5:.5',
    'D5:.5 A4:.5 D5:.5 F5:.5 A5:.5 G5:.5 F5:.5 E5:.5',
    'F5:.5 E5:.5 D5:.5 A4:.5 Bb4:1 A4:1',
    'G5:.5 D5:.5 Bb4:.5 D5:.5 G5:.5 A5:.5 Bb5:1',
    'A5:.5 G5:.5 F5:.5 E5:.5 C#5:1 A4:1',
    'F5:1.5 D5:.5 Bb4:1 D5:1',
    'G5:1.5 E5:.5 C5:1 E5:1',
    'D5:.5 F5:.5 A5:.5 F5:.5 D5:.5 F5:.5 A5:1',
    'A5:1 G5:.5 F5:.5 D5:1 E5:1',
    'D5:.5 G5:.5 Bb5:.5 G5:.5 D5:.5 Bb4:.5 D5:1',
    'C#5:.5 E5:.5 A5:.5 E5:.5 G5:1 F5:.5 E5:.5',
    'D5:.5 F5:.5 A5:.5 F5:.5 Bb5:.5 F5:.5 D5:.5 F5:.5',
    'E5:.5 A5:.5 C#6:.5 A5:.5 G5:.5 E5:.5 C#5:.5 E5:.5',
]


def music_boss():
    tr = Track(150, 16, seed=303)
    lead = Voice(ins_pluck, shape='brass', bright=0.85, rel=0.06)
    stab = Voice(ins_stab, shape='saw', cutoff=2400.0, detune=0.005, att=0.005)
    bass = Voice(ins_bass, bright=1.1)
    xylo = Voice(ins_xylo)
    prog = parse_prog(BOSS_PROG)
    check_melody('boss', BOSS_MELODY, prog, D_MIN)
    for b, bar in enumerate(BOSS_MELODY):
        for t, d, m in parse_bar(bar):
            if m is None:
                continue
            beat = b * 4 + t
            buf = lead(m, tr.sec(d) * 0.8)
            tr.add(buf, beat, 0.6, send=0.2, stem='lead')
            tr.add(buf, beat + 0.75, 0.6 * 0.18, send=0.5, stem='echo')
    acc8 = (1.0, 0.5, 0.55, 1.0, 0.5, 0.55, 1.0, 0.6)                  # 3-3-2 accents
    gallop = ((0, 0), (0.5, 0), (0.75, 0), (1, 0), (1.5, 0), (1.75, 0),
              (2, 12), (2.5, 0), (2.75, 0), (3, 0), (3.5, 7), (3.75, 12))
    for beat0, beats, root, iv in prog:
        notes = voicing(root, iv[:3], 57)
        for k in range(int(beats * 2)):                  # staccato string ostinato
            for m in notes:
                tr.add(stab(m, tr.sec(0.2)), beat0 + 0.5 * k, 0.34 * acc8[k], send=0.18, stem='strings')
            tr.add(stab(place(root, 45), tr.sec(0.2)), beat0 + 0.5 * k, 0.24 * acc8[k], send=0.1, stem='strings')
        R = place(root, 38)
        for t, iv_ in gallop:                            # galloping bass
            if t < beats:
                tr.add(bass(R + iv_, tr.sec(0.4 if t % 1.0 == 0.0 else 0.2)), beat0 + t, 0.16, stem='bass')
        if beat0 >= 32:                                  # bright xylophone counter, 2nd half
            arpn = voicing(root, iv[:3], 74)
            for k in range(int(beats * 2)):
                m = arpn[(0, 1, 2, 1)[k % 4]]
                tr.add(xylo(m, 0.2), beat0 + 0.5 * k, 0.11, send=0.25, stem='xylo')
    kick, snare, crash = drum_kick(), drum_snare(12), drum_crash(9)
    hats = [drum_hat(700 + k) for k in range(4)]
    toms = {'h': drum_tom(240.0, 180.0, 31), 'm': drum_tom(180.0, 135.0, 32), 'l': drum_tom(130.0, 98.0, 33)}
    timp = thump(0.7, 120.0, 72.0, 0.05, 0.22)
    hv = (0.6, 0.25, 0.45, 0.25)
    for b in range(16):
        base = b * 4
        fill = b % 4 == 3
        for k in range(4):
            tr.add(kick, base + k, 0.4, stem='kick')
        for k in (1, 3):
            if not (fill and k == 3):
                tr.add(snare, base + k, 0.45, send=0.15, stem='snare')
        stop = 16 if not fill else 12
        for s in range(stop):
            if b >= 8 or s % 2 == 0:
                tr.add(hats[s % 4], base + 0.25 * s, 0.36 * hv[s % 4], send=0.05, stem='hat')
        for t, w, v in ((0, 'l', 0.5), (1.5, 'm', 0.35), (2.5, 'l', 0.4), (3.5, 'h', 0.3)):
            if not (fill and t >= 3):
                tr.add(toms[w], base + t, 0.32 * v, send=0.15, stem='tom')
        if fill:
            seq = 'hhml' if b != 15 else 'hmll'
            for s, w in zip(range(12, 16), seq):
                tr.add(toms[w], base + 0.25 * s, 0.34, send=0.15, stem='tom')
        if b % 4 == 0:
            tr.add(timp, base, 0.32, send=0.25, stem='timp')
    for b in (0, 8):
        tr.add(crash, b * 4, 0.22, send=0.25, stem='crash')
    return mixdown(tr, 0.16, 0.83, 0.3, -2.5)


# ---- music_bonus: carefree coin rush, G major, 125 bpm, 14 bars (26.9 s) -----
# Form: A (4) A' (4) B (4) + 2-bar turnaround tag back to the top.

BONUS_PROG = ['G', 'C', 'D', 'G', 'Em', 'C', 'A7', 'D7',
              'G', 'C', 'D', 'Bm', 'Em:2 C:2', 'Am7:2 D7:2']

BONUS_MELODY = [                      # whistle tune
    'B5:1 G5:.5 B5:.5 D6:1.5 B5:.5',
    'C6:1 E6:.5 C6:.5 G5:1.5 r:.5',
    'A5:.5 F#5:.5 A5:.5 D6:.5 A5:.5 B5:.5 C6:.5 A5:.5',
    'G5:1.5 B5:.5 D6:1 r:1',
    'E6:1 B5:.5 G5:.5 E5:1.5 G5:.5',
    'G5:.5 A5:.5 C6:.5 D6:.5 E6:1 C6:1',
    'C#6:.5 E6:.5 C#6:.5 A5:.5 G5:1 A5:1',
    'F#5:1 A5:.5 D6:.5 C6:1 A5:1',
    'D6:.5 B5:.5 G5:.5 B5:.5 D6:1 G6:1',
    'E6:.5 C6:.5 G5:.5 C6:.5 E6:1.5 D6:.5',
    'D6:.5 C6:.5 A5:.5 F#5:.5 A5:1 D6:1',
    'B5:1.5 A5:.5 F#5:1 D5:1',
    'E5:.5 G5:.5 B5:.5 G5:.5 E6:.5 D6:.5 C6:.5 G5:.5',
    'A5:.5 C6:.5 E6:.5 C6:.5 F#5:.5 A5:.5 C6:.5 A5:.5',
]


def music_bonus():
    bars = len(BONUS_MELODY)
    tr = Track(125, bars, seed=505)
    wh, xylo, pad = Voice(ins_whistle), Voice(ins_xylo), Voice(ins_pad)
    bass, bell = Voice(ins_sine_bass), Voice(ins_celesta)
    prog = parse_prog(BONUS_PROG)
    check_melody('bonus', BONUS_MELODY, prog, G_MAJ)
    for b, bar in enumerate(BONUS_MELODY):
        for t, d, m in parse_bar(bar):
            if m is None:
                continue
            beat = b * 4 + t
            buf = wh(m, tr.sec(d) * 0.88)
            tr.add(buf, beat, 0.36, send=0.3, stem='whistle')
            tr.add(buf, beat + 0.75, 0.36 * 0.14, send=0.6, stem='echo')
    phrase_end = (3, 7, 11, 13)
    for b in range(bars):
        base = b * 4
        for k in range(4):                                # xylophone off-beat skank
            root, iv = chord_at(prog, base + k + 0.5)
            for m in voicing(root, iv[:3], 67)[1:]:
                tr.add(xylo(m, 0.2), base + k + 0.5, 0.11, send=0.2, jitter=0.002, stem='xylo')
        if b in phrase_end:                               # run up into the next phrase
            root, iv = chord_at(prog, base + 2.0)
            for k, m in enumerate(voicing(root, iv, 74)[:4]):
                tr.add(xylo(m, 0.15), base + 2.0 + 0.25 * k, 0.09, send=0.25, stem='xylo')
        nroot, _ = chord_at(prog, ((b + 1) % bars) * 4)
        for t, d, iv_ in ((0, .45, 0), (1, .45, 7), (2, .45, 0), (2.5, .4, 12), (3, .45, 7), (3.5, .4, None)):
            root, _ = chord_at(prog, base + t)
            Rb = place(root, 36)
            m = _approach(nroot, Rb, G_MAJ) if iv_ is None else Rb + iv_
            tr.add(bass(m, tr.sec(d)), base + t, 0.30, stem='bass')
    for beat0, beats, root, iv in prog:
        for m in voicing(root, iv, 55):
            tr.add(pad(m, tr.sec(beats) * 0.98), beat0, 0.045, send=0.5, stem='pad')
    kick, snare, clap = drum_kick(), drum_snare(14), drum_clap(24)
    hats = [drum_hat(1100 + k) for k in range(4)]
    shakers = [perc_shaker(1120 + k, k % 2 == 1) for k in range(4)]
    tamb = perc_tamb(1130)
    block = perc_block()
    for b in range(bars):
        base = b * 4
        for t in ((0, 2, 2.5) if b % 2 else (0, 2)):
            tr.add(kick, base + t, 0.55, stem='kick')
        for t in (1, 3):
            tr.add(clap, base + t, 0.95, send=0.25, jitter=0.002, stem='clap')
            tr.add(snare, base + t, 0.16, send=0.15, stem='snare')
            tr.add(tamb, base + t, 0.14, send=0.2, stem='tamb')
        if b in phrase_end:
            tr.add(clap, base + 3.5, 0.6, send=0.25, stem='clap')
        for k in range(8):
            tr.add(hats[k % 4], base + 0.5 * k, 0.2 * (1.0 if k % 2 else 0.6), send=0.05, stem='hat')
        for s_ in range(16):
            tr.add(shakers[s_ % 4], base + 0.25 * s_, 0.11 * (1.0 if s_ % 2 else 0.55), send=0.1, stem='shaker')
        if 8 <= b < 12:
            for t in (0.75, 2.75):
                tr.add(block, base + t, 0.10, send=0.2, stem='block')
        if b in phrase_end:                               # coin-rush sparkle
            for k, m in enumerate((91, 98)):
                tr.add(bell(m, 0.2), base + 3.5 + 0.25 * k, 0.06, send=0.5, stem='bell')
    return mixdown(tr, 0.22, 0.85, 0.32, -3.0)


# ============================================================================
# registry, verification, manifest, CLI
# ============================================================================

# (name, generator, kind, (min_s, max_s) accepted duration, description)
SOUNDS = [
    ('sfx_ui_tap', sfx_ui_tap, 'sfx', (0.03, 0.15), 'Soft bubbly pop tap: rising sine bubble + tiny click.'),
    ('sfx_ui_back', sfx_ui_back, 'sfx', (0.04, 0.15), 'Lower, rounder bubble pop for back/cancel.'),
    ('sfx_ui_open', sfx_ui_open, 'sfx', (0.15, 0.4), 'Panel open: airy upward swoosh into a rising "bloop".'),
    ('sfx_ui_close', sfx_ui_close, 'sfx', (0.12, 0.35), 'Panel close: downward swoosh into a falling "bloop".'),
    ('sfx_coin', sfx_coin, 'sfx', (0.2, 0.4), 'Bright E6-B6 coin "bling" with metallic ring and sparkles.'),
    ('sfx_coin_tick', sfx_coin_tick, 'sfx', (0.01, 0.06), 'Tiny high E7 tick for coin count-up.'),
    ('sfx_star1', sfx_star1, 'sfx', (0.3, 0.6), 'Star 1: celesta chime G5->C6 with warm pan body and twinkles.'),
    ('sfx_star2', sfx_star2, 'sfx', (0.4, 0.7), 'Star 2: brighter chime C6->E6, more sparkle.'),
    ('sfx_star3', sfx_star3, 'sfx', (0.6, 1.0), 'Star 3 (biggest): E6-G6-C7 chime run, G7 sparkle and shimmer tail.'),
    ('sfx_unlock', sfx_unlock, 'sfx', (0.8, 1.2), 'Magical unlock: C5-E5-G5-C6 steel-pan run, bell chord, sparkle tail.'),
    ('sfx_chest_open', sfx_chest_open, 'sfx', (1.0, 1.6), 'Hinge creak (stick-slip), lid thunk, then a bell/pan sparkle burst with magic dust.'),
    ('sfx_wheel_tick', sfx_wheel_tick, 'sfx', (0.01, 0.05), 'Short wooden click for each prize-wheel peg.'),
    ('sfx_wheel_win', sfx_wheel_win, 'sfx', (0.8, 1.3), 'Short brass + steel-pan fanfare (C-F-C) with timpani hit and bells.'),
    ('sfx_countdown', sfx_countdown, 'sfx', (0.1, 0.25), 'Friendly A5 beep with a woody marimba onset for 3-2-1.'),
    ('sfx_go', sfx_go, 'sfx', (0.8, 1.2), 'Big start: A6 beep + A-major horn hit + full water SPLASH and whoosh.'),
    ('sfx_step_vinyl1', sfx_step_vinyl1, 'sfx', (0.06, 0.16), 'Inflatable footstep: soft vinyl "thup" with a short rubbery squeak (up).'),
    ('sfx_step_vinyl2', sfx_step_vinyl2, 'sfx', (0.06, 0.16), 'Inflatable footstep variation: slightly higher thup, squeak bends down.'),
    ('sfx_step_wood', sfx_step_wood, 'sfx', (0.04, 0.12), 'Wooden plank footstep (modal knock + low thud).'),
    ('sfx_step_stone', sfx_step_stone, 'sfx', (0.04, 0.12), 'Stone footstep: gritty scuff + dull thud.'),
    ('sfx_step_ice', sfx_step_ice, 'sfx', (0.04, 0.13), 'Ice footstep: crisp crunch grains with a tiny icy tink.'),
    ('sfx_bonk', sfx_bonk, 'sfx', (0.25, 0.45), 'Rubbery cartoon hit: noise "thwap" + thud + wobbling pitch-drop boing.'),
    ('sfx_squeak', sfx_squeak, 'sfx', (0.2, 0.35), 'Inflatable toy squeak: reedy tone bending up and back with air.'),
    ('sfx_boing', sfx_boing, 'sfx', (0.4, 0.65), 'Jump-pad spring "boyoyoing": wobbling pitch + jaw-harp formant sweep.'),
    ('sfx_trampoline', sfx_trampoline, 'sfx', (0.6, 0.9), 'Deeper bouncy trampoline: membrane whomp + slow low boing.'),
    ('sfx_zip_loop', sfx_zip_loop, 'loop', (1.45, 1.55), 'Zipline LOOP: trolley-wheel whine, metallic cable zing, hiss, rumble and rattles.'),
    ('sfx_slide_loop', sfx_slide_loop, 'loop', (1.95, 2.05), 'Water-slide LOOP: rushing water with swells, spray hiss and a stream of bubbles.'),
    ('sfx_rope_creak', sfx_rope_creak, 'sfx', (0.5, 0.8), 'Rope creak: two stick-slip creaks through rope/post resonances.'),
    ('sfx_climb', sfx_climb, 'sfx', (0.1, 0.22), 'Hand grab on plastic grip: quick swish, hollow plastic tap, tiny rubber squeak.'),
    ('sfx_splash_big', sfx_splash_big, 'sfx', (0.9, 1.2), 'Body falls into water: slap, plunge thump, spray, bubbles, glugs and drips.'),
    ('sfx_splash_small', sfx_splash_small, 'sfx', (0.25, 0.5), 'Small splash: light slap, short spray, a few bubbles and droplets.'),
    ('sfx_wind_gust', sfx_wind_gust, 'sfx', (1.1, 1.3), 'Fan gust: swelling band-passed wind with low body, whistle and blade flutter.'),
    ('sfx_wave_surge', sfx_wave_surge, 'sfx', (1.1, 1.3), 'Wave surge: rising rumble to a crest, white-water wash and fizz.'),
    ('sfx_cannon_pop', sfx_cannon_pop, 'sfx', (0.3, 0.5), 'Beach-ball cannon "foomp": air burst, low thump and tube pop.'),
    ('sfx_glove_punch', sfx_glove_punch, 'sfx', (0.35, 0.6), 'Spring-loaded boxing glove: sproing + swish + padded leather impact.'),
    ('sfx_press_thump', sfx_press_thump, 'sfx', (0.4, 0.7), 'Heavy soft press: deep padded thump with an air squish.'),
    ('sfx_trapdoor', sfx_trapdoor, 'sfx', (0.15, 0.32), 'Plastic trapdoor clack-clack with a small rattle.'),
    ('sfx_gate', sfx_gate, 'sfx', (0.5, 0.75), 'Sliding gate: whoosh-roll then a solid clunk.'),
    ('sfx_paddle', sfx_paddle, 'sfx', (0.5, 0.85), 'Water-wheel paddle: slap into water, slosh, pour-off bubbles, axle creak.'),
    ('sfx_checkpoint', sfx_checkpoint, 'sfx', (0.7, 1.0), 'Checkpoint: bright G5-C6-E6 pan/bell chime with a flag flutter.'),
    ('sfx_nice', sfx_nice, 'sfx', (0.4, 0.65), 'Short positive stinger: steel pan A5 -> D6+F#6 with marimba.'),
    ('sfx_perfect', sfx_perfect, 'sfx', (0.9, 1.2), 'Bigger sparkly stinger: pan run into C-major chord, glock sparkles, shimmer.'),
    ('sfx_turbo', sfx_turbo, 'sfx', (0.7, 0.95), 'Turbo: rocket jet sweep with rising tone, crackle and ignition thump.'),
    ('sfx_whoosh', sfx_whoosh, 'sfx', (0.2, 0.35), 'Quick swish (band-pass sweep up then down).'),
    ('sfx_pop', sfx_pop, 'sfx', (0.05, 0.1), 'Bubble pop.'),
    ('sfx_powerup', sfx_powerup, 'sfx', (0.45, 0.7), 'Booster activation: rising G-major arpeggio over a charging sweep, sparkle.'),
    ('sfx_shield_pop', sfx_shield_pop, 'sfx', (0.3, 0.55), 'Soap-bubble shield bursting: film snap, soft fwup, falling shimmer, droplets.'),
    ('sfx_cheer', sfx_cheer, 'sfx', (1.8, 2.1), 'Crowd cheer (noise-shaped "yaaay" formants, individual yells, applause, whistles).'),
    ('sfx_win_fanfare', sfx_win_fanfare, 'sfx', (2.3, 2.7), 'Happy fanfare: steel-pan pickup, brass F-G-C hits, timpani, crash, pan roll.'),
    ('sfx_lose', sfx_lose, 'sfx', (1.1, 1.5), 'Gentle "aww": muted-trombone E4-D4-C4 with droop and wobble.'),
    ('sfx_error', sfx_error, 'sfx', (0.2, 0.3), 'Soft low double buzz.'),
    ('sfx_tide_loop', sfx_tide_loop, 'loop', (1.95, 2.05), 'Lagoon tide LOOP: low rolling water rumble, slow swell, glugs and fizz.'),
    ('sfx_duck_quack', sfx_duck_quack, 'sfx', (0.4, 0.6), 'Giant rubber-duck honk-squeak (raspy reed through nasal formants).'),
    ('sfx_thunder', sfx_thunder, 'sfx', (1.6, 2.4), 'Soft cartoon thunder: muffled crack and rolling low rumble.'),
    ('music_menu', music_menu, 'music', (28.0, 34.0), 'Sunny calypso menu theme, F major, 100 bpm, 12 bars: steel-pan lead, marimba comping, soft pad, round bass, shaker, bongos, clave.'),
    ('music_race', music_race, 'music', (28.0, 32.0), 'Upbeat soca-pop race theme, C major, 135 bpm, 16 bars: steel-pan hook, ukulele strums, xylophone lift, synth bass, kick/snare/claps, bongos.'),
    ('music_race_night', music_race_night, 'music', (28.0, 32.0), 'Neon night race, A minor, 132 bpm, 16 bars: synth-pluck hook with delay, 16th arps, pumping pad, octave bass, claps, risers.'),
    ('music_boss', music_boss, 'music', (24.0, 30.0), 'Tense-but-fun boss chase, D minor, 150 bpm, 16 bars: staccato string ostinato, brassy lead, galloping bass, toms, timpani, xylophone.'),
    ('music_bonus', music_bonus, 'music', (24.0, 30.0), 'Carefree coin rush, G major, 125 bpm, 14 bars (A, A2, B + 2-bar turnaround): whistle lead, xylophone skank, bouncy bass, claps, tambourine, coin sparkles.'),
]


def generate(names):
    os.makedirs(RAW_DIR, exist_ok=True)
    for name, fn, kind, rng_, desc in SOUNDS:
        if names and name not in names:
            continue
        t0 = time.time()
        if DEBUG_STEMS and kind == 'music':
            print('  %s:' % name)
        x = fn()
        write_wav(os.path.join(RAW_DIR, name + '.wav'), x)
        print('  %-20s %7.3f s   rendered in %.2fs' % (name, len(x) / SR, time.time() - t0))


def _db(v):
    return 20.0 * math.log10(v) if v > 0 else -999.0


def riff_chunks(path):
    with open(path, 'rb') as fh:
        b = fh.read()
    ids, pos = [], 12
    while pos + 8 <= len(b):
        cid = b[pos:pos + 4].decode('latin-1')
        size = int.from_bytes(b[pos + 4:pos + 8], 'little')
        ids.append(cid)
        pos += 8 + size + (size & 1)
    return b[:4] == b'RIFF' and b[8:12] == b'WAVE', ids


def verify():
    """Re-open every WAV and check format, RIFF layout, duration, peak, DC,
    clipping, clean starts/ends (one-shots), seam continuity (loops) and
    loudness (music); then the total size budget."""
    rows, ok_all = [], True
    print('\n%-20s %8s %7s %8s %7s %7s  %s' % ('file', 'dur(s)', 'peakdB', 'level', 'size kB', 'DC', 'edges / seam'))
    for name, fn, kind, (dmin, dmax), desc in SOUNDS:
        path = os.path.join(RAW_DIR, name + '.wav')
        if not os.path.exists(path):
            print('%-20s MISSING' % name)
            ok_all = False
            continue
        (ch, sw, fr, nf), d = read_wav(path)
        probs = []
        if (ch, sw, fr) != (1, 2, SR):
            probs.append('format %r' % ((ch, sw, fr),))
        is_riff, chunks = riff_chunks(path)
        if not is_riff or chunks != ['fmt ', 'data']:
            probs.append('riff %r' % chunks)
        dur = nf / float(fr)
        if not dmin <= dur <= dmax:
            probs.append('duration')
        x = [v / 32768.0 for v in d]
        pk = peak(x)
        target = MUSIC_PEAK_DB if kind == 'music' else SFX_PEAK_DB
        if kind == 'music':
            if _db(pk) > MUSIC_PEAK_DB + 0.05 or _db(pk) < MUSIC_PEAK_DB - 1.5:
                probs.append('peak')
        elif abs(_db(pk) - target) > 0.25:
            probs.append('peak')
        dc = sum(x) / len(x)
        if abs(dc) > 0.002:
            probs.append('DC')
        clipped = sum(1 for v in d if v >= 32767 or v <= -32768)
        if clipped:
            probs.append('clipped x%d' % clipped)
        if kind == 'sfx':
            edge = 'first=%d last=%d LSB' % (d[0], d[-1])
            if abs(d[0]) > 2 or abs(d[-1]) > 2:
                probs.append('edge click')
            level = '%5.1f rms' % _db(rms(x))
        else:
            diffs = sorted(abs(b - a) for a, b in zip(d, d[1:]))
            p99 = diffs[int(0.99 * (len(diffs) - 1))] or 1
            jump = abs(d[0] - d[-1])
            d2 = sorted(abs(c - 2 * b + a) for a, b, c in zip(d, d[1:], d[2:]))
            p99b = d2[int(0.99 * (len(d2) - 1))] or 1
            jump2 = max(abs(d[1] - 2 * d[0] + d[-1]), abs(d[0] - 2 * d[-1] + d[-2]))
            whole = rms(x) or 1e-9
            q = ns(0.25)
            hl, tl = _db(rms(x[:q]) / whole), _db(rms(x[-q:]) / whole)
            edge = ('seam step %d (p99 %d), 2nd-diff %d (p99 %d); head %+.1f / tail %+.1f dB'
                    % (jump, p99, jump2, p99b, hl, tl))
            if jump > p99 or jump2 > p99b or min(hl, tl) < -24.0:
                probs.append('seam')
            if kind == 'music':
                lv = lufs(x)
                level = '%5.1f LUFS' % lv
                if not -16.0 <= lv <= -14.0:
                    probs.append('loudness')
            else:
                level = '%5.1f rms' % _db(rms(x))
        status = 'OK' if not probs else 'FAIL: ' + ', '.join(probs)
        ok_all = ok_all and not probs
        size = os.path.getsize(path)
        print('%-20s %8.3f %7.2f %10s %7.1f %7.4f  %s  %s' % (name, dur, _db(pk), level, size / 1024.0, dc, edge, status))
        rows.append((name, kind, dur, _db(pk), size, level.strip(), desc))
    total = sum(os.path.getsize(os.path.join(RAW_DIR, f)) for f in os.listdir(RAW_DIR)) if os.path.isdir(RAW_DIR) else 0
    if total > SIZE_LIMIT:
        ok_all = False
    print('\n%d files, res/raw total %.2f MB (%d bytes, limit %d)  ->  %s'
          % (len(rows), total / 1e6, total, SIZE_LIMIT, 'ALL CHECKS PASSED' if ok_all else 'SOME CHECKS FAILED'))
    return ok_all, rows, total


def write_manifest(rows, total):
    kinds = {'sfx': 'one-shot', 'loop': 'loop', 'music': 'music loop'}
    lines = [
        '# Splash Dash: Obstacle Party - audio manifest',
        '',
        'All audio is 100% original and procedurally synthesised by `tools/gen_audio.py`',
        '(Python 3 standard library only, fixed seeds, no samples, no downloads, bit-identical on',
        'every run). Regenerate everything from the project root with:',
        '',
        '    python3 tools/gen_audio.py',
        '',
        'Format: WAV (plain RIFF with only `fmt ` + `data` chunks), PCM 16-bit, mono, 22050 Hz, in',
        '`app/src/main/res/raw/`.',
        '',
        '- One-shot SFX: silence trimmed, raised-cosine fade-in (>= 3 ms) and fade-out (>= 5 ms, longer',
        '  on ringing tails), peak-normalised to -1 dBFS. They start and end on exact zero.',
        '- Loops (`sfx_*_loop`, `music_*`) are rendered circularly: note releases, echoes, reverb and',
        '  filter tails that ring past the loop end are folded back onto the start, so they repeat',
        '  seamlessly (`MediaPlayer.isLooping = true` / SoundPool `loop = -1` / custom streamer).',
        '- Music is mastered to about -15 LUFS integrated (BS.1770-style K-weighted, gated) with a',
        '  circular look-ahead limiter at -3 dBFS, so it sits under the louder, punchier SFX.',
        '  Relative balance between sounds can still be tuned in-game via playback volume.',
        '',
        '| File | Type | Duration (s) | Size (KB) | Peak (dBFS) | Level | Description |',
        '|---|---|---:|---:|---:|---|---|',
    ]
    for name, kind, dur, pk, size, level, desc in rows:
        lines.append('| `%s.wav` | %s | %.3f | %.1f | %.1f | %s | %s |'
                     % (name, kinds[kind], dur, size / 1024.0, pk, level, desc))
    music = [(r[0], r[2]) for r in rows if r[1] == 'music']
    lines += [
        '',
        'Music loop lengths are sample-exact whole bars (' +
        ', '.join('%s %.3f s' % (n.replace('music_', ''), d) for n, d in music) + ').',
        '',
        'Total: %d files, %.2f MB (%d bytes) in res/raw (budget 8 MB).' % (len(rows), total / 1e6, total),
        '',
    ]
    with open(MANIFEST_PATH, 'w') as fh:
        fh.write('\n'.join(lines))


def main(argv):
    global DEBUG_STEMS
    flags = {a for a in argv if a.startswith('--')}
    names = [a for a in argv if not a.startswith('--')]
    known = {s[0] for s in SOUNDS}
    bad = [n for n in names if n not in known]
    if bad:
        print('unknown sound(s): %s' % ', '.join(bad))
        return 2
    DEBUG_STEMS = '--stems' in flags
    t0 = time.time()
    if '--verify' not in flags:
        print('Rendering to %s' % RAW_DIR)
        generate(names)
        print('generation time: %.1f s' % (time.time() - t0))
    ok, rows, total = verify()
    if len(rows) == len(SOUNDS):
        write_manifest(rows, total)
        print('manifest: %s' % MANIFEST_PATH)
    print('total time: %.1f s' % (time.time() - t0))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
