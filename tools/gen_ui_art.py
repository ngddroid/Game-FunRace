#!/usr/bin/env python3
"""Splash Dash: Obstacle Party - procedural 2D UI icon generator.

Every UI icon (HUD, buttons, shop, rewards, settings) is drawn from code with Pillow
only: no external images, no fonts, no unseeded randomness, so every run writes
byte-identical PNGs.

Run from the project root (plain `python3`, not `-I`, so a user-site Pillow is found):

    python3 tools/gen_ui_art.py               # all icons + tools/ART_MANIFEST.md
    python3 tools/gen_ui_art.py coin gem      # only some icons (names without the ui_ prefix)
    python3 tools/gen_ui_art.py --list        # list icon names

Output: app/src/main/res/drawable-nodpi/ui_*.png, 256x256 RGBA, transparent background.

Technique (shared with the studio's SnowRace generator): each icon is drawn at 4x
supersampling in a 256-unit coordinate space using layered masks, gradient fills, a
glossy "premium casual" shader (core shadow, bounce light, specular blob), a thick ink
outline made by exact Minkowski dilation plus a short downward extrusion, and is then
fitted (bounding box -> ~88% of the canvas, centred) and downscaled with LANCZOS.
Lighting is always from the top-left.
"""
import math
import os
import random
import sys

try:
    from PIL import Image, ImageChops, ImageDraw, ImageFilter
except ImportError:  # `python3 -I` drops the user site-packages dir, where Pillow may live.
    import site

    _user_site = site.getusersitepackages()
    if _user_site and _user_site not in sys.path:
        sys.path.append(_user_site)
    from PIL import Image, ImageChops, ImageDraw, ImageFilter

_RS = getattr(Image, "Resampling", Image)
LANCZOS, BICUBIC = _RS.LANCZOS, _RS.BICUBIC

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NODPI = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
MANIFEST = os.path.join(ROOT, "tools", "ART_MANIFEST.md")

SS = 4          # supersampling factor
_LAST_FIT = [1.0]
OUT = 256       # output size (px)
TARGET = 226.0  # longest side of the fitted icon, incl. outline (~88% of 256)

# --------------------------------------------------------------------------
# Colour
# --------------------------------------------------------------------------


def hx(s, a=255):
    s = s.lstrip("#")
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)


def rgba(c):
    c = tuple(c)
    return c + (255,) * (4 - len(c))


def mix(c1, c2, t):
    c1, c2 = rgba(c1), rgba(c2)
    return tuple(int(round(c1[i] + (c2[i] - c1[i]) * t)) for i in range(4))


def lighten(c, t):
    return mix(c, (255, 255, 255, rgba(c)[3]), t)


def shade(c, t):
    """Darken toward a cool deep ink-blue (keeps shadows saturated, never grey)."""
    return mix(c, (24, 40, 92, rgba(c)[3]), t)


def alpha(c, a):
    c = rgba(c)
    return c[:3] + (int(a),)


# Palette (art bible). *_L = derived highlight, *_DD = derived extra-deep shade.
INK = hx("#13304A")
LAG, LAG_D, LAG_L, LAG_DD = hx("#19C6C2"), hx("#0E8F9E"), hx("#A4F3EE"), hx("#0A6677")
OCE, OCE_D, OCE_L, OCE_DD = hx("#2E9BFF"), hx("#1B6FD1"), hx("#A8DAFF"), hx("#134F9E")
COR, COR_D, COR_L, COR_DD = hx("#FF6B57"), hx("#E04A3A"), hx("#FFB7A9"), hx("#AE2F28")
SUN, SUN_D, SUN_L, SUN_DD = hx("#FFD23F"), hx("#F2A90F"), hx("#FFF2A8"), hx("#C27606")
LIM, LIM_D, LIM_L, LIM_DD = hx("#7BE36B"), hx("#34B54A"), hx("#D3FBC2"), hx("#21863A")
PNK, PNK_D, PNK_L, PNK_DD = hx("#FF5FA8"), hx("#D93A86"), hx("#FFB8D9"), hx("#A5256A")
PUR, PUR_D, PUR_L, PUR_DD = hx("#9B6BFF"), hx("#6B3FD9"), hx("#D4C1FF"), hx("#4C2BA6")
SAND, SAND_D, SAND_L, SAND_DD = hx("#FFE9B8"), hx("#E8C27C"), hx("#FFF8E8"), hx("#C29453")
FOAM, FOAM_D = hx("#F6FFFE"), hx("#A9D5E1")
WHITE = hx("#FFFFFF")

# Supporting materials (derived from the palette, used sparingly)
STEEL, STEEL_L, STEEL_D = hx("#BBD3E4"), hx("#F3FAFF"), hx("#7193B3")
WOOD, WOOD_L, WOOD_D = hx("#D98A4A"), hx("#FFC58C"), hx("#8F4E22")
DEV, DEV_L, DEV_D = hx("#2C5E8A"), hx("#6299C6"), hx("#173B5C")      # device bodies (phone, speaker)
BEAK, BEAK_L, BEAK_D = hx("#FF8A3D"), hx("#FFC28F"), hx("#D9581F")   # rubber-duck orange
SKIN, SKIN_L, SKIN_D = hx("#FFD6B3"), hx("#FFF1E4"), hx("#EDA67C")
HAIR, HAIR_L, HAIR_D = hx("#8A5230"), hx("#C4834F"), hx("#5A311A")
SILV, SILV_L, SILV_D = hx("#D6E3EE"), hx("#FFFFFF"), hx("#8AA2BA")
BRNZ, BRNZ_L, BRNZ_D = hx("#E59A5E"), hx("#FFD0A4"), hx("#A65A2B")
CLOUD, CLOUD_L, CLOUD_D = hx("#B9C3EA"), hx("#F1F4FF"), hx("#7A86C4")
ROAD, ROAD_L, ROAD_D = hx("#5B6E9E"), hx("#8A9CCB"), hx("#34446E")

# --------------------------------------------------------------------------
# Math helpers
# --------------------------------------------------------------------------


def clamp01(v):
    return 0.0 if v < 0 else (1.0 if v > 1 else v)


def lerp(a, b, t):
    return a + (b - a) * t


def smooth(t):
    t = clamp01(t)
    return t * t * (3 - 2 * t)


def rot_pts(pts, cx, cy, deg):
    """Rotate points around (cx, cy); positive degrees turn clockwise on screen."""
    a = math.radians(deg)
    ca, sa = math.cos(a), math.sin(a)
    return [(cx + (x - cx) * ca - (y - cy) * sa, cy + (x - cx) * sa + (y - cy) * ca) for x, y in pts]


def ellipse_pts(cx, cy, rx, ry, ang=0.0, n=96):
    pts = [(cx + rx * math.cos(2 * math.pi * i / n), cy + ry * math.sin(2 * math.pi * i / n)) for i in range(n)]
    return rot_pts(pts, cx, cy, ang) if ang else pts


def rrect_pts(x0, y0, x1, y1, r, n=10):
    r = max(0.0, min(r, (x1 - x0) / 2.0, (y1 - y0) / 2.0))
    pts = []
    for cx, cy, a0 in ((x1 - r, y0 + r, -90), (x1 - r, y1 - r, 0), (x0 + r, y1 - r, 90), (x0 + r, y0 + r, 180)):
        for i in range(n + 1):
            a = math.radians(a0 + 90.0 * i / n)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def star_pts(cx, cy, ro, ri, n=5, rot=-90.0):
    pts = []
    for i in range(2 * n):
        r = ro if i % 2 == 0 else ri
        a = math.radians(rot + 180.0 * i / n)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def twinkle_pts(cx, cy, r, p=2.3, rot=0.0, n=72):
    """Four-point sparkle with concave sides (super-astroid)."""
    pts = []
    for i in range(n):
        t = 2 * math.pi * i / n
        c, s = math.cos(t), math.sin(t)
        pts.append((cx + r * math.copysign(abs(c) ** p, c), cy + r * math.copysign(abs(s) ** p, s)))
    return rot_pts(pts, cx, cy, rot) if rot else pts


def catmull(pts, closed=True, n=14):
    out = []
    P = list(pts)
    m = len(P)
    rng = range(m) if closed else range(m - 1)
    for i in rng:
        p0 = P[(i - 1) % m] if closed else P[max(i - 1, 0)]
        p1 = P[i]
        p2 = P[(i + 1) % m] if closed else P[min(i + 1, m - 1)]
        p3 = P[(i + 2) % m] if closed else P[min(i + 2, m - 1)]
        for k in range(n):
            t = k / float(n)
            t2, t3 = t * t, t * t * t
            out.append(tuple(
                0.5 * ((2 * p1[q]) + (-p0[q] + p2[q]) * t + (2 * p0[q] - 5 * p1[q] + 4 * p2[q] - p3[q]) * t2
                       + (-p0[q] + 3 * p1[q] - 3 * p2[q] + p3[q]) * t3) for q in (0, 1)))
    if not closed:
        out.append(tuple(P[-1]))
    return out


def arc_pts(cx, cy, r, a0, a1, n=48):
    return [(cx + r * math.cos(math.radians(lerp(a0, a1, i / float(n)))),
             cy + r * math.sin(math.radians(lerp(a0, a1, i / float(n))))) for i in range(n + 1)]


def _cum_len(pts):
    L = [0.0]
    for i in range(1, len(pts)):
        L.append(L[-1] + math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]))
    return L


def offset_strip(center, off_fn, w_fn):
    """Closed polygon of a strip that follows a centre polyline at offset off_fn(t) with width w_fn(t).
    The normal is (-dy, dx): for a path heading right it points down (screen space)."""
    n = len(center)
    L = _cum_len(center)
    total = L[-1] or 1.0
    left, right = [], []
    for i in range(n):
        a = center[max(i - 1, 0)]
        b = center[min(i + 1, n - 1)]
        dx, dy = b[0] - a[0], b[1] - a[1]
        ln = math.hypot(dx, dy) or 1.0
        nx, ny = -dy / ln, dx / ln
        t = L[i] / total
        o, hw = off_fn(t), w_fn(t) / 2.0
        left.append((center[i][0] + nx * (o + hw), center[i][1] + ny * (o + hw)))
        right.append((center[i][0] + nx * (o - hw), center[i][1] + ny * (o - hw)))
    return left + right[::-1]


def taper_pts(center, wfn):
    return offset_strip(center, lambda t: 0.0, wfn)


def resample(pts, step):
    """Evenly spaced points along a polyline."""
    out = [tuple(pts[0])]
    carry = 0.0
    for i in range(1, len(pts)):
        x0, y0 = pts[i - 1]
        x1, y1 = pts[i]
        d = math.hypot(x1 - x0, y1 - y0)
        pos = step - carry
        while pos <= d:
            t = pos / d
            out.append((x0 + (x1 - x0) * t, y0 + (y1 - y0) * t))
            pos += step
        carry = d - (pos - step)
    return out


# --------------------------------------------------------------------------
# Masks (mode "L")
# --------------------------------------------------------------------------


def mnew(size, v=0):
    return Image.new("L", size, v)


def m_poly(size, pts):
    m = mnew(size)
    if len(pts) >= 3:
        ImageDraw.Draw(m).polygon([(float(x), float(y)) for x, y in pts], fill=255)
    return m


def m_ellipse(size, box):
    m = mnew(size)
    ImageDraw.Draw(m).ellipse([float(v) for v in box], fill=255)
    return m


def m_circle(size, cx, cy, r):
    return m_ellipse(size, (cx - r, cy - r, cx + r, cy + r))


def m_ell(size, cx, cy, rx, ry, ang=0.0):
    if not ang:
        return m_ellipse(size, (cx - rx, cy - ry, cx + rx, cy + ry))
    return m_poly(size, ellipse_pts(cx, cy, rx, ry, ang, 120))


def m_rrect(size, box, r):
    m = mnew(size)
    x0, y0, x1, y1 = [float(v) for v in box]
    r = max(0.0, min(r, (x1 - x0) / 2.0 - 0.5, (y1 - y0) / 2.0 - 0.5))
    ImageDraw.Draw(m).rounded_rectangle((x0, y0, x1, y1), radius=r, fill=255)
    return m


def m_line(size, pts, w, cap=True):
    m = mnew(size)
    d = ImageDraw.Draw(m)
    pts = [(float(x), float(y)) for x, y in pts]
    if len(pts) >= 2:
        d.line(pts, fill=255, width=max(1, int(round(w))), joint="curve")
    if cap:
        r = w / 2.0
        for (x, y) in (pts[0], pts[-1]):
            d.ellipse((x - r, y - r, x + r, y + r), fill=255)
    return m


def m_union(*ms):
    out = ms[0]
    for m in ms[1:]:
        out = ImageChops.lighter(out, m)
    return out


def m_inter(a, b):
    return ImageChops.multiply(a, b)


def m_sub(a, b):
    return ImageChops.subtract(a, b)


def m_shift(m, dx, dy, fill=0):
    out = Image.new("L", m.size, fill)
    out.paste(m, (int(round(dx)), int(round(dy))))
    return out


def m_blur(m, r):
    return m.filter(ImageFilter.GaussianBlur(r)) if r > 0.3 else m


def m_mul(m, k):
    if k >= 0.999:
        return m
    return m.point([int(v * k + 0.5) for v in range(256)])


def _crop_box(m, pad):
    bb = m.getbbox()
    if bb is None:
        return None
    W, H = m.size
    return (max(0, bb[0] - pad), max(0, bb[1] - pad), min(W, bb[2] + pad), min(H, bb[3] + pad))


def _ring(m, s, fill=0):
    W, H = m.size
    n = max(8, int(math.ceil(2 * math.pi * s / 2.2)))
    seen = set()
    out = m
    for i in range(n):
        a = 2 * math.pi * i / n
        dx, dy = int(round(s * math.cos(a))), int(round(s * math.sin(a)))
        if (dx, dy) in seen or (dx == 0 and dy == 0):
            continue
        seen.add((dx, dy))
        sh = Image.new("L", (W, H), fill)
        sh.paste(m, (dx, dy))
        out = ImageChops.lighter(out, sh)
    return out


def m_dilate(m, r):
    """Exact-ish Minkowski dilation by a disc: two ring passes of radius r/2 (C_r/2 + C_r/2 = D_r)."""
    if r < 0.5:
        return m
    box = _crop_box(m, int(math.ceil(r)) + 2)
    if box is None:
        return m
    sub = m.crop(box)
    sub = _ring(sub, r) if r <= 3 else _ring(_ring(sub, r / 2.0), r / 2.0)
    out = mnew(m.size)
    out.paste(sub, box[:2])
    return out


def m_erode(m, r):
    if r < 0.5:
        return m
    box = _crop_box(m, int(math.ceil(r)) + 2)
    if box is None:
        return m
    sub = ImageChops.invert(m.crop(box))
    sub = _ring(sub, r, 255) if r <= 3 else _ring(_ring(sub, r / 2.0, 255), r / 2.0, 255)
    out = mnew(m.size)
    out.paste(ImageChops.invert(sub), box[:2])
    return out


def m_open(m, r):
    return m_dilate(m_erode(m, r), r)


def m_cloud(size, circles):
    m = mnew(size)
    d = ImageDraw.Draw(m)
    for (cx, cy, r) in circles:
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=255)
    return m


# --------------------------------------------------------------------------
# Painting
# --------------------------------------------------------------------------


def paint_fill(cv, mask, color, opacity=1.0):
    bb = mask.getbbox()
    if bb is None:
        return
    color = rgba(color)
    m = m_mul(mask.crop(bb), color[3] / 255.0 * opacity)
    layer = Image.new("RGBA", m.size, color[:3] + (255,))
    layer.putalpha(m)
    cv.alpha_composite(layer, dest=(bb[0], bb[1]))


def field_L(w, h, fn, N=64):
    gw, gh = max(2, min(N, w)), max(2, min(N, h))
    sx, sy = w / float(gw), h / float(gh)
    data = []
    for j in range(gh):
        y = (j + 0.5) * sy
        for i in range(gw):
            v = fn((i + 0.5) * sx, y)
            data.append(0 if v <= 0 else (255 if v >= 1 else int(v * 255 + 0.5)))
    g = Image.new("L", (gw, gh))
    g.putdata(data)
    if (gw, gh) != (w, h):
        g = g.resize((w, h), BICUBIC)
    return g


def ramp_luts(stops):
    st = sorted([(float(t), rgba(c)) for t, c in stops], key=lambda s: s[0])
    luts = [[], [], [], []]
    for i in range(256):
        t = i / 255.0
        c = st[-1][1]
        if t <= st[0][0]:
            c = st[0][1]
        else:
            for k in range(len(st) - 1):
                if st[k][0] <= t <= st[k + 1][0]:
                    u = (t - st[k][0]) / max(1e-9, st[k + 1][0] - st[k][0])
                    c = tuple(st[k][1][q] + (st[k + 1][1][q] - st[k][1][q]) * u for q in range(4))
                    break
        for q in range(4):
            luts[q].append(int(round(c[q])))
    return luts


def ramp_img(L, stops):
    lr, lg, lb, la = ramp_luts(stops)
    return Image.merge("RGBA", (L.point(lr), L.point(lg), L.point(lb), L.point(la)))


def lin_fn(p0, p1):
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    L2 = float(dx * dx + dy * dy) or 1.0
    return lambda x, y: ((x - p0[0]) * dx + (y - p0[1]) * dy) / L2


def rad_fn(c, rx, ry=None):
    ry = ry or rx
    return lambda x, y: math.hypot((x - c[0]) / rx, (y - c[1]) / ry)


def paint_grad(cv, mask, fn, stops, opacity=1.0, N=64):
    bb = mask.getbbox()
    if bb is None:
        return
    x0, y0, x1, y1 = bb
    L = field_L(x1 - x0, y1 - y0, lambda x, y: fn(x + x0, y + y0), N)
    layer = ramp_img(L, stops)
    a = m_mul(ImageChops.multiply(layer.getchannel("A"), mask.crop(bb)), opacity)
    layer.putalpha(a)
    cv.alpha_composite(layer, dest=(x0, y0))


def glossy(cv, mask, base, light=None, dark=None, kind="sphere", lc=(0.34, 0.28), R=1.0,
           core=0.45, core_d=0.13, rim=0.0, rim_d=0.05, spec=0.75, spec_box=None, spec_ang=-28.0,
           spec_blur=0.03, bounce=0.0, bounce_c=None, stops=None):
    """Premium casual-game shading: gradient body, core shadow, rim light, bounce light, specular."""
    bb = mask.getbbox()
    if bb is None:
        return
    w, h = bb[2] - bb[0], bb[3] - bb[1]
    s = float(min(w, h))
    box = _crop_box(mask, int(max(w, h) * 0.2) + 4)
    ox, oy = box[0], box[1]
    sub = cv.crop(box)
    m = mask.crop(box)
    x0, y0, x1, y1 = bb[0] - ox, bb[1] - oy, bb[2] - ox, bb[3] - oy
    base = rgba(base)
    light = rgba(light) if light is not None else lighten(base, 0.42)
    dark = rgba(dark) if dark is not None else shade(base, 0.32)
    if kind == "sphere":
        cx, cy = x0 + lc[0] * w, y0 + lc[1] * h
        rr = R * max(w, h)
        paint_grad(sub, m, lambda x, y: math.hypot(x - cx, y - cy) / rr,
                   stops or [(0, light), (0.5, base), (1.0, dark)])
    elif kind == "vertical":
        paint_grad(sub, m, lambda x, y: (y - y0) / float(h), stops or [(0, light), (0.55, base), (1.0, dark)])
    elif kind == "diag":
        paint_grad(sub, m, lambda x, y: ((x - x0) / float(w) * 0.45 + (y - y0) / float(h) * 0.55),
                   stops or [(0, light), (0.5, base), (1.0, dark)])
    else:
        paint_fill(sub, m, base)
    if core > 0:
        d = core_d * s
        cr = m_sub(m, m_shift(m, -0.45 * d, -d))
        cr = m_inter(m_blur(cr, 0.45 * d), m)
        paint_fill(sub, cr, dark, core)
    if bounce > 0:
        d = 0.045 * s
        br = m_sub(m, m_shift(m, 0.2 * d, -d))
        br = m_inter(m_blur(br, 0.35 * d), m)
        paint_fill(sub, br, bounce_c or lighten(base, 0.35), bounce)
    if rim > 0:
        d = rim_d * s
        rm = m_sub(m, m_shift(m, 0.55 * d, d))
        rm = m_inter(m_blur(rm, 0.45 * d), m)
        paint_fill(sub, rm, WHITE, rim)
    if spec > 0:
        if spec_box is None:
            sb = (x0 + 0.16 * w, y0 + 0.08 * h, x0 + 0.48 * w, y0 + 0.28 * h)
        else:
            sb = (spec_box[0] - ox, spec_box[1] - oy, spec_box[2] - ox, spec_box[3] - oy)
        sm = m_ell(sub.size, (sb[0] + sb[2]) / 2.0, (sb[1] + sb[3]) / 2.0, (sb[2] - sb[0]) / 2.0,
                   (sb[3] - sb[1]) / 2.0, spec_ang)
        sm = m_inter(m_blur(sm, spec_blur * s), m)
        paint_fill(sub, sm, WHITE, spec)
    cv.paste(sub, (ox, oy))


def draw_sparkle(cv, cx, cy, r, color=WHITE, ow=0.0, rot=0.0):
    m = m_poly(cv.size, twinkle_pts(cx, cy, r, 2.35, rot))
    if ow > 0:
        paint_fill(cv, m_dilate(m, ow), INK)
    paint_fill(cv, m, color)


# --------------------------------------------------------------------------
# Icon canvas
# --------------------------------------------------------------------------


class Ico:
    """256-unit icon drawn at SS x on a padded canvas so nothing can be clipped.
    done() fits the art's bounding box to TARGET (longest side), centres it and downsamples."""
    # Stroke widths are in OUTPUT pixels: KS (set by the driver to 1/fit) converts them to drawing units,
    # so every icon ends up with the same outline weight whatever its fit scale.
    W_OUT, W_IN, EXT = 9.5, 4.8, 5.5
    PAD = 48.0
    KS = 1.0

    def __init__(self):
        self.k = SS
        self.o = self.PAD
        n = int((256 + 2 * self.PAD) * SS)
        self.size = (n, n)
        self.cv = Image.new("RGBA", self.size, (0, 0, 0, 0))
        self.fit = 1.0

    # geometry (256-unit coordinates)
    def s(self, v):
        return v * self.k

    def X(self, v):
        return (v + self.o) * self.k

    def pt(self, p):
        return ((p[0] + self.o) * self.k, (p[1] + self.o) * self.k)

    def pts(self, ps):
        return [((x + self.o) * self.k, (y + self.o) * self.k) for x, y in ps]

    def circle(self, cx, cy, r):
        return m_circle(self.size, self.X(cx), self.X(cy), r * self.k)

    def ell(self, cx, cy, rx, ry, ang=0.0):
        return m_ell(self.size, self.X(cx), self.X(cy), rx * self.k, ry * self.k, ang)

    def poly(self, ps):
        return m_poly(self.size, self.pts(ps))

    def spoly(self, ps, n=12):
        return m_poly(self.size, self.pts(catmull(ps, True, n)))

    def rrect(self, x0, y0, x1, y1, r):
        return m_rrect(self.size, (self.X(x0), self.X(y0), self.X(x1), self.X(y1)), r * self.k)

    def rrect_rot(self, x0, y0, x1, y1, r, ang, cx=None, cy=None):
        cx = (x0 + x1) / 2.0 if cx is None else cx
        cy = (y0 + y1) / 2.0 if cy is None else cy
        return self.poly(rot_pts(rrect_pts(x0, y0, x1, y1, r), cx, cy, ang))

    def line(self, ps, w, cap=True):
        return m_line(self.size, self.pts(ps), w * self.k, cap)

    def pie(self, cx, cy, r, a0, a1):
        m = mnew(self.size)
        ImageDraw.Draw(m).pieslice((self.X(cx - r), self.X(cy - r), self.X(cx + r), self.X(cy + r)), a0, a1,
                                   fill=255)
        return m

    def rect_above(self, y):
        return self.poly([(-60, -60), (316, -60), (316, y), (-60, y)])

    def rect_below(self, y):
        return self.poly([(-60, y), (316, y), (316, 316), (-60, 316)])

    def rnd(self, m, r):
        return m_open(m, r * self.k)

    def rot(self, m, ang, cx=128, cy=128):
        return m.rotate(-ang, resample=BICUBIC, center=(self.X(cx), self.X(cy)))

    def shift(self, m, dx, dy):
        return m_shift(m, dx * self.k, dy * self.k)

    def dil(self, m, r):
        return m_dilate(m, r * self.k)

    def ero(self, m, r):
        return m_erode(m, r * self.k)

    def bbu(self, m):
        """Mask bounding box in 256-unit coordinates."""
        bb = m.getbbox()
        return tuple(v / float(self.k) - self.o for v in bb)

    # painting
    def outline(self, *ms, ext=None, w=None):
        sil = m_union(*ms) if len(ms) > 1 else ms[0]
        o = m_dilate(sil, (self.W_OUT if w is None else w) * self.KS * self.k)
        e = (self.EXT if ext is None else ext) * self.KS * self.k
        if e:
            o = m_union(o, m_shift(o, 0, e))
        paint_fill(self.cv, o, INK)
        return sil

    def outline_ring(self, m, ext=None, w=None):
        """Outline only (interior left untouched) - for translucent shapes."""
        o = m_dilate(m, (self.W_OUT if w is None else w) * self.KS * self.k)
        e = (self.EXT if ext is None else ext) * self.KS * self.k
        if e:
            o = m_union(o, m_shift(o, 0, e))
        paint_fill(self.cv, m_sub(o, m), INK)

    def line_under(self, m, w=None):
        paint_fill(self.cv, m_dilate(m, (self.W_IN if w is None else w) * self.KS * self.k), INK)

    def part(self, m, base, light=None, dark=None, line=True, lw=None, sb=None, **kw):
        if line:
            self.line_under(m, lw)
        if sb is not None:
            kw["spec_box"] = tuple(self.X(v) for v in sb)
        kw.setdefault("spec", 0)
        glossy(self.cv, m, base, light, dark, **kw)

    def fill(self, m, color, op=1.0):
        paint_fill(self.cv, m, color, op)

    def grad(self, m, p0, p1, stops, op=1.0):
        paint_grad(self.cv, m, lin_fn(self.pt(p0), self.pt(p1)), stops, op)

    def rgrad(self, m, c, r, stops, op=1.0):
        paint_grad(self.cv, m, rad_fn(self.pt(c), r * self.k), stops, op)

    def fgrad(self, m, fn, stops, op=1.0):
        """Gradient from a field function of unit coordinates."""
        k, o = float(self.k), self.o
        paint_grad(self.cv, m, lambda x, y: fn(x / k - o, y / k - o), stops, op)

    def spec(self, cx, cy, rx, ry, ang=-30.0, op=0.85, clip=None, blur=2.0):
        m = m_blur(self.ell(cx, cy, rx, ry, ang), blur * self.k)
        if clip is not None:
            m = m_inter(m, clip)
        self.fill(m, WHITE, op)

    def inner_shadow(self, m, dx, dy, blur, color, op):
        cr = m_sub(m, self.shift(m, dx, dy))
        self.fill(m_inter(m_blur(cr, blur * self.k), m), color, op)

    def overlay(self, m, top_op=0.3, bot_op=0.3, dark=INK, mid=0.5):
        """Vertical light-to-dark wash over a multi-coloured surface (keeps the set's top-left lighting)."""
        x0, y0, x1, y1 = self.bbu(m)
        self.grad(m, (0, y0), (0, y1), [(0, alpha(WHITE, 255 * top_op)), (mid * 0.9, alpha(WHITE, 0)),
                                        (min(1.0, mid * 1.1), alpha(dark, 0)), (1, alpha(dark, 255 * bot_op))])

    def sparkle(self, cx, cy, r, color=WHITE, ow=3.5, rot=0.0):
        draw_sparkle(self.cv, self.X(cx), self.X(cy), r * self.k, color, ow=ow * self.KS * self.k, rot=rot)

    def glyph_shadow(self, glyph, clip, color, op=0.55, dy=6):
        self.fill(m_inter(m_blur(self.shift(glyph, 0, dy), self.s(2)), clip), color, op)

    def done(self):
        k, o = self.k, self.o
        bb = self.cv.getchannel("A").point(lambda v: 255 if v > 24 else 0).getbbox()
        x0, y0, x1, y1 = [b / float(k) - o for b in bb]
        f = TARGET / max(x1 - x0, y1 - y0)
        cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
        half = 128.0 / f
        box = tuple(int(round((v + o) * k)) for v in (cx - half, cy - half, cx + half, cy + half))
        self.fit = f
        _LAST_FIT[0] = f
        return self.cv.crop(box).resize((OUT, OUT), LANCZOS)


# --------------------------------------------------------------------------
# Shared parts
# --------------------------------------------------------------------------


def _emboss(c, m, base, light, dark, d=3.0, op=0.75):
    """Raised emboss: dark drop to the lower-right, light edge to the upper-left."""
    c.fill(m_sub(c.shift(m, d, d), m), dark, op)
    c.fill(m_sub(c.shift(m, -d * 0.6, -d * 0.6), m), light, op)
    c.fill(m, base)


def _engrave(c, m, base, light, dark, d=3.0):
    """Recessed groove: shadow on the upper-left inner edge, light on the lower-right."""
    c.fill(m, dark)
    c.fill(m_inter(m_sub(m, c.shift(m, d, d)), m), shade(dark, 0.25))
    c.fill(m_inter(m_sub(m, c.shift(m, -d, -d)), m), light, 0.8)


def _gold(c, m, **kw):
    kw.setdefault("kind", "sphere")
    c.part(m, SUN, SUN_L, SUN_D, **kw)


def _white(c, m, **kw):
    """Foam-white glyph shading used for every white symbol in the set."""
    kw.setdefault("kind", "diag")
    kw.setdefault("core", 0.5)
    kw.setdefault("core_d", 0.13)
    c.part(m, FOAM, WHITE, FOAM_D, line=kw.pop("line", False), **kw)


def _round_button(c, base, light, dark, cy=124, r=104):
    disc = c.circle(128, cy, r)
    c.outline(disc)
    c.part(disc, base, light, dark, kind="sphere", lc=(0.35, 0.25), R=1.05, core=0.45, line=False)
    sheen = m_inter(c.ell(128, cy - r * 0.5, r * 0.74, r * 0.4), disc)
    c.grad(sheen, (0, cy - r), (0, cy - r * 0.1), [(0, alpha(WHITE, 150)), (1, alpha(WHITE, 0))])
    return disc


def _star_mask(c, cx=128, cy=136, ro=114, ri=55, rr=14):
    return c.rnd(c.poly(star_pts(cx, cy, ro, ri, 5, -90)), rr)


def _small_star(c, cx, cy, r, col=SUN, lw=3.5, rot=-90):
    st = c.rnd(c.poly(star_pts(cx, cy, r, r * 0.5, 5, rot)), max(1.0, r * 0.1))
    c.line_under(st, lw)
    c.grad(st, (cx - r, cy - r), (cx + r * 0.6, cy + r), [(0, lighten(col, 0.55)), (0.5, col), (1, shade(col, 0.15))])
    return st


def _eye(c, cx, cy, rx, ry, px=2.0, py=3.0, lw=4.0):
    e = c.ell(cx, cy, rx, ry)
    c.line_under(e, lw)
    c.grad(e, (cx, cy - ry), (cx, cy + ry), [(0, WHITE), (1, hx("#D5ECF1"))])
    pr = min(rx, ry) * 0.74
    p = m_inter(c.ell(cx + px, cy + py, pr, pr * 1.1), e)
    c.rgrad(p, (cx + px - pr * 0.3, cy + py - pr * 0.45), pr * 1.5, [(0, hx("#3A6C94")), (0.7, INK), (1, INK)])
    c.fill(c.circle(cx + px - pr * 0.36, cy + py - pr * 0.42, pr * 0.42), WHITE)
    c.fill(c.circle(cx + px + pr * 0.42, cy + py + pr * 0.4, pr * 0.17), WHITE, 0.9)
    return e


def _coin_face(c, cx, cy, r, star=True, outline=True, ow=None):
    """A face-on coin (front disc + thick gold edge below), returns the silhouette."""
    th = r * 0.15
    face = c.circle(cx, cy, r)
    edge = c.circle(cx, cy + th, r)
    if outline:
        c.outline(face, edge, w=ow)
    c.grad(edge, (0, cy), (0, cy + r + th), [(0, SUN_D), (1, SUN_DD)])
    c.part(face, SUN, SUN_L, SUN_D, kind="sphere", lc=(0.3, 0.22), core=0.35, line=False)
    ring = m_sub(c.circle(cx, cy, r * 0.78), c.circle(cx, cy, r * 0.68))
    _engrave(c, ring, SUN, SUN_L, hx("#E59A0C"), max(1.0, r * 0.025))
    if star:
        st = c.rnd(c.poly(star_pts(cx, cy + r * 0.02, r * 0.46, r * 0.22, 5, -90)), r * 0.04)
        _emboss(c, st, SUN_L, WHITE, SUN_DD, max(1.0, r * 0.03), 0.8)
        c.grad(st, (cx - r * 0.3, cy - r * 0.4), (cx + r * 0.2, cy + r * 0.4), [(0, hx("#FFF8D0")), (1, SUN)], 0.9)
    c.spec(cx - r * 0.42, cy - r * 0.5, r * 0.32, r * 0.14, -38, 0.75, clip=face, blur=max(1.0, r * 0.03))
    return m_union(face, edge)


def _coin_flat(c, cx, cy, rx, ry, th, ang=0.0, star=False, lw=3.5):
    """A coin lying flat seen from the front-top: ellipse face + edge band. Returns the silhouette."""
    top = c.ell(cx, cy, rx, ry, ang)
    side = top
    steps = max(2, int(th / 2.0))
    for i in range(1, steps + 1):
        side = m_union(side, c.ell(cx, cy + th * i / steps, rx, ry, ang))
    c.line_under(side, lw)
    c.grad(side, (0, cy), (0, cy + ry + th), [(0, SUN_D), (1, SUN_DD)])
    ridges = mnew(c.size)
    for i in range(-8, 9):
        x = cx + rx * math.sin(math.radians(i * 10))
        ridges = m_union(ridges, c.line([(x, cy), (x, cy + ry + th + 2)], 1.6))
    c.fill(m_inter(m_sub(ridges, top), side), hx("#A86206"), 0.45)
    c.fill(m_inter(c.shift(m_sub(c.dil(top, 2.5), top), 0, 1), side), INK, 0.9)
    c.grad(top, (cx - rx, cy - ry), (cx + rx * 0.5, cy + ry), [(0, SUN_L), (0.5, SUN), (1, hx("#F7BC22"))])
    inner = c.ell(cx, cy, rx * 0.72, ry * 0.68, ang)
    c.fill(m_sub(inner, c.shift(inner, 0, -1.5)), hx("#E59A0C"), 0.9)
    c.fill(m_sub(c.shift(inner, 0, 1.5), inner), WHITE, 0.4)
    if star:
        st = c.poly([(x, cy + (y - cy) * (ry / float(rx)) * 1.25) for x, y in star_pts(cx, cy, rx * 0.36, rx * 0.17, 5, -90)])
        _emboss(c, st, SUN_L, WHITE, SUN_DD, 1.5, 0.8)
    c.spec(cx - rx * 0.45, cy - ry * 0.35, rx * 0.25, ry * 0.22, ang, 0.7, clip=top, blur=1.2)
    return side


def _trophy_masks(c, cx, cy, s):
    def T(x, y):
        return (cx + (x - 128) * s, cy + (y - 128) * s)

    def TP(ps):
        return [T(x, y) for x, y in ps]
    bowl = c.spoly(TP([(50, 40), (206, 40), (200, 104), (176, 140), (146, 158), (110, 158), (80, 140), (56, 104)]), 10)
    bowl = m_inter(bowl, c.rect_below(T(0, 40)[1]))
    hL = m_sub(c.ell(*T(52, 84), 32 * s, 34 * s), c.ell(*T(52, 84), 17 * s, 19 * s))
    hR = m_sub(c.ell(*T(204, 84), 32 * s, 34 * s), c.ell(*T(204, 84), 17 * s, 19 * s))
    stem = c.poly(TP([(114, 150), (142, 150), (150, 190), (106, 190)]))
    base = c.poly(TP(rrect_pts(66, 184, 190, 228, 12)))
    cap = c.poly(TP(rrect_pts(84, 182, 172, 200, 6)))
    return T, bowl, hL, hR, stem, base, cap


def _trophy(c, cx, cy, s, ow=None, sparkle=True):
    T, bowl, hL, hR, stem, base, cap = _trophy_masks(c, cx, cy, s)
    c.outline(bowl, hL, hR, stem, base, w=ow)
    lw = 5 * min(1.0, s + 0.25)
    for hm in (hL, hR):
        _gold(c, hm, core=0.35, line=False)
    c.part(stem, SUN, SUN_L, SUN_D, kind="sphere", core=0.4, lw=lw)
    c.part(base, OCE, OCE_L, OCE_DD, kind="vertical", core=0.35, lw=lw)
    plate = c.poly([T(x, y) for x, y in rrect_pts(100, 204, 156, 218, 4)])
    c.fill(plate, SUN)
    c.fill(m_inter(c.shift(plate, 0, -3 * s), plate), SUN_L)
    c.part(cap, SUN, SUN_L, SUN_D, kind="vertical", core=0.3, lw=lw * 0.8)
    c.line_under(bowl, lw)
    _gold(c, bowl, lc=(0.3, 0.2), core=0.45, line=False)
    rim = m_inter(c.ell(*T(128, 42), 78 * s, 13 * s), bowl)
    c.fill(rim, hx("#E39A12"))
    c.fill(m_inter(c.ell(*T(128, 46), 70 * s, 9 * s), rim), SUN_DD, 0.7)
    st = c.rnd(c.poly([T(x, y) for x, y in star_pts(128, 98, 30, 14, 5, -90)]), 3 * s)
    _emboss(c, st, hx("#FFF0A0"), WHITE, SUN_DD, 2.5 * s, 0.8)
    c.spec(*T(84, 72), 10 * s, 26 * s, 12, 0.75, clip=bowl, blur=3 * s)
    if sparkle:
        c.sparkle(*T(216, 30), 18 * s, WHITE, ow=3.2 * min(1.0, s + 0.3))
    return m_union(bowl, hL, hR, stem, base)


def _padlock(c, cx, cy, s, outline=True):
    def X(x):
        return cx + (x - 128) * s

    def Y(y):
        return cy + (y - 128) * s
    outer = c.rrect(X(72), Y(20), X(184), Y(160), 56 * s)
    inner = c.rrect(X(100), Y(48), X(156), Y(160), 28 * s)
    shackle = m_inter(m_sub(outer, inner), c.rect_above(Y(132)))
    body = c.rrect(X(46), Y(102), X(210), Y(234), 32 * s)
    if outline:
        c.outline(shackle, body)
    c.part(shackle, STEEL, STEEL_L, STEEL_D, kind="sphere", lc=(0.3, 0.3), core=0.35, line=not outline)
    c.spec(X(90), Y(56), 6 * s, 16 * s, 20, 0.8, clip=shackle, blur=1.5)
    c.part(body, SUN, SUN_L, SUN_D, kind="vertical", core=0.4, bounce=0.3, line=not outline)
    band = m_inter(c.rrect(X(46), Y(102), X(210), Y(128), 12 * s), body)
    c.fill(band, WHITE, 0.25)
    kh = m_union(c.circle(X(128), Y(158), 18 * s), c.poly([(X(117), Y(162)), (X(139), Y(162)), (X(134), Y(204)),
                                                          (X(122), Y(204))]))
    kh = c.rnd(kh, 2 * s)
    c.fill(kh, INK)
    c.fill(m_inter(c.shift(m_sub(c.dil(kh, 3 * s), kh), 1.5 * s, 2 * s), body), WHITE, 0.45)
    c.spec(X(80), Y(122), 16 * s, 8 * s, -25, 0.75, clip=body, blur=2 * s)
    return shackle, body


def _shield_mask(c, cy=0.0, k=1.0):
    pts = [(128, 22), (172, 34), (216, 40), (214, 112), (192, 168), (154, 210), (128, 232), (102, 210), (64, 168),
           (42, 112), (40, 40), (84, 34)]
    pts = [(128 + (x - 128) * k, 128 + (y - 128) * k + cy) for x, y in pts]
    return c.spoly(pts, 10)


def m_question(c, cx, cy, s, w):
    """A chunky '?' glyph centred at (cx, cy); s = glyph height."""
    r = 0.25 * s
    acy = cy - 0.2 * s
    arc = arc_pts(cx, acy, r, 192, 395, 40)
    ex, ey = arc[-1]
    tail = catmull([(ex, ey), (cx + 0.06 * s, cy + 0.04 * s), (cx, cy + 0.13 * s), (cx, cy + 0.2 * s)],
                   closed=False, n=10)
    stroke = c.line(arc + tail[1:], w)
    dot = c.circle(cx, cy + 0.42 * s, w * 0.62)
    return m_union(stroke, dot)


def m_seven(c, x0, y0, x1, xb, yb, w):
    return c.line([(x0, y0), (x1, y0), (xb, yb)], w)


def path_dots(c, path, spacing, r):
    m = mnew(c.size)
    for (x, y) in resample(path, spacing):
        m = m_union(m, c.circle(x, y, r))
    return m


def _note(c, hx_, hy, stem_top, flag=True, rx=30, ry=23, sw=16):
    """Eighth note: head centre (hx_, hy), stem on the head's right side up to stem_top."""
    head = c.ell(hx_, hy, rx, ry, -22)
    sx = hx_ + rx * 0.62
    stem = c.rrect(sx, stem_top, sx + sw, hy - 4, sw / 2.0 - 1)
    parts = [head, stem]
    if flag:
        fl = c.spoly([(sx + 4, stem_top - 2), (sx + 34, stem_top + 18), (sx + 50, stem_top + 48),
                      (sx + 42, stem_top + 76), (sx + 36, stem_top + 52), (sx + 20, stem_top + 38),
                      (sx + 6, stem_top + 36)], 8)
        parts.append(fl)
    return m_union(*parts), head


# --------------------------------------------------------------------------
# Icons: currency & rewards
# --------------------------------------------------------------------------


def icon_coin():
    c = Ico()
    face = c.circle(128, 118, 100)
    edge = c.circle(128, 134, 100)
    c.outline(face, edge)
    c.grad(edge, (0, 150), (0, 236), [(0, SUN_D), (1, SUN_DD)])
    ridges = mnew(c.size)
    for i in range(-9, 10):
        a = math.radians(90 + i * 8.5)
        x = 128 + 100 * math.cos(a)
        ridges = m_union(ridges, c.line([(x, 120), (x, 236)], 3.2))
    c.fill(m_inter(m_sub(ridges, face), edge), hx("#A86206"), 0.5)
    c.part(face, SUN, SUN_L, SUN_D, kind="sphere", lc=(0.3, 0.22), core=0.35, line=False)
    ring = m_sub(c.circle(128, 118, 79), c.circle(128, 118, 69))
    _engrave(c, ring, SUN, SUN_L, hx("#E59A0C"), 2.5)
    disc = c.circle(128, 118, 69)
    c.rgrad(disc, (100, 90), 110, [(0, hx("#FFEC8C")), (1, hx("#FFC524"))], 0.6)
    st = c.rnd(c.poly(star_pts(128, 104, 42, 20, 5, -90)), 4)
    wave = c.line([(88 + i * 2.0, 154 + 5.5 * math.sin((i * 2.0) / 40.0 * 2 * math.pi)) for i in range(41)], 8)
    mark = m_union(st, wave)
    _emboss(c, mark, SUN_L, WHITE, SUN_DD, 3.2, 0.8)
    c.grad(mark, (100, 70), (150, 165), [(0, hx("#FFF8D0")), (1, hx("#FFCB3A"))], 0.9)
    c.spec(84, 66, 34, 15, -38, 0.75, clip=face, blur=3)
    c.spec(60, 106, 7, 7, 0, 0.7, clip=face, blur=1)
    c.sparkle(202, 44, 22, WHITE, ow=3.5)
    return c.done()


def icon_gem():
    c = Ico()
    A, B, C2, D = (80, 40), (110, 40), (146, 40), (176, 40)
    L, M1, M2, R = (28, 94), (94, 94), (162, 94), (228, 94)
    P = (128, 230)
    sil = c.rnd(c.poly([A, D, R, P, L]), 5)
    c.outline(sil)
    facets = [
        ([A, B, M1, L], hx("#FF8CC4"), hx("#FFC9E3")),
        ([B, C2, M2, M1], hx("#FFB3DA"), hx("#FFE8F4")),
        ([C2, D, R, M2], hx("#E070E0"), hx("#FF9ED2")),
        ([L, M1, P], PNK, hx("#FF8FC4")),
        ([M1, M2, P], hx("#C55BE8"), hx("#E984E6")),
        ([M2, R, P], PUR_D, PUR),
    ]
    for pts_, base, lt in facets:
        fm = m_inter(c.poly(pts_), sil)
        c.grad(fm, pts_[0], pts_[-1] if len(pts_) == 3 else pts_[2], [(0, lt), (1, base)])
    g1 = m_inter(c.poly([(66, 108), (94, 108), (122, 196)]), sil)
    c.grad(g1, (78, 106), (118, 196), [(0, alpha(LAG_L, 230)), (1, alpha(LAG, 0))], 0.85)
    g2 = m_inter(c.poly([(172, 104), (200, 104), (142, 190)]), sil)
    c.grad(g2, (182, 104), (148, 186), [(0, alpha(LAG_L, 200)), (1, alpha(LAG, 0))], 0.55)
    lines = mnew(c.size)
    for a, b in ((L, R), (M1, P), (M2, P), (B, M1), (C2, M2)):
        lines = m_union(lines, c.line([a, b], 3.0))
    c.fill(m_inter(lines, c.ero(sil, 1)), WHITE, 0.55)
    c.inner_shadow(sil, -4, -6, 4, PUR_DD, 0.55)
    c.spec(118, 58, 22, 6, -6, 0.9, clip=sil, blur=1.5)
    c.sparkle(66, 80, 20, WHITE, ow=3.2)
    c.sparkle(196, 146, 14, WHITE, ow=3.0)
    return c.done()


def _star_icon(c):
    st = _star_mask(c)
    c.outline(st)
    c.part(st, SUN, SUN_L, SUN_D, kind="sphere", lc=(0.32, 0.25), core=0.4, line=False)
    inner = _star_mask(c, 128, 138, 78, 37, 9)
    c.fill(m_sub(c.dil(inner, 3), inner), hx("#E8960E"), 0.7)
    c.grad(inner, (100, 70), (150, 190), [(0, hx("#FFF4B0")), (0.55, hx("#FFDA4F")), (1, hx("#FFB823"))])
    c.inner_shadow(st, -3, -6, 4, SUN_DD, 0.45)
    c.spec(96, 92, 20, 10, -35, 0.85, clip=st, blur=2.5)
    c.spec(76, 122, 5, 5, 0, 0.8, clip=st, blur=1)
    return st


def icon_star():
    c = Ico()
    _star_icon(c)
    return c.done()


def icon_star_empty():
    c = Ico()
    st = _star_mask(c)
    c.outline_ring(st)
    c.fill(st, INK, 0.55)
    inner = _star_mask(c, 128, 138, 80, 38, 9)
    c.fill(inner, INK, 0.35)
    c.inner_shadow(inner, 4, 7, 4, INK, 0.6)
    c.fill(m_inter(m_sub(inner, c.shift(inner, -2, -4)), inner), WHITE, 0.28)
    c.fill(m_inter(m_blur(m_sub(st, c.shift(st, 3, 5)), c.s(1)), st), WHITE, 0.3)
    c.fill(m_inter(m_sub(st, c.shift(st, -3, -5)), st), INK, 0.4)
    c.spec(92, 90, 14, 6, -35, 0.25, clip=st, blur=2)
    return c.done()


def icon_trophy():
    c = Ico()
    _trophy(c, 128, 128, 1.0)
    return c.done()


def icon_crown():
    c = Ico()
    band = c.rrect(42, 162, 214, 214, 14)
    body = c.rnd(c.poly([(46, 170), (32, 76), (90, 122), (128, 48), (166, 122), (224, 76), (210, 170)]), 6)
    balls = [c.circle(32, 68, 15), c.circle(128, 40, 17), c.circle(224, 68, 15)]
    c.outline(band, body, *balls)
    _gold(c, body, lc=(0.3, 0.2), core=0.45, line=False)
    for b in balls:
        c.part(b, SUN, SUN_L, SUN_D, kind="sphere", core=0.35, spec=0.8, spec_blur=0.1)
    c.part(band, SUN_D, SUN, SUN_DD, kind="vertical", core=0.35)
    for (x, col, rx) in ((128, PNK, 17), (78, LAG, 12), (178, LAG, 12)):
        j = c.ell(x, 188, rx, 13 if rx > 12 else 10)
        c.line_under(j, 3.5)
        c.part(j, col, lighten(col, 0.55), shade(col, 0.3), kind="sphere", core=0.3, spec=0.85, spec_blur=0.12,
               line=False)
    gem = c.rnd(c.poly([(128, 92), (145, 120), (128, 146), (111, 120)]), 2)
    c.line_under(gem, 3.5)
    c.grad(gem, (114, 96), (142, 144), [(0, hx("#FFE0F0")), (1, PNK_D)])
    c.spec(74, 112, 8, 20, 20, 0.7, clip=body, blur=2)
    return c.done()


def _medal(metal, ribbon):
    c = Ico()
    mb, ml, md = metal
    rb, rl, rd = ribbon
    lp = [(56, 12), (110, 12), (154, 134), (100, 134)]
    rp = [(146, 12), (200, 12), (156, 134), (102, 134)]
    sL, sR = c.poly(lp), c.poly(rp)
    disc = c.circle(128, 164, 70)
    c.outline(sL, sR, disc)
    for pts_, m in ((lp, sL), (rp, sR)):
        c.part(m, rb, rl, rd, kind="vertical", core=0.25, lw=4.5)
        stripe = c.poly([(lerp(pts_[0][0], pts_[1][0], 0.36), 12), (lerp(pts_[0][0], pts_[1][0], 0.64), 12),
                         (lerp(pts_[3][0], pts_[2][0], 0.64), 134), (lerp(pts_[3][0], pts_[2][0], 0.36), 134)])
        c.fill(m_inter(stripe, m), FOAM, 0.95)
        c.fill(m_inter(m_blur(c.shift(disc, 0, -8), c.s(5)), m), INK, 0.35)
    c.part(disc, mb, ml, md, kind="sphere", lc=(0.3, 0.22), core=0.4, line=False)
    ring = m_sub(c.circle(128, 164, 55), c.circle(128, 164, 48))
    _engrave(c, ring, mb, ml, shade(md, 0.05), 2.2)
    inner = c.circle(128, 164, 48)
    c.rgrad(inner, (110, 140), 70, [(0, alpha(ml, 200)), (1, alpha(mb, 0))])
    st = c.rnd(c.poly(star_pts(128, 167, 34, 16, 5, -90)), 3)
    _emboss(c, st, ml, WHITE, shade(md, 0.2), 2.6, 0.8)
    c.grad(st, (110, 140), (140, 190), [(0, lighten(ml, 0.4)), (1, mb)], 0.85)
    c.spec(96, 124, 22, 10, -38, 0.8, clip=disc, blur=2.5)
    c.spec(76, 160, 5, 5, 0, 0.7, clip=disc, blur=1)
    return c


def icon_medal_gold():
    c = _medal((SUN, SUN_L, SUN_D), (COR, COR_L, COR_D))
    c.sparkle(198, 118, 18, WHITE, ow=3.2)
    return c.done()


def icon_medal_silver():
    return _medal((SILV, SILV_L, SILV_D), (OCE, OCE_L, OCE_D)).done()


def icon_medal_bronze():
    return _medal((BRNZ, BRNZ_L, BRNZ_D), (PUR, PUR_L, PUR_D)).done()


def icon_chest():
    c = Ico()
    body = c.rrect(30, 120, 226, 228, 14)
    lid = m_union(m_inter(c.ell(128, 96, 98, 50), c.rect_above(104)), c.rrect(30, 94, 226, 128, 6))
    c.outline(body, lid)
    c.part(body, WOOD, WOOD_L, WOOD_D, kind="vertical", core=0.35, line=False)
    pl = mnew(c.size)
    for y in (158, 192):
        pl = m_union(pl, c.line([(34, y), (222, y)], 3))
    c.fill(m_inter(pl, body), WOOD_D, 0.55)
    c.line_under(lid, 4.5)
    c.part(lid, hx("#E4955A"), hx("#FFCB94"), WOOD_D, kind="vertical", core=0.3, line=False)
    c.fill(m_inter(c.line([(34, 80), (222, 80)], 3), lid), WOOD_D, 0.45)
    straps = m_inter(m_union(c.rrect(56, 30, 80, 232, 3), c.rrect(176, 30, 200, 232, 3)), m_union(body, lid))
    c.line_under(straps, 3.5)
    c.grad(straps, (0, 46), (0, 228), [(0, SUN_L), (0.5, SUN), (1, SUN_D)])
    trim = c.rrect(26, 116, 230, 133, 5)
    c.line_under(trim, 4)
    _gold(c, trim, kind="vertical", core=0.3, line=False)
    for (sx, sy) in ((68, 102), (188, 102), (68, 150), (188, 150), (68, 204), (188, 204)):
        c.fill(c.circle(sx, sy, 4.5), SUN_DD)
        c.fill(c.circle(sx - 1, sy - 1, 3), hx("#FFF6C0"))
    plate = c.rrect(104, 106, 152, 166, 10)
    c.line_under(plate, 4.5)
    _gold(c, plate, core=0.35, line=False)
    kh = m_union(c.circle(128, 130, 8), c.poly([(123, 132), (133, 132), (131, 152), (125, 152)]))
    c.fill(kh, INK)
    c.spec(80, 62, 22, 7, -14, 0.55, clip=lid, blur=2.5)
    c.sparkle(216, 52, 17, WHITE, ow=3)
    return c.done()


def icon_gift():
    c = Ico()
    lid = c.rrect(30, 96, 226, 136, 10)
    box = c.rrect(44, 130, 212, 230, 10)
    loopL = c.ell(94, 66, 38, 24, 28)
    loopR = c.ell(162, 66, 38, 24, -28)
    knot = c.ell(128, 84, 19, 17)
    c.outline(lid, box, loopL, loopR, knot)
    for lp, cx_ in ((loopL, 94), (loopR, 162)):
        c.part(lp, SUN, SUN_L, SUN_D, kind="sphere", core=0.35)
        hole = c.ell(cx_ + (8 if cx_ < 128 else -8), 68, 16, 8, 28 if cx_ < 128 else -28)
        c.fill(hole, SUN_DD)
    c.part(box, PUR, PUR_L, PUR_D, kind="vertical", core=0.4, bounce=0.2)
    c.part(lid, hx("#AE86FF"), PUR_L, PUR_D, kind="vertical", core=0.35)
    rb = m_inter(c.rrect(110, 90, 146, 234, 3), m_union(lid, box))
    c.line_under(rb, 4)
    c.grad(rb, (110, 0), (146, 0), [(0, SUN_L), (0.5, SUN), (1, SUN_D)])
    c.fill(m_inter(c.shift(lid, 0, 7), m_sub(box, rb)), PUR_DD, 0.35)
    c.part(knot, SUN, SUN_L, SUN_D, kind="sphere", core=0.3, spec=0.6, spec_blur=0.08)
    c.spec(68, 106, 20, 5, -4, 0.7, clip=lid, blur=2)
    c.spec(66, 154, 6, 22, 0, 0.45, clip=box, blur=2.5)
    for (x, y, r) in ((70, 182, 5), (186, 168, 4.5), (172, 206, 4)):
        c.fill(c.circle(x, y, r), WHITE, 0.55)
    return c.done()


def icon_bonus():
    c = Ico()
    # back stack (left, taller) then front stack (right, shorter)
    stacks = [(94, 208, 5, 62, 21), (172, 222, 3, 54, 18)]
    sil = mnew(c.size)
    masks = []
    for (cx, by, n, rx, ry) in stacks:
        for i in range(n):
            cy = by - i * 22
            top = c.ell(cx, cy, rx, ry)
            side = m_union(top, c.ell(cx, cy + 16, rx, ry), c.poly([(cx - rx, cy), (cx + rx, cy), (cx + rx, cy + 16),
                                                                    (cx - rx, cy + 16)]))
            masks.append(side)
    sil = m_union(*masks)
    c.outline(sil)
    for (cx, by, n, rx, ry) in stacks:
        for i in range(n):
            cy = by - i * 22
            _coin_flat(c, cx, cy, rx, ry, 16, star=(i == n - 1), lw=3.5)
    c.sparkle(206, 74, 24, WHITE, ow=3.5)
    c.sparkle(40, 72, 13, SUN_L, ow=3)
    return c.done()


def icon_coins_pile():
    c = Ico()
    rows = [
        [(128, 52)],
        [(104, 76), (152, 74)],
        [(80, 102), (128, 98), (176, 102)],
        [(56, 130), (104, 126), (152, 128), (200, 132)],
    ]
    ground = [(52, 204), (210, 202)]
    rnd = random.Random(7)
    coins = []
    for row in rows:
        for (x, y) in row:
            coins.append((x, y, rnd.uniform(-10, 10)))
    sil = mnew(c.size)
    for (x, y, a) in coins + [(x, y, 0) for x, y in ground]:
        sil = m_union(sil, c.ell(x, y, 32, 12, a), c.ell(x, y + 10, 32, 12, a))
    sil = m_union(sil, c.poly([(128, 56), (226, 150), (30, 150)]))
    big = (128, 160, 62)
    sil = m_union(sil, c.circle(big[0], big[1], big[2]), c.circle(big[0], big[1] + 9, big[2]))
    c.outline(sil)
    c.fill(c.poly([(128, 60), (222, 152), (34, 152)]), SUN_DD)
    for (x, y, a) in coins:
        _coin_flat(c, x, y, 32, 12, 10, a, lw=3)
    for (x, y) in ground:
        _coin_flat(c, x, y, 32, 12, 10, 0, lw=3)
    c.line_under(m_union(c.circle(big[0], big[1], big[2]), c.circle(big[0], big[1] + 9, big[2])), 4.5)
    _coin_face(c, big[0], big[1], big[2], star=True, outline=False)
    c.sparkle(46, 66, 20, WHITE, ow=3.2)
    c.sparkle(214, 74, 13, WHITE, ow=3)
    return c.done()


def icon_capsule():
    c = Ico()
    cx, cy, R = 128, 126, 104
    ball = c.circle(cx, cy, R)
    c.outline(ball)
    seam = [(x, cy + 12 * (1 - ((x - cx) / float(R)) ** 2)) for x in range(cx - R - 4, cx + R + 6, 4)]
    top = m_inter(ball, c.poly(seam + [(cx + R + 10, -40), (cx - R - 10, -40)]))
    bot = m_sub(ball, top)
    c.rgrad(top, (cx - 40, cy - 70), 170, [(0, PUR_L), (0.45, PUR), (1, PUR_DD)])
    c.rgrad(bot, (cx - 40, cy - 10), 190, [(0, SUN_L), (0.4, SUN), (1, SUN_DD)])
    band = m_inter(c.line(seam, 13), ball)
    c.line_under(band, 3.5)
    c.grad(band, (0, cy), (0, cy + 26), [(0, WHITE), (1, FOAM_D)])
    c.inner_shadow(ball, -6, -10, 6, INK, 0.35)
    lab = c.circle(cx, cy + 6, 52)
    c.line_under(lab, 5)
    c.part(lab, FOAM, WHITE, FOAM_D, kind="sphere", lc=(0.35, 0.3), core=0.35, line=False)
    q = m_question(c, cx, cy + 2, 74, 15)
    c.line_under(q, 3)
    c.grad(q, (cx, cy - 34), (cx, cy + 44), [(0, PUR), (1, PUR_D)])
    c.spec(cx - 48, cy - 64, 30, 14, -38, 0.85, clip=top, blur=3)
    c.spec(cx - 78, cy - 12, 6, 6, 0, 0.7, clip=ball, blur=1)
    c.sparkle(206, 34, 20, WHITE, ow=3.2)
    return c.done()


def icon_wheel():
    c = Ico()
    cx, cy, R = 128, 134, 100
    disc = c.circle(cx, cy, R)
    pointer = c.rnd(c.poly([(104, 14), (152, 14), (128, 64)]), 7)
    c.outline(disc, pointer)
    _gold(c, disc, core=0.4, line=False)
    Ri = R - 17
    cols = [COR, SUN, LAG, PUR, PNK, LIM, OCE, SUN]
    for i, col in enumerate(cols):
        wm = c.pie(cx, cy, Ri, -90 + i * 45 - 22.5, -90 + i * 45 + 22.5)
        c.rgrad(wm, (cx - 30, cy - 34), Ri * 1.6, [(0, lighten(col, 0.45)), (0.55, col), (1, shade(col, 0.25))])
    seps = mnew(c.size)
    for i in range(8):
        a = math.radians(-90 + i * 45 - 22.5)
        seps = m_union(seps, c.line([(cx, cy), (cx + Ri * math.cos(a), cy + Ri * math.sin(a))], 5))
    c.fill(seps, WHITE, 0.95)
    inner = c.circle(cx, cy, Ri)
    c.fill(m_sub(c.dil(inner, 2.5), inner), SUN_DD)
    c.inner_shadow(inner, 4, 6, 4, INK, 0.35)
    for i in range(12):
        a = math.radians(i * 30)
        bx, by = cx + (R - 8.5) * math.cos(a), cy + (R - 8.5) * math.sin(a)
        c.fill(c.circle(bx, by, 5.5), SUN_DD)
        c.fill(c.circle(bx - 0.8, by - 0.8, 4.2), hx("#FFFBE0"))
    hub = c.circle(cx, cy, 22)
    c.line_under(hub, 5)
    _gold(c, hub, core=0.35, spec=0.8, line=False, spec_blur=0.08)
    c.part(pointer, COR, COR_L, COR_D, kind="vertical", core=0.4, line=False)
    c.spec(118, 26, 9, 4, 0, 0.8, clip=pointer, blur=1)
    c.spec(70, 70, 20, 8, -42, 0.5, clip=disc, blur=3)
    return c.done()


# --------------------------------------------------------------------------
# Icons: navigation & system buttons
# --------------------------------------------------------------------------


def icon_play():
    c = Ico()
    tri = c.rnd(c.poly([(58, 22), (58, 230), (234, 126)]), 22)
    c.outline(tri)
    c.part(tri, LIM, LIM_L, LIM_DD, kind="diag", core=0.45, line=False)
    c.spec(88, 64, 7, 26, 32, 0.9, clip=tri, blur=2)
    c.spec(80, 108, 4, 4, 0, 0.8, clip=tri, blur=1)
    return c.done()


def icon_pause():
    c = Ico()
    b1 = c.rrect(50, 22, 110, 230, 20)
    b2 = c.rrect(146, 22, 206, 230, 20)
    c.outline(b1, b2)
    for b, x in ((b1, 66), (b2, 162)):
        _white(c, b)
        c.spec(x, 52, 6, 18, 0, 0.9, clip=b, blur=1.5)
    return c.done()


def icon_close():
    c = Ico()
    disc = _round_button(c, COR, COR_L, COR_D)
    x = m_union(c.line([(86, 82), (170, 166)], 38), c.line([(170, 82), (86, 166)], 38))
    c.glyph_shadow(x, disc, COR_DD)
    c.line_under(x, 5)
    _white(c, x)
    return c.done()


def icon_back():
    c = Ico()
    disc = _round_button(c, LAG, LAG_L, LAG_D)
    arr = c.rnd(c.poly([(54, 124), (116, 64), (116, 100), (198, 100), (198, 148), (116, 148), (116, 184)]), 9)
    c.glyph_shadow(arr, disc, LAG_DD)
    c.line_under(arr, 5)
    _white(c, arr)
    return c.done()


def icon_check():
    c = Ico()
    disc = _round_button(c, LIM, LIM_L, LIM_D)
    ck = c.line([(72, 128), (110, 166), (184, 88)], 38)
    c.glyph_shadow(ck, disc, LIM_DD)
    c.line_under(ck, 5)
    _white(c, ck)
    return c.done()


def icon_info():
    c = Ico()
    disc = _round_button(c, OCE, OCE_L, OCE_D)
    i_ = m_union(c.circle(128, 70, 19), c.rrect(110, 102, 146, 192, 14))
    c.glyph_shadow(i_, disc, OCE_DD)
    c.line_under(i_, 5)
    _white(c, i_)
    return c.done()


def icon_plus():
    c = Ico()
    disc = _round_button(c, LIM, LIM_L, LIM_D)
    p = m_union(c.rrect(106, 58, 150, 190, 14), c.rrect(62, 102, 194, 146, 14))
    c.glyph_shadow(p, disc, LIM_DD)
    c.line_under(p, 5)
    _white(c, p)
    return c.done()


def icon_restart():
    c = Ico()
    cx, cy, r = 128, 134, 76
    a1 = 214
    arc = c.line(arc_pts(cx, cy, r, -52, a1, 70), 38)
    a = math.radians(a1)
    ex, ey = cx + r * math.cos(a), cy + r * math.sin(a)
    tx, ty = -math.sin(a), math.cos(a)
    nx, ny = math.cos(a), math.sin(a)
    head = c.rnd(c.poly([(ex + tx * 60, ey + ty * 60), (ex - tx * 6 + nx * 54, ey - ty * 6 + ny * 54),
                         (ex - tx * 6 - nx * 54, ey - ty * 6 - ny * 54)]), 6)
    c.outline(arc, head)
    _white(c, m_union(arc, head))
    c.spec(150, 70, 18, 6, 20, 0.8, clip=arc, blur=2)
    return c.done()


def icon_home():
    c = Ico()
    walls = c.rrect(56, 112, 200, 230, 8)
    roof = c.rnd(c.poly([(12, 144), (128, 22), (244, 144)]), 10)
    fringe = mnew(c.size)
    for i in range(12):
        fringe = m_union(fringe, c.circle(22 + i * 19.3, 146, 11))
    roof = m_union(roof, m_inter(fringe, c.rrect(10, 120, 246, 162, 8)))
    c.outline(walls, roof)
    c.part(walls, LAG, LAG_L, LAG_D, kind="vertical", core=0.3, line=False)
    pl = mnew(c.size)
    for x in range(76, 200, 20):
        pl = m_union(pl, c.line([(x, 116), (x, 230)], 3))
    c.fill(m_inter(pl, walls), LAG_DD, 0.45)
    c.fill(m_inter(m_blur(c.shift(roof, 0, 7), c.s(3)), m_sub(walls, roof)), LAG_DD, 0.55)
    # door (coral, arched) and porthole window
    door = m_inter(c.rrect(78, 154, 122, 250, 22), c.rect_above(230))
    c.part(door, COR, COR_L, COR_D, kind="vertical", core=0.35, lw=4.5)
    c.fill(m_inter(c.line([(100, 160), (100, 230)], 3), door), COR_DD, 0.5)
    c.fill(c.circle(113, 196, 4.5), SUN)
    win = c.circle(162, 180, 20)
    c.fill(c.circle(162, 180, 24), INK)
    c.fill(c.circle(162, 180, 21), SAND)
    c.rgrad(win, (155, 172), 26, [(0, hx("#E6FBFF")), (0.6, OCE_L), (1, OCE)])
    c.fill(m_inter(m_union(c.line([(162, 160), (162, 200)], 3.5), c.line([(142, 180), (182, 180)], 3.5)), win), INK)
    c.spec(154, 172, 6, 3, -30, 0.8, clip=win, blur=1)
    # straw thatch
    c.line_under(roof, 4.5)
    c.part(roof, SUN, SUN_L, SUN_D, kind="vertical", core=0.35, line=False)
    straw = mnew(c.size)
    rnd = random.Random(3)
    for i in range(26):
        x = 34 + i * 7.6
        top = 144 - (122 - abs(x - 128) * 1.05) * 0.55
        straw = m_union(straw, c.line([(x, top), (x + rnd.uniform(-3, 3), top + rnd.uniform(18, 30))], 2.6))
    for i in range(12):
        x = 22 + i * 19.3
        straw = m_union(straw, c.line([(x, 132), (x, 152)], 2.4))
    c.fill(m_inter(straw, roof), SUN_DD, 0.45)
    c.fill(m_inter(c.line([(18, 128), (238, 128)], 4), roof), SUN_DD, 0.35)
    c.spec(92, 66, 26, 8, -46, 0.75, clip=roof, blur=2.5)
    _small_star(c, 128, 34, 15, COR)
    return c.done()


def icon_shop():
    c = Ico()
    TL, TR, BR, BL = (56, 96), (200, 96), (218, 230), (38, 230)
    body = c.rnd(c.poly([TL, TR, BR, BL]), 12)
    handles = []
    for hx_, top in ((136, 20), (120, 26)):  # back handle, then front handle
        pts = [(hx_ - 40 + 80 * (1 - math.cos(math.pi * i / 28)) / 2, 106 - (106 - top) * math.sin(math.pi * i / 28))
               for i in range(29)]
        handles.append(c.line(pts, 13))
    c.outline(body, *handles)
    c.part(handles[0], LAG_D, LAG, LAG_DD, kind="sphere", core=0.35, lw=4)
    c.part(handles[1], LAG, LAG_L, LAG_D, kind="sphere", core=0.35, lw=4)
    c.fill(body, PNK)
    n = 7

    def xat(p, q, y):
        return p[0] + (q[0] - p[0]) * (y - p[1]) / float(q[1] - p[1])
    for i in range(1, n, 2):
        u0, u1 = i / float(n), (i + 1) / float(n)
        a0 = (lerp(TL[0], TR[0], u0), TL[1])
        a1 = (lerp(TL[0], TR[0], u1), TL[1])
        b0 = (lerp(BL[0], BR[0], u0), BL[1])
        b1 = (lerp(BL[0], BR[0], u1), BL[1])
        stripe = c.poly([(xat(a0, b0, 60), 60), (xat(a1, b1, 60), 60), (xat(a1, b1, 250), 250),
                         (xat(a0, b0, 250), 250)])
        c.fill(m_inter(stripe, body), FOAM)
    c.overlay(body, 0.2, 0.35, PNK_DD, 0.5)
    c.inner_shadow(body, -6, -8, 5, PNK_DD, 0.5)
    fold = m_inter(c.rect_above(122), body)
    c.part(fold, LAG, LAG_L, LAG_D, kind="vertical", core=0.25, line=False)
    c.fill(m_inter(c.line([(30, 122), (226, 122)], 3.5), body), INK)
    for x in (80, 160):
        c.fill(c.circle(x, 108, 6.5), INK)
        c.fill(c.circle(x - 0.6, 107.4, 3.8), LAG_L)
    lab = c.circle(128, 176, 32)
    c.line_under(lab, 4.5)
    c.part(lab, FOAM, WHITE, FOAM_D, kind="sphere", core=0.3, line=False)
    _small_star(c, 128, 178, 22, SUN, lw=3)
    c.spec(70, 140, 8, 24, 8, 0.5, clip=body, blur=3)
    return c.done()


def icon_levels():
    c = Ico()
    P1 = [(24, 60), (92, 38), (92, 212), (24, 234)]
    P2 = [(92, 38), (164, 60), (164, 234), (92, 212)]
    P3 = [(164, 60), (232, 38), (232, 212), (164, 234)]
    panels = [c.poly(p) for p in (P1, P2, P3)]
    sil = c.rnd(m_union(*panels), 6)
    c.outline(sil)
    for i, pm in enumerate(panels):
        pm = m_inter(pm, sil)
        if i == 1:
            c.grad(pm, (92, 0), (164, 0), [(0, hx("#EBC77F")), (1, hx("#F8DEA6"))])
        else:
            c.grad(pm, (0, 40), (0, 234), [(0, SAND_L), (1, SAND)])
    c.fill(m_inter(m_union(c.line([(92, 38), (92, 212)], 4), c.line([(164, 60), (164, 234)], 4)), sil),
           SAND_DD, 0.9)
    # little lagoon + palm island (top-left)
    water = m_inter(c.ell(58, 96, 30, 16), sil)
    c.fill(water, LAG_L)
    c.fill(m_inter(c.ell(58, 100, 20, 8), water), SAND_D)
    trunk = c.line(catmull([(60, 100), (56, 86), (58, 72)], False, 6), 4.5)
    c.fill(trunk, WOOD)
    for a in (-160, -110, -60, -10):
        lf = c.ell(58 + 11 * math.cos(math.radians(a)), 70 + 7 * math.sin(math.radians(a)), 12, 4.5, a)
        c.fill(lf, LIM_D)
    # wave squiggles (bottom-right)
    wv = mnew(c.size)
    for (x, y) in ((186, 186), (204, 204)):
        wv = m_union(wv, c.line([(x - 14 + i * 2, y + 4 * math.sin(i * 2 / 14.0 * math.pi)) for i in range(15)], 4))
    c.fill(m_inter(wv, sil), LAG)
    # dotted path to the X
    path = catmull([(48, 200), (78, 178), (116, 186), (138, 150), (118, 118), (150, 98), (184, 98)], False, 12)
    dots = path_dots(c, path, 15, 5.2)
    c.fill(dots, COR_D)
    start = c.circle(48, 200, 10)
    c.line_under(start, 3.5)
    c.fill(start, LIM)
    x1 = m_union(c.line([(178, 70), (210, 102)], 13), c.line([(210, 70), (178, 102)], 13))
    c.line_under(x1, 3.5)
    c.grad(x1, (178, 70), (210, 102), [(0, COR_L), (0.4, COR), (1, COR_D)])
    c.spec(54, 70, 14, 5, -16, 0.4, clip=sil, blur=2)
    return c.done()


def icon_profile():
    c = Ico()
    head = c.circle(128, 98, 70)
    body = m_inter(c.ell(128, 246, 100, 84), c.rect_above(232))
    tuft = c.spoly([(116, 36), (112, 16), (128, 6), (148, 14), (140, 26), (130, 22), (134, 36)], 8)
    c.outline(head, body, tuft)
    c.part(body, LAG, LAG_L, LAG_D, kind="sphere", lc=(0.35, 0.1), core=0.35, line=False)
    c.fill(m_inter(m_blur(c.shift(head, 0, 8), c.s(4)), body), LAG_DD, 0.5)
    c.part(tuft, LAG, LAG_L, LAG_D, kind="sphere", core=0.3, lw=4)
    c.line_under(head, 5)
    c.part(head, hx("#2CD3CE"), LAG_L, LAG_D, kind="sphere", lc=(0.32, 0.25), core=0.4, line=False)
    _eye(c, 102, 102, 17, 22, 2, 4)
    _eye(c, 154, 102, 17, 22, 2, 4)
    for x in (78, 178):
        c.fill(c.ell(x, 130, 12, 7), PNK, 0.55)
    c.fill(c.line(arc_pts(128, 126, 12, 25, 155, 16), 5), INK)
    c.spec(92, 52, 22, 9, -36, 0.75, clip=head, blur=2.5)
    c.spec(76, 186, 18, 8, -20, 0.4, clip=body, blur=3)
    return c.done()


def icon_character():
    c = Ico()
    head = c.circle(128, 106, 72)
    ears = [c.circle(56, 116, 15), c.circle(200, 116, 15)]
    body = m_inter(c.ell(128, 250, 96, 82), c.rect_above(234))
    hair = m_inter(c.circle(128, 100, 79),
                   c.poly([(36, 124), (50, 86), (74, 98), (96, 80), (124, 94), (152, 78), (178, 94), (204, 82),
                           (220, 124), (220, -20), (36, -20)]))
    hair = c.rnd(hair, 3)
    tuft = c.spoly([(118, 32), (124, 10), (144, 6), (152, 20), (138, 22), (138, 34)], 8)
    c.outline(head, body, hair, tuft, *ears)
    c.part(body, COR, COR_L, COR_D, kind="sphere", lc=(0.35, 0.1), core=0.4, line=False)
    stripe = m_inter(c.rrect(20, 200, 236, 214, 4), body)
    c.fill(stripe, FOAM, 0.95)
    collar = m_inter(c.ell(128, 178, 40, 20), body)
    c.fill(collar, COR_DD, 0.5)
    c.fill(m_inter(m_blur(c.shift(head, 0, 8), c.s(4)), body), COR_DD, 0.45)
    for e in ears:
        c.part(e, SKIN, SKIN_L, SKIN_D, kind="sphere", core=0.3, lw=4.5)
    c.line_under(head, 5)
    c.part(head, SKIN, SKIN_L, SKIN_D, kind="sphere", lc=(0.32, 0.28), core=0.35, line=False)
    c.part(tuft, HAIR, HAIR_L, HAIR_D, kind="sphere", core=0.3, lw=4)
    c.line_under(hair, 4.5)
    c.part(hair, HAIR, HAIR_L, HAIR_D, kind="sphere", lc=(0.3, 0.2), core=0.35, line=False)
    c.spec(96, 52, 22, 7, -22, 0.55, clip=hair, blur=2)
    # swim goggles pushed up on the hair
    strap = m_inter(c.line([(40, 92), (128, 76), (216, 92)], 9), m_union(hair, head))
    c.fill(strap, LAG_D)
    for gx in (100, 156):
        g = c.ell(gx, 76, 22, 15)
        c.line_under(g, 4)
        c.rgrad(g, (gx - 6, 70), 26, [(0, hx("#E8FFFD")), (0.5, LAG_L), (1, LAG)])
        c.spec(gx - 8, 71, 7, 3, -20, 0.9, clip=g, blur=0.8)
    _eye(c, 104, 128, 15, 19, 2, 3)
    _eye(c, 152, 128, 15, 19, 2, 3)
    for x in (80, 176):
        c.fill(c.ell(x, 150, 11, 6.5), PNK, 0.55)
    mouth = m_inter(c.ell(128, 152, 15, 13), c.rect_below(152))
    c.line_under(mouth, 3)
    c.fill(mouth, hx("#7A2440"))
    c.fill(m_inter(c.ell(128, 164, 9, 5), mouth), PNK)
    c.spec(60, 210, 16, 6, -20, 0.4, clip=body, blur=2)
    return c.done()


def icon_events():
    c = Ico()
    tip = (30, 232)
    M = (134, 128)
    ax = ((M[0] - tip[0]) / 147.08, (M[1] - tip[1]) / 147.08)
    n = (-ax[1], ax[0])
    hw = 50
    p1 = (M[0] + n[0] * hw, M[1] + n[1] * hw)
    p2 = (M[0] - n[0] * hw, M[1] - n[1] * hw)
    cone = c.rnd(c.poly([tip, p1, p2]), 6)
    mouth = c.ell(M[0], M[1], hw + 2, 17, 45)
    # confetti pieces first (behind/around the burst)
    bits = []
    streamers = []
    s1 = [(150, 108), (166, 86), (156, 66), (174, 46), (166, 24)]
    s2 = [(160, 120), (186, 112), (200, 126), (222, 112), (240, 120)]
    for pts_, col in ((s1, PNK), (s2, LAG)):
        streamers.append((c.line(catmull(pts_, False, 10), 10), col))
    rects = [(206, 38, 22, 12, 30, LIM), (226, 74, 20, 11, -40, COR), (128, 46, 20, 11, 60, OCE),
             (198, 156, 18, 10, 20, PUR)]
    for (x, y, w, h, a, col) in rects:
        bits.append((c.rrect_rot(x - w / 2, y - h / 2, x + w / 2, y + h / 2, 3, a), col))
    dots = [(108, 58, 7, SUN), (234, 152, 6.5, PNK), (194, 92, 6, LIM)]
    for (x, y, r, col) in dots:
        bits.append((c.circle(x, y, r), col))
    allm = [cone, mouth] + [m for m, _ in streamers] + [m for m, _ in bits]
    c.outline(*allm)
    # cone with stripes
    c.part(cone, PUR, PUR_L, PUR_D, kind="sphere", lc=(0.3, 0.3), core=0.0, line=False)
    for d0, d1 in ((30, 52), (74, 96), (116, 138)):
        q0 = (tip[0] + ax[0] * d0, tip[1] + ax[1] * d0)
        q1 = (tip[0] + ax[0] * d1, tip[1] + ax[1] * d1)
        band = c.poly([(q0[0] + n[0] * 90, q0[1] + n[1] * 90), (q1[0] + n[0] * 90, q1[1] + n[1] * 90),
                       (q1[0] - n[0] * 90, q1[1] - n[1] * 90), (q0[0] - n[0] * 90, q0[1] - n[1] * 90)])
        c.fill(m_inter(band, cone), SUN)
    c.fgrad(cone, lambda x, y: ((x - M[0]) * n[0] + (y - M[1]) * n[1]) / (2.0 * hw) + 0.5,
            [(0, alpha(WHITE, 90)), (0.4, alpha(WHITE, 0)), (0.6, alpha(INK, 0)), (1, alpha(INK, 110))])
    c.spec(76, 160, 6, 26, 45, 0.6, clip=cone, blur=2)
    c.line_under(mouth, 4.5)
    c.fill(mouth, PUR_L)
    c.fill(c.ell(M[0] + 2, M[1] + 2, hw - 8, 10, 45), PUR_DD)
    for m, col in streamers:
        c.part(m, col, lighten(col, 0.5), shade(col, 0.25), kind="diag", core=0.3, lw=3.5)
    for m, col in bits:
        c.part(m, col, lighten(col, 0.5), shade(col, 0.25), kind="sphere", core=0.25, lw=3.5)
    _small_star(c, 118, 22, 16, SUN, lw=3.5)
    _small_star(c, 236, 40, 13, SUN, lw=3.5)
    c.sparkle(230, 202, 14, WHITE, ow=3)
    return c.done()


# --------------------------------------------------------------------------
# Icons: settings & utility
# --------------------------------------------------------------------------


def icon_gear():
    c = Ico()
    teeth = mnew(c.size)
    for i in range(8):
        teeth = m_union(teeth, c.poly(rot_pts(rrect_pts(107, 18, 149, 70, 9), 128, 128, i * 45 + 22.5)))
    gear = c.rnd(m_union(c.circle(128, 128, 86), teeth), 4)
    hole = c.circle(128, 128, 30)
    gear = m_sub(gear, hole)
    c.outline(gear)
    c.part(gear, LAG, LAG_L, LAG_D, kind="sphere", lc=(0.3, 0.25), core=0.45, line=False)
    ring = m_sub(c.circle(128, 128, 64), c.circle(128, 128, 48))
    c.fill(ring, hx("#13B4B1"), 0.9)
    c.fill(m_inter(m_sub(ring, c.shift(ring, -3, -3)), ring), WHITE, 0.6)
    c.fill(m_inter(m_sub(ring, c.shift(ring, 3, 3)), ring), LAG_DD, 0.7)
    c.fill(m_sub(c.circle(128, 128, 38), hole), INK)
    c.spec(84, 70, 22, 9, -40, 0.75, clip=gear, blur=2.5)
    return c.done()


def icon_lock():
    c = Ico()
    _padlock(c, 128, 124, 1.0)
    return c.done()


def icon_music():
    c = Ico()
    h1 = c.ell(76, 190, 34, 26, -22)
    h2 = c.ell(178, 166, 34, 26, -22)
    s1 = c.rrect(94, 66, 111, 190, 7)
    s2 = c.rrect(196, 42, 213, 166, 7)
    beam = c.rnd(c.poly([(94, 58), (213, 32), (213, 74), (94, 100)]), 5)
    sil = m_union(h1, h2, s1, s2, beam)
    c.outline(sil)
    c.part(sil, PUR, PUR_L, PUR_D, kind="diag", core=0.4, line=False)
    c.spec(64, 180, 12, 6, -22, 0.85, clip=h1, blur=1.5)
    c.spec(166, 156, 12, 6, -22, 0.85, clip=h2, blur=1.5)
    c.spec(130, 66, 24, 4, -12, 0.6, clip=beam, blur=1.5)
    c.sparkle(46, 72, 17, WHITE, ow=3)
    return c.done()


def icon_sound():
    c = Ico()
    box = c.rrect(24, 90, 88, 166, 12)
    cone = c.rnd(c.poly([(76, 92), (142, 42), (152, 42), (152, 214), (142, 214), (76, 164)]), 8)
    w1 = c.line(arc_pts(150, 128, 44, -48, 48, 30), 18)
    w2 = c.line(arc_pts(150, 128, 80, -52, 52, 40), 18)
    c.outline(box, cone, w1, w2)
    c.part(box, DEV, DEV_L, DEV_D, kind="vertical", core=0.35, line=False)
    c.part(cone, OCE, OCE_L, OCE_D, kind="sphere", lc=(0.3, 0.3), core=0.4)
    c.spec(110, 72, 8, 18, 30, 0.7, clip=cone, blur=2)
    for w in (w1, w2):
        _white(c, w, line=True, lw=0.01)
    return c.done()


def icon_vibrate():
    c = Ico()
    phone = c.rrect(82, 24, 174, 232, 22)
    zz = []
    for sgn in (-1, 1):
        x0 = 128 + sgn * 74
        pts = [(x0 + (sgn * 10 if i % 2 else -sgn * 10), 62 + i * 27) for i in range(6)]
        zz.append(c.line(pts, 12))
    c.outline(phone, *zz)
    for z in zz:
        c.part(z, COR, COR_L, COR_D, kind="sphere", core=0.3, line=False)
    c.part(phone, DEV, DEV_L, DEV_D, kind="vertical", core=0.35, line=False)
    scr = c.rrect(95, 48, 161, 192, 10)
    c.grad(scr, (95, 48), (161, 192), [(0, LAG_L), (0.6, LAG), (1, LAG_D)])
    c.fill(m_inter(c.poly([(95, 122), (161, 70), (161, 92), (95, 144)]), scr), WHITE, 0.3)
    c.fill(c.rrect(116, 32, 140, 38, 3), DEV_D)
    c.fill(c.circle(128, 211, 8), STEEL)
    c.spec(96, 40, 4, 10, 0, 0.6, clip=phone, blur=1.5)
    return c.done()


def icon_graphics():
    c = Ico()
    mon = c.rrect(22, 46, 216, 182, 18)
    neck = c.poly([(106, 180), (148, 180), (156, 212), (98, 212)])
    base = c.rrect(72, 204, 182, 228, 10)
    c.outline(mon, neck, base)
    c.part(neck, STEEL, STEEL_L, STEEL_D, kind="vertical", core=0.3)
    c.part(base, STEEL, STEEL_L, STEEL_D, kind="vertical", core=0.3)
    c.part(mon, DEV, DEV_L, DEV_D, kind="vertical", core=0.35, line=False)
    scr = c.rrect(36, 60, 202, 168, 10)
    c.grad(scr, (0, 60), (0, 168), [(0, OCE), (0.55, OCE_L), (1, hx("#E6F7FF"))])
    sun = m_inter(c.circle(150, 104, 20), scr)
    c.fill(m_inter(m_blur(c.circle(150, 104, 30), c.s(6)), scr), SUN_L, 0.7)
    c.rgrad(sun, (145, 98), 24, [(0, SUN_L), (1, SUN)])
    sea = m_inter(c.poly([(36, 168)] + [(36 + i * 4, 132 + 3 * math.sin(i * 0.7)) for i in range(43)] +
                         [(206, 168)]), scr)
    c.grad(sea, (0, 128), (0, 168), [(0, LAG), (1, LAG_D)])
    for (x, y) in ((70, 146), (120, 156), (170, 146)):
        c.fill(m_inter(c.line([(x - 12, y), (x - 4, y - 3), (x + 4, y), (x + 12, y - 3)], 3.5), sea), FOAM, 0.9)
    isl = m_inter(c.ell(64, 140, 26, 10), scr)
    c.fill(isl, SAND)
    c.fill(c.line(catmull([(66, 138), (60, 118), (66, 98)], False, 6), 4), WOOD_D)
    for a in (-170, -120, -60, -10):
        c.fill(c.ell(66 + 11 * math.cos(math.radians(a)), 96 + 6 * math.sin(math.radians(a)), 13, 4.5, a), LIM_D)
    c.fill(m_inter(c.poly([(36, 168), (110, 60), (140, 60), (66, 168)]), scr), WHITE, 0.12)
    st = m_poly(c.size, twinkle_pts(c.X(204), c.X(46), c.s(42), 2.3))
    c.fill(c.dil(st, 4.5), INK)
    c.rgrad(st, (204, 46), 42, [(0, hx("#FFFBE0")), (0.5, SUN_L), (1, SUN)])
    c.sparkle(238, 96, 12, WHITE, ow=3)
    return c.done()


def icon_privacy():
    c = Ico()
    sh = _shield_mask(c)
    c.outline(sh)
    c.rgrad(sh, (96, 70), 190, [(0, OCE_L), (0.45, OCE), (1, OCE_DD)])
    inner = c.ero(sh, 14)
    rim = m_sub(sh, inner)
    c.grad(rim, (60, 40), (190, 220), [(0, hx("#E6F4FF")), (0.6, hx("#A7D3FF")), (1, hx("#5C9DE2"))])
    c.fill(m_sub(c.dil(inner, 3), inner), OCE_DD, 0.85)
    c.fill(m_inter(c.poly([(20, 20), (236, 20), (128, 128)]), inner), WHITE, 0.12)
    c.spec(82, 64, 14, 26, 28, 0.55, clip=inner, blur=3)
    ck = c.line([(82, 124), (114, 156), (176, 92)], 30)
    c.glyph_shadow(ck, inner, OCE_DD, 0.5)
    c.line_under(ck, 5)
    c.part(ck, LIM, LIM_L, LIM_D, kind="diag", core=0.4, line=False)
    c.spec(96, 128, 6, 3, 45, 0.8, clip=ck, blur=1)
    return c.done()


def icon_doc():
    c = Ico()
    page = c.rnd(c.poly([(48, 22), (164, 22), (210, 68), (210, 234), (48, 234)]), 12)
    c.outline(page)
    c.grad(page, (48, 22), (210, 234), [(0, WHITE), (1, hx("#D9F0F2"))])
    c.inner_shadow(page, -4, -5, 3, FOAM_D, 0.6)
    fold = c.rnd(c.poly([(162, 18), (214, 70), (162, 70)]), 4)
    c.line_under(fold, 4.5)
    c.grad(fold, (162, 70), (190, 40), [(0, LAG_L), (1, hx("#E2FBF9"))])
    head = c.rrect(72, 58, 140, 78, 10)
    c.fill(head, LAG)
    for i, y in enumerate((104, 130, 156, 182)):
        c.fill(c.rrect(72, y, 186 - (30 if i == 3 else 0), y + 12, 6), hx("#B7D6DE"))
    c.fill(c.rrect(72, 206, 118, 216, 5), hx("#B7D6DE"))
    c.spec(70, 40, 18, 5, 0, 0.6, clip=page, blur=1.5)
    return c.done()


def icon_edit():
    c = Ico()
    T = (46, 210)
    d = (0.7071, -0.7071)
    n = (0.7071, 0.7071)

    def P(u, v):
        return (T[0] + d[0] * u + n[0] * v, T[1] + d[1] * u + n[1] * v)
    hw = 25
    cone = c.poly([P(0, 0), P(52, -hw), P(52, hw)])
    lead = c.poly([P(-2, 0), P(20, -hw * 0.38), P(20, hw * 0.38)])
    body = c.poly([P(50, -hw), P(166, -hw), P(166, hw), P(50, hw)])
    ferr = c.poly([P(164, -hw - 1), P(190, -hw - 1), P(190, hw + 1), P(164, hw + 1)])
    eras = m_union(c.poly([P(188, -hw), P(204, -hw), P(204, hw), P(188, hw)]), c.circle(*P(204, 0), hw))
    eras = c.rnd(m_inter(eras, c.poly([P(188, -hw - 2), P(232, -hw - 2), P(232, hw + 2), P(188, hw + 2)])), 6)
    c.outline(cone, body, ferr, eras)
    c.part(cone, hx("#FFDDB0"), hx("#FFF0DA"), hx("#D9A76A"), kind="diag", core=0.25, line=False)
    c.fill(lead, INK)
    for (v0, v1, col) in ((-hw, -hw / 3.0, SUN_L), (-hw / 3.0, hw / 3.0, SUN), (hw / 3.0, hw, SUN_D)):
        band = c.poly([P(50, v0), P(166, v0), P(166, v1), P(50, v1)])
        c.fill(m_inter(c.dil(band, 0.5), body), col)
    c.fill(m_inter(m_union(c.line([P(50, -hw / 3.0), P(166, -hw / 3.0)], 2.5),
                           c.line([P(50, hw / 3.0), P(166, hw / 3.0)], 2.5)), body), hx("#E39A12"), 0.7)
    c.line_under(ferr, 4)
    c.grad(ferr, P(176, -hw), P(176, hw), [(0, STEEL_L), (0.5, STEEL), (1, STEEL_D)])
    rid = m_union(c.line([P(172, -hw), P(172, hw)], 3), c.line([P(182, -hw), P(182, hw)], 3))
    c.fill(m_inter(rid, ferr), STEEL_D, 0.8)
    c.line_under(eras, 4)
    c.grad(eras, P(204, -hw), P(204, hw), [(0, PNK_L), (0.5, PNK), (1, PNK_D)])
    c.spec(*P(110, -hw * 0.55), 40, 3.5, -45, 0.6, clip=body, blur=1.2)
    c.sparkle(66, 66, 19, WHITE, ow=3.2)
    return c.done()


def icon_trash():
    c = Ico()
    bin_ = c.rnd(c.poly([(58, 84), (198, 84), (186, 232), (70, 232)]), 12)
    lid = c.rrect(38, 56, 218, 90, 12)
    handle = m_sub(c.rrect(98, 26, 158, 64, 14), c.rrect(113, 40, 143, 64, 6))
    c.outline(bin_, lid, handle)
    c.part(handle, STEEL, STEEL_L, STEEL_D, kind="vertical", core=0.3, line=False)
    c.part(bin_, STEEL, STEEL_L, STEEL_D, kind="diag", core=0.4, line=False)
    grooves = mnew(c.size)
    for x0, x1 in ((94, 97), (128, 128), (162, 159)):
        grooves = m_union(grooves, c.line([(x0, 106), (x1, 210)], 9))
    _engrave(c, m_inter(grooves, bin_), STEEL, STEEL_L, hx("#7F9EBE"), 2)
    c.part(lid, hx("#C6DAEA"), STEEL_L, STEEL_D, kind="vertical", core=0.35)
    c.spec(82, 64, 26, 4, 0, 0.7, clip=lid, blur=1.5)
    c.spec(78, 124, 6, 30, 4, 0.5, clip=bin_, blur=2.5)
    return c.done()


def icon_mission():
    c = Ico()
    board = c.rrect(36, 34, 220, 234, 22)
    clip = c.rrect(88, 14, 168, 58, 14)
    c.outline(board, clip)
    c.part(board, OCE, OCE_L, OCE_D, kind="vertical", core=0.35, line=False)
    paper = c.rrect(54, 52, 202, 218, 8)
    c.line_under(paper, 4)
    c.grad(paper, (0, 52), (0, 218), [(0, WHITE), (1, hx("#DDF3F4"))])
    c.inner_shadow(paper, -3, -4, 3, FOAM_D, 0.6)
    c.line_under(clip, 4.5)
    c.part(clip, STEEL, STEEL_L, STEEL_D, kind="vertical", core=0.35, line=False)
    c.fill(c.rrect(114, 24, 142, 34, 5), INK)
    c.spec(104, 22, 14, 3, 0, 0.8, clip=clip, blur=1)
    for i, y in enumerate((96, 136, 176)):
        box = c.rrect(70, y - 15, 100, y + 15, 7)
        c.line_under(box, 4)
        c.fill(box, WHITE)
        if i < 2:
            ck = c.line([(76, y), (85, y + 9), (104, y - 14)], 8.5)
            c.line_under(ck, 3.5)
            c.fill(ck, LIM)
            c.fill(m_inter(c.shift(ck, 0, 2.5), ck), LIM_D, 0.6)
        bar = c.rrect(112, y - 7, 186 - (i * 14), y + 7, 7)
        c.fill(bar, hx("#B7D6DE") if i < 2 else LAG_L)
    c.spec(56, 44, 14, 4, 0, 0.5, clip=board, blur=1.5)
    return c.done()


def icon_calendar():
    c = Ico()
    page = c.rrect(28, 48, 228, 232, 28)
    rings = [c.rrect(68, 18, 92, 78, 12), c.rrect(164, 18, 188, 78, 12)]
    c.outline(page, *rings)
    c.part(page, WHITE, WHITE, hx("#BFE3E8"), kind="vertical", core=0.35, line=False)
    band = m_inter(m_inter(c.rrect(28, 48, 228, 108, 28), c.rect_above(106)), page)
    c.grad(band, (0, 48), (0, 106), [(0, COR_L), (0.5, COR), (1, COR_D)])
    c.fill(m_inter(c.line([(28, 106), (228, 106)], 5), page), INK)
    c.spec(68, 62, 24, 5, 0, 0.5, clip=band, blur=1.5)
    for hx_ in (80, 176):
        c.fill(c.circle(hx_, 78, 10), INK)
    for r in rings:
        c.part(r, STEEL, STEEL_L, STEEL_D, kind="sphere", core=0.35, lw=4)
    seven = m_seven(c, 90, 134, 166, 116, 212, 26)
    c.glyph_shadow(seven, page, hx("#9FC7D0"), 0.6, 5)
    c.line_under(seven, 5)
    c.grad(seven, (0, 122), (0, 222), [(0, OCE_L), (0.35, OCE), (1, OCE_D)])
    c.spec(110, 130, 14, 3, 0, 0.8, clip=seven, blur=1)
    return c.done()


def icon_ad():
    c = Ico()
    body = c.rrect(28, 104, 228, 232, 22)
    stick = c.rrect(28, 80, 228, 112, 8)
    arm = c.poly(rot_pts(rrect_pts(28, 42, 226, 74, 8), 36, 76, -14))
    c.outline(body, stick, arm)
    c.part(body, PUR, PUR_L, PUR_D, kind="vertical", core=0.4, bounce=0.2, line=False)

    def stripes(m, ang):
        s = mnew(c.size)
        for i in range(-2, 12):
            x = 20 + i * 30
            s = m_union(s, c.poly(rot_pts([(x, 0), (x + 15, 0), (x + 35, 140), (x + 20, 140)], 128, 80, ang)))
        return m_inter(s, m)
    for m, ang in ((stick, 0), (arm, -14)):
        c.line_under(m, 4.5)
        c.fill(m, FOAM)
        c.fill(stripes(m, ang), INK)
        c.overlay(m, 0.35, 0.25, INK)
    hinge = c.circle(38, 82, 9)
    c.line_under(hinge, 3.5)
    c.part(hinge, STEEL, STEEL_L, STEEL_D, kind="sphere", core=0.3, line=False)
    play = c.rnd(c.poly([(104, 136), (104, 206), (166, 171)]), 9)
    c.glyph_shadow(play, body, PUR_DD, 0.6)
    c.line_under(play, 5)
    c.part(play, SUN, SUN_L, SUN_D, kind="diag", core=0.35, line=False)
    c.spec(62, 124, 26, 5, 0, 0.5, clip=body, blur=2)
    return c.done()


def icon_video():
    c = Ico()
    body = c.rrect(22, 36, 234, 220, 50)
    c.outline(body)
    c.part(body, PNK, PNK_L, PNK_D, kind="sphere", lc=(0.3, 0.2), R=1.1, core=0.4, line=False)
    perf = mnew(c.size)
    for i in range(6):
        x = 58 + i * 28
        perf = m_union(perf, c.rrect(x - 7, 48, x + 7, 60, 3.5), c.rrect(x - 7, 196, x + 7, 208, 3.5))
    c.fill(perf, PNK_DD, 0.75)
    c.fill(m_sub(c.shift(perf, 0, 2), perf), WHITE, 0.35)
    disc = c.circle(128, 128, 56)
    c.glyph_shadow(disc, body, PNK_DD, 0.5)
    c.line_under(disc, 5)
    c.part(disc, FOAM, WHITE, FOAM_D, kind="sphere", lc=(0.35, 0.3), core=0.35, line=False)
    tri = c.rnd(c.poly([(110, 96), (110, 160), (164, 128)]), 8)
    c.line_under(tri, 3.5)
    c.grad(tri, (110, 96), (160, 160), [(0, PNK), (1, PNK_D)])
    sheen = m_inter(c.ell(128, 62, 92, 34), body)
    c.grad(sheen, (0, 30), (0, 96), [(0, alpha(WHITE, 130)), (1, alpha(WHITE, 0))])
    return c.done()


def icon_hand():
    c = Ico()
    ang = -40.0

    def T(x, y):
        return rot_pts([(x, y)], 128, 140, ang)[0]

    def TP(ps):
        return [T(x, y) for x, y in ps]
    index = c.line([T(110, 48), T(110, 132)], 42)
    fist = m_union(c.poly(TP(rrect_pts(84, 104, 176, 196, 32))),
                   *[c.circle(*T(x, y), r) for (x, y, r) in ((172, 125, 19), (177, 152, 19), (172, 179, 18))])
    thumb = c.line([T(82, 172), T(136, 146)], 36)
    cuff = c.poly(TP(rrect_pts(90, 188, 172, 234, 12)))
    tip = T(110, 26)
    dirn = ang - 90
    rip = [c.line(arc_pts(tip[0], tip[1], 26, dirn - 50, dirn + 50, 16), 8),
           c.line(arc_pts(tip[0], tip[1], 44, dirn - 44, dirn + 44, 18), 8)]
    c.outline(index, fist, thumb, cuff, *rip)
    for r_ in rip:
        c.part(r_, SUN, SUN_L, SUN_D, kind="sphere", core=0.2, line=False)
    wp = dict(kind="diag", core=0.45, core_d=0.12)
    c.part(cuff, FOAM, WHITE, FOAM_D, **wp)
    c.fill(m_inter(c.poly(TP([(80, 206), (180, 206), (180, 216), (80, 216)])), cuff), LAG)
    c.part(index, FOAM, WHITE, FOAM_D, line=False, **wp)
    c.part(fist, FOAM, WHITE, FOAM_D, **wp)
    gaps = m_union(c.line([T(150, 137), T(186, 136)], 4.5), c.line([T(150, 164), T(184, 164)], 4.5))
    c.fill(m_inter(gaps, fist), INK)
    c.part(thumb, FOAM, WHITE, FOAM_D, lw=4.5, **wp)
    c.fill(m_inter(c.line([T(110, 106), T(110, 122)], 3.5), fist), FOAM_D, 0.9)
    c.spec(*T(102, 58), 5, 16, ang, 0.9, clip=index, blur=1.5)
    return c.done()


# --------------------------------------------------------------------------
# Icons: power-ups & race
# --------------------------------------------------------------------------


def icon_flag():
    c = Ico()
    pole = c.rrect(38, 32, 56, 234, 9)
    knob = c.circle(47, 28, 15)

    def fy(x, base):
        return base + 10 * math.sin((x - 54) / 166.0 * 2 * math.pi)

    xs = [54 + i * 4 for i in range(42)]
    top = [(x, fy(x, 40)) for x in xs]
    bot = [(x, fy(x, 150) - (x - 54) * 0.06) for x in xs]
    flag = c.rnd(c.poly(top + bot[::-1]), 4)
    c.outline(pole, knob, flag)
    c.part(pole, STEEL, STEEL_L, STEEL_D, kind="sphere", core=0.3, line=False)
    c.part(knob, SUN, SUN_L, SUN_D, kind="sphere", core=0.3, spec=0.8, spec_blur=0.1)
    c.line_under(flag, 4.5)
    c.grad(flag, (54, 40), (220, 150), [(0, COR_L), (0.35, COR), (1, COR_D)])
    stripe = c.poly([(x, fy(x, 40) + 72 - (x - 54) * 0.04) for x in xs] +
                    [(x, fy(x, 40) + 86 - (x - 54) * 0.045) for x in reversed(xs)])
    c.fill(m_inter(stripe, flag), FOAM)
    sh = mnew(c.size)
    hl = mnew(c.size)
    for x in range(54, 222, 2):
        v = math.cos((x - 54) / 166.0 * 2 * math.pi)
        strip = c.poly([(x, 20), (x + 2, 20), (x + 2, 190), (x, 190)])
        if v > 0.2:
            sh = m_union(sh, strip)
        if v < -0.6:
            hl = m_union(hl, strip)
    c.fill(m_blur(m_inter(sh, flag), c.s(6)), INK, 0.2)
    c.fill(m_blur(m_inter(hl, flag), c.s(5)), WHITE, 0.25)
    _small_star(c, 128, 86, 22, SUN, lw=4)
    return c.done()


def icon_turbo():
    c = Ico()
    bolt = c.rnd(c.poly([(124, 12), (200, 12), (156, 94), (210, 94), (88, 244), (114, 140), (56, 140)]), 6)
    streaks = []
    for (y, x0, x1, w) in ((84, 22, 92, 13), (122, 14, 64, 12), (176, 30, 72, 11)):
        ctr = [(x0 + (x1 - x0) * i / 10.0, y) for i in range(11)]
        streaks.append(c.poly(taper_pts(ctr, lambda t, w=w: 2 + (w - 2) * smooth(t * 1.6))))
    streaks = [c.rnd(s_, 1.5) for s_ in streaks]
    c.outline(bolt, *streaks)
    for s_ in streaks:
        _white(c, s_)
    c.part(bolt, SUN, SUN_L, SUN_D, kind="diag", core=0.45, core_d=0.1, line=False)
    inner = c.ero(bolt, 14)
    c.fill(m_inter(m_blur(inner, c.s(4)), bolt), SUN_L, 0.55)
    c.inner_shadow(bolt, -4, -6, 3, SUN_DD, 0.55)
    c.spec(150, 26, 18, 5, -30, 0.9, clip=bolt, blur=1.5)
    c.spec(92, 128, 12, 4, -30, 0.6, clip=bolt, blur=1.5)
    c.sparkle(214, 210, 18, WHITE, ow=3.2)
    return c.done()


def icon_sneaker():
    c = Ico()
    ang = -6.0

    def T(x, y):
        return rot_pts([(128 + (x - 128) * 0.84, 150 + (y - 150) * 1.06)], 128, 150, ang)[0]

    def TP(ps):
        return [T(x, y) for x, y in ps]
    upper = c.spoly(TP([(44, 198), (40, 150), (48, 106), (70, 84), (104, 84), (122, 108), (148, 116), (184, 130),
                        (216, 148), (236, 174), (234, 198)]), 10)
    tongue = c.poly(TP(rrect_pts(94, 66, 126, 112, 13)))
    sole = c.spoly(TP([(32, 192), (238, 188), (248, 202), (238, 220), (44, 222), (28, 208)]), 8)
    # wing on the heel: four stacked feathers with pointed tips (top one longest) under a rounded covert
    B = (70, 112)

    def W(ps, rot=24.0):
        return TP([(B[0] + x, B[1] + y) for x, y in rot_pts(ps, 0, 0, rot)])
    feathers = []
    for (y, xe) in ((18, -48), (0, -68), (-18, -88), (-36, -106)):
        fp = [(8, y - 11), (xe + 20, y - 11.5), (xe, y + 2), (xe + 18, y + 11), (8, y + 11)]
        feathers.append(c.rnd(c.poly(W(fp)), 2.5))
    covert = c.poly(W(ellipse_pts(-2, -12, 21, 31, 0, 60)))
    wing = m_union(covert, *feathers)
    c.outline(upper, tongue, sole, wing)
    c.part(tongue, FOAM, WHITE, FOAM_D, kind="vertical", core=0.3, line=False)
    c.line_under(upper, 4.5)
    c.part(upper, COR, COR_L, COR_D, kind="sphere", lc=(0.35, 0.2), core=0.4, line=False)
    toe = m_inter(c.ell(*T(222, 202), 42, 42), upper)
    c.fill(m_inter(c.dil(toe, 3.5), upper), INK)
    c.fill(toe, FOAM)
    guard = m_inter(c.poly(TP([(10, 170), (204, 170), (204, 240), (10, 240)])), m_sub(upper, c.dil(toe, 3.5)))
    c.fill(guard, LAG)
    c.fill(m_inter(c.line(TP([(14, 170), (204, 170)]), 3.5), m_sub(upper, toe)), INK)
    bolt = c.poly(TP([(96, 120), (130, 120), (114, 140), (138, 140), (82, 168), (98, 146), (76, 146)]))
    c.line_under(bolt, 3.5)
    c.grad(bolt, T(80, 120), T(124, 168), [(0, SUN_L), (1, SUN)])
    c.fill(c.ell(*T(82, 89), 18, 6, ang), INK)
    laces = mnew(c.size)
    for u in (0.1, 0.42, 0.74):
        p = (lerp(132, 194, u), lerp(114, 138, u))
        laces = m_union(laces, c.line(TP([(p[0] - 8, p[1] - 6), (p[0] + 6, p[1] + 12)]), 7.5))
    laces = m_inter(laces, upper)
    c.line_under(laces, 3)
    c.fill(laces, FOAM)
    c.line_under(sole, 4.5)
    c.part(sole, FOAM, WHITE, FOAM_D, kind="vertical", core=0.4, line=False)
    c.fill(m_inter(c.line(TP([(36, 210), (242, 208)]), 4), sole), LAG, 0.9)
    c.spec(*T(66, 124), 12, 6, -40 + ang, 0.75, clip=upper, blur=2)
    for fm in feathers + [covert]:
        c.line_under(fm, 3.5)
        c.part(fm, FOAM, WHITE, FOAM_D, kind="vertical", core=0.35, line=False)
    c.spec(*W([(-44, -40)])[0], 18, 3, -24 + ang, 0.85, clip=feathers[3], blur=1)
    c.spec(*W([(-10, -30)])[0], 8, 12, ang, 0.8, clip=covert, blur=1.5)
    c.sparkle(236, 112, 14, WHITE, ow=3)
    return c.done()


def icon_bubble():
    c = Ico()
    cx, cy, R = 128, 126, 104
    disc = c.circle(cx, cy, R)
    c.outline_ring(disc)
    c.fill(disc, LAG_L, 0.38)
    c.rgrad(disc, (cx, cy), R, [(0, alpha(WHITE, 40)), (0.7, alpha(WHITE, 0)), (1, alpha(WHITE, 0))])
    rim = m_inter(m_blur(m_sub(disc, c.ero(disc, 18)), c.s(7)), disc)

    def irid(x, y):
        a = math.atan2(y - cy, x - cx)
        return 0.5 + 0.5 * math.sin(2.0 * a + 0.6)
    c.fgrad(rim, irid, [(0, PNK), (0.3, PUR), (0.55, OCE), (0.8, LAG), (1, LIM)], 0.85)

    def irid2(x, y):
        a = math.atan2(y - cy, x - cx)
        return 0.5 + 0.5 * math.cos(3.0 * a)
    rim2 = m_inter(m_blur(m_sub(disc, c.ero(disc, 34)), c.s(10)), disc)
    c.fgrad(rim2, irid2, [(0, alpha(SUN, 140)), (0.5, alpha(PNK, 0)), (1, alpha(LAG, 120))], 0.6)
    sh = _shield_mask(c, cy=0, k=0.44)
    sh = c.shift(sh, 0, 2)
    c.fill(sh, WHITE, 0.55)
    c.fill(m_sub(c.dil(sh, 4), sh), WHITE, 0.85)
    c.fill(m_inter(c.poly([(60, 60), (196, 60), (128, 128)]), sh), WHITE, 0.25)
    crescent = m_sub(c.ell(92, 82, 52, 34, -38), c.ell(104, 96, 52, 36, -38))
    c.fill(m_inter(m_blur(crescent, c.s(1.5)), disc), WHITE, 0.95)
    c.spec(62, 128, 6, 9, 0, 0.9, clip=disc, blur=1)
    low = m_sub(c.ell(170, 176, 44, 26, -38), c.ell(160, 164, 46, 28, -38))
    c.fill(m_inter(m_blur(low, c.s(2)), disc), WHITE, 0.5)
    c.sparkle(212, 40, 22, WHITE, ow=3.5)
    return c.done()


def icon_magnet():
    c = Ico()
    cx, cy, R, r = 128, 108, 82, 36
    arch = m_inter(m_sub(c.circle(cx, cy, R), c.circle(cx, cy, r)), c.rect_above(cy))
    legs = m_union(c.rrect(cx - R, cy - 2, cx - r, 172, 2), c.rrect(cx + r, cy - 2, cx + R, 172, 2))
    body = c.rnd(m_union(arch, legs), 3)
    tips = m_union(c.rrect(cx - R, 168, cx - r, 210, 6), c.rrect(cx + r, 168, cx + R, 210, 6))
    ang = -32
    body, tips = c.rot(body, ang, 128, 128), c.rot(tips, ang, 128, 128)
    body = m_sub(body, tips)
    c.outline(body, tips)
    c.part(body, COR, COR_L, COR_D, kind="sphere", lc=(0.3, 0.25), core=0.45, line=False)
    c.part(tips, STEEL, STEEL_L, STEEL_D, kind="vertical", core=0.3)
    c.spec(70, 72, 22, 8, -50, 0.75, clip=body, blur=2.5)
    x, y, rr = 198, 216, 24
    ang_c = math.degrees(math.atan2(178 - y, 160 - x))
    pull = m_union(c.line(arc_pts(x, y, rr + 16, ang_c - 34, ang_c + 34, 12), 6.5),
                   c.line(arc_pts(x, y, rr + 30, ang_c - 28, ang_c + 28, 12), 6.5))
    c.line_under(pull, 3.5)
    c.fill(pull, SUN_L)
    c.outline(c.circle(x, y, rr), c.circle(x, y + rr * 0.15, rr))
    _coin_face(c, x, y, rr, star=True, outline=False)
    return c.done()


def icon_stopwatch():
    c = Ico()
    case = c.circle(128, 142, 94)
    stem = c.rrect(114, 30, 142, 62, 6)
    cap = c.rrect(92, 14, 164, 40, 13)
    side = c.rrect_rot(186, 50, 212, 74, 6, 45)
    c.outline(case, stem, cap, side)
    c.part(stem, STEEL, STEEL_L, STEEL_D, kind="vertical", core=0.3, line=False)
    c.part(side, SUN, SUN_L, SUN_D, kind="vertical", core=0.3)
    c.part(cap, SUN, SUN_L, SUN_D, kind="vertical", core=0.35)
    c.spec(110, 22, 14, 4, 0, 0.8, clip=cap, blur=1)
    c.line_under(case, 0.01)
    c.part(case, OCE, OCE_L, OCE_D, kind="sphere", lc=(0.3, 0.25), core=0.4, line=False)
    face = c.circle(128, 144, 72)
    c.line_under(face, 4.5)
    c.grad(face, (0, 72), (0, 216), [(0, WHITE), (1, hx("#D7F1F4"))])
    c.inner_shadow(face, -4, -6, 4, FOAM_D, 0.6)
    wedge = c.pie(128, 144, 60, -90, -20)
    c.fill(wedge, LAG_L, 0.85)
    ticks = mnew(c.size)
    for i in range(12):
        a = math.radians(-90 + i * 30)
        r0 = 52 if i % 3 == 0 else 58
        ticks = m_union(ticks, c.line([(128 + r0 * math.cos(a), 144 + r0 * math.sin(a)),
                                       (128 + 64 * math.cos(a), 144 + 64 * math.sin(a))], 6 if i % 3 == 0 else 4))
    c.fill(ticks, INK)
    a = math.radians(-20)
    hand = c.line([(128, 144), (128 + 54 * math.cos(a), 144 + 54 * math.sin(a))], 9)
    c.line_under(hand, 3)
    c.fill(hand, COR)
    hub = c.circle(128, 144, 11)
    c.line_under(hub, 3)
    c.part(hub, COR, COR_L, COR_D, kind="sphere", core=0.3, line=False)
    c.spec(76, 86, 20, 9, -42, 0.7, clip=m_sub(case, face), blur=2.5)
    c.spec(96, 106, 14, 5, -35, 0.6, clip=face, blur=2)
    return c.done()


def icon_road():
    c = Ico()
    ctr = catmull([(78, 250), (150, 208), (168, 166), (112, 130), (100, 100), (136, 76)], closed=False, n=16)
    wfn = lambda t: lerp(104, 30, t ** 0.85)
    road = c.poly(taper_pts(ctr, wfn))
    road = m_inter(c.ero(c.dil(road, 5), 5), c.rect_above(234))  # close: heals the tight inner curve
    grass = m_inter(c.dil(road, 12), c.rect_above(234))
    c.outline(grass)
    c.part(grass, LIM, LIM_L, LIM_D, kind="vertical", core=0.3, line=False)
    c.line_under(road, 4)
    c.grad(road, (0, 76), (0, 234), [(0, ROAD_L), (0.5, ROAD), (1, ROAD_D)])
    edge = m_sub(road, c.ero(road, 5))
    c.fill(edge, FOAM, 0.95)
    pts = resample(ctr, 2.0)
    L = len(pts)
    dash = mnew(c.size)
    seg = []
    for i, p in enumerate(pts):
        t = i / float(L - 1)
        on = (i % 11) < 6
        if on:
            seg.append(p)
        elif seg:
            if len(seg) >= 2:
                dash = m_union(dash, c.line(seg, max(3.0, wfn(t) * 0.11)))
            seg = []
    c.fill(m_inter(dash, c.ero(road, 6)), SUN)
    for (x, y, r) in ((44, 200, 9), (214, 120, 8), (60, 112, 7)):
        b = c.circle(x, y, r)
        c.fill(m_inter(b, grass), LIM_D, 0.8)
    _trophy(c, 132, 44, 0.36, ow=8, sparkle=False)
    c.sparkle(196, 26, 15, WHITE, ow=3)
    return c.done()


def icon_dance():
    c = Ico()
    n1, h1 = _note(c, 74, 186, 76, True, 32, 25, 17)
    n2, h2 = _note(c, 166, 150, 34, True, 28, 22, 15)
    n1 = c.rot(n1, -10, 90, 140)
    h1 = c.rot(h1, -10, 90, 140)
    n2 = c.rot(n2, 12, 180, 100)
    h2 = c.rot(h2, 12, 180, 100)
    c.outline(n1, n2)
    c.part(n1, PNK, PNK_L, PNK_D, kind="diag", core=0.4, line=False)
    c.line_under(n2, 4.5)
    c.part(n2, LAG, LAG_L, LAG_D, kind="diag", core=0.4, line=False)
    c.spec(62, 178, 11, 6, -30, 0.85, clip=h1, blur=1.5)
    c.spec(160, 142, 10, 5, -12, 0.85, clip=h2, blur=1.5)
    c.sparkle(200, 220, 22, SUN, ow=3.5)
    c.sparkle(40, 70, 20, SUN, ow=3.5)
    c.sparkle(232, 92, 12, WHITE, ow=3)
    _small_star(c, 126, 38, 16, SUN, lw=3.5)
    mot = m_union(c.line(arc_pts(132, 210, 22, 200, 260, 10), 6), c.line(arc_pts(132, 210, 36, 205, 255, 10), 6))
    c.line_under(mot, 3)
    c.fill(mot, PUR)
    return c.done()


def icon_hat():
    c = Ico()
    brim = c.ell(128, 166, 114, 54)
    crown = m_union(m_inter(c.ell(128, 154, 66, 84), c.rect_above(158)), c.ell(128, 156, 66, 20))
    c.outline(brim, crown)
    c.part(brim, SUN, SUN_L, SUN_D, kind="sphere", lc=(0.35, 0.3), R=0.9, core=0.0, line=False)
    rings = mnew(c.size)
    for (rx, ry) in ((96, 44), (80, 36)):
        rings = m_union(rings, m_sub(c.ell(128, 166, rx, ry), c.ell(128, 166, rx - 2.5, ry - 2.5)))
    c.fill(rings, SUN_DD, 0.35)
    c.fill(m_inter(m_sub(brim, c.shift(brim, 0, -7)), brim), SUN_DD, 0.55)
    c.fill(m_inter(m_blur(c.shift(crown, 6, 10), c.s(5)), brim), SUN_DD, 0.5)
    c.line_under(crown, 4.5)
    c.part(crown, SUN, SUN_L, SUN_D, kind="sphere", lc=(0.3, 0.2), core=0.45, line=False)
    weave = mnew(c.size)
    for i in range(-6, 8):
        x = 128 + i * 14
        weave = m_union(weave, c.line([(x, 40), (x + 30, 170)], 2.2), c.line([(x + 30, 40), (x, 170)], 2.2))
    c.fill(m_inter(weave, crown), SUN_DD, 0.22)
    band = m_inter(crown, m_sub(c.ell(128, 156, 68, 20), c.ell(128, 130, 68, 20)))
    c.line_under(band, 3.5)
    c.part(band, COR, COR_L, COR_D, kind="vertical", core=0.3, line=False)
    fx, fy = 176, 140
    petals = m_union(*[c.circle(fx + 13 * math.cos(math.radians(a)), fy + 13 * math.sin(math.radians(a)), 11)
                       for a in range(-90, 270, 72)])
    c.line_under(petals, 4)
    c.part(petals, PNK, PNK_L, PNK_D, kind="sphere", core=0.3, line=False)
    ctr = c.circle(fx, fy, 7)
    c.line_under(ctr, 2.5)
    c.fill(ctr, SUN)
    c.spec(102, 74, 12, 22, 20, 0.7, clip=crown, blur=2.5)
    c.spec(56, 150, 22, 6, -12, 0.55, clip=brim, blur=2)
    return c.done()


def icon_trail():
    c = Ico()
    ctr = catmull([(18, 222), (64, 206), (112, 178), (152, 136), (178, 92)], closed=False, n=16)
    wfn = lambda t: 4 + 96 * smooth(t) ** 0.8
    trail = c.poly(taper_pts(ctr, wfn))
    sx, sy = 192, 64
    star = _star_mask(c, sx, sy + 4, 56, 27, 7)
    c.outline(trail)
    cols = [COR, SUN, LIM, OCE, PUR]
    for i, col in enumerate(cols):
        off = (lambda i: (lambda t: -wfn(t) / 2.0 + (i + 0.5) * wfn(t) / 5.0))(i)
        sw = lambda t: wfn(t) / 5.0 + 1.2
        band = m_inter(c.poly(offset_strip(ctr, off, sw)), trail)
        c.fill(band, col)
    c.overlay(trail, 0.25, 0.25, INK)
    c.spec(110, 160, 26, 4, -40, 0.6, clip=trail, blur=2)
    c.outline(star)
    c.part(star, SUN, SUN_L, SUN_D, kind="sphere", lc=(0.32, 0.25), core=0.4, line=False)
    inner = _star_mask(c, sx, sy + 6, 36, 17, 5)
    c.grad(inner, (sx - 30, sy - 30), (sx + 20, sy + 40), [(0, hx("#FFF6C0")), (1, hx("#FFC92E"))])
    c.spec(sx - 18, sy - 14, 12, 6, -35, 0.85, clip=star, blur=2)
    c.sparkle(70, 150, 16, WHITE, ow=3)
    c.sparkle(150, 214, 14, WHITE, ow=3)
    c.sparkle(232, 140, 12, SUN_L, ow=3)
    c.sparkle(34, 182, 9, WHITE, ow=2.5)
    return c.done()


def icon_boss():
    c = Ico()
    head = c.ell(128, 142, 106, 94)
    crown = c.rnd(c.poly([(86, 66), (78, 22), (106, 42), (128, 12), (150, 42), (178, 22), (170, 66)]), 3)
    c.outline(head, crown)
    c.part(crown, SUN_D, SUN, SUN_DD, kind="sphere", core=0.3, lw=4.5)
    c.fill(c.circle(128, 52, 6), PNK)
    c.line_under(head, 0.01)
    c.part(head, SUN, SUN_L, SUN_D, kind="sphere", lc=(0.32, 0.22), core=0.4, line=False)
    for x in (56, 200):
        c.fill(c.ell(x, 166, 20, 12), COR, 0.5)
    _eye(c, 90, 128, 24, 27, 5, 5)
    _eye(c, 166, 128, 24, 27, -5, 5)
    brows = m_union(c.line([(56, 86), (114, 108)], 15), c.line([(200, 86), (142, 108)], 15))
    c.fill(brows, INK)
    c.fill(m_inter(c.shift(brows, -1, -3), c.ero(brows, 3)), hx("#2E5476"))
    lower = c.ell(128, 198, 38, 15)
    upper = c.spoly([(70, 172), (128, 160), (186, 172), (178, 190), (128, 196), (78, 190)], 8)
    c.part(lower, BEAK_D, BEAK, COR_DD, kind="vertical", core=0.3, lw=4.5)
    c.part(upper, BEAK, BEAK_L, BEAK_D, kind="vertical", core=0.35, lw=4.5)
    c.fill(c.ell(114, 172, 4, 2.5), BEAK_D)
    c.fill(c.ell(142, 172, 4, 2.5), BEAK_D)
    c.spec(96, 168, 16, 4, -6, 0.7, clip=upper, blur=1.2)
    vein = mnew(c.size)
    vx, vy = 214, 52
    for a in (45, 135, 225, 315):
        ar = math.radians(a)
        px, py = vx + 11 * math.cos(ar), vy + 11 * math.sin(ar)
        vein = m_union(vein, c.line(arc_pts(px, py, 9, a + 90, a + 180, 8), 5.5))
    c.line_under(vein, 3)
    c.fill(vein, COR)
    c.spec(70, 74, 24, 10, -38, 0.7, clip=head, blur=3)
    return c.done()


def icon_duck():
    c = Ico()
    body = c.spoly([(34, 150), (26, 104), (44, 116), (70, 140), (122, 142), (190, 150), (224, 172), (212, 210),
                    (160, 226), (80, 224), (38, 202)], 10)
    head = c.circle(152, 86, 56)
    beak_u = c.spoly([(194, 84), (230, 82), (240, 94), (222, 104), (198, 104)], 8)
    beak_l = c.spoly([(198, 102), (228, 104), (222, 116), (202, 114)], 8)
    water = c.rnd(c.poly([(18, 212)] + [(18 + i * 4, 212 + 6 * math.sin(i * 0.55)) for i in range(56)] +
                         [(238, 236), (18, 236)]), 5)
    c.outline(body, head, beak_u, beak_l, water)
    c.part(beak_l, BEAK_D, BEAK, COR_DD, kind="vertical", core=0.3, line=False)
    c.line_under(beak_u, 4)
    c.part(beak_u, BEAK, BEAK_L, BEAK_D, kind="vertical", core=0.3, line=False)
    c.part(body, SUN, SUN_L, SUN_D, kind="sphere", lc=(0.35, 0.2), core=0.4, line=False)
    c.line_under(head, 5)
    c.part(head, SUN, SUN_L, SUN_D, kind="sphere", lc=(0.3, 0.25), core=0.4, line=False)
    wing = c.spoly([(160, 160), (130, 146), (96, 146), (66, 156), (80, 166), (64, 176), (82, 184), (72, 194),
                    (104, 198), (140, 192)], 8)
    c.line_under(wing, 4)
    c.part(wing, hx("#FFC92E"), SUN_L, SUN_D, kind="vertical", core=0.35, line=False)
    c.spec(118, 156, 18, 4, -4, 0.6, clip=wing, blur=1.2)
    eye = c.ell(166, 74, 9, 12)
    c.fill(eye, INK)
    c.fill(c.circle(163, 69, 3.5), WHITE)
    c.fill(c.ell(178, 102, 10, 6), PNK, 0.55)
    c.spec(130, 46, 18, 8, -38, 0.8, clip=head, blur=2)
    c.spec(70, 150, 16, 6, -25, 0.55, clip=body, blur=2)
    c.line_under(water, 4.5)
    c.grad(water, (0, 204), (0, 236), [(0, LAG_L), (0.4, LAG), (1, LAG_D)])
    rip = m_union(c.line([(30, 224), (56, 224)], 4), c.line([(176, 226), (210, 226)], 4))
    c.fill(m_inter(rip, water), FOAM, 0.9)
    return c.done()


def icon_lifebuoy():
    c = Ico()
    cx, cy, Ro, Ri = 128, 126, 104, 50
    ring = m_sub(c.circle(cx, cy, Ro), c.circle(cx, cy, Ri))
    c.outline(ring)
    for i in range(8):
        col = COR if i % 2 == 0 else FOAM
        seg = c.pie(cx, cy, Ro + 2, -90 + i * 45 - 22.5, -90 + i * 45 + 22.5)
        c.fill(m_inter(seg, ring), col)
    Rc, hw = (Ro + Ri) / 2.0, (Ro - Ri) / 2.0
    c.fgrad(ring, lambda x, y: abs(math.hypot(x - cx, y - cy) - Rc) / hw,
            [(0, alpha(WHITE, 0)), (0.55, alpha(INK, 0)), (1, alpha(INK, 120))])
    c.grad(ring, (cx - Ro, cy - Ro), (cx + Ro, cy + Ro), [(0, alpha(WHITE, 90)), (0.5, alpha(WHITE, 0)),
                                                          (0.55, alpha(INK, 0)), (1, alpha(INK, 90))])
    seps = mnew(c.size)
    for i in range(8):
        a = math.radians(-90 + i * 45 - 22.5)
        seps = m_union(seps, c.line([(cx + Ri * math.cos(a), cy + Ri * math.sin(a)),
                                     (cx + Ro * math.cos(a), cy + Ro * math.sin(a))], 2.5))
    c.fill(m_inter(seps, ring), INK, 0.5)
    for k in range(4):
        a = -45 + k * 90
        wrap = c.poly(rot_pts(rrect_pts(cx + Ri - 6, cy - 7, cx + Ro + 6, cy + 7, 5), cx, cy, a))
        c.line_under(wrap, 3.5)
        c.part(wrap, SAND, SAND_L, SAND_D, kind="vertical", core=0.25, line=False)
        tw = mnew(c.size)
        for j in range(5):
            r0 = Ri - 2 + j * 12
            tw = m_union(tw, c.line(rot_pts([(cx + r0, cy + 7), (cx + r0 + 7, cy - 7)], cx, cy, a), 2.2))
        c.fill(m_inter(tw, wrap), SAND_DD, 0.8)
    hl = c.line(arc_pts(cx, cy, Rc + 6, 195, 250, 20), 10)
    c.fill(m_inter(m_blur(hl, c.s(2.5)), ring), WHITE, 0.85)
    c.spec(cx + 40, cy + 64, 8, 4, -40, 0.5, clip=ring, blur=1.5)
    return c.done()


def icon_storm():
    c = Ico()
    cloud = m_union(m_cloud(c.size, [(c.X(x), c.X(y), c.s(r)) for (x, y, r) in
                                     ((70, 112, 44), (122, 82, 58), (178, 98, 48), (210, 132, 32), (46, 140, 30))]),
                    c.rrect(30, 116, 230, 166, 25))
    bolt = c.rnd(c.poly([(124, 120), (172, 120), (146, 168), (178, 168), (104, 244), (118, 186), (86, 186)]), 5)
    drops = []
    for (x, y, s) in ((54, 198, 1.0), (188, 196, 0.9), (214, 232, 0.75), (40, 236, 0.7)):
        drops.append(c.spoly([(x, y - 22 * s), (x + 11 * s, y), (x, y + 11 * s), (x - 11 * s, y)], 8))
    c.outline(cloud, bolt, *drops)
    for d_ in drops:
        c.part(d_, OCE, OCE_L, OCE_D, kind="sphere", core=0.3, line=False)
        bx, by = c.bbu(d_)[:2]
        c.spec(bx + 7, by + 16, 2.5, 5, 20, 0.85, clip=d_, blur=0.8)
    c.part(cloud, CLOUD, CLOUD_L, CLOUD_D, kind="vertical", core=0.45, core_d=0.18, line=False)
    c.spec(96, 62, 30, 10, -24, 0.7, clip=cloud, blur=3)
    c.line_under(bolt, 5)
    c.part(bolt, SUN, SUN_L, SUN_D, kind="diag", core=0.4, line=False)
    c.spec(140, 130, 10, 3, -20, 0.8, clip=bolt, blur=1)
    return c.done()


def icon_wave():
    c = Ico()
    cx, cy = 118, 110
    N = 110
    ctr = []
    for i in range(N + 1):
        t = i / float(N)
        th = math.radians(lerp(30, -292, t))
        r = lerp(98, 24, t ** 0.85)
        ctr.append((cx + r * math.cos(th), cy + r * math.sin(th)))
    wfn = lambda t: lerp(82, 12, t ** 0.9)
    curl = c.poly(taper_pts(ctr, wfn))
    barrel = m_union(c.circle(cx + 2, cy + 4, 46), c.poly([(64, 112), (172, 112), (176, 200), (60, 200)]))
    top = catmull([(14, 196), (50, 184), (92, 194), (134, 186), (176, 172), (214, 146), (244, 116)], False, 10)
    body = c.rnd(c.poly(top + [(244, 234), (14, 234)]), 6)
    spray = [c.circle(214, 40, 10), c.circle(236, 66, 7), c.circle(198, 20, 6)]
    c.outline(curl, barrel, body, *spray)
    c.grad(barrel, (0, 70), (0, 200), [(0, OCE_DD), (1, OCE_D)])
    c.fill(m_inter(m_blur(c.circle(cx + 6, cy + 14, 24), c.s(8)), barrel), OCE, 0.6)
    c.line_under(curl, 4.5)
    c.fgrad(curl, lambda x, y: (math.hypot(x - cx, y - cy) - 20) / 110.0,
            [(0, OCE_D), (0.4, OCE), (0.8, LAG), (1, LAG_L)])
    fw = lambda t: lerp(20, 7, t)
    t0, t1 = 0.06, 0.78
    sub = [p for i, p in enumerate(ctr) if t0 <= i / float(N) <= t1]
    foam = c.poly(offset_strip(sub, lambda u: wfn(lerp(t0, t1, u)) / 2.0 - fw(u) / 2.0 + 1, fw))
    c.fill(m_inter(foam, curl), FOAM)
    c.fill(m_inter(c.shift(foam, 2, 4), m_sub(curl, foam)), WHITE, 0.25)
    tipc = ctr[-1]
    c.fill(c.circle(tipc[0], tipc[1], 7), FOAM)
    c.line_under(body, 4.5)
    c.grad(body, (0, 120), (0, 234), [(0, LAG), (0.5, OCE), (1, OCE_D)])
    c.fill(m_inter(c.poly(offset_strip(top, lambda t: 5, lambda t: 9)), body), FOAM, 0.95)
    rip = m_union(c.line([(40, 214), (72, 214)], 5), c.line([(120, 220), (160, 220)], 5),
                  c.line([(196, 206), (224, 206)], 5))
    c.fill(m_inter(rip, body), WHITE, 0.6)
    for s_ in spray:
        c.part(s_, FOAM, WHITE, FOAM_D, kind="sphere", core=0.3, line=False)
    return c.done()


def icon_daily():
    c = Ico()
    cx, cy = 112, 110
    rays = mnew(c.size)
    for i in range(10):
        a = math.radians(-90 + i * 36)
        tip = (cx + 102 * math.cos(a), cy + 102 * math.sin(a))
        b1 = (cx + 60 * math.cos(a - 0.24), cy + 60 * math.sin(a - 0.24))
        b2 = (cx + 60 * math.cos(a + 0.24), cy + 60 * math.sin(a + 0.24))
        rays = m_union(rays, c.poly([tip, b1, b2]))
    rays = c.rnd(rays, 5)
    sun = c.circle(cx, cy, 68)
    page = c.rrect(146, 146, 238, 236, 16)
    nubs = [c.rrect(164, 134, 178, 160, 7), c.rrect(206, 134, 220, 160, 7)]
    c.outline(rays, sun)
    c.part(rays, SUN_D, SUN, SUN_DD, kind="sphere", core=0.3, line=False)
    c.line_under(sun, 5)
    c.part(sun, SUN, SUN_L, SUN_D, kind="sphere", lc=(0.32, 0.26), core=0.4, line=False)
    for ex in (90, 134):
        c.fill(c.line(arc_pts(ex, 112, 10, 200, 340, 12), 6), INK)
    c.fill(c.line(arc_pts(cx, 124, 16, 25, 155, 16), 6), INK)
    for x in (74, 150):
        c.fill(c.ell(x, 132, 11, 6.5), COR, 0.5)
    c.spec(82, 70, 22, 10, -38, 0.8, clip=sun, blur=2.5)
    c.outline(page, *nubs)
    c.part(page, WHITE, WHITE, hx("#BFE3E8"), kind="vertical", core=0.35, line=False)
    band = m_inter(c.rect_above(170), page)
    c.grad(band, (0, 146), (0, 170), [(0, COR_L), (0.5, COR), (1, COR_D)])
    c.fill(m_inter(c.line([(146, 170), (238, 170)], 4), page), INK)
    for n_ in nubs:
        c.part(n_, STEEL, STEEL_L, STEEL_D, kind="sphere", core=0.3, lw=3.5)
    _small_star(c, 192, 204, 22, SUN, lw=3.5)
    return c.done()


# --------------------------------------------------------------------------
# Registry & manifest
# --------------------------------------------------------------------------

ICONS = [
    ("coin", "Gold coin with thick milled edge, engraved ring and an embossed star over a wave mark; sparkle."),
    ("gem", "Faceted gem, pink crown fading to purple pavilion, lagoon glints and two sparkles."),
    ("gear", "Settings gear in lagoon teal with a bevelled inner ring and ink hub hole."),
    ("play", "Rounded play triangle, lime fill with ink outline (no button behind it)."),
    ("pause", "Two rounded pause bars, foam-white glyph for HUD buttons."),
    ("home", "Beach hut: straw thatch roof with fringe, lagoon plank walls, coral arched door, porthole window."),
    ("shop", "Pink and white striped shopping bag with lagoon handles and fold, star label."),
    ("levels", "Folded sand-coloured treasure map with a dotted coral path from a lime start dot to a coral X, "
               "palm islet and lagoon waves."),
    ("profile", "Cute round teal head-and-shoulders avatar silhouette with big glossy eyes, blush and a tuft."),
    ("events", "Purple party popper with sunshine stripes bursting confetti, streamers and stars."),
    ("trophy", "Gold trophy cup with handles, embossed star, ocean-blue base and gold plate."),
    ("star", "Gold star with inner bevel and glossy highlight (filled rating star)."),
    ("star_empty", "Empty rating star slot: ink outline with translucent navy fill and inset shading."),
    ("lock", "Padlock: sunshine-gold body, steel shackle, ink keyhole."),
    ("close", "Coral round button with a white X."),
    ("back", "Lagoon round button with a white left arrow."),
    ("check", "Lime round button with a white check mark."),
    ("restart", "Foam-white circular arrow (retry)."),
    ("music", "Purple beamed double music note with sparkle (music setting)."),
    ("sound", "Speaker with ocean-blue cone and two white sound waves (sound effects setting)."),
    ("vibrate", "Navy phone with lagoon screen and coral zig-zag vibration lines."),
    ("graphics", "Monitor showing a sunny lagoon scene with palm islet, big gold sparkle (graphics quality)."),
    ("privacy", "Ocean-blue shield with a lime check mark (privacy policy)."),
    ("doc", "White document with folded corner, lagoon header and text lines (terms / docs)."),
    ("info", "Ocean-blue round button with a white 'i'."),
    ("edit", "Yellow pencil with steel ferrule and pink eraser, sparkle (edit name)."),
    ("trash", "Steel rubbish bin with lid and grooves (delete)."),
    ("gift", "Purple gift box with sunshine ribbon and bow."),
    ("chest", "Wooden treasure chest with gold straps, trim and lock plate, sparkle."),
    ("wheel", "Prize wheel with eight palette segments, gold bulb rim and coral pointer."),
    ("mission", "Ocean-blue clipboard with steel clip, two lime-checked tasks and one open task."),
    ("calendar", "Calendar page with coral header, steel rings and a big ocean-blue 7."),
    ("crown", "Gold crown with pink and lagoon jewels."),
    ("ad", "Purple video clapperboard with striped clapper and gold play triangle (watch ad)."),
    ("hand", "White cartoon glove pointing up-left with sunshine tap ripples, lagoon cuff band (tutorial)."),
    ("flag", "Checkpoint flag: coral waving flag with white stripe and gold star on a steel pole."),
    ("turbo", "Sunshine lightning bolt with speed streaks (turbo power-up)."),
    ("sneaker", "Coral 'Jet Sneaker' with lagoon mudguard, lightning side mark and a white wing on the heel."),
    ("bubble", "Iridescent translucent soap-bubble shield with a faint shield emblem and highlights."),
    ("magnet", "Coral horseshoe magnet with steel tips pulling two gold coins."),
    ("stopwatch", "Ocean-blue stopwatch with gold crown button, tick marks, coral hand and elapsed wedge."),
    ("medal_gold", "Gold medal with embossed star on coral ribbon, sparkle."),
    ("medal_silver", "Silver medal with embossed star on ocean ribbon."),
    ("medal_bronze", "Bronze medal with embossed star on purple ribbon."),
    ("daily", "Smiling sun with a small calendar badge holding a gold star (daily reward)."),
    ("road", "Winding road with yellow centre dashes through a lime verge, small trophy at the far end."),
    ("dance", "Two bouncing music notes (pink, lagoon) with gold sparkles and a star (victory dances)."),
    ("hat", "Straw sun hat with coral band and pink hibiscus flower."),
    ("trail", "Rainbow trail swoosh (coral to purple) led by a gold star, with sparkles."),
    ("character", "Generic cute racer bust: kid with brown hair, swim goggles on head, coral striped shirt."),
    ("boss", "Giant boss rubber-duck head: cute-angry eyebrows, pouty beak, little crown, comic anger mark."),
    ("bonus", "Two stacks of gold coins with sparkles (bonus coins)."),
    ("storm", "Lavender cartoon storm cloud with a sunshine lightning bolt and blue raindrops."),
    ("wave", "Curling ocean wave with foam crest, deep barrel and spray droplets."),
    ("duck", "Cute yellow rubber duck floating on a lagoon ripple."),
    ("lifebuoy", "Coral and white lifebuoy ring with sand rope wraps."),
    ("capsule", "Mystery capsule toy ball (purple top, sunshine bottom) with a white label showing '?'."),
    ("plus", "Lime round badge with a white plus."),
    ("coins_pile", "Heap of gold coins with a big upright star coin in front, sparkles."),
    ("video", "Pink rounded video badge with film perforations and a white play disc."),
]


def write_manifest(fits):
    lines = [
        "# UI icon manifest - Splash Dash: Obstacle Party",
        "",
        "Generated by `tools/gen_ui_art.py` (Pillow only, deterministic). Re-run from the project root:",
        "",
        "    python3 tools/gen_ui_art.py",
        "",
        "All icons: `app/src/main/res/drawable-nodpi/`, 256x256 RGBA PNG, transparent background, drawn at 4x and",
        "LANCZOS-downsampled. The icon's bounding box is fitted to ~88% of the canvas (226 px) and centred.",
        "Ink outline #13304A ~9-10 px with a short downward extrusion, glossy gradient fills, top-left lighting.",
        "",
        "Palette: ink #13304A, lagoon #19C6C2/#0E8F9E, ocean #2E9BFF/#1B6FD1, coral #FF6B57/#E04A3A,",
        "sunshine #FFD23F/#F2A90F, lime #7BE36B/#34B54A, pink #FF5FA8/#D93A86, purple #9B6BFF/#6B3FD9,",
        "sand #FFE9B8, foam #F6FFFE.",
        "",
        "| Drawable | Description | Outline px |",
        "|---|---|---|",
    ]
    for name, desc in ICONS:
        f = fits.get(name)
        ow = "%.1f" % (Ico.W_OUT * f) if f else "-"  # f = fit * stroke scale
        lines.append("| `ui_%s` | %s | %s |" % (name, desc, ow))
    lines.append("")
    with open(MANIFEST, "w") as fh:
        fh.write("\n".join(lines))
    print("  wrote", os.path.relpath(MANIFEST, ROOT))


def render(fn):
    """Render an icon, re-rendering with stroke scale 1/fit until the outline weight converges."""
    ks = 1.0
    for _ in range(4):
        Ico.KS = ks
        img = fn()
        f = _LAST_FIT[0]
        if abs(ks * f - 1.0) < 0.01:
            break
        ks = 1.0 / f
    Ico.KS = 1.0
    return img, f, ks


def main(argv):
    names = [n for n, _ in ICONS]
    if "--list" in argv:
        print("\n".join(names))
        return 0
    only = [a[3:] if a.startswith("ui_") else a for a in argv if not a.startswith("-")]
    bad = [n for n in only if n not in names]
    if bad:
        print("unknown icon(s):", ", ".join(bad))
        return 2
    os.makedirs(NODPI, exist_ok=True)
    fits = {}
    for name in names:
        if only and name not in only:
            continue
        img, f, ks = render(globals()["icon_" + name])
        img.save(os.path.join(NODPI, "ui_%s.png" % name), optimize=True)
        fits[name] = f * ks
        print("  ui_%-14s fit %.3f  outline %.2f px" % (name, f, Ico.W_OUT * ks * f))
    if not only:
        write_manifest(fits)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
