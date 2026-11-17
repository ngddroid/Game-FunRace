"""Scenery for the 8 worlds. Vegetation carries a wobble weight (sway) growing with height."""
import math
import random
from mathutils import Vector, noise
from dlib import *
from m_course import A, Bc, INK


def sway_paint(color, gloss=0.1, w=0.6, tint=0):
    return paint(color, tint, gloss, 0.0, w)


# ----------------------------------------------------------------------------- lagoon
def palm(height=6.0, lean=0.35, seed=1, fronds=7):
    rnd = random.Random(seed)
    pts = []
    for i in range(8):
        t = i / 7
        pts.append((math.sin(t * 1.2) * lean * height * 0.35, t * height, 0.0))
    trunk_parts = []
    for i in range(len(pts) - 1):
        a, b = pts[i], pts[i + 1]
        r0 = 0.24 - 0.1 * (i / 7)
        trunk_parts.append(capsule(a, b, r0, mat=sway_paint(0xB98A5A if i % 2 == 0 else 0xA97A4C, 0.05, 0.12 * i / 7), seg=10, rings=6, r2=r0 * 0.92))
    top = pts[-1]
    leaves = []
    for k in range(fronds):
        a = 2 * math.pi * k / fronds + rnd.random() * 0.3
        L = 2.2 + rnd.random() * 0.6
        seg_pts = []
        for j in range(6):
            t = j / 5
            r = t * L
            y = top[1] + 0.25 + math.sin(t * math.pi * 0.9) * 0.5 - t * t * 1.1
            seg_pts.append((top[0] + math.cos(a) * r, y, top[2] + math.sin(a) * r))
        leaf = tube(seg_pts, 0.28, mat=sway_paint(0x3FBF57 if k % 2 == 0 else 0x2FA24A, 0.15, 1.0), seg=6, res=3, taper=[0.3, 1.0, 1.0, 0.85, 0.5, 0.1])
        transform(leaf, loc=(-top[0], -top[1], -top[2]))
        # flatten the leaf
        for v in leaf.data.vertices:
            pass
        transform(leaf, loc=(top[0], top[1], top[2]))
        leaves.append(leaf)
    nuts = [sphere(0.16, (top[0] + math.cos(a) * 0.2, top[1] - 0.05, top[2] + math.sin(a) * 0.2), mat=sway_paint(0x6B4A2A, 0.2, 0.5), seg=10, rings=8) for a in (0.3, 2.4, 4.4)]
    return join(trunk_parts + leaves + nuts)


def palm_small():
    return palm(3.6, 0.5, 4, 6)


def island(seed, radius=9.0, height=1.6, grass=True, sand=0xF2DDA4, grass_c=0x6CCB5A, rock=False):
    """Mound island with a sandy beach skirt (extends below water level y=0)."""
    rnd = random.Random(seed)
    prof = [(radius * 1.15, -1.5), (radius * 1.05, -0.2), (radius, 0.12), (radius * 0.86, 0.35), (radius * 0.7, height * 0.7), (radius * 0.45, height), (0.0, height * 1.05)]
    o = lathe(prof, mat=paint(sand, 0, 0.05), seg=40)
    off = Vector((rnd.random() * 50, rnd.random() * 50, 0))
    for v in o.data.vertices:
        p = v.co
        n = noise.noise(Vector((p.x * 0.18, p.y * 0.18, 0)) + off)
        r = Vector((p.x, p.y, 0))
        if r.length > 0.01:
            scale = 1 + n * 0.22
            v.co.x *= scale
            v.co.y *= scale
        if p.z > 0.3:
            v.co.z += noise.noise(Vector((p.x * 0.4, p.y * 0.4, 3)) + off) * 0.35
    shade_smooth(o)
    if grass:
        paint_where(o, paint(grass_c, 0, 0.05), lambda x, y, z, nx, ny, nz: y > height * 0.55)
    if rock:
        paint_where(o, paint(0x9C9890, 0, 0.05), lambda x, y, z, nx, ny, nz: y > height * 0.55)
    return o


def island_a():
    return join([island(3, 9.0, 1.8), palm(6.2, 0.4, 2), palm(5.0, 0.5, 5)][0:1] + [move(palm(6.2, 0.4, 2), 1.5, 1.5, 0.5), move(palm(5.0, 0.5, 5), -2.5, 1.3, -1.0), move(rock_b(), 3.5, 0.5, 2.5)])


def island_b():
    return join([island(11, 6.0, 1.2), move(palm(5.0, 0.6, 8), 0.5, 1.1, 0), move(umbrella(), -2.0, 0.6, 1.5)])


def island_c():
    return join([island(21, 14.0, 4.5, rock=True), move(palm(6.5, 0.3, 9), 4, 2.5, 3), move(palm(5.5, 0.4, 12), -5, 2.0, 2.5), move(lighthouse(), 0, 4.2, -2)])


def rock_a():
    r = ico(1.0, (0, 0.3, 0), scale=(1.3, 0.9, 1.0), mat=paint(0x9C9890, 0, 0.08), sub=2)
    displace_noise(r, 0.22, 1.2, 4)
    flat(r)
    return r


def rock_b():
    r = ico(0.7, (0, 0.2, 0), scale=(1.0, 0.7, 1.2), mat=paint(0xA8A398, 0, 0.08), sub=2)
    displace_noise(r, 0.15, 1.6, 6)
    flat(r)
    return r


def umbrella():
    pole = cyl(0.04, 2.3, (0, 1.15, 0), mat=paint(0xFFFFFF, 0, 0.3), seg=8)
    canopy = cyl(1.2, 0.5, (0, 2.25, 0), mat=paint(WHITE, TINT_A, 0.3), r2=0.05, seg=16, caps=False)
    paint_where(canopy, paint(0xFFFFFF, 0, 0.3), lambda x, y, z, nx, ny, nz: (int((math.atan2(z, x) + math.pi) / (math.pi / 4)) % 2) == 0)
    flat(canopy)
    towel = rbox(0.9, 0.03, 1.8, (0.9, 0.02, 0.4), mat=paint(WHITE, TINT_B, 0.1), r=0.01)
    return join([pole, canopy, towel])


def lifeguard_tower():
    legs = [capsule((0.6 * sx, 0, 0.6 * sz), (0.45 * sx, 2.4, 0.45 * sz), 0.07, mat=paint(0xFFFFFF, 0, 0.3)) for sx in (1, -1) for sz in (1, -1)]
    deck = rbox(1.4, 0.15, 1.4, (0, 2.45, 0), mat=paint(0xE8433A, 0, 0.3), r=0.04)
    hut = rbox(1.2, 1.0, 1.2, (0, 3.05, 0), mat=paint(0xFFFFFF, 0, 0.3), r=0.06)
    roof = cyl(1.0, 0.5, (0, 3.8, 0), mat=paint(0xE8433A, 0, 0.3), r2=0.1, seg=4, rot=(0, math.pi / 4, 0), smooth=False)
    flag = rbox(0.5, 0.3, 0.03, (0.3, 4.4, 0), mat=paint(0xFFD23F, 0, 0.2, 0, 0.6), r=0.01)
    pole = cyl(0.025, 0.6, (0.05, 4.2, 0), mat=paint(0xFFFFFF, 0, 0.3), seg=6)
    ring = torus(0.3, 0.09, (0, 3.05, 0.62), rot=(math.pi / 2, 0, 0), mat=paint(0xFF4F4F, 0, 0.5), seg=16, side=8)
    return join(legs + [deck, hut, roof, flag, pole, ring])


def sailboat():
    hull = lathe([(0.0, -0.3), (0.9, -0.2), (1.1, 0.3), (1.05, 0.4), (0.0, 0.4)], mat=paint(0xFFFFFF, 0, 0.4), seg=20)
    transform(hull, scale=(0.45, 1, 1.4))
    stripe = torus(1.05, 0.05, (0, 0.28, 0), mat=paint(0x2E9BFF, 0, 0.4), seg=20, side=6)
    transform(stripe, scale=(0.45, 1, 1.4))
    mast = cyl(0.05, 3.2, (0, 2.0, -0.1), mat=paint(0xE6EEF5, 0, 0.3), seg=8)
    sail = extrude_shape([(0, 0), (0, 2.8), (1.4, 0.1)], 0.03, mat=paint(0xFFFFFF, 0, 0.2, 0, 0.3))
    transform(sail, rot=(0, -math.pi / 2, 0), loc=(0.0, 0.6, 0.0))
    sail2 = extrude_shape([(0, 0), (0, 2.2), (-0.9, 0.1)], 0.03, mat=paint(0xFF6B57, 0, 0.2, 0, 0.3))
    transform(sail2, rot=(0, -math.pi / 2, 0), loc=(0.0, 0.6, -0.2))
    return join([hull, stripe, mast, sail, sail2])


def lighthouse():
    body = lathe([(1.2, 0), (1.1, 1.0), (0.8, 6.0), (0.85, 6.2)], mat=paint(0xFFFFFF, 0, 0.3), seg=24)
    bands = [cyl(1.17 - 0.06 * k * 1.5, 0.7, (0, 0.9 + k * 1.6, 0), mat=paint(0xE8433A, 0, 0.3), r2=1.17 - 0.06 * (k * 1.5 + 0.7), seg=24, caps=False) for k in range(3)]
    gallery = cyl(1.05, 0.15, (0, 6.25, 0), mat=paint(0x2B3A66, 0, 0.3), seg=24)
    lamp = cyl(0.6, 0.9, (0, 6.8, 0), mat=paint(0xFFF2A6, 0, 0.5, 0.7), seg=16)
    roof = cyl(0.8, 0.7, (0, 7.6, 0), mat=paint(0xE8433A, 0, 0.3), r2=0.05, seg=16)
    return join([body, gallery, lamp, roof] + bands)


def flamingo_float():
    ring = torus(0.9, 0.35, (0, 0, 0), mat=paint(0xFF8FB8, 0, 0.75, 0, 0.3), seg=28, side=12)
    neck = tube([(0, 0.2, -0.9), (0, 1.0, -1.05), (0, 1.6, -0.85), (0, 1.75, -0.5)], 0.15, mat=paint(0xFF8FB8, 0, 0.75, 0, 0.3), seg=10, res=4)
    head = sphere(0.24, (0, 1.75, -0.45), mat=paint(0xFF8FB8, 0, 0.75))
    beak = cyl(0.1, 0.3, (0, 1.68, -0.18), rot=(math.pi / 2 + 0.4, 0, 0), mat=paint(0x2B2B33, 0, 0.5), r2=0.03, seg=10)
    eyes = [sphere(0.04, (0.18 * s, 1.82, -0.35), mat=paint(INK, 0, 0.6)) for s in (1, -1)]
    return join([ring, neck, head, beak] + eyes)


def donut_float():
    ring = torus(0.85, 0.36, (0, 0, 0), mat=paint(0xF2C48A, 0, 0.6, 0, 0.3), seg=28, side=12)
    icing = torus(0.85, 0.37, (0, 0.06, 0), mat=paint(0xFF6FAE, 0, 0.7, 0, 0.3), seg=28, side=12)
    import bmesh
    from dlib import bpy
    bm = bmesh.new(); bm.from_mesh(icing.data)
    bmesh.ops.delete(bm, geom=[v for v in bm.verts if v.co.z < 0.12], context='VERTS')
    bm.to_mesh(icing.data); bm.free()
    sprinkles = []
    rnd = random.Random(3)
    for i in range(16):
        a = rnd.random() * 2 * math.pi
        rr = 0.85 + (rnd.random() - 0.5) * 0.4
        sprinkles.append(capsule((math.cos(a) * rr, 0.42, math.sin(a) * rr), (math.cos(a) * rr + 0.06, 0.42, math.sin(a) * rr + 0.04), 0.025, mat=paint([0xFFFFFF, 0xFFD23F, 0x2E9BFF, 0x7BE36B][i % 4], 0, 0.5), seg=6, rings=4))
    return join([ring, icing] + sprinkles)


# ----------------------------------------------------------------------------- candy
def lollipop():
    stick = cyl(0.08, 3.0, (0, 1.5, 0), mat=paint(0xFFFFFF, 0, 0.5), seg=8)
    pts = []
    for i in range(80):
        t = i / 79
        a = t * 6 * math.pi
        r = 0.1 + t * 0.95
        pts.append((math.cos(a) * r, 3.6 + math.sin(a) * r, 0.0))
    disc = cyl(1.1, 0.3, (0, 3.6, 0), rot=(math.pi / 2, 0, 0), mat=paint(WHITE, TINT_A, 0.8), seg=32, bevel=0.1)
    swirl = tube(pts, 0.11, mat=paint(WHITE, TINT_B, 0.8), seg=6, res=1)
    move(swirl, dz=0.12)
    return join([stick, disc, swirl])


def candy_cane():
    pts = [(0, 0, 0), (0, 2.5, 0), (0, 3.2, 0), (0.25, 3.6, 0), (0.6, 3.65, 0), (0.85, 3.4, 0), (0.9, 3.0, 0)]
    c = tube(pts, 0.22, mat=paint(0xFFFFFF, 0, 0.8), seg=10, res=6)
    paint_where(c, paint(0xFF3D6B, 0, 0.8), lambda x, y, z, nx, ny, nz: (int((y + x) * 3.2 + math.atan2(z, x) * 0.5) % 2) == 0)
    return c


def gumdrop():
    g = lathe([(0.9, 0.0), (0.95, 0.3), (0.75, 0.9), (0.4, 1.2), (0.0, 1.25)], mat=paint(WHITE, TINT_A, 0.85, 0, 0.3), seg=24)
    return g


def candy_island():
    base = island(17, 8.0, 1.5, grass=False, sand=0xF7C8DC)
    frost = lathe([(5.7, 0.9), (5.2, 1.3), (3.6, 1.7), (0.0, 1.8)], mat=paint(0xFFF4FA, 0, 0.6), seg=32)
    sprinkles = []
    rnd = random.Random(8)
    for i in range(30):
        a = rnd.random() * 2 * math.pi
        rr = rnd.random() * 4.8
        sprinkles.append(rbox(0.28, 0.08, 0.08, (math.cos(a) * rr, 1.8 - rr * 0.08, math.sin(a) * rr), rot=(0, rnd.random() * 3, 0), mat=paint([0xFF5FA8, 0xFFD23F, 0x2E9BFF, 0x7BE36B][i % 4], 0, 0.5), r=0.03))
    return join([base, frost, move(lollipop(), 2, 1.7, 1), move(gumdrop(), -2.5, 1.7, -1)] + sprinkles)


# ----------------------------------------------------------------------------- jungle
def jungle_tree():
    trunk = capsule((0, 0, 0), (0.3, 5.0, 0.2), 0.35, mat=sway_paint(0x7A5A3A, 0.05, 0.1), r2=0.22)
    blobs = []
    rnd = random.Random(14)
    for i in range(6):
        a = i * 1.05
        blobs.append(ico(1.3 + rnd.random() * 0.4, (0.3 + math.cos(a) * 1.2, 5.3 + rnd.random() * 0.8, 0.2 + math.sin(a) * 1.2), scale=(1, 0.75, 1), mat=sway_paint(0x3E9A4A if i % 2 else 0x4FB45A, 0.1, 0.7), sub=2))
    blobs.append(ico(1.5, (0.3, 6.3, 0.2), scale=(1, 0.8, 1), mat=sway_paint(0x5CC066, 0.1, 0.9), sub=2))
    return join([trunk] + blobs)


def ruin_column():
    shaft = cyl(0.55, 4.0, (0, 2.0, 0), mat=paint(0xC9BDA6, 0, 0.05), seg=12, smooth=False)
    base = rbox(1.5, 0.4, 1.5, (0, 0.2, 0), mat=paint(0xB9AD96, 0, 0.05), r=0.05)
    cap = rbox(1.5, 0.4, 1.5, (0, 4.1, 0), mat=paint(0xB9AD96, 0, 0.05), r=0.05)
    moss = sphere(1.0, (0.4, 4.35, 0.2), scale=(0.7, 0.25, 0.6), mat=sway_paint(0x5E9A4A, 0.05, 0.2))
    vine = tube([(0.56, 4.0, 0.0), (0.6, 3.0, 0.2), (0.5, 2.0, 0.4), (0.55, 1.2, 0.1)], 0.07, mat=sway_paint(0x4F8F3F, 0.05, 0.3), seg=6, res=3)
    return join([shaft, base, cap, moss, vine])


def ruin_arch():
    l = rbox(1.2, 4.0, 1.2, (-2.0, 2.0, 0), mat=paint(0xC9BDA6, 0, 0.05), r=0.06)
    r = rbox(1.2, 4.0, 1.2, (2.0, 2.0, 0), mat=paint(0xC9BDA6, 0, 0.05), r=0.06)
    top = rbox(5.4, 1.0, 1.3, (0, 4.5, 0), mat=paint(0xB9AD96, 0, 0.05), r=0.06)
    return join([l, r, top])


def bush():
    return join([ico(0.9, (0, 0.5, 0), scale=(1.3, 0.8, 1.1), mat=sway_paint(0x4FB45A, 0.1, 0.4), sub=2), ico(0.6, (0.8, 0.4, 0.2), mat=sway_paint(0x3E9A4A, 0.1, 0.4), sub=2)])


# ----------------------------------------------------------------------------- pirate
def ship():
    hull = lathe([(0.0, -0.6), (1.5, -0.4), (1.9, 0.6), (2.0, 1.2), (0.0, 1.2)], mat=paint(0x8A5A32, 0, 0.15), seg=24)
    transform(hull, scale=(0.55, 1, 2.2))
    rail = torus(2.0, 0.08, (0, 1.2, 0), mat=paint(0xFFD23F, 0, 0.4), seg=24, side=6)
    transform(rail, scale=(0.55, 1, 2.2))
    masts = [cyl(0.1, 6.0, (0, 4.0, z), mat=paint(0x6B4A2A, 0, 0.1), seg=8) for z in (-1.4, 1.2)]
    sails = []
    for z, h in ((-1.4, 2.6), (1.2, 3.0)):
        s = rbox(2.6, h, 0.06, (0, 3.6 + h * 0.2, z), mat=paint(0xF4EEDC, 0, 0.1, 0, 0.3), r=0.02)
        sails.append(s)
    flag = rbox(0.8, 0.5, 0.03, (0.4, 7.2, 1.2), mat=paint(0x2B2B33, 0, 0.1, 0, 0.6), r=0.01)
    stripes = [rbox(1.1, 0.12, 4.4, (0, 0.5 + k * 0.35, 0), mat=paint(0xE8433A, 0, 0.3), r=0.02) for k in range(1)]
    return join([hull, rail, flag] + masts + sails)


def treasure_chest():
    base = rbox(1.2, 0.7, 0.8, (0, 0.35, 0), mat=paint(0x8A5A32, 0, 0.2), r=0.05)
    lid = cyl(0.4, 1.2, (0, 0.72, 0), rot=(0, 0, math.pi / 2), mat=paint(0x8A5A32, 0, 0.2), seg=16)
    bands = [rbox(0.12, 0.75, 0.84, (x, 0.38, 0), mat=paint(0xFFD23F, 0, 0.8), r=0.02) for x in (-0.45, 0.45)]
    coins = [sphere(0.12, (x, 1.0, z), scale=(1, 0.3, 1), mat=paint(0xFFC83A, 0, 0.8, 0.2)) for (x, z) in ((0, 0), (0.2, 0.1), (-0.2, -0.05), (0.1, -0.15))]
    return join([base, lid] + bands + coins)


def dock():
    planks = [rbox(4.0, 0.15, 0.55, (0, 0.6, -1.5 + k * 0.6), mat=paint(0xB07A4A if k % 2 else 0xA06E42, 0, 0.1), r=0.02) for k in range(6)]
    posts = [cyl(0.16, 2.6, (1.8 * sx, -0.3, 1.6 * sz), mat=paint(0x7A5032, 0, 0.1), seg=10) for sx in (1, -1) for sz in (1, -1)]
    return join(planks + posts)


# ----------------------------------------------------------------------------- frosty
def ice_floe():
    f = cyl(3.0, 0.8, (0, 0.0, 0), mat=paint(0xF2FAFF, 0, 0.5), seg=9, bevel=0.15)
    displace_noise(f, 0.25, 0.5, 2)
    return f


def pine_snow():
    trunk = cyl(0.2, 1.0, (0, 0.5, 0), mat=paint(0x7A5A3A, 0, 0.05), seg=8)
    tiers = []
    for k in range(3):
        tiers.append(cyl(1.4 - k * 0.35, 1.5, (0, 1.6 + k * 1.0, 0), mat=sway_paint(0x2F8F5A, 0.05, 0.2 + k * 0.2), r2=0.1, seg=10))
        tiers.append(cyl(1.25 - k * 0.35, 0.3, (0, 1.2 + k * 1.0, 0), mat=paint(0xFFFFFF, 0, 0.3, 0, 0.2), r2=0.9 - k * 0.3, seg=10))
    return join([trunk] + tiers)


def igloo():
    dome = sphere(1.8, (0, 0, 0), mat=paint(0xF4FAFF, 0, 0.4), seg=24, rings=12)
    import bmesh
    from dlib import bpy
    bm = bmesh.new(); bm.from_mesh(dome.data)
    bmesh.ops.delete(bm, geom=[v for v in bm.verts if v.co.z < -0.05], context='VERTS')
    bm.to_mesh(dome.data); bm.free()
    door = cyl(0.7, 1.0, (0, 0.5, 1.6), rot=(math.pi / 2, 0, 0), mat=paint(0xE6F2FF, 0, 0.4), seg=16)
    hole = cyl(0.45, 0.3, (0, 0.45, 2.05), rot=(math.pi / 2, 0, 0), mat=paint(0x3A5A7A, 0, 0.1), seg=16)
    return join([dome, door, hole])


def penguin_prop():
    body = sphere(0.45, (0, 0.55, 0), scale=(1, 1.3, 0.9), mat=paint(0x2C3550, 0, 0.4))
    belly = sphere(0.36, (0, 0.5, 0.12), scale=(1, 1.3, 0.8), mat=paint(0xFFFFFF, 0, 0.3))
    beak = cyl(0.07, 0.15, (0, 0.9, 0.42), rot=(math.pi / 2, 0, 0), mat=paint(0xFF9A2E, 0, 0.4), r2=0.0)
    eyes = [sphere(0.05, (0.13 * s, 1.0, 0.33), mat=paint(INK, 0, 0.6)) for s in (1, -1)]
    feet = [sphere(0.12, (0.15 * s, 0.03, 0.15), scale=(1, 0.4, 1.4), mat=paint(0xFF9A2E, 0, 0.3)) for s in (1, -1)]
    return join([body, belly, beak] + eyes + feet)


# ----------------------------------------------------------------------------- volcano
def volcano():
    v = lathe([(30, -2), (26, 2), (16, 14), (8, 26), (5.5, 28), (4, 27)], mat=paint(0x4A3F4A, 0, 0.05), seg=40)
    displace_noise(v, 1.2, 0.08, 11)
    lava = cyl(4.2, 0.5, (0, 27.2, 0), mat=paint(0xFF7A2E, 0, 0, 1.0), seg=24)
    rivers = []
    for k in range(4):
        a = k * 1.6 + 0.4
        pts = [(math.cos(a) * 5 * (1 + t * 3.5), 27 - t * 22, math.sin(a) * 5 * (1 + t * 3.5)) for t in (0, 0.25, 0.5, 0.75, 1.0)]
        rivers.append(tube(pts, 0.6, mat=paint(0xFF6A1E, 0, 0, 0.9), seg=6, res=3))
    return join([v, lava] + rivers)


def basalt():
    cols = []
    rnd = random.Random(5)
    for i in range(7):
        a = i * 0.9
        h = 2.0 + rnd.random() * 3.0
        cols.append(cyl(0.7, h, (math.cos(a) * 1.0 * (i > 0), h / 2 - 0.5, math.sin(a) * 1.0 * (i > 0)), mat=paint(0x3A3540, 0, 0.1), seg=6, smooth=False))
    glow = cyl(1.8, 0.2, (0, -0.4, 0), mat=paint(0xFF6A1E, 0, 0, 0.8), seg=12)
    return join(cols + [glow])


# ----------------------------------------------------------------------------- sky
def cloud_island():
    puffs = []
    rnd = random.Random(31)
    for i in range(9):
        a = i * 0.7
        rr = 3.0 + rnd.random() * 3
        puffs.append(ico(2.2 + rnd.random() * 1.2, (math.cos(a) * rr * 0.8, -1.0 + rnd.random(), math.sin(a) * rr * 0.6), mat=paint(0xFFFFFF, 0, 0.1), sub=2))
    top = island(41, 5.0, 1.0)
    move(top, dy=0.5)
    return join(puffs + [top, move(palm(4.0, 0.3, 17), 0.5, 1.4, 0.5)])


def hot_air_balloon():
    env = sphere(2.2, (0, 4.0, 0), scale=(1, 1.15, 1), mat=paint(WHITE, TINT_A, 0.4), seg=16, rings=12)
    paint_where(env, paint(WHITE, TINT_B, 0.4), lambda x, y, z, nx, ny, nz: (int((math.atan2(z, x) + math.pi) / (math.pi / 8)) % 2) == 0)
    basket = rbox(1.0, 0.8, 1.0, (0, 0.4, 0), mat=paint(0xB07A4A, 0, 0.1), r=0.08)
    ropes = [cyl(0.03, 2.0, (0.45 * sx, 1.6, 0.45 * sz), mat=paint(0xE8D3A6, 0, 0.1), seg=4) for sx in (1, -1) for sz in (1, -1)]
    return join([env, basket] + ropes)


def floating_rock():
    r = cyl(3.0, 4.0, (0, -2.0, 0), mat=paint(0x9C8F7A, 0, 0.05), r2=0.6, seg=10, rot=(math.pi, 0, 0))
    displace_noise(r, 0.4, 0.5, 3)
    top = cyl(3.1, 0.5, (0, 0.2, 0), mat=paint(0x6CCB5A, 0, 0.05), seg=12, bevel=0.15)
    return join([r, top, move(bush(), 0.8, 0.4, 0.5)])


# ----------------------------------------------------------------------------- neon
def ferris_wheel():
    ring = torus(6.0, 0.18, (0, 7.5, 0), rot=(math.pi / 2, 0, 0), mat=paint(0x19F0E6, 0, 0.3, 0.9), seg=48, side=8)
    ring2 = torus(4.5, 0.12, (0, 7.5, 0), rot=(math.pi / 2, 0, 0), mat=paint(0xFF3DD1, 0, 0.3, 0.9), seg=40, side=6)
    spokes = [cyl(0.08, 12.0, (0, 7.5, 0), rot=(0, 0, a), mat=paint(0xE6EEF5, 0, 0.5), seg=6) for a in (0, math.pi / 4, math.pi / 2, 3 * math.pi / 4)]
    legs = [capsule((3.0 * s, 0, 0), (0, 7.5, 0), 0.25, mat=paint(0x2F2F4A, 0, 0.4)) for s in (1, -1)]
    cabins = [rbox(0.9, 0.9, 0.9, (math.cos(a) * 6.0, 7.5 + math.sin(a) * 6.0 - 0.7, 0), mat=paint([0xFF3DD1, 0x19F0E6, 0xFFE33D][i % 3], 0, 0.4, 0.4), r=0.15) for i, a in enumerate([k * math.pi / 4 for k in range(8)])]
    return join([ring, ring2] + spokes + legs + cabins)


def neon_arch():
    pts = [(-3, 0, 0), (-3, 3, 0), (-2, 4.6, 0), (0, 5.2, 0), (2, 4.6, 0), (3, 3, 0), (3, 0, 0)]
    a = tube(pts, 0.15, mat=paint(0xFF3DD1, 0, 0.2, 1.0), seg=8, res=6)
    pts2 = [(x * 0.85, y * 0.85, z) for (x, y, z) in pts]
    b = tube(pts2, 0.1, mat=paint(0x19F0E6, 0, 0.2, 1.0), seg=8, res=6)
    return join([a, b])


def lamp_post():
    pole = cyl(0.08, 3.2, (0, 1.6, 0), mat=paint(0x2F2F4A, 0, 0.5), seg=8)
    arm = cyl(0.05, 0.6, (0.25, 3.15, 0), rot=(0, 0, math.pi / 2), mat=paint(0x2F2F4A, 0, 0.5), seg=6)
    lamp = sphere(0.22, (0.55, 3.0, 0), mat=paint(0xFFE9A6, 0, 0.4, 1.0), seg=12, rings=8)
    return join([pole, arm, lamp])


MODELS = {
    "palm": palm, "palm_small": palm_small, "island_a": island_a, "island_b": island_b, "island_c": island_c,
    "rock_a": rock_a, "rock_b": rock_b, "umbrella": umbrella, "lifeguard_tower": lifeguard_tower,
    "sailboat": sailboat, "lighthouse": lighthouse, "flamingo_float": flamingo_float, "donut_float": donut_float,
    "lollipop": lollipop, "candy_cane": candy_cane, "gumdrop": gumdrop, "candy_island": candy_island,
    "jungle_tree": jungle_tree, "ruin_column": ruin_column, "ruin_arch": ruin_arch, "bush": bush,
    "ship": ship, "treasure_chest": treasure_chest, "dock": dock,
    "ice_floe": ice_floe, "pine_snow": pine_snow, "igloo": igloo, "penguin_prop": penguin_prop,
    "volcano": volcano, "basalt": basalt,
    "cloud_island": cloud_island, "hot_air_balloon": hot_air_balloon, "floating_rock": floating_rock,
    "ferris_wheel": ferris_wheel, "neon_arch": neon_arch, "lamp_post": lamp_post,
}


def build_all(outdir, only=None):
    stats = {}
    for name, fn in MODELS.items():
        if only is None or name in only:
            try:
                stats[name] = build_and_export(name, fn, outdir, ao_dist=1.2, ao_samples=14)
            except Exception as e:
                print("FAILED", name, e)
                import traceback; traceback.print_exc()
    return stats
