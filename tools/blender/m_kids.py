"""Kid racers: a skinned human-proportioned "human" body (same bones as the Dashling, longer), plus
hairstyles and a backpack. Hair (like hats and the face) is authored in Dashling head space and
fitted onto the human head by the renderer (see Bodies in CharacterRig.kt).

Tint slots on the body: A = shirt, B = pants, SKIN = head/forearms/hands, SHOE = shoes.
Tint slots on hair: A = hair colour, B = accent (hair ties, shaved sides).
"""
import bmesh
import bpy
import math
from mathutils import Vector
from dlib import *
from m_character import BONES, HEAD_C, HEAD_R, assign_group

SHIRT = lambda g=0.12: paint(WHITE, TINT_A, g)
PANTS = lambda g=0.1: paint(WHITE, TINT_B, g)
SKIN = lambda g=0.18: paint(WHITE, TINT_SKIN, g)
HAIR = lambda g=0.3: paint(WHITE, TINT_A, g)
ACCENT = lambda g=0.3: paint(WHITE, TINT_B, g)



# ----------------------------------------------------------------------------- body
# Human-proportioned racer (~1.5 m, about 4.5 heads tall). Same 13 bones as the Dashling with the
# same rest directions (so every procedural animation works), only longer. Head parts (hair, hats,
# face) are authored in Dashling head space and fitted onto this smaller head at runtime.
HUMAN_RIG = [
    ("HIPS", (0, 0.62, 0), (0, 0.74, 0), None),
    ("CHEST", (0, 0.74, 0), (0, 1.0, 0), "HIPS"),
    ("HEAD", (0, 1.02, 0), (0, 1.45, 0), "CHEST"),
    ("ARM_L", (0.16, 0.97, 0), (0.295, 0.796, 0.0), "CHEST"),
    ("HAND_L", (0.295, 0.796, 0.0), (0.399, 0.627, 0.026), "ARM_L"),
    ("ARM_R", (-0.16, 0.97, 0), (-0.295, 0.796, 0.0), "CHEST"),
    ("HAND_R", (-0.295, 0.796, 0.0), (-0.399, 0.627, 0.026), "ARM_R"),
    ("THIGH_L", (0.095, 0.61, 0), (0.1, 0.34, 0.0), "HIPS"),
    ("SHIN_L", (0.1, 0.34, 0.0), (0.1, 0.09, 0), "THIGH_L"),
    ("FOOT_L", (0.1, 0.09, 0), (0.1, 0.03, 0.14), "SHIN_L"),
    ("THIGH_R", (-0.095, 0.61, 0), (-0.1, 0.34, 0.0), "HIPS"),
    ("SHIN_R", (-0.1, 0.34, 0.0), (-0.1, 0.09, 0), "THIGH_R"),
    ("FOOT_R", (-0.1, 0.09, 0), (-0.1, 0.03, 0.14), "SHIN_R"),
]
H_HEAD_C = Vector((0.0, 1.235, 0.0))
H_HEAD_R = 0.2
WAIST_Y = 0.70       # shirt above, pants below
SLEEVE_T = 0.55      # short sleeves end 55 % down the upper arm


def make_human_rig():
    data = bpy.data.armatures.new("rig")
    arm = bpy.data.objects.new("rig", data)
    bpy.context.collection.objects.link(arm)
    active(arm)
    bpy.ops.object.mode_set(mode='EDIT')
    for name, h, t, parent in HUMAN_RIG:
        eb = data.edit_bones.new(name)
        eb.head = B(*h)
        eb.tail = B(*t)
        if parent:
            eb.parent = data.edit_bones[parent]
        eb.use_connect = False
    bpy.ops.object.mode_set(mode='OBJECT')
    return arm


def human_suit():
    parts = [
        sphere(1.0, (0, 0.89, 0), scale=(0.18, 0.15, 0.12)),      # chest
        sphere(1.0, (0, 0.77, 0), scale=(0.16, 0.11, 0.11)),      # waist
        sphere(1.0, (0, 0.66, 0), scale=(0.17, 0.09, 0.115)),     # hips
    ]
    for s in (1, -1):
        parts.append(sphere(0.072, (0.16 * s, 0.965, 0)))                                       # shoulder
        parts.append(capsule((0.16 * s, 0.97, 0), (0.295 * s, 0.796, 0.0), 0.064, r2=0.058))     # upper arm
        parts.append(capsule((0.295 * s, 0.796, 0.0), (0.399 * s, 0.627, 0.026), 0.058, r2=0.05))  # forearm
        parts.append(capsule((0.095 * s, 0.63, 0), (0.1 * s, 0.34, 0.0), 0.088, r2=0.074))      # thigh
        parts.append(capsule((0.1 * s, 0.34, 0.0), (0.1 * s, 0.1, 0), 0.07, r2=0.06))           # shin
    suit = join(parts, "suit")
    r = suit.modifiers.new("rm", 'REMESH')
    r.mode = 'VOXEL'
    r.voxel_size = 0.008
    apply_mods(suit)
    sm = suit.modifiers.new("sm", 'SMOOTH')
    sm.factor = 0.6
    sm.iterations = 6
    apply_mods(suit)
    decimate(suit, 0.05)
    shade_smooth(suit)
    set_paint(suit, PANTS())
    paint_where(suit, SHIRT(), lambda x, y, z, nx, ny, nz: y >= WAIST_Y)
    # bare arms past the short sleeve: cut square across the arm (distance along the upper-arm bone)
    sh = Vector((0.16, 0.97))
    ad = Vector((0.295 - 0.16, 0.796 - 0.97))
    alen = ad.length
    ad.normalize()

    elbow = Vector((0.295, 0.796))
    wrist = Vector((0.399, 0.627))

    def seg_dist(p, a, b):
        ab = b - a
        t = max(0.0, min(1.0, (p - a).dot(ab) / ab.length_squared))
        return (a + ab * t - p).length

    def bare_arm(x, y, z, nx, ny, nz):
        p = Vector((abs(x), y))
        # only the arm itself (never the torso or the legs)
        if min(seg_dist(p, sh, elbow), seg_dist(p, elbow, wrist)) > 0.085:
            return False
        t = (p - sh).dot(ad) / alen
        return t > SLEEVE_T

    paint_where(suit, SKIN(), bare_arm)
    extra = [
        torus(0.168, 0.02, (0, WAIST_Y, 0.0), mat=paint(0x2B2F3A, 0, 0.3), seg=36, side=8),   # belt
        torus(0.07, 0.016, (0, 1.0, 0.0), mat=SHIRT(0.15), seg=24, side=8),                    # collar
    ]
    for s in (1, -1):
        extra.append(torus(0.064, 0.016, (0.1 * s, 0.125, 0.0), mat=PANTS(0.12), seg=20, side=8))        # pant hem
        extra.append(sphere(1.0, (0.415 * s, 0.6, 0.03), scale=(0.058, 0.066, 0.05), mat=SKIN(0.2), seg=16, rings=12))  # hand
        extra.append(sphere(0.024, (0.39 * s, 0.625, 0.075), mat=SKIN(0.2), seg=10, rings=8))          # thumb
    return join([suit] + extra, "suit")


def human_head():
    h = sphere(1.0, tuple(H_HEAD_C), scale=(H_HEAD_R, H_HEAD_R * 1.05, H_HEAD_R * 0.975), seg=32, rings=20, mat=SKIN(0.16))
    ears = [sphere(1.0, (0.198 * s, H_HEAD_C.y - 0.01, -0.005), scale=(0.022, 0.045, 0.035), mat=SKIN(0.16), seg=12, rings=8) for s in (1, -1)]
    neck = cyl(0.062, 0.1, (0, 1.02, 0), mat=SKIN(0.12), seg=16)
    return join([h, neck] + ears, "head")


def human_shoe(side):
    s = 1 if side == "L" else -1
    sh = rbox(0.14, 0.1, 0.26, (0.1 * s, 0.055, 0.04), mat=paint(WHITE, TINT_SHOE, 0.25), r=0.045, segs=3)
    sole = rbox(0.148, 0.03, 0.268, (0.1 * s, 0.015, 0.04), mat=paint(0xE3E9F1, 0, 0.1), r=0.014, segs=1)
    toe = sphere(0.056, (0.1 * s, 0.062, 0.13), scale=(1.15, 0.85, 0.9), mat=paint(WHITE, TINT_SHOE, 0.25), seg=14, rings=9)
    stripe = rbox(0.142, 0.022, 0.11, (0.1 * s, 0.078, -0.005), mat=paint(WHITE, TINT_B, 0.2), r=0.009, segs=2)
    return join([sh, sole, toe, stripe], "shoe_" + side)


def build_human(outdir):
    reset()
    arm = make_human_rig()
    suit = human_suit()
    bpy.ops.object.select_all(action='DESELECT')
    suit.select_set(True)
    arm.select_set(True)
    bpy.context.view_layer.objects.active = arm
    try:
        bpy.ops.object.parent_set(type='ARMATURE_AUTO')
    except RuntimeError as e:
        print("auto weights failed:", e)
    for v in suit.data.vertices:
        if not any(g.weight > 0.01 for g in v.groups):
            best, bd = "HIPS", 1e9
            p = Vector((v.co.x, v.co.z, -v.co.y))
            for name, hd, tl, _ in HUMAN_RIG:
                a, b = Vector(hd), Vector(tl)
                ab = b - a
                tt = max(0.0, min(1.0, (p - a).dot(ab) / max(ab.length_squared, 1e-9)))
                d = (a + ab * tt - p).length
                if d < bd:
                    bd, best = d, name
            g = suit.vertex_groups.get(best) or suit.vertex_groups.new(name=best)
            g.add([v.index], 1.0, 'REPLACE')
    # hands ride the forearm bones, the rest of the extras ride their nearest bone
    head = human_head()
    assign_group(head, "HEAD")
    shl = human_shoe("L"); assign_group(shl, "FOOT_L")
    shr = human_shoe("R"); assign_group(shr, "FOOT_R")
    for m in list(suit.modifiers):
        suit.modifiers.remove(m)
    suit.parent = None
    body = join([suit, head, shl, shr], "human")
    bake_ao(body, samples=28, dist=0.2, strength=0.7)
    return export(body, "human", outdir, arm=arm, bone_order=BONES)


# ----------------------------------------------------------------------------- hair helpers
def _smooth(a, b, x):
    t = max(0.0, min(1.0, (x - a) / (b - a)))
    return t * t * (3 - 2 * t)


def hairline(front=0.16, side=-0.06, back=-0.2):
    """Hair edge height (relative to the head centre) as a function of the azimuth's forward
    component c = cos(azimuth): [front] over the forehead, [side] at the ears, [back] at the nape."""
    def limit(c):
        if c > 0.0:
            return side + (front - side) * _smooth(0.0, 0.8, c)
        return side + (back - side) * _smooth(0.0, 0.85, -c)
    return limit


def shell(limit, r=0.352, mat=None, thick=0.03, nphi=44, nth=14):
    """Hair cap built row by row from the crown down to a smooth hairline (no jagged cut edges)."""
    bm = bmesh.new()
    top = bm.verts.new(B(HEAD_C.x, HEAD_C.y + r, HEAD_C.z))
    cols = []
    for i in range(nphi):
        phi = 2 * math.pi * i / nphi            # 0 = straight forward (+Z)
        dx, dz = math.sin(phi), math.cos(phi)
        th_e = math.acos(max(-0.97, min(0.97, limit(dz) / r)))
        col = []
        for j in range(1, nth + 1):
            th = th_e * j / nth
            col.append(bm.verts.new(B(HEAD_C.x + r * math.sin(th) * dx, HEAD_C.y + r * math.cos(th), HEAD_C.z + r * math.sin(th) * dz)))
        cols.append(col)
    for i in range(nphi):
        a, b = cols[i], cols[(i + 1) % nphi]
        bm.faces.new((top, b[0], a[0]))
        for j in range(nth - 1):
            bm.faces.new((a[j], b[j], b[j + 1], a[j + 1]))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    me = bpy.data.meshes.new("hair")
    bm.to_mesh(me)
    bm.free()
    o = bpy.data.objects.new("hair", me)
    bpy.context.collection.objects.link(o)
    set_paint(o, mat or HAIR())
    sol = o.modifiers.new("s", 'SOLIDIFY')
    sol.thickness = thick
    sol.offset = -1.0
    apply_mods(o)
    shade_smooth(o)
    return o


def on_scalp(dx, dy, dz, lift=0.0):
    d = Vector((dx, dy, dz)).normalized()
    p = HEAD_C + d * (0.352 + lift)
    return (p.x, p.y, p.z), d


def orient_up(d):
    """GL euler that turns +Y toward the unit direction d (X rotation first, then Z)."""
    rx = math.asin(max(-1.0, min(1.0, d.z)))
    rz = math.atan2(-d.x, d.y)
    return (rx, 0.0, rz)


def tie(p, r=0.06, rot=(0, 0, 0)):
    return torus(r, 0.022, p, rot=rot, mat=ACCENT(0.4), seg=20, side=8)


# ----------------------------------------------------------------------------- hairstyles
def hair_crop():
    """Short tidy crop with a soft quiff."""
    cap = shell(hairline(0.17, -0.04, -0.16))
    quiff = [sphere(1.0, on_scalp(dx, 0.85, 0.45, -0.01)[0], scale=(0.11, 0.06, 0.09), mat=HAIR(), seg=14, rings=9) for dx in (-0.12, 0.02, 0.14)]
    return join([cap] + quiff)


def hair_spiky():
    """Tousled spikes sweeping up and back."""
    cap = shell(hairline(0.17, -0.05, -0.18))
    spikes = []
    dirs = [(0.0, 1.0, 0.25), (0.35, 0.9, 0.1), (-0.35, 0.9, 0.1), (0.2, 0.8, -0.45), (-0.2, 0.8, -0.45),
            (0.0, 0.6, -0.8), (0.55, 0.6, -0.35), (-0.55, 0.6, -0.35), (0.18, 0.95, 0.5), (-0.18, 0.95, 0.5)]
    for d in dirs:
        p, n = on_scalp(*d, lift=-0.03)
        c = cyl(0.075, 0.2, p, mat=HAIR(), seg=10, r2=0.0)
        # cone base at the scalp, tip outward
        transform(c, loc=(-p[0], -p[1], -p[2]))
        transform(c, loc=(0, 0.08, 0))
        transform(c, rot=orient_up(n))
        transform(c, loc=p)
        spikes.append(c)
    return join([cap] + spikes)


def hair_ponytail():
    cap = shell(hairline(0.16, -0.05, -0.12))
    base = (0.0, 1.16, -0.3)
    t = tie(base, 0.07, rot=(1.1, 0, 0))
    tail = tube([base, (0.0, 1.08, -0.45), (0.0, 0.92, -0.5), (0.0, 0.76, -0.44)], 0.085, mat=HAIR(), seg=14, res=8, taper=[1.0, 1.1, 0.8, 0.35])
    return join([cap, t, tail])


def hair_pigtails():
    cap = shell(hairline(0.15, -0.06, -0.14))
    out = [cap]
    for s in (1, -1):
        base = (0.3 * s, 1.04, -0.12)
        out.append(tie(base, 0.055, rot=(0, 0, math.pi / 2)))
        out.append(tube([base, (0.4 * s, 0.96, -0.16), (0.42 * s, 0.82, -0.14), (0.38 * s, 0.7, -0.1)], 0.07, mat=HAIR(), seg=12, res=8, taper=[1.0, 1.05, 0.8, 0.35]))
    return join(out)


def hair_buns():
    """Two space buns on top."""
    cap = shell(hairline(0.15, -0.06, -0.16))
    out = [cap]
    for s in (1, -1):
        p = (0.2 * s, 1.3, -0.06)
        out.append(sphere(0.12, p, mat=HAIR(), seg=20, rings=14))
        out.append(tie((0.17 * s, 1.24, -0.05), 0.085, rot=(0, 0, -0.6 * s)))
    return join(out)


def hair_bob():
    """Chin-length bob with a fringe."""
    cap = shell(hairline(0.13, -0.24, -0.26), r=0.365, thick=0.04)
    fringe = sphere(1.0, (0.0, 1.17, 0.26), scale=(0.24, 0.07, 0.1), rot=(-0.35, 0, 0), mat=HAIR())
    return join([cap, fringe])


def hair_long():
    """Long straight hair down the back."""
    cap = shell(hairline(0.15, -0.1, -0.2))
    back = sphere(1.0, (0.0, 0.86, -0.3), scale=(0.27, 0.34, 0.09), mat=HAIR())
    sides = [sphere(1.0, (0.27 * s, 0.9, -0.14), scale=(0.08, 0.25, 0.12), mat=HAIR()) for s in (1, -1)]
    return join([cap, back] + sides)


def hair_curly():
    """Big round curly hair."""
    cap = shell(hairline(0.16, -0.05, -0.18), r=0.36)
    curls = []
    k = 0
    for ring, (dy, n) in enumerate(((0.95, 1), (0.7, 6), (0.4, 9), (0.05, 10), (-0.3, 7))):
        for i in range(n):
            a = (i + 0.5 * ring) / n * 2 * math.pi
            rr = math.sqrt(max(0.0, 1 - dy * dy))
            d = Vector((math.sin(a) * rr, dy, math.cos(a) * rr))
            if d.z > 0.35 and d.y < 0.55:
                continue  # keep the face clear
            p, _ = on_scalp(d.x, d.y, d.z, lift=0.02)
            curls.append(sphere(0.105 + 0.02 * ((k * 7) % 3) / 2, p, mat=HAIR(), seg=10, rings=7))
            k += 1
    return join([cap] + curls)


def hair_mohawk():
    """Short shaved sides (accent colour) with a tall ridge on top."""
    cap = shell(hairline(0.17, -0.05, -0.18), r=0.338, thick=0.012, mat=ACCENT(0.15))
    ridge = []
    for i in range(7):
        t = i / 6
        a = 1.0 - t * 2.4            # from the forehead (+z) over the top to the back (-z)
        d = Vector((0.0, math.cos(a), math.sin(a)))
        p, _ = on_scalp(0.0, d.y, d.z, lift=0.02)
        ridge.append(sphere(1.0, p, scale=(0.045, 0.14 - 0.04 * abs(t - 0.4), 0.09), rot=(a, 0, 0), mat=HAIR(), seg=12, rings=8))
    return join([cap] + ridge)


def hair_topbun():
    cap = shell(hairline(0.16, -0.06, -0.16))
    bun = sphere(0.135, (0.0, 1.38, -0.08), mat=HAIR(), seg=22, rings=14)
    t = tie((0.0, 1.31, -0.07), 0.1, rot=(-0.2, 0, 0))
    return join([cap, bun, t])


def hair_braid():
    cap = shell(hairline(0.16, -0.06, -0.14))
    out = [cap, tie((0.0, 1.0, -0.36), 0.06, rot=(1.3, 0, 0))]
    for i in range(6):
        y = 0.95 - i * 0.07
        x = 0.025 if i % 2 == 0 else -0.025
        out.append(sphere(1.0, (x, y, -0.4 + i * 0.008), scale=(0.07 - i * 0.005, 0.05, 0.06), rot=(0, 0, 0.5 if i % 2 == 0 else -0.5), mat=HAIR(), seg=12, rings=8))
    out.append(tie((0.0, 0.53, -0.36), 0.035, rot=(1.5, 0, 0)))
    out.append(sphere(1.0, (0.0, 0.48, -0.36), scale=(0.04, 0.05, 0.035), mat=HAIR()))
    return join(out)


def hair_puffs():
    """Two big curly puffs."""
    cap = shell(hairline(0.15, -0.06, -0.16), r=0.348, thick=0.02)
    out = [cap]
    for s in (1, -1):
        c = Vector((0.24 * s, 1.3, -0.08))
        for i in range(9):
            a = i / 9 * 2 * math.pi
            off = Vector((math.cos(a) * 0.08, math.sin(a) * 0.07, math.sin(a * 2) * 0.05))
            out.append(sphere(0.075, tuple(c + off), mat=HAIR(), seg=10, rings=7))
        out.append(sphere(0.1, tuple(c), mat=HAIR(), seg=16, rings=12))
        out.append(tie((0.2 * s, 1.22, -0.07), 0.07, rot=(0, 0, -0.7 * s)))
    return join(out)


def hair_wavy():
    """Shoulder-length waves."""
    cap = shell(hairline(0.15, -0.14, -0.22), r=0.36)
    out = [cap]
    for i in range(7):
        a = math.pi + (i - 3) * 0.32
        x = math.sin(a) * 0.31
        z = math.cos(a) * 0.31
        out.append(sphere(1.0, (x, 0.82, z), scale=(0.09, 0.15, 0.08), mat=HAIR(), seg=14, rings=9))
    return join(out)


# ----------------------------------------------------------------------------- backpack (chest bone)
def acc_backpack():
    """Backpack for the human body (chest bone, human rest space)."""
    body = rbox(0.23, 0.26, 0.11, (0, 0.86, -0.175), mat=paint(WHITE, TINT_A, 0.3), r=0.045)
    pocket = rbox(0.16, 0.1, 0.045, (0, 0.8, -0.235), mat=paint(WHITE, TINT_B, 0.3), r=0.02)
    flap = rbox(0.22, 0.045, 0.12, (0, 0.985, -0.175), mat=paint(WHITE, TINT_B, 0.3), r=0.018)
    straps = []
    for s in (1, -1):
        straps.append(tube([(0.085 * s, 0.985, -0.13), (0.1 * s, 1.0, -0.05), (0.105 * s, 0.96, 0.08), (0.1 * s, 0.78, 0.115)], 0.015, mat=paint(WHITE, TINT_B, 0.2), seg=8, res=6))
    return join([body, pocket, flap] + straps)


MAX_TRIS = 3200


def budget(obj):
    """Keeps each hairstyle light enough for six racers on a mid-range phone."""
    tris = sum(len(p.vertices) - 2 for p in obj.data.polygons)
    if tris > MAX_TRIS:
        decimate(obj, MAX_TRIS / tris)
    return obj


HAIRS = {
    "hair_crop": hair_crop, "hair_spiky": hair_spiky, "hair_ponytail": hair_ponytail, "hair_pigtails": hair_pigtails,
    "hair_buns": hair_buns, "hair_bob": hair_bob, "hair_long": hair_long, "hair_curly": hair_curly,
    "hair_mohawk": hair_mohawk, "hair_topbun": hair_topbun, "hair_braid": hair_braid, "hair_puffs": hair_puffs,
    "hair_wavy": hair_wavy, "acc_backpack": acc_backpack,
}


def build_all(outdir, only=None):
    stats = {}
    if only is None or "human" in only:
        stats["human"] = build_human(outdir)
    for name, fn in HAIRS.items():
        if only is None or name in only:
            try:
                stats[name] = build_and_export(name, lambda fn=fn: budget(fn()), outdir, ao_dist=0.12, ao_samples=12)
            except Exception as e:
                print("FAILED", name, e)
                import traceback; traceback.print_exc()
    return stats
