"""
Composes launcher icons, splash icon, Play icon and the feature graphic from the Blender renders.

    python3 tools/make_icons.py <render_dir>
    python3 tools/make_icons.py --splash <avatar.png>   # launch-screen icon only

Inputs (from tools/blender/render_icon.py): <render_dir>/icon_fg.png, <render_dir>/hero.png.
The launch screen shows the default racer (Kai), rendered with
tools/blender/preview_avatars.py in portrait mode; put it in <render_dir>/splash_avatar.png
so a full rebuild keeps it.
"""
import math
import os
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
STORE = os.path.join(ROOT, "store")
SRC = sys.argv[1]

INK = (19, 48, 74)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def background(size):
    """Lagoon gradient with sun glow, rays, waves and bubbles (supersampled)."""
    S = size * 4
    im = Image.new("RGBA", (S, S))
    px = im.load()
    c0, c1, c2 = (120, 245, 235), (25, 198, 194), (27, 111, 209)
    cx, cy = S * 0.5, S * 0.38
    for y in range(S):
        for x in range(S):
            d = math.hypot(x - cx, y - cy) / (S * 0.75)
            t = min(1.0, d)
            col = lerp(c0, c1, min(1, t * 1.6)) if t < 0.62 else lerp(c1, c2, min(1, (t - 0.62) / 0.5))
            px[x, y] = col + (255,)
    # sun rays on their own layer, blended softly
    rays = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    rd = ImageDraw.Draw(rays)
    for k in range(14):
        a0 = k * 2 * math.pi / 14
        a1 = a0 + math.pi / 28
        r = S * 1.2
        rd.polygon([(cx, cy), (cx + math.cos(a0) * r, cy + math.sin(a0) * r), (cx + math.cos(a1) * r, cy + math.sin(a1) * r)], fill=(255, 255, 255, 255))
    rays.putalpha(rays.split()[3].point(lambda v: int(v * 0.13)))
    im.alpha_composite(rays)
    dr = ImageDraw.Draw(im, "RGBA")
    # waves at the bottom
    for i, (yy, col) in enumerate([(0.74, (255, 255, 255, 70)), (0.82, (255, 255, 255, 110)), (0.9, (255, 255, 255, 150))]):
        pts = []
        for xx in range(0, S + 8, 8):
            pts.append((xx, S * yy + math.sin(xx / S * math.pi * 4 + i) * S * 0.018))
        dr.line(pts, fill=col, width=int(S * 0.018))
    # bubbles
    for (bx, by, br) in [(0.16, 0.22, 0.03), (0.82, 0.18, 0.022), (0.88, 0.55, 0.03), (0.12, 0.6, 0.02)]:
        dr.ellipse([S * (bx - br), S * (by - br), S * (bx + br), S * (by + br)], outline=(255, 255, 255, 140), width=int(S * 0.006))
    return im.resize((size, size), Image.LANCZOS)


def subject(fg_path):
    fg = Image.open(fg_path).convert("RGBA")
    box = fg.getbbox()
    fg = fg.crop(box)
    # soft dark outline/shadow for pop on any background
    a = fg.split()[3]
    sh = Image.new("RGBA", fg.size, INK + (0,))
    sh.putalpha(a.filter(ImageFilter.MaxFilter(9)).point(lambda v: int(v * 0.85)))
    out = Image.new("RGBA", fg.size)
    out.alpha_composite(sh)
    out.alpha_composite(fg)
    return out


def place(canvas_size, subj, frac, dy=0.0):
    c = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    s = frac * canvas_size / max(subj.size)
    im = subj.resize((max(1, int(subj.size[0] * s)), max(1, int(subj.size[1] * s))), Image.LANCZOS)
    c.alpha_composite(im, ((canvas_size - im.size[0]) // 2, int((canvas_size - im.size[1]) // 2 + dy * canvas_size)))
    return c


def mask(im, shape):
    m = Image.new("L", (im.size[0] * 4, im.size[1] * 4), 0)
    d = ImageDraw.Draw(m)
    if shape == "circle":
        d.ellipse([0, 0, m.size[0] - 1, m.size[1] - 1], fill=255)
    else:
        d.rounded_rectangle([0, 0, m.size[0] - 1, m.size[1] - 1], radius=int(m.size[0] * 0.22), fill=255)
    m = m.resize(im.size, Image.LANCZOS)
    out = im.copy()
    out.putalpha(m)
    return out


def main():
    subj = subject(os.path.join(SRC, "icon_fg.png"))
    dens = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
    for name, k in dens.items():
        d = os.path.join(RES, "mipmap-" + name)
        os.makedirs(d, exist_ok=True)
        for f in os.listdir(d):
            if f.startswith("ic_launcher"):
                os.remove(os.path.join(d, f))
        a = int(108 * k)
        bg = background(a)
        fg = place(a, subj, 0.66, 0.0)
        bg.save(os.path.join(d, "ic_launcher_background.png"))
        fg.save(os.path.join(d, "ic_launcher_foreground.png"))
        mono = Image.new("RGBA", fg.size, (255, 255, 255, 0))
        mono.putalpha(fg.split()[3])
        mono.save(os.path.join(d, "ic_launcher_monochrome.png"))
        L = int(48 * k)
        full = background(L)
        full.alpha_composite(place(L, subj, 0.92, 0.0))
        mask(full, "rounded").save(os.path.join(d, "ic_launcher.png"))
        mask(full, "circle").save(os.path.join(d, "ic_launcher_round.png"))
    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
           '    <background android:drawable="@mipmap/ic_launcher_background" />\n    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
           '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />\n</adaptive-icon>\n')
    for n in ("ic_launcher.xml", "ic_launcher_round.xml"):
        open(os.path.join(anydpi, n), "w").write(xml)
    for stale in ("drawable/ic_launcher_background.xml", "drawable/ic_launcher_foreground.xml"):
        p = os.path.join(RES, stale)
        if os.path.exists(p):
            os.remove(p)
    # splash icon (inside the system splash circle): the default racer when rendered, else the mascot
    sp = os.path.join(SRC, "splash_avatar.png")
    if os.path.exists(sp):
        splash(sp)
    else:
        place(432, subj, 0.6, 0.02).save(os.path.join(RES, "drawable-nodpi", "splash_icon.png"))
    # Play icon 512 (full bleed; Play rounds it)
    os.makedirs(STORE, exist_ok=True)
    p = background(512)
    p.alpha_composite(place(512, subj, 0.86, 0.02))
    p.convert("RGB").save(os.path.join(STORE, "play_icon_512.png"))
    feature_graphic()
    print("icons written")


# hop cycle for the animated launch icon: (lift px at 432, squash-y, ripple 0..1), ~90 ms per frame
HOP = [(0, 0.86, 0.0), (8, 1.08, 0.0), (26, 1.02, 0.0), (34, 1.0, 0.0), (26, 1.0, 0.0), (9, 1.06, 0.0), (0, 0.84, 0.35), (0, 0.96, 0.75)]
HOP_MS = 110
# exactly two hops, then crouch ready to jump (the app's exit animation is the third jump that flies
# away; MainActivity.LAUNCH_HOPS_MS must equal HOPS * len(HOP) * HOP_MS)
HOPS = 2
READY = (0, 0.86, 0.0)
STAND = (0, 1.0, 0.0)


def splash(avatar_png):
    """Launch-screen icon: the default racer hopping on the spot (an AnimationDrawable the system
    plays while the app starts), drawn small enough to stay inside the system's circle mask."""
    subj = subject(avatar_png)
    S = 432
    fh = int(S * 0.47)
    k = fh / subj.size[1]
    fig = subj.resize((max(1, int(subj.size[0] * k)), fh), Image.LANCZOS)
    feet = int(S * 0.735)
    out = os.path.join(RES, "drawable-nodpi")
    for i, (lift, sy, rip) in enumerate(HOP + [READY, STAND]):
        c = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        d = ImageDraw.Draw(c)
        # soft shadow shrinks as the racer rises; a ripple ring spreads on landing
        sw = int(fig.size[0] * (0.62 - lift / 120))
        d.ellipse([S // 2 - sw // 2, feet - 7, S // 2 + sw // 2, feet + 7], fill=(10, 60, 110, 70))
        if rip > 0:
            rw = int(fig.size[0] * (0.55 + rip * 0.55))
            d.ellipse([S // 2 - rw // 2, feet - 6 - int(rip * 4), S // 2 + rw // 2, feet + 6 + int(rip * 4)], outline=(255, 255, 255, int(220 * (1 - rip * 0.6))), width=4)
        sx = 1.0 + (1.0 - sy) * 0.6
        w, h = max(1, int(fig.size[0] * sx)), max(1, int(fig.size[1] * sy))
        im = fig.resize((w, h), Image.LANCZOS)
        c.alpha_composite(im, (S // 2 - w // 2, feet - h - lift))
        c.save(os.path.join(out, "splash_icon_%d.png" % i))
        if i == len(HOP) + 1:
            os.replace(os.path.join(out, "splash_icon_%d.png" % i), os.path.join(out, "splash_icon.png"))  # still (older Android)
    items = "".join('    <item android:drawable="@drawable/splash_icon_%d" android:duration="%d" />\n' % (i, HOP_MS) for _ in range(HOPS) for i in range(len(HOP)))
    items += '    <item android:drawable="@drawable/splash_icon_%d" android:duration="10000" />\n' % len(HOP)
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n<animation-list xmlns:android="http://schemas.android.com/apk/res/android" android:oneshot="true">\n'
           + items + '</animation-list>\n')
    open(os.path.join(ROOT, "app", "src", "main", "res", "drawable", "splash_icon_anim.xml"), "w").write(xml)
    print("splash icon: %d hops of %d frames, then crouched, ready to jump" % (HOPS, len(HOP)))


def feature_graphic():
    W, H = 1024, 500
    bg = background(1100).resize((W, W)).crop((0, (W - H) // 2, W, (W - H) // 2 + H))
    hero = Image.open(os.path.join(SRC, "hero.png")).convert("RGBA")
    hb = hero.getbbox()
    hero = hero.crop(hb)
    s = (H * 0.92) / hero.size[1]
    hero = hero.resize((int(hero.size[0] * s), int(hero.size[1] * s)), Image.LANCZOS)
    if hero.size[0] > W * 0.62:
        s2 = W * 0.62 / hero.size[0]
        hero = hero.resize((int(hero.size[0] * s2), int(hero.size[1] * s2)), Image.LANCZOS)
    bg.alpha_composite(hero, (W - hero.size[0] - 10, H - hero.size[1] - 6))
    font_path = os.path.join(RES, "font", "lilita_one.ttf")
    d = ImageDraw.Draw(bg)

    def word(text, y, size, top, bottom):
        f = ImageFont.truetype(font_path, size)
        x = 40
        tw, th = d.textbbox((0, 0), text, font=f)[2:]
        layer = Image.new("RGBA", (tw + 40, th + 40), (0, 0, 0, 0))
        ld = ImageDraw.Draw(layer)
        ld.text((20, 10), text, font=f, fill=INK + (255,), stroke_width=int(size * 0.12), stroke_fill=INK + (255,))
        grad = Image.new("RGBA", layer.size)
        gp = grad.load()
        for yy in range(layer.size[1]):
            c = lerp(top, bottom, yy / layer.size[1])
            for xx in range(layer.size[0]):
                gp[xx, yy] = c + (255,)
        txt_mask = Image.new("L", layer.size, 0)
        ImageDraw.Draw(txt_mask).text((20, 10), text, font=f, fill=255)
        layer.paste(grad, (0, 0), txt_mask)
        bg.alpha_composite(layer, (x, y))

    word("SPLASH", 70, 120, (255, 244, 168), (255, 158, 46))
    word("DASH", 190, 150, (184, 255, 248), (46, 155, 255))
    f = ImageFont.truetype(font_path, 34)
    ribbon = "OBSTACLE RACE 3D"  # matches the Play title "Splash Dash: Obstacle Race 3D"
    right = max(380, 80 + d.textbbox((0, 0), ribbon, font=f, stroke_width=3)[2] + 20)
    d.rounded_rectangle([60, 372, right, 420], radius=16, fill=(255, 107, 87), outline=INK, width=4)
    d.text((80, 376), ribbon, font=f, fill=(255, 255, 255), stroke_width=3, stroke_fill=INK)
    bg.convert("RGB").save(os.path.join(STORE, "feature_graphic_1024x500.png"))


if sys.argv[1] == "--splash":
    splash(sys.argv[2])
else:
    main()
