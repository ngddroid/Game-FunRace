"""Course furniture: modular posts/beams, coins, flags, arches, podium, set-piece props.
Nominal sizes are documented per model; the engine scales/poses them to fit each level."""
import math
from mathutils import Vector
from dlib import *

A = lambda g=0.5: paint(WHITE, TINT_A, g)          # tint A (world/hazard body colour)
Bc = lambda g=0.5: paint(WHITE, TINT_B, g)         # tint B (accent)
INK = 0x1B2340


def face(center, normal_rot_y, scale=1.0, angry=False):
    """Friendly cartoon face for hazards (eyes + brows + mouth), looking along rotated +Z."""
    cx, cy, cz = center
    parts = []
    for s in (1, -1):
        parts.append(sphere(1.0, (cx + 0.13 * s * scale, cy + 0.05 * scale, cz), scale=(0.07 * scale, 0.1 * scale, 0.04 * scale), mat=paint(WHITE, 0, 0.4), seg=14, rings=10))
        parts.append(sphere(0.045 * scale, (cx + 0.12 * s * scale, cy + 0.04 * scale, cz + 0.035 * scale), mat=paint(INK, 0, 0.6), seg=10, rings=8))
        parts.append(sphere(0.014 * scale, (cx + 0.105 * s * scale, cy + 0.065 * scale, cz + 0.07 * scale), mat=paint(WHITE, 0, 0, 0.6), seg=6, rings=4))
        if angry:
            parts.append(capsule((cx + 0.05 * s * scale, cy + 0.15 * scale, cz + 0.03 * scale), (cx + 0.2 * s * scale, cy + 0.2 * scale, cz + 0.02 * scale), 0.018 * scale, mat=paint(INK, 0, 0.2), seg=6, rings=4))
    pts = []
    for i in range(7):
        t = -1 + 2 * i / 6
        pts.append((cx + t * 0.1 * scale, cy - 0.12 * scale + (0.025 if angry else -0.03) * scale * (1 - t * t), cz + 0.02 * scale))
    parts.append(tube(pts, 0.016 * scale, mat=paint(INK, 0, 0.2), seg=6, res=3))
    f = join(parts, "face")
    transform(f, loc=(-cx, -cy, -cz))
    transform(f, loc=(cx, cy, cz), rot=(0, normal_rot_y, 0))
    return f


# ----------------------------------------------------------------------------- modular parts
def post():
    """Inflatable post: height 1 (y 0..1), radius 0.2. Scale Y for height."""
    body = cyl(0.2, 1.0, (0, 0.5, 0), mat=A(0.55), seg=20, caps=False)
    rings = [torus(0.205, 0.035, (0, y, 0), mat=Bc(0.5), seg=20, side=8) for y in (0.08, 0.92)]
    return join([body] + rings)


def post_cap():
    """Ball cap for post tops, radius 0.26 centred at origin."""
    return sphere(0.26, (0, 0, 0), mat=Bc(0.6), seg=18, rings=12)


def beam():
    """Horizontal tube along X from -0.5 to 0.5, radius 0.13."""
    return cyl(0.13, 1.0, (0, 0, 0), rot=(0, 0, math.pi / 2), mat=A(0.55), seg=16, caps=True)


def rope():
    """Rope hanging down: y from 0 to -1, radius 0.035, twisted look."""
    r = cyl(0.035, 1.0, (0, -0.5, 0), mat=paint(0xE8D3A6, 0, 0.1), seg=8)
    return r


def chain_link():
    return torus(0.05, 0.015, (0, 0, 0), rot=(math.pi / 2, 0, 0), mat=paint(0xB9C3CF, 0, 0.7), seg=12, side=6)


def coin():
    """Gold coin, radius 0.3, facing +Z, thickness 0.07."""
    body = cyl(0.3, 0.07, (0, 0, 0), rot=(math.pi / 2, 0, 0), mat=paint(0xFFC83A, 0, 0.75), seg=28, bevel=0.02)
    rim = torus(0.25, 0.022, (0, 0, 0.0), rot=(math.pi / 2, 0, 0), mat=paint(0xFFE07A, 0, 0.8), seg=28, side=6)
    pts = []
    for k in range(10):
        rr = 0.15 if k % 2 == 0 else 0.065
        b = math.pi * k / 5
        pts.append((math.sin(b) * rr, math.cos(b) * rr))
    star = extrude_shape(pts, 0.1, mat=paint(0xFFE58A, 0, 0.8), bevel=0.008)
    return join([body, rim, star])


def gem():
    g = ico(0.25, (0, 0, 0), scale=(1, 1.25, 1), mat=paint(0xFF5FA8, 0, 0.9, 0.15), sub=1, smooth=False)
    return g


def flag_pole():
    """Checkpoint pole, height 2.6, with ball top."""
    pole = cyl(0.05, 2.6, (0, 1.3, 0), mat=paint(0xF4F6FA, 0, 0.5), seg=12)
    top = sphere(0.1, (0, 2.62, 0), mat=paint(0xFFD23F, 0, 0.7), seg=12, rings=8)
    base = cyl(0.22, 0.12, (0, 0.06, 0), mat=paint(0xF4F6FA, 0, 0.3), seg=16, bevel=0.03)
    return join([pole, top, base])


def flag_cloth():
    """Flag cloth attached at x=0, extends +X 0.9, height 0.6, top at y=2.5. Sways (wobble weight)."""
    from dlib import bpy
    import bmesh
    bm = bmesh.new()
    nx, ny = 6, 3
    verts = []
    for j in range(ny + 1):
        row = []
        for i in range(nx + 1):
            x = 0.9 * i / nx
            y = 2.5 - 0.6 * j / ny + 0.04 * math.sin(i * 1.3)
            row.append(bm.verts.new(B(x, y, 0.02 * math.sin(i * 1.7))))
        verts.append(row)
    for j in range(ny):
        for i in range(nx):
            bm.faces.new((verts[j][i], verts[j + 1][i], verts[j + 1][i + 1], verts[j][i + 1]))
    me = bpy.data.meshes.new("flag")
    bm.to_mesh(me)
    bm.free()
    o = bpy.data.objects.new("flag", me)
    bpy.context.collection.objects.link(o)
    sol = o.modifiers.new("s", 'SOLIDIFY'); sol.thickness = 0.03
    apply_mods(o)
    set_paint(o, paint(WHITE, TINT_A, 0.2, 0.0, 1.0))
    shade_smooth(o)
    # wobble weight grows away from the pole: encode via separate paints in bands
    paint_where(o, paint(WHITE, TINT_A, 0.2, 0.0, 0.35), lambda x, y, z, nx_, ny_, nz_: x < 0.3)
    paint_where(o, paint(WHITE, TINT_A, 0.2, 0.0, 0.7), lambda x, y, z, nx_, ny_, nz_: 0.3 <= x < 0.6)
    return o


def arch(checker):
    """Inflatable arch spanning X -2.6..2.6, height 4.2. Scale X to track width."""
    parts = []
    for s in (1, -1):
        parts.append(capsule((2.6 * s, 0.0, 0), (2.6 * s, 3.4, 0), 0.42, mat=A(0.55)))
        for k in range(4):
            parts.append(torus(0.43, 0.06, (2.6 * s, 0.6 + k * 0.8, 0), mat=Bc(0.5), seg=20, side=8))
    pts = [(-2.6, 3.4, 0), (-1.8, 4.3, 0), (0, 4.6, 0), (1.8, 4.3, 0), (2.6, 3.4, 0)]
    parts.append(tube(pts, 0.42, mat=A(0.55), seg=16, res=8))
    # banner
    if checker:
        n = 10
        for i in range(n):
            for j in range(2):
                x0 = -1.9 + 3.8 * i / n
                col = 0x1B2340 if (i + j) % 2 == 0 else 0xFFFFFF
                parts.append(rbox(3.8 / n, 0.38, 0.12, (x0 + 3.8 / n / 2, 3.35 + j * 0.38, 0.35), mat=paint(col, 0, 0.3), r=0.0, smooth=False))
    else:
        parts.append(rbox(3.6, 0.7, 0.14, (0, 3.5, 0.35), mat=Bc(0.4), r=0.08))
        for i in range(5):
            parts.append(sphere(0.12, (-1.4 + i * 0.7, 3.5, 0.45), scale=(1, 1, 0.4), mat=paint(0xFFFFFF, 0, 0.4)))
    # balloons on top
    for s in (1, -1):
        for k, (dx, dy) in enumerate([(0, 0.6), (0.35, 0.35), (-0.3, 0.4)]):
            parts.append(sphere(0.32, (2.6 * s + dx, 3.6 + dy + 0.4, 0.0), scale=(1, 1.15, 1), mat=paint([0xFF6B57, 0xFFD23F, 0x2E9BFF][k], 0, 0.7)))
    return join(parts)


def start_arch():
    return arch(False)


def finish_arch():
    return arch(True)


def podium():
    """Three steps: 1st centre (h 1.2), 2nd left (+X, h 0.85), 3rd right (h 0.6). Width 1.6 each."""
    parts = []
    for (x, h, col, glow) in [(0, 1.2, 0xFFD23F, 0.0), (1.65, 0.85, 0xC9D6E6, 0.0), (-1.65, 0.6, 0xE09A5A, 0.0)]:
        # body stops inside the white top plate so the two tops never share a plane (z-fighting)
        hb = h - 0.07
        parts.append(rbox(1.6, hb, 1.6, (x, hb / 2, 0), mat=paint(col, 0, 0.6), r=0.12))
        parts.append(rbox(1.66, 0.12, 1.66, (x, h - 0.06, 0), mat=paint(0xFFFFFF, 0, 0.4), r=0.05))
        # star emblem on the front
        pts = []
        for k in range(10):
            rr = 0.3 if k % 2 == 0 else 0.13
            b = math.pi * k / 5
            pts.append((math.sin(b) * rr, math.cos(b) * rr))
        st = extrude_shape(pts, 0.06, mat=paint(0xFFFFFF, 0, 0.6, 0.2), bevel=0.01)
        transform(st, loc=(x, h * 0.48, 0.81))
        parts.append(st)
    return join(parts)


def balloon():
    b = sphere(0.35, (0, 0.45, 0), scale=(1, 1.18, 1), mat=A(0.8))
    knot = cyl(0.05, 0.08, (0, 0.04, 0), mat=A(0.5), r2=0.0, rot=(math.pi, 0, 0))
    string = cyl(0.008, 1.0, (0, -0.5, 0), mat=paint(0xFFFFFF, 0, 0.1), seg=4)
    return join([b, knot, string])


def bunting():
    """Flag string along X (-0.5..0.5) with 4 pennants alternating tints."""
    parts = [cyl(0.012, 1.0, (0, 0, 0), rot=(0, 0, math.pi / 2), mat=paint(0xFFFFFF, 0, 0.1), seg=4)]
    for i in range(4):
        x = -0.375 + i * 0.25
        tri = extrude_shape([(-0.1, 0), (0.1, 0), (0, -0.24)], 0.015, mat=paint(WHITE, TINT_A if i % 2 == 0 else TINT_B, 0.2, 0, 0.6))
        transform(tri, loc=(x, 0, 0))
        parts.append(tri)
    return join(parts)


def buoy():
    body = sphere(0.45, (0, 0.2, 0), scale=(1, 0.9, 1), mat=paint(0xFF4F4F, 0, 0.6))
    band = torus(0.43, 0.08, (0, 0.2, 0), mat=paint(0xFFFFFF, 0, 0.5))
    mast = cyl(0.04, 0.8, (0, 0.8, 0), mat=paint(0xFFFFFF, 0, 0.4))
    light = sphere(0.09, (0, 1.22, 0), mat=paint(0xFFE36B, 0, 0.5, 0.8))
    return join([body, band, mast, light])


def jump_pad():
    """Inflatable bounce pad, radius 1.1, top at y 0.35, arrow ring."""
    base = lathe([(1.1, 0.0), (1.15, 0.12), (1.1, 0.3), (0.95, 0.36), (0.0, 0.36)], mat=A(0.6), seg=32)
    ring = torus(0.85, 0.06, (0, 0.37, 0), mat=Bc(0.5), seg=32, side=8)
    arrows = []
    for i in range(3):
        a = extrude_shape([(-0.25, -0.2), (0, 0.15), (0.25, -0.2), (0.12, -0.2), (0, -0.05), (-0.12, -0.2)], 0.04, mat=paint(0xFFFFFF, 0, 0.3))
        transform(a, rot=(-math.pi / 2, 0, 0))
        transform(a, loc=(0, 0.38, -0.35 + i * 0.32))
        arrows.append(a)
    return join([base, ring] + arrows)


def trampoline():
    rim = torus(1.0, 0.18, (0, 0.5, 0), mat=A(0.6), seg=32, side=12)
    mat_ = cyl(0.95, 0.06, (0, 0.47, 0), mat=paint(0x2B2B44, 0, 0.3), seg=32)
    legs = [cyl(0.07, 0.5, (math.cos(a) * 0.9, 0.25, math.sin(a) * 0.9), mat=Bc(0.4), seg=8) for a in (0.5, 2.1, 3.7, 5.3)]
    return join([rim, mat_] + legs)


def climb_wall():
    """Wall 4 wide (X -2..2), 1 tall (y 0..1) face toward +Z; holds added separately (climb_hold)."""
    w = rbox(4.0, 1.0, 0.4, (0, 0.5, -0.2), mat=A(0.35), r=0.06)
    return w


def climb_hold():
    return ico(0.12, (0, 0, 0.04), scale=(1, 0.8, 0.6), mat=Bc(0.5), sub=1, smooth=True)


def coconut():
    c = sphere(0.24, (0, 0, 0), scale=(1, 1.1, 1), mat=paint(0x7A4A2A, 0, 0.2), seg=14, rings=10)
    displace_noise(c, 0.015, 8.0, 3)
    dots = [sphere(0.035, (math.cos(a) * 0.06, 0.25, math.sin(a) * 0.06), mat=paint(0x3A2414, 0, 0.1), seg=6, rings=4) for a in (0, 2.1, 4.2)]
    return join([c] + dots)


def zip_handle():
    trolley = rbox(0.4, 0.22, 0.3, (0, 0.0, 0), mat=paint(0xF4F6FA, 0, 0.7), r=0.06)
    wheels = [cyl(0.09, 0.05, (0, 0.1, z), rot=(0, 0, math.pi / 2), mat=paint(0x2B2B44, 0, 0.3), seg=12) for z in (-0.1, 0.1)]
    straps = [cyl(0.02, 0.7, (0.12 * s, -0.42, 0), mat=paint(0xFFD23F, 0, 0.2), seg=6) for s in (1, -1)]
    bar = cyl(0.04, 0.5, (0, -0.78, 0), rot=(0, 0, math.pi / 2), mat=paint(0xFF6B57, 0, 0.5), seg=10)
    return join([trolley, bar] + wheels + straps)


def swing_bar():
    bar = cyl(0.05, 0.7, (0, 0, 0), rot=(0, 0, math.pi / 2), mat=paint(0xFF6B57, 0, 0.5), seg=10)
    grips = [cyl(0.06, 0.16, (0.25 * s, 0, 0), rot=(0, 0, math.pi / 2), mat=paint(0x2B2B44, 0, 0.2), seg=10) for s in (1, -1)]
    return join([bar] + grips)


def raft():
    """Inflatable raft: 2.6 (X) x 2.6 (Z), deck top at y 0.0, tube rim."""
    deck = rbox(2.3, 0.2, 2.3, (0, -0.1, 0), mat=Bc(0.4), r=0.08)
    pts = [(-1.15, 0.0, -1.15), (1.15, 0.0, -1.15), (1.15, 0.0, 1.15), (-1.15, 0.0, 1.15)]
    rim = []
    for i in range(4):
        a = pts[i]; b = pts[(i + 1) % 4]
        rim.append(capsule(a, b, 0.2, mat=A(0.65)))
    for p in pts:
        rim.append(sphere(0.2, p, mat=A(0.65)))
    handles = [torus(0.12, 0.025, (0, 0.18, 1.15 * s), rot=(math.pi / 2, 0, 0), mat=paint(0xFFFFFF, 0, 0.3), seg=12, side=6) for s in (1, -1)]
    o = join([deck] + rim + handles)
    for p in o.data.polygons:
        pass
    return o


def monkey_rung():
    """Rung along X -0.5..0.5 (scale to bar width)."""
    return cyl(0.05, 1.0, (0, 0, 0), rot=(0, 0, math.pi / 2), mat=paint(0xF4F6FA, 0, 0.7), seg=10)


def pillar_top():
    """Foam ring where a pillar meets the water (purely visual)."""
    return torus(1.0, 0.12, (0, 0, 0), mat=paint(0xFFFFFF, 0, 0.0, 0.25), seg=24, side=6)


def life_ring():
    r = torus(0.35, 0.12, (0, 0, 0), rot=(math.pi / 2, 0, 0), mat=paint(0xFFFFFF, 0, 0.5), seg=24, side=10)
    from dlib import paint_where
    paint_where(r, paint(0xFF4F4F, 0, 0.5), lambda x, y, z, nx, ny, nz: (math.atan2(y, x) % (math.pi / 2)) < math.pi / 4)
    return r


def rail_post():
    return cyl(0.09, 0.9, (0, 0.45, 0), mat=paint(0xFFFFFF, 0, 0.6), seg=10)


MODELS = {
    "post": post, "post_cap": post_cap, "beam": beam, "rope": rope, "chain_link": chain_link,
    "coin": coin, "gem": gem, "flag_pole": flag_pole, "flag_cloth": flag_cloth,
    "start_arch": start_arch, "finish_arch": finish_arch, "podium": podium,
    "balloon": balloon, "bunting": bunting, "buoy": buoy, "jump_pad": jump_pad, "trampoline": trampoline,
    "climb_wall": climb_wall, "climb_hold": climb_hold, "coconut": coconut, "zip_handle": zip_handle,
    "swing_bar": swing_bar, "raft": raft, "monkey_rung": monkey_rung, "pillar_top": pillar_top,
    "life_ring": life_ring, "rail_post": rail_post,
}


def build_all(outdir, only=None):
    stats = {}
    for name, fn in MODELS.items():
        if only is None or name in only:
            try:
                stats[name] = build_and_export(name, fn, outdir, ao_dist=0.6, ao_samples=16)
            except Exception as e:
                print("FAILED", name, e)
                import traceback; traceback.print_exc()
    return stats
