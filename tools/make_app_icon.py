"""
Makes every app icon from one square, full-bleed illustration (store/app_icon_source.png):

  - Android adaptive launcher icon (mipmap-*/ic_launcher_background.png + an empty foreground):
    the art fills the visible 72 dp of the 108 dp layer at ART_DP, over a blurred copy of itself so
    launcher motion effects never show a hard edge
  - legacy launcher icons for Android 7.x (ic_launcher.png rounded square, ic_launcher_round.png circle)
  - Google Play icon store/play_icon_512.png and the website logo store/website/public/app_logo.png
  - Google Play feature graphic store/feature_graphic_1024x500.png: the game's logo on the left, the
    icon art on the right, blended into a backdrop stretched and blurred from the art's own left edge

    python3 tools/make_app_icon.py                 # uses store/app_icon_source.png
    python3 tools/make_app_icon.py path/to/art.png # copies it to store/app_icon_source.png first

A full illustration has no clean silhouette, so there is no monochrome (themed icon) layer: with
themed icons on, Android shows the normal colour icon.
"""
import colorsys
import os
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
SOURCE = os.path.join(ROOT, "store", "app_icon_source.png")
# how big the art is inside the 108 dp adaptive layer: the launcher shows the middle 72 dp, so
# 78 dp shows 92% of the picture (the mask then rounds the corners)
ART_DP = 78
DENS = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
FONT = os.path.join(RES, "font", "lilita_one.ttf")
INK = (19, 48, 74)


def mask(im, shape):
    m = Image.new("L", (im.size[0] * 4, im.size[1] * 4), 0)
    d = ImageDraw.Draw(m)
    if shape == "circle":
        d.ellipse([0, 0, m.size[0] - 1, m.size[1] - 1], fill=255)
    else:
        d.rounded_rectangle([0, 0, m.size[0] - 1, m.size[1] - 1], radius=int(m.size[0] * 0.22), fill=255)
    out = im.convert("RGBA")
    out.putalpha(m.resize(im.size, Image.LANCZOS))
    return out


def adaptive_layer(art, px):
    """A 108 dp layer: blurred, enlarged art behind the sharp art at ART_DP."""
    bg = art.resize((px, px), Image.LANCZOS).filter(ImageFilter.GaussianBlur(px * 0.04))
    s = round(px * ART_DP / 108)
    o = (px - s) // 2
    # feather the sharp art's square edge into the blur, well outside the visible 72 dp
    sharp = art.resize((s, s), Image.LANCZOS).convert("RGBA")
    f = Image.new("L", (s, s), 0)
    edge = max(2, s // 40)
    ImageDraw.Draw(f).rectangle([edge, edge, s - 1 - edge, s - 1 - edge], fill=255)
    sharp.putalpha(f.filter(ImageFilter.GaussianBlur(edge * 0.7)))
    out = bg.convert("RGBA")
    out.alpha_composite(sharp, (o, o))
    return out


def word(canvas, text, x, y, size, top, bottom):
    """The game's chunky logo letters: a vertical colour gradient inside a thick ink outline."""
    f = ImageFont.truetype(FONT, size)
    d = ImageDraw.Draw(canvas)
    stroke = int(size * 0.12)
    l, t, r, b = d.textbbox((0, 0), text, font=f, stroke_width=stroke)
    layer = Image.new("RGBA", (r - l + 20, b - t + 20), (0, 0, 0, 0))
    ox, oy = 10 - l, 10 - t
    ImageDraw.Draw(layer).text((ox, oy), text, font=f, fill=INK + (255,), stroke_width=stroke, stroke_fill=INK + (255,))
    grad = Image.new("RGBA", layer.size)
    gd = ImageDraw.Draw(grad)
    for yy in range(layer.size[1]):
        k = yy / max(1, layer.size[1] - 1)
        gd.line([(0, yy), (layer.size[0], yy)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * k) for i in range(3)) + (255,))
    letters = Image.new("L", layer.size, 0)
    ImageDraw.Draw(letters).text((ox, oy), text, font=f, fill=255)
    layer.paste(grad, (0, 0), letters)
    # a soft drop shadow so the letters lift off the picture
    shadow = Image.new("RGBA", layer.size, (0, 0, 0, 0))
    shadow.putalpha(layer.split()[3].point(lambda v: int(v * 0.45)).filter(ImageFilter.GaussianBlur(6)))
    canvas.alpha_composite(shadow, (x + 4, y + 8))
    canvas.alpha_composite(layer, (x, y))
    return x + layer.size[0], y + layer.size[1]


def feature_graphic(art):
    W, H = 1024, 500
    pic = art.resize((H, H), Image.LANCZOS).convert("RGBA")
    # backdrop: a sky-to-street gradient in the art's own colours (its top and bottom rows) ...
    def avg(box):
        return pic.crop(box).convert("RGB").resize((1, 1), Image.BOX).getpixel((0, 0))
    def vivid(c, sat=1.45, val=1.08):
        h, sa, v = colorsys.rgb_to_hsv(*(x / 255 for x in c))
        return tuple(int(255 * x) for x in colorsys.hsv_to_rgb(h, min(1.0, sa * sat), min(1.0, v * val)))
    sky, mid, street = (vivid(avg(b)) for b in ((0, 0, H, 60), (0, H // 2 - 30, H, H // 2 + 30), (0, H - 60, H, H)))
    canvas = Image.new("RGBA", (W, H))
    cd = ImageDraw.Draw(canvas)
    for y in range(H):
        k = y / (H - 1)
        a, b, t = (sky, mid, k * 2) if k < 0.5 else (mid, street, k * 2 - 1)
        cd.line([(0, y), (W, y)], fill=tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,))
    # ... meeting the art's left edge, stretched and blurred, just before the art begins
    strip = pic.crop((0, 0, 40, H)).resize((W, H), Image.BILINEAR).filter(ImageFilter.GaussianBlur(28))
    blend = Image.new("L", (W, H), 0)
    bd = ImageDraw.Draw(blend)
    for x in range(W):
        bd.line([(x, 0), (x, H)], fill=int(255 * min(1.0, max(0.0, (x - 330) / (W - H - 330))) ** 1.2))
    strip.putalpha(blend)
    canvas.alpha_composite(strip)
    # a few soft sparkles in the open sky
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    for (sx, sy, sr) in [(36, 40, 9), (470, 36, 7), (420, 150, 5), (500, 300, 6), (30, 445, 7), (360, 470, 5)]:
        gd.ellipse([sx - sr, sy - sr, sx + sr, sy + sr], fill=(255, 255, 255, 150))
        gd.polygon([(sx, sy - sr * 3), (sx + sr * 0.5, sy), (sx, sy + sr * 3), (sx - sr * 0.5, sy)], fill=(255, 255, 255, 110))
        gd.polygon([(sx - sr * 3, sy), (sx, sy - sr * 0.5), (sx + sr * 3, sy), (sx, sy + sr * 0.5)], fill=(255, 255, 255, 110))
    canvas.alpha_composite(glow.filter(ImageFilter.GaussianBlur(1.2)))
    # the art on the right, fading in from the left
    fade = Image.new("L", (H, H), 255)
    fd = ImageDraw.Draw(fade)
    ramp = 150
    for x in range(ramp):
        fd.line([(x, 0), (x, H)], fill=int(255 * (x / ramp) ** 1.4))
    pic.putalpha(fade)
    canvas.alpha_composite(pic, (W - H, 0))
    # darken the left a little so the logo reads on any colour
    shade = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shade)
    for x in range(W):
        sd.line([(x, 0), (x, H)], fill=(8, 24, 48, int(85 * max(0.0, 1 - x / 620) ** 1.3)))
    canvas.alpha_composite(shade)
    # logo: SPLASH / DASH and the ribbon, as in the game
    word(canvas, "SPLASH", 46, 52, 116, (255, 244, 168), (255, 158, 46))
    _, dash_bottom = word(canvas, "DASH", 46, 160, 150, (184, 255, 248), (46, 155, 255))
    f = ImageFont.truetype(FONT, 34)
    d = ImageDraw.Draw(canvas)
    ribbon = "OBSTACLE RACE 3D"
    rw = d.textbbox((0, 0), ribbon, font=f, stroke_width=3)[2]
    ry = dash_bottom + 8
    d.rounded_rectangle([58, ry, 58 + rw + 40, ry + 50], radius=16, fill=(255, 107, 87), outline=INK, width=4)
    d.text((78, ry + 5), ribbon, font=f, fill=(255, 255, 255), stroke_width=3, stroke_fill=INK)
    canvas.convert("RGB").save(os.path.join(ROOT, "store", "feature_graphic_1024x500.png"), optimize=True)


def main():
    if len(sys.argv) > 1:
        Image.open(sys.argv[1]).convert("RGB").save(SOURCE)
    art = Image.open(SOURCE).convert("RGB")
    assert art.size[0] == art.size[1], "the icon art must be square"
    for name, k in DENS.items():
        d = os.path.join(RES, "mipmap-" + name)
        os.makedirs(d, exist_ok=True)
        for f in os.listdir(d):
            if f.startswith("ic_launcher"):
                os.remove(os.path.join(d, f))
        a = int(108 * k)
        adaptive_layer(art, a).save(os.path.join(d, "ic_launcher_background.png"), optimize=True)
        Image.new("RGBA", (a, a), (0, 0, 0, 0)).save(os.path.join(d, "ic_launcher_foreground.png"), optimize=True)
        L = int(48 * k)
        legacy = art.resize((L, L), Image.LANCZOS)
        mask(legacy, "rounded").save(os.path.join(d, "ic_launcher.png"), optimize=True)
        mask(legacy, "circle").save(os.path.join(d, "ic_launcher_round.png"), optimize=True)
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<!-- made by tools/make_app_icon.py from store/app_icon_source.png -->\n'
           '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
           '    <background android:drawable="@mipmap/ic_launcher_background" />\n'
           '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
           '</adaptive-icon>\n')
    for n in ("ic_launcher.xml", "ic_launcher_round.xml"):
        open(os.path.join(RES, "mipmap-anydpi-v26", n), "w").write(xml)
    # Google Play: 512 x 512, full bleed (Play rounds the corners itself)
    art.resize((512, 512), Image.LANCZOS).save(os.path.join(ROOT, "store", "play_icon_512.png"), optimize=True)
    feature_graphic(art)
    web = os.path.join(ROOT, "store", "website", "public")
    if os.path.isdir(web):
        art.resize((256, 256), Image.LANCZOS).save(os.path.join(web, "app_logo.png"), optimize=True)
    print("app icons written from", os.path.relpath(SOURCE, ROOT))


if __name__ == "__main__":
    main()
