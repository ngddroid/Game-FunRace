"""Hazard pieces. Moving parts are separate meshes with pivots at the origin (documented)."""
import math
from mathutils import Vector
from dlib import *
from m_course import face, A, Bc, INK


def pusher_pad():
    """Soft pad that slides along +X. Body centred at x=0.3 (depth 0.6 X), 1.0 along Z, 1.2 tall (y 0..1.2).
    The face looks along +X (toward the racers)."""
    body = pillow(0.6, 1.2, 1.0, (0.3, 0.62, 0), mat=paint(WHITE, TINT_A, 0.55, 0, 0.8), puff=0.42, sub=3)
    trim = pillow(0.5, 1.26, 1.08, (0.12, 0.62, 0), mat=paint(WHITE, TINT_B, 0.5, 0, 0.5), puff=0.5, sub=2)
    f = face((0.0, 0.7, 0.62), 0.0, 1.6, angry=True)
    transform(f, rot=(0, math.pi / 2, 0))
    return join([body, trim, f])


def piston():
    """Telescopic rod along +X from 0 to 1 (scale X)."""
    return cyl(0.08, 1.0, (0.5, 0, 0), rot=(0, 0, math.pi / 2), mat=paint(0xD8E0EA, 0, 0.8), seg=12)


def side_box():
    """Housing at the track edge: 0.9 (X) x 1.5 (Y) x 1.3 (Z); open side faces +X. Origin at bottom centre."""
    box = rbox(0.9, 1.5, 1.3, (-0.45, 0.75, 0), mat=paint(WHITE, TINT_B, 0.45), r=0.12)
    stripe = rbox(0.92, 0.2, 1.32, (-0.45, 1.2, 0), mat=paint(WHITE, TINT_A, 0.5), r=0.05)
    hole = cyl(0.25, 0.1, (0.0, 0.75, 0), rot=(0, 0, math.pi / 2), mat=paint(0x2B2B44, 0, 0.2), seg=16)
    bolts = [sphere(0.05, (0.01, 0.75 + 0.4 * math.sin(a), 0.4 * math.cos(a)), mat=paint(0xD8E0EA, 0, 0.8), seg=8, rings=6) for a in (0.8, 2.4, 3.9, 5.5)]
    return join([box, stripe, hole] + bolts)


def duck():
    """Rubber duck pendulum bob, ~1.4 m, centred at origin, looking +Z. Swings along X."""
    body = sphere(0.62, (0, -0.1, 0), scale=(0.95, 0.8, 1.15), mat=paint(WHITE, TINT_A, 0.75, 0, 0.6))
    head = sphere(0.4, (0, 0.55, 0.38), mat=paint(WHITE, TINT_A, 0.75, 0, 0.5))
    tail = cyl(0.25, 0.4, (0, 0.15, -0.68), rot=(-1.0, 0, 0), mat=paint(WHITE, TINT_A, 0.75, 0, 0.6), r2=0.02)
    beak = sphere(1.0, (0, 0.5, 0.78), scale=(0.2, 0.08, 0.17), mat=paint(WHITE, TINT_B, 0.6))
    beak2 = sphere(1.0, (0, 0.43, 0.74), scale=(0.15, 0.05, 0.12), mat=paint(WHITE, TINT_B, 0.6))
    eyes = []
    for s in (1, -1):
        eyes.append(sphere(0.09, (0.2 * s, 0.68, 0.62), mat=paint(0xFFFFFF, 0, 0.5)))
        eyes.append(sphere(0.055, (0.21 * s, 0.69, 0.69), mat=paint(INK, 0, 0.6)))
        eyes.append(sphere(1.0, (0.55 * s, -0.05, -0.05), scale=(0.12, 0.3, 0.42), rot=(0.3, 0, 0.3 * s), mat=paint(WHITE, TINT_A, 0.75, 0, 0.6)))
    hook = torus(0.1, 0.03, (0, 0.98, 0.3), rot=(0, math.pi / 2, math.pi / 2), mat=paint(0xD8E0EA, 0, 0.8), seg=12, side=6)
    return join([body, head, tail, beak, beak2, hook] + eyes)


def beachball():
    """Striped ball radius 0.7 at origin (6 gores: tintA, white, tintB...)."""
    b = sphere(0.7, (0, 0, 0), mat=paint(0xFFFFFF, 0, 0.7, 0, 0.5), seg=36, rings=20)
    cols = [paint(WHITE, TINT_A, 0.7, 0, 0.5), paint(0xFFFFFF, 0, 0.7, 0, 0.5), paint(WHITE, TINT_B, 0.7, 0, 0.5),
            paint(0x2E9BFF, 0, 0.7, 0, 0.5), paint(0xFFD23F, 0, 0.7, 0, 0.5), paint(0xFFFFFF, 0, 0.7, 0, 0.5)]
    for i, m in enumerate(cols):
        lo = i * math.pi / 3
        paint_where(b, m, lambda x, y, z, nx, ny, nz, lo=lo: lo <= (math.atan2(z, x) % (2 * math.pi)) < lo + math.pi / 3 and abs(y) < 0.62)
    caps = [cyl(0.16, 0.04, (0, 0.69 * s, 0), mat=paint(0xFFFFFF, 0, 0.6), seg=16) for s in (1, -1)]
    return join([b] + caps)


def anchor():
    shank = cyl(0.1, 1.2, (0, 0, 0), mat=A(0.7), seg=12)
    ring = torus(0.16, 0.05, (0, 0.72, 0), rot=(math.pi / 2, 0, 0), mat=A(0.7), seg=16, side=8)
    arms = tube([(-0.6, -0.2, 0), (-0.45, -0.55, 0), (0, -0.66, 0), (0.45, -0.55, 0), (0.6, -0.2, 0)], 0.09, mat=A(0.7), seg=10, res=6)
    flukes = [cyl(0.16, 0.3, (0.62 * s, -0.12, 0), mat=Bc(0.6), r2=0.0, seg=4) for s in (1, -1)]
    stock = cyl(0.07, 0.8, (0, 0.45, 0), rot=(0, 0, math.pi / 2), mat=Bc(0.6), seg=10)
    return join([shank, ring, arms, stock] + flukes)


def discoball():
    b = ico(0.6, (0, 0, 0), mat=paint(0xE6EEF8, 0, 1.0, 0.25), sub=3, smooth=False)
    cap = cyl(0.12, 0.2, (0, 0.65, 0), mat=paint(0xB9C3CF, 0, 0.8), seg=12)
    return join([b, cap])


def idol():
    head = rbox(1.0, 1.25, 0.9, (0, 0, 0), mat=paint(0x9C8F7A, 0, 0.05), r=0.18)
    displace_noise(head, 0.03, 3.0, 5)
    brow = rbox(1.05, 0.18, 0.2, (0, 0.25, 0.42), mat=paint(0x857860, 0, 0.05), r=0.06)
    eyes = [sphere(0.11, (0.22 * s, 0.1, 0.45), scale=(1, 0.6, 0.4), mat=paint(0x7BE36B, 0, 0.5, 0.6)) for s in (1, -1)]
    nose = rbox(0.2, 0.35, 0.2, (0, -0.1, 0.48), mat=paint(0x857860, 0, 0.05), r=0.06)
    mouth = rbox(0.55, 0.1, 0.1, (0, -0.38, 0.45), mat=paint(0x4A4030, 0, 0.05), r=0.03)
    leaves = [sphere(1.0, (0.3 * s, 0.7, 0), scale=(0.12, 0.35, 0.05), rot=(0, 0, -0.5 * s), mat=paint(0x4FB45A, 0, 0.2, 0, 0.6)) for s in (1, -1)]
    return join([head, brow, nose, mouth] + eyes + leaves)


def icecube():
    c = rbox(1.1, 1.1, 1.1, (0, 0, 0), mat=paint(0xCFF3FF, 0, 0.9, 0.1), r=0.15)
    inner = rbox(0.7, 0.7, 0.7, (0, 0, 0), mat=paint(0x9FE0FF, 0, 0.6), r=0.12)
    return join([c, inner])


def magma():
    r = ico(0.65, (0, 0, 0), mat=paint(0x3A2E33, 0, 0.1), sub=2)
    displace_noise(r, 0.08, 2.0, 9)
    cracks = []
    for k in range(6):
        a = k * 1.05
        cracks.append(sphere(1.0, (math.cos(a) * 0.55, math.sin(a * 1.7) * 0.4, math.sin(a) * 0.55), scale=(0.15, 0.04, 0.15), rot=(a, a * 0.5, 0), mat=paint(0xFF8A2E, 0, 0, 1.0)))
    return join([r] + cracks)


def candy():
    body = sphere(0.55, (0, 0, 0), scale=(1.0, 1.0, 1.0), mat=A(0.8), seg=24, rings=16)
    paint_where(body, Bc(0.8), lambda x, y, z, nx, ny, nz: (int((math.atan2(z, x) + y * 3) / 0.5) % 2) == 0)
    wraps = [cyl(0.32, 0.45, (0.72 * s, 0, 0), rot=(0, 0, -math.pi / 2 * s), mat=A(0.6), r2=0.08, seg=12) for s in (1, -1)]
    return join([body] + wraps)


def sweeper_hub():
    """Central hub, height 1.25, radius 0.45, origin at deck level. Arms attach at y 0.7."""
    base = cyl(0.55, 0.25, (0, 0.12, 0), mat=paint(WHITE, TINT_B, 0.5), seg=24, bevel=0.06)
    col = cyl(0.4, 1.0, (0, 0.72, 0), mat=paint(WHITE, TINT_A, 0.55), seg=24, bevel=0.05)
    cap = sphere(0.45, (0, 1.22, 0), scale=(1, 0.55, 1), mat=paint(WHITE, TINT_B, 0.6), seg=24, rings=12)
    band = torus(0.41, 0.05, (0, 0.7, 0), mat=paint(0xFFFFFF, 0, 0.4), seg=24, side=8)
    f1 = face((0, 0.95, 0.4), 0.0, 1.2, angry=False)
    f2 = face((0, 0.95, 0.4), math.pi, 1.2, angry=False)
    return join([base, col, cap, band, f1, f2])


def noodle():
    """Pool noodle arm along +X from 0 to 1 (scale X to length), radius 0.17, at y 0."""
    n = cyl(0.17, 1.0, (0.5, 0, 0), rot=(0, 0, math.pi / 2), mat=paint(WHITE, TINT_A, 0.35, 0, 0.6), seg=12, caps=True)
    rings = [torus(0.17, 0.04, (x, 0, 0), rot=(0, 0, math.pi / 2), mat=paint(WHITE, TINT_B, 0.4, 0, 0.6), seg=12, side=6) for x in (0.25, 0.5, 0.75)]
    return join([n] + rings)


def noodle_tip():
    """Soft ball at the noodle end, radius 0.24."""
    return sphere(0.24, (0, 0, 0), mat=paint(WHITE, TINT_B, 0.6, 0, 0.6), seg=16, rings=10)


def glove():
    """Boxing glove punching along +X, ~0.75 long, centred at x 0.35."""
    fist = sphere(0.36, (0.4, 0, 0), scale=(1.05, 0.95, 1.0), mat=paint(WHITE, TINT_A, 0.75, 0, 0.5))
    thumb = sphere(0.14, (0.42, 0.1, 0.3), scale=(1.2, 1, 0.9), mat=paint(WHITE, TINT_A, 0.75, 0, 0.5))
    cuff = cyl(0.27, 0.3, (0.05, 0, 0), rot=(0, 0, math.pi / 2), mat=paint(WHITE, TINT_B, 0.5), seg=20, bevel=0.06)
    lace = torus(0.2, 0.03, (0.0, 0, 0), rot=(0, 0, math.pi / 2), mat=paint(0xFFFFFF, 0, 0.3), seg=16, side=6)
    return join([fist, thumb, cuff, lace])


def spring():
    """Accordion spring along +X from 0 to 1."""
    pts = []
    for i in range(61):
        t = i / 60
        a = t * 2 * math.pi * 7
        pts.append((t, math.cos(a) * 0.14, math.sin(a) * 0.14))
    return tube(pts, 0.03, mat=paint(0xD8E0EA, 0, 0.8), seg=6, res=1)


def press_block():
    """Padded press: X -1..1 (scale to width), y 0..1.1, Z -0.8..0.8. Face on +Z and -Z."""
    body = pillow(2.0, 1.1, 1.6, (0, 0.55, 0), mat=paint(WHITE, TINT_A, 0.5, 0, 0.6), puff=0.3, sub=3)
    sole = rbox(2.04, 0.18, 1.64, (0, 0.09, 0), mat=paint(WHITE, TINT_B, 0.4), r=0.06)
    f1 = face((0, 0.65, 0.82), 0.0, 2.0, angry=True)
    f2 = face((0, 0.65, 0.82), math.pi, 2.0, angry=True)
    return join([body, sole, f1, f2])


def tile():
    """Trap tile 1 x 0.2 x 1 (hinge at x = -0.5), top at y 0."""
    t = rbox(1.0, 0.2, 1.0, (0, -0.1, 0), mat=paint(WHITE, TINT_A, 0.35), r=0.03)
    edge = rbox(0.86, 0.02, 0.86, (0, 0.005, 0), mat=paint(WHITE, TINT_B, 0.3), r=0.02)
    move(t, dx=0.5)
    move(edge, dx=0.5)
    return join([t, edge])


def paddle_wheel():
    """Wheel rotating about X: hub length 1 (X -0.5..0.5, scale), 3 soft paddles reaching radius 1.6."""
    hub = cyl(0.3, 1.0, (0, 0, 0), rot=(0, 0, math.pi / 2), mat=paint(WHITE, TINT_B, 0.5), seg=20)
    pads = []
    for i in range(3):
        a = i * 2 * math.pi / 3
        # radial paddle (long axis pointing away from the axle), matching the simulation's paddles
        p = pillow(0.92, 1.1, 0.22, (0, math.cos(a) * 1.0, math.sin(a) * 1.0), rot=(a, 0, 0), mat=paint(WHITE, TINT_A, 0.45, 0, 0.5), puff=0.3, sub=2)
        pads.append(p)
    return join([hub] + pads)


def axle_support():
    """A-frame support at the track edge, height 2.6 (pivot), origin at deck."""
    legs = [capsule((0, 0, 0.7 * s), (0, 2.6, 0), 0.12, mat=paint(WHITE, TINT_B, 0.5)) for s in (1, -1)]
    cap = sphere(0.22, (0, 2.6, 0), mat=paint(WHITE, TINT_A, 0.5))
    return join(legs + [cap])


def cannon():
    """Beach-ball cannon at origin (track edge), barrel along +X, mouth at x 1.0, y 0.9."""
    base = cyl(0.6, 0.35, (0, 0.17, 0), mat=paint(WHITE, TINT_B, 0.45), seg=24, bevel=0.08)
    barrel = lathe([(0.45, 0.0), (0.5, 0.2), (0.48, 0.9), (0.58, 1.05), (0.58, 1.15), (0.42, 1.15), (0.42, 0.3)], mat=paint(WHITE, TINT_A, 0.6), seg=24)
    transform(barrel, rot=(0, 0, -math.pi / 2 + 0.12))
    move(barrel, dx=-0.15, dy=0.9)
    rings = [torus(0.5, 0.05, (0.2 + k * 0.3, 0.9 + 0.035 * k, 0), rot=(0, 0, math.pi / 2), mat=paint(0xFFFFFF, 0, 0.5), seg=20, side=6) for k in range(2)]
    wheels = [cyl(0.38, 0.12, (-0.1, 0.4, 0.55 * s), rot=(math.pi / 2, 0, 0), mat=paint(0x2B2B44, 0, 0.3), seg=20, bevel=0.03) for s in (1, -1)]
    return join([base, barrel] + rings + wheels)


def fan():
    """Fan housing ring facing +X, centre at y 1.2; blades are separate (fan_blades)."""
    ring = torus(1.0, 0.16, (0, 1.2, 0), rot=(0, 0, math.pi / 2), mat=paint(WHITE, TINT_A, 0.55), seg=36, side=10)
    grill = [cyl(0.02, 2.0, (0.12, 1.2, 0), rot=(a, 0, 0), mat=paint(0xE6EEF5, 0, 0.6), seg=6) for a in (0, math.pi / 4, math.pi / 2, 3 * math.pi / 4)]
    stand = cyl(0.14, 0.9, (-0.1, 0.0, 0), mat=paint(WHITE, TINT_B, 0.5), seg=12)
    move(stand, dy=0.0)
    foot = cyl(0.55, 0.15, (-0.1, 0.07, 0), mat=paint(WHITE, TINT_B, 0.5), seg=20, bevel=0.05)
    motor = sphere(0.3, (-0.25, 1.2, 0), scale=(1.2, 1, 1), mat=paint(WHITE, TINT_B, 0.5))
    return join([ring, stand, foot, motor] + grill)


def fan_blades():
    """Blades rotating about X, centred at origin."""
    hub = sphere(0.18, (0, 0, 0), scale=(0.6, 1, 1), mat=paint(0xFFFFFF, 0, 0.6))
    blades = []
    for i in range(4):
        a = i * math.pi / 2
        blades.append(sphere(1.0, (0, math.cos(a) * 0.5, math.sin(a) * 0.5), scale=(0.04, 0.42, 0.22), rot=(-a, 0.35, 0), mat=paint(WHITE, TINT_B, 0.5)))
    return join([hub] + blades)


def bollard():
    """Pop-up post radius 0.32, height 1.0 (y 0..1), rounded top, stripes."""
    body = cyl(0.32, 0.85, (0, 0.425, 0), mat=paint(WHITE, TINT_A, 0.55, 0, 0.4), seg=20)
    top = sphere(0.32, (0, 0.85, 0), scale=(1, 0.55, 1), mat=paint(WHITE, TINT_A, 0.55, 0, 0.4), seg=20, rings=10)
    bands = [torus(0.325, 0.045, (0, y, 0), mat=paint(WHITE, TINT_B, 0.5, 0, 0.4), seg=20, side=6) for y in (0.3, 0.6)]
    return join([body, top] + bands)


def gate_door():
    """Sliding door panel: X 0..1 (scale), y 0..2.2, thickness 0.3; padded."""
    p = pillow(1.0, 2.2, 0.3, (0.5, 1.1, 0), mat=paint(WHITE, TINT_A, 0.45, 0, 0.4), puff=0.25, sub=2)
    stripe = rbox(1.02, 0.25, 0.32, (0.5, 1.5, 0), mat=paint(WHITE, TINT_B, 0.45), r=0.05)
    return join([p, stripe])


def wave_tank():
    """Side tank (origin bottom centre at track edge), opening toward +X."""
    box = rbox(1.0, 1.6, 3.2, (-0.5, 0.8, 0), mat=paint(WHITE, TINT_B, 0.45), r=0.15)
    win = rbox(0.06, 0.9, 2.6, (0.01, 0.95, 0), mat=paint(0x5FD0F0, 0, 0.9, 0.15), r=0.05)
    lip = rbox(1.1, 0.16, 3.3, (-0.5, 1.62, 0), mat=paint(WHITE, TINT_A, 0.5), r=0.06)
    return join([box, win, lip])


def wave():
    """Water wave crest travelling along +X: length 3.2 along Z, height 1.1, thickness 0.9."""
    import bmesh
    from dlib import bpy
    prof = [(-0.5, 0.0), (-0.45, 0.35), (-0.2, 0.8), (0.1, 1.05), (0.35, 1.0), (0.48, 0.7), (0.4, 0.45), (0.5, 0.0)]
    bm = bmesh.new()
    nz = 10
    rows = []
    for k in range(nz + 1):
        z = -1.6 + 3.2 * k / nz
        h = 1.0 - (abs(z) / 1.6) ** 4 * 0.6
        row = [bm.verts.new(B(x, y * h, z)) for (x, y) in prof]
        rows.append(row)
    for k in range(nz):
        for i in range(len(prof) - 1):
            bm.faces.new((rows[k][i], rows[k][i + 1], rows[k + 1][i + 1], rows[k + 1][i]))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    me = bpy.data.meshes.new("wave")
    bm.to_mesh(me)
    bm.free()
    o = bpy.data.objects.new("wave", me)
    bpy.context.collection.objects.link(o)
    set_paint(o, paint(0x3FD8E8, 0, 0.9, 0.1))
    paint_where(o, paint(0xF4FFFF, 0, 0.5, 0.2), lambda x, y, z, nx, ny, nz_: y > 0.75)
    shade_smooth(o)
    return o


def barrel():
    """Barrel lying along X (rolls about X): radius 0.48, length 1.1."""
    body = lathe([(0.0, -0.55), (0.42, -0.55), (0.48, -0.3), (0.5, 0.0), (0.48, 0.3), (0.42, 0.55), (0.0, 0.55)], mat=paint(WHITE, TINT_A, 0.4), seg=24)
    hoops = [torus(r, 0.03, (0, y, 0), mat=paint(WHITE, TINT_B, 0.6), seg=24, side=6) for (r, y) in ((0.46, -0.38), (0.5, 0.0), (0.46, 0.38))]
    b = join([body] + hoops)
    transform(b, rot=(0, 0, math.pi / 2))
    return b


def turntable():
    """Spinning disc radius 1 (scale XZ), top at y 0, thickness 0.3, striped ring."""
    d = cyl(1.0, 0.3, (0, -0.15, 0), mat=paint(WHITE, TINT_B, 0.4), seg=40, bevel=0.06)
    top = cyl(0.9, 0.02, (0, 0.005, 0), mat=paint(WHITE, TINT_A, 0.35), seg=40)
    wedges = []
    for i in range(4):
        a = i * math.pi / 2
        w = extrude_shape([(0, 0), (math.cos(a) * 0.85, math.sin(a) * 0.85), (math.cos(a + 0.6) * 0.85, math.sin(a + 0.6) * 0.85)], 0.02, mat=paint(0xFFFFFF, 0, 0.3))
        transform(w, rot=(math.pi / 2, 0, 0), loc=(0, 0.02, 0))
        wedges.append(w)
    return join([d, top] + wedges)


def tilt_deck():
    """Tilting platform: X -0.5..0.5 (scale width), y -0.35..0, Z -0.5..0.5 (scale length)."""
    d = rbox(1.0, 0.35, 1.0, (0, -0.175, 0), mat=paint(WHITE, TINT_A, 0.4), r=0.05)
    edge = rbox(1.01, 0.08, 1.01, (0, -0.3, 0), mat=paint(WHITE, TINT_B, 0.4), r=0.03)
    return join([d, edge])


def conveyor_roller():
    return cyl(0.18, 1.0, (0, 0, 0), rot=(0, 0, math.pi / 2), mat=paint(0xB9C3CF, 0, 0.7), seg=12)


def coconut_dropper():
    """Basket at the top of a climb wall that tips coconuts (origin at its pivot)."""
    b = lathe([(0.0, -0.25), (0.3, -0.25), (0.42, 0.1), (0.44, 0.15), (0.38, 0.15), (0.3, -0.18), (0.0, -0.18)], mat=paint(0xB07A4A, 0, 0.1), seg=20)
    return b


# ----------------------------------------------------------------------------- spin bar (edge-mounted sweeper)
def spin_post():
    """Column standing just off the deck edge; top at y 0 (the bar pivots at y 0.2 above it).
    Runs down to y -10 so it always reaches the sea."""
    col = cyl(0.3, 10.0, (0, -5.0, 0), mat=paint(WHITE, TINT_B, 0.45), seg=24, caps=False)
    bands = [torus(0.31, 0.05, (0, y, 0), mat=paint(0xFFFFFF, 0, 0.5), seg=24, side=8) for y in (-0.35, -1.1)]
    collar = cyl(0.42, 0.22, (0, -0.08, 0), mat=paint(WHITE, TINT_A, 0.55), seg=28, bevel=0.06)
    return join([col, collar] + bands)


def spin_hub():
    """Rotating head of the spin bar, pivot at origin (bar height). Carries a short counterweight
    arm on -X that tilts up out of reach. The long bar (spin_bar) attaches along +X."""
    hub = cyl(0.36, 0.5, (0, 0, 0), mat=paint(WHITE, TINT_A, 0.6), seg=28, bevel=0.08)
    dome = sphere(0.36, (0, 0.24, 0), scale=(1, 0.6, 1), mat=paint(0xFFFFFF, 0, 0.6), seg=24, rings=12)
    ring = torus(0.37, 0.05, (0, -0.12, 0), mat=paint(WHITE, TINT_B, 0.5), seg=28, side=8)
    arm = capsule((-0.2, 0.05, 0), (-1.05, 0.62, 0), 0.12, mat=paint(WHITE, TINT_B, 0.5))
    ball = sphere(0.34, (-1.15, 0.7, 0), mat=paint(WHITE, TINT_A, 0.7, 0, 0.5), seg=24, rings=14)
    band = torus(0.345, 0.045, (-1.15, 0.7, 0), rot=(0, 0, 0.6), mat=paint(0xFFFFFF, 0, 0.5), seg=24, side=8)
    f1 = face((0, 0.02, 0.37), 0.0, 1.0)
    f2 = face((0, 0.02, -0.37), math.pi, 1.0)
    return join([hub, dome, ring, arm, ball, band, f1, f2])


def spin_bar():
    """Long striped pool-noodle bar along +X from 0 to 1 (scale X to its length), radius 0.2."""
    bars = []
    n = 9
    for k in range(n):
        x0 = k / n
        m = paint(WHITE, TINT_A, 0.5, 0, 0.3) if k % 2 == 0 else paint(0xFFFFFF, 0, 0.45, 0, 0.3)
        bars.append(cyl(0.2, 1.0 / n, (x0 + 0.5 / n, 0, 0), rot=(0, 0, math.pi / 2), mat=m, seg=16, caps=False))
    return join(bars)


# ----------------------------------------------------------------------------- mega mallet
MALLET_ARM = 4.3


def mallet():
    """Giant inflatable mallet hanging from its pivot at the origin, swinging about Z (along the
    course). Head: a drum of radius 0.78 and length 2.0 with its axis along X (the swing direction),
    centred at y = -MALLET_ARM."""
    hy = -MALLET_ARM
    axle = cyl(0.16, 0.7, (0, 0, 0), rot=(math.pi / 2, 0, 0), mat=paint(0xD8E0EA, 0, 0.8), seg=16)
    knob = sphere(0.27, (0, 0, 0), mat=paint(WHITE, TINT_B, 0.6), seg=20, rings=12)
    handle = cyl(0.12, MALLET_ARM - 0.5, (0, -(MALLET_ARM - 0.5) / 2, 0), mat=paint(0xF4F6FA, 0, 0.5), seg=16, caps=False)
    grip = [torus(0.13, 0.035, (0, y, 0), mat=paint(WHITE, TINT_B, 0.5), seg=16, side=6) for y in (-0.6, -0.9, -1.2)]
    collar = cyl(0.22, 0.3, (0, hy + 0.88, 0), mat=paint(WHITE, TINT_B, 0.5), seg=20, bevel=0.05)
    drum = lathe([(0.0, -1.0), (0.62, -1.0), (0.75, -0.95), (0.78, -0.84), (0.74, -0.5), (0.71, -0.42), (0.71, 0.0),
                  (0.71, 0.42), (0.74, 0.5), (0.78, 0.84), (0.75, 0.95), (0.62, 1.0), (0.0, 1.0)],
                 mat=paint(WHITE, TINT_A, 0.65, 0, 0.35), seg=40)
    paint_where(drum, paint(0xFFFFFF, 0, 0.55, 0, 0.35), lambda x, y, z, nx, ny, nz: 0.45 < abs(y) < 0.7)
    paint_where(drum, paint(WHITE, TINT_B, 0.6, 0, 0.35), lambda x, y, z, nx, ny, nz: abs(y) > 0.9)
    transform(drum, rot=(0, 0, math.pi / 2), loc=(0, hy, 0))
    faces = [face((0, hy + 0.05, 0.72), 0.0, 1.9, angry=True), face((0, hy + 0.05, -0.72), math.pi, 1.9, angry=True)]
    return join([axle, knob, handle, collar, drum] + grip + faces)


# ----------------------------------------------------------------------------- roller squeeze
def roller():
    """Spinning foam roller along +X from 0 to 1 (scale X to length), radius 0.34, spiral stripes."""
    parts = []
    n = 18
    for k in range(n):
        x0 = k / n
        seg = cyl(0.34, 1.0 / n, (x0 + 0.5 / n, 0, 0), rot=(0, 0, math.pi / 2), mat=paint(WHITE, TINT_A, 0.5, 0, 0.4), seg=28, caps=False)
        # spiral stripes: colour each face by its angle around the axis plus a twist along X
        paint_where(seg, paint(0xFFFFFF, 0, 0.5, 0, 0.4),
                    lambda x, y, z, nx, ny, nz: ((math.atan2(z, y) / (2 * math.pi) + x * 1.6) % 0.5) < 0.2)
        parts.append(seg)
    caps = [sphere(0.34, (x, 0, 0), scale=(0.35, 1, 1), mat=paint(WHITE, TINT_B, 0.6), seg=28, rings=10) for x in (0.0, 1.0)]
    return join(parts + caps)

MODELS = {
    "pusher_pad": pusher_pad, "piston": piston, "side_box": side_box, "duck": duck, "beachball": beachball,
    "anchor": anchor, "discoball": discoball, "idol": idol, "icecube": icecube, "magma": magma, "candy": candy,
    "sweeper_hub": sweeper_hub, "noodle": noodle, "noodle_tip": noodle_tip, "glove": glove, "spring": spring,
    "press_block": press_block, "tile": tile, "paddle_wheel": paddle_wheel, "axle_support": axle_support,
    "cannon": cannon, "fan": fan, "fan_blades": fan_blades, "bollard": bollard, "gate_door": gate_door,
    "wave_tank": wave_tank, "wave": wave, "barrel": barrel, "turntable": turntable, "tilt_deck": tilt_deck,
    "conveyor_roller": conveyor_roller, "coconut_dropper": coconut_dropper,
    "spin_post": spin_post, "spin_hub": spin_hub, "spin_bar": spin_bar, "mallet": mallet, "roller": roller,
}


def build_all(outdir, only=None):
    stats = {}
    for name, fn in MODELS.items():
        if only is None or name in only:
            try:
                stats[name] = build_and_export(name, fn, outdir, ao_dist=0.5, ao_samples=16)
            except Exception as e:
                print("FAILED", name, e)
                import traceback; traceback.print_exc()
    return stats
