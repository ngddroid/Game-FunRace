"""Dashling: skinned body, face parts, costume accessories and hats (all in rest-pose model space)."""
import bpy
import math
from mathutils import Vector
from dlib import *

BONES = ["HIPS", "CHEST", "HEAD", "ARM_L", "HAND_L", "ARM_R", "HAND_R",
         "THIGH_L", "SHIN_L", "FOOT_L", "THIGH_R", "SHIN_R", "FOOT_R"]

# (name, head, tail, parent) in GL space
RIG = [
    ("HIPS", (0, 0.42, 0), (0, 0.56, 0), None),
    ("CHEST", (0, 0.56, 0), (0, 0.72, 0), "HIPS"),
    ("HEAD", (0, 0.72, 0), (0, 1.30, 0), "CHEST"),
    ("ARM_L", (0.17, 0.655, 0), (0.29, 0.50, 0.0), "CHEST"),
    ("HAND_L", (0.29, 0.50, 0.0), (0.37, 0.37, 0.02), "ARM_L"),
    ("ARM_R", (-0.17, 0.655, 0), (-0.29, 0.50, 0.0), "CHEST"),
    ("HAND_R", (-0.29, 0.50, 0.0), (-0.37, 0.37, 0.02), "ARM_R"),
    ("THIGH_L", (0.095, 0.40, 0), (0.1, 0.235, 0.0), "HIPS"),
    ("SHIN_L", (0.1, 0.235, 0.0), (0.1, 0.09, 0), "THIGH_L"),
    ("FOOT_L", (0.1, 0.09, 0), (0.1, 0.03, 0.14), "SHIN_L"),
    ("THIGH_R", (-0.095, 0.40, 0), (-0.1, 0.235, 0.0), "HIPS"),
    ("SHIN_R", (-0.1, 0.235, 0.0), (-0.1, 0.09, 0), "THIGH_R"),
    ("FOOT_R", (-0.1, 0.09, 0), (-0.1, 0.03, 0.14), "SHIN_R"),
]

HEAD_C = Vector((0.0, 1.0, 0.0))   # GL head centre
HEAD_R = 0.33
FACE_DOT = 0.52                    # face opening: dot(dir, +Z) > FACE_DOT

PRIMARY = paint  # alias to keep calls short
INK = 0x1B2340


def P_A(gloss=0.12):
    return paint(WHITE, TINT_A, gloss)


def P_B(gloss=0.12):
    return paint(WHITE, TINT_B, gloss)


def on_head(dx, dy, dz, lift=0.0):
    """Point on the head sphere in direction (dx,dy,dz) (GL), lifted outward by [lift]."""
    d = Vector((dx, dy, dz)).normalized()
    p = HEAD_C + d * (HEAD_R + lift)
    return (p.x, p.y, p.z), d


def orient_to(d):
    """GL euler (rx, ry, 0) that turns +Z toward direction d."""
    ry = math.atan2(d.x, d.z)
    rx = -math.asin(max(-1.0, min(1.0, d.y)))
    return (rx, ry, 0.0)


# ----------------------------------------------------------------------------- body
def make_rig():
    data = bpy.data.armatures.new("rig")
    arm = bpy.data.objects.new("rig", data)
    bpy.context.collection.objects.link(arm)
    active(arm)
    bpy.ops.object.mode_set(mode='EDIT')
    for name, h, t, parent in RIG:
        eb = data.edit_bones.new(name)
        eb.head = B(*h)
        eb.tail = B(*t)
        if parent:
            eb.parent = data.edit_bones[parent]
        eb.use_connect = False
    bpy.ops.object.mode_set(mode='OBJECT')
    return arm


def suit_mesh():
    parts = []
    # torso: soft pear
    parts.append(sphere(0.205, (0, 0.52, 0), scale=(1.0, 1.05, 0.9)))
    parts.append(sphere(0.175, (0, 0.63, 0), scale=(1.0, 0.8, 0.88)))
    for s in (1, -1):
        # legs
        parts.append(capsule((0.095 * s, 0.40, 0), (0.1 * s, 0.235, 0.0), 0.078, r2=0.07))
        parts.append(capsule((0.1 * s, 0.235, 0.0), (0.1 * s, 0.11, 0), 0.07, r2=0.066))
        # arms (end at the wrist; mittens are separate parts)
        parts.append(capsule((0.15 * s, 0.655, 0), (0.29 * s, 0.50, 0.0), 0.062, r2=0.056))
        parts.append(capsule((0.29 * s, 0.50, 0.0), (0.33 * s, 0.435, 0.01), 0.056, r2=0.052))
    suit = join(parts, "suit")
    r = suit.modifiers.new("rm", 'REMESH')
    r.mode = 'VOXEL'
    r.voxel_size = 0.0085
    apply_mods(suit)
    sm = suit.modifiers.new("sm", 'SMOOTH')
    sm.factor = 0.6
    sm.iterations = 8
    apply_mods(suit)
    decimate(suit, 0.032)
    shade_smooth(suit)
    set_paint(suit, P_A())
    extra = []
    # belly patch, wrist + ankle cuffs and mittens in the secondary colour (separate parts = crisp edges)
    extra.append(sphere(1.0, (0, 0.5, 0.152), scale=(0.118, 0.13, 0.04), rot=(-0.12, 0, 0), mat=P_B(0.1), seg=18, rings=10))
    for s in (1, -1):
        extra.append(torus(0.052, 0.018, (0.322 * s, 0.445, 0.01), rot=(0, 0, -0.55 * s), mat=P_B(0.12), seg=18, side=8))
        extra.append(torus(0.068, 0.02, (0.1 * s, 0.125, 0.0), mat=P_B(0.12), seg=20, side=8))
        extra.append(sphere(0.078, (0.37 * s, 0.37, 0.02), scale=(0.9, 1.0, 1.0), mat=P_B(0.15), seg=16, rings=12))
        extra.append(sphere(0.034, (0.335 * s, 0.405, 0.07), mat=P_B(0.15), seg=12, rings=8))
    return join([suit] + extra, "suit")


def head_mesh():
    h = sphere(HEAD_R, tuple(HEAD_C), seg=34, rings=22)
    set_paint(h, P_A(0.1))
    paint_where(h, paint(WHITE, TINT_SKIN, 0.18), lambda x, y, z, nx, ny, nz: nz > FACE_DOT and ny < 0.62)
    # hood rim framing the face
    ang = math.acos(FACE_DOT)
    rim_r = HEAD_R * math.sin(ang)
    rim_z = HEAD_R * math.cos(ang)
    rim = torus(rim_r + 0.006, 0.034, (0, HEAD_C.y + 0.0, rim_z + 0.006), rot=(math.pi / 2, 0, 0), mat=P_B(0.15), seg=32, side=8)
    # little drawstrings
    strings = []
    for s in (1, -1):
        strings.append(capsule((0.1 * s, 0.76, 0.24), (0.11 * s, 0.66, 0.23), 0.012, seg=8, rings=6))
        strings.append(sphere(0.022, (0.11 * s, 0.645, 0.23), seg=10, rings=8))
    for o in strings:
        set_paint(o, paint(0xFFFFFF, TINT_B, 0.2))
    return join([h, rim] + strings, "head")


def shoes_mesh(side):
    s = 1 if side == "L" else -1
    sh = rbox(0.15, 0.11, 0.25, (0.1 * s, 0.058, 0.035), mat=paint(WHITE, TINT_SHOE, 0.25), r=0.052, segs=3)
    sole = rbox(0.158, 0.035, 0.258, (0.1 * s, 0.018, 0.035), mat=paint(0xE3E9F1, 0, 0.1), r=0.016, segs=1)
    toe = sphere(0.06, (0.1 * s, 0.07, 0.115), scale=(1.15, 0.85, 0.9), mat=paint(WHITE, TINT_SHOE, 0.25), seg=14, rings=9)
    stripe = rbox(0.152, 0.025, 0.12, (0.1 * s, 0.082, -0.01), mat=paint(WHITE, TINT_B, 0.2), r=0.01, segs=2)
    return join([sh, sole, toe, stripe], "shoe_" + side)


def assign_group(obj, bone):
    g = obj.vertex_groups.new(name=bone)
    g.add([v.index for v in obj.data.vertices], 1.0, 'REPLACE')


def build_dashling(outdir):
    reset()
    arm = make_rig()
    suit = suit_mesh()
    bpy.ops.object.select_all(action='DESELECT')
    suit.select_set(True)
    arm.select_set(True)
    bpy.context.view_layer.objects.active = arm
    try:
        bpy.ops.object.parent_set(type='ARMATURE_AUTO')
    except RuntimeError as e:
        print("auto weights failed:", e)
    # verts with no weights -> nearest bone
    names = [g.name for g in suit.vertex_groups]
    for v in suit.data.vertices:
        if not any(g.weight > 0.01 for g in v.groups):
            best, bd = "HIPS", 1e9
            p = Vector((v.co.x, v.co.z, -v.co.y))
            for name, h, t, _ in RIG:
                a, b = Vector(h), Vector(t)
                ab = b - a
                tt = max(0.0, min(1.0, (p - a).dot(ab) / max(ab.length_squared, 1e-9)))
                d = (a + ab * tt - p).length
                if d < bd:
                    bd, best = d, name
            g = suit.vertex_groups.get(best) or suit.vertex_groups.new(name=best)
            g.add([v.index], 1.0, 'REPLACE')
    head = head_mesh()
    assign_group(head, "HEAD")
    shl = shoes_mesh("L"); assign_group(shl, "FOOT_L")
    shr = shoes_mesh("R"); assign_group(shr, "FOOT_R")
    for m in list(suit.modifiers):
        suit.modifiers.remove(m)
    suit.parent = None
    body = join([suit, head, shl, shr], "dashling")
    bake_ao(body, samples=28, dist=0.25, strength=0.75)
    return export(body, "dashling", outdir, arm=arm, bone_order=BONES)


# ----------------------------------------------------------------------------- face
def build_face(outdir):
    out = {}

    def eye(side):
        p, d = on_head(0.34 * side, 0.07, 0.94, -0.012)
        e = sphere(1.0, p, scale=(0.054, 0.074, 0.03), rot=orient_to(d), mat=paint(INK, 0, 0.7), seg=20, rings=14)
        # highlights sit a little in front of the eye surface
        right = Vector((d.z, 0, -d.x)).normalized()
        up = d.cross(right).normalized() * -1
        base = Vector(p) + d * 0.026
        h1 = base + right * (-0.018 * side) + up * 0.028
        h2 = base + right * (0.015 * side) + up * -0.022
        s1 = sphere(0.017, tuple(h1), mat=paint(0xFFFFFF, 0, 0.0, 0.6), seg=10, rings=8)
        s2 = sphere(0.0085, tuple(h2), mat=paint(0xFFFFFF, 0, 0.0, 0.6), seg=8, rings=6)
        return join([e, s1, s2])

    def brow(side):
        a, _ = on_head(0.25 * side, 0.36, 0.9, 0.006)
        b, _ = on_head(0.44 * side, 0.31, 0.85, 0.006)
        return capsule(a, b, 0.013, mat=paint(0x4A3030, 0, 0.1))

    def blush(side):
        p, d = on_head(0.55 * side, -0.16, 0.82, -0.004)
        return sphere(1.0, p, scale=(0.05, 0.028, 0.012), rot=orient_to(d), mat=paint(0xFF8FA6, 0, 0.05), seg=14, rings=8)

    def smile():
        pts = []
        for i in range(9):
            t = -1 + 2 * i / 8
            raw = Vector((t * 0.072, -0.105 - 0.032 * (1 - t * t), 0.32))
            dd = (raw).normalized()
            p = HEAD_C + Vector((raw.x, raw.y, 0)) * 1.0
            q, _ = on_head(raw.x, raw.y, 0.31, 0.004)
            pts.append(q)
        return tube(pts, 0.013, mat=paint(0x5A2333, 0, 0.2), seg=8, res=4)

    def mouth_open():
        p, d = on_head(0.0, -0.36, 0.93, -0.008)
        m = sphere(1.0, p, scale=(0.058, 0.05, 0.022), rot=orient_to(d), mat=paint(0x6E1E2E, 0, 0.3), seg=18, rings=10)
        p2, d2 = on_head(0.0, -0.41, 0.91, -0.003)
        t = sphere(1.0, p2, scale=(0.034, 0.018, 0.012), rot=orient_to(d2), mat=paint(0xFF7A8A, 0, 0.3), seg=12, rings=8)
        return join([m, t])

    jobs = [("face_eye_l", lambda: eye(1)), ("face_eye_r", lambda: eye(-1)),
            ("face_brow_l", lambda: brow(1)), ("face_brow_r", lambda: brow(-1)),
            ("face_blush_l", lambda: blush(1)), ("face_blush_r", lambda: blush(-1)),
            ("face_mouth_smile", smile), ("face_mouth_open", mouth_open)]
    for name, fn in jobs:
        out[name] = build_and_export(name, fn, outdir, ao=False)
    return out


# ----------------------------------------------------------------------------- accessories
def ears(shape_fn):
    return join([shape_fn(1), shape_fn(-1)])


def acc_cat():
    def ear(s):
        p, d = on_head(0.55 * s, 0.8, 0.05, 0.05)
        o = cyl(0.13, 0.25, p, rot=(0.0, 0.0, -0.5 * s), mat=P_A(), r2=0.0, seg=4)
        transform(o, loc=(-p[0], -p[1], -p[2]), scale=(1, 1, 0.6))
        transform(o, loc=p)
        i = cyl(0.085, 0.17, (p[0], p[1] - 0.01, p[2] + 0.045), rot=(0.0, 0.0, -0.5 * s), mat=P_B(), r2=0.0, seg=4)
        return join([o, i])
    return ears(ear)


def acc_bear():
    def ear(s):
        p, d = on_head(0.62 * s, 0.72, 0.05, -0.01)
        o = sphere(0.085, p, scale=(1, 1, 0.6), mat=P_A())
        i = sphere(0.05, (p[0], p[1] - 0.005, p[2] + 0.04), scale=(1, 1, 0.4), mat=P_B())
        return join([o, i])
    return ears(ear)


def acc_bunny():
    def ear(s):
        a, _ = on_head(0.28 * s, 0.94, -0.05, -0.03)
        b = (a[0] + 0.1 * s, a[1] + 0.4, a[2] - 0.04)
        o = capsule(a, b, 0.06, mat=P_A(), r2=0.055)
        transform(o, loc=(0, 0, 0))
        i = capsule((a[0], a[1] + 0.06, a[2] + 0.03), (b[0], b[1] - 0.03, b[2] + 0.035), 0.033, mat=P_B())
        return join([o, i])
    return ears(ear)


def acc_frog_eyes():
    def e(s):
        p, d = on_head(0.36 * s, 0.86, 0.38, -0.02)
        lid = sphere(0.1, p, mat=P_A())
        w = sphere(0.075, (p[0], p[1] + 0.01, p[2] + 0.04), mat=P_B())
        pu = sphere(0.035, (p[0], p[1] + 0.015, p[2] + 0.105), mat=paint(INK, 0, 0.6))
        return join([lid, w, pu])
    return ears(e)


def acc_fox():
    def ear(s):
        p, d = on_head(0.5 * s, 0.82, 0.0, 0.03)
        o = cyl(0.11, 0.26, p, rot=(0.0, 0.0, -0.42 * s), mat=P_A(), r2=0.0, seg=6)
        tip = cyl(0.04, 0.08, (p[0] + 0.098 * s * 0.42 * 2.5, p[1] + 0.1, p[2]), rot=(0.0, 0.0, -0.42 * s), mat=P_B(), r2=0.0, seg=6)
        i = cyl(0.065, 0.17, (p[0], p[1] - 0.01, p[2] + 0.04), rot=(0.0, 0.0, -0.42 * s), mat=paint(0xFFF4E8, 0, 0.1), r2=0.0, seg=6)
        return join([o, tip, i])
    return ears(ear)


def acc_panda():
    def ear(s):
        p, d = on_head(0.62 * s, 0.74, 0.0, -0.01)
        return sphere(0.09, p, scale=(1, 1, 0.65), mat=P_A())
    return ears(ear)


def acc_duck_bill():
    p, d = on_head(0.0, 0.62, 0.78, 0.0)
    b = sphere(1.0, (p[0], p[1] - 0.02, p[2] + 0.06), scale=(0.16, 0.04, 0.12), rot=(-0.25, 0, 0), mat=P_A(0.3))
    return b


def acc_penguin_beak():
    p, d = on_head(0.0, 0.64, 0.77, -0.01)
    return cyl(0.06, 0.12, (p[0], p[1], p[2] + 0.04), rot=(math.pi / 2 - 0.2, 0, 0), mat=P_A(0.3), r2=0.0, seg=12)


def acc_bee_antennae():
    out = []
    for s in (1, -1):
        a, _ = on_head(0.22 * s, 0.95, 0.15, -0.01)
        pts = [a, (a[0] + 0.03 * s, a[1] + 0.12, a[2] + 0.02), (a[0] + 0.1 * s, a[1] + 0.2, a[2] + 0.06)]
        out.append(tube(pts, 0.012, mat=P_A(), seg=8, res=4))
        out.append(sphere(0.04, pts[-1], mat=P_B(0.4)))
    return join(out)


def acc_bee_wings():
    out = []
    for s in (1, -1):
        out.append(sphere(1.0, (0.15 * s, 0.68, -0.2), scale=(0.17, 0.1, 0.012), rot=(0.2, 0.4 * s, 0.5 * s), mat=paint(WHITE, TINT_A, 0.8, 0.15)))
        out.append(sphere(1.0, (0.12 * s, 0.56, -0.19), scale=(0.11, 0.07, 0.012), rot=(0.2, 0.4 * s, -0.4 * s), mat=paint(WHITE, TINT_A, 0.8, 0.15)))
    return join(out)


def acc_shark():
    p, d = on_head(0.0, 0.75, -0.62, -0.03)
    fin = extrude_shape([(-0.06, 0.0), (0.3, 0.0), (0.0, 0.36)], 0.07, mat=P_A(), loc=(0, 0, 0), rot=(0, math.pi / 2, 0), bevel=0.018)
    transform(fin, loc=(0, 1.22, -0.12), rot=(0.25, 0, 0))
    teeth = []
    ang = math.acos(FACE_DOT)
    for i in range(14):
        a = math.pi * 0.08 + (math.pi * 0.84) * i / 13
        # teeth along the top arc of the hood opening
        x = math.cos(a) * HEAD_R * math.sin(ang) * 1.02
        y = math.sin(a) * HEAD_R * math.sin(ang) * 1.02
        z = HEAD_R * math.cos(ang) + 0.02
        t = cyl(0.025, 0.05, (x, HEAD_C.y + y - 0.02, z), rot=(math.pi, 0, -(a - math.pi / 2)), mat=P_B(0.3), r2=0.0, seg=4)
        teeth.append(t)
    return join([fin] + teeth)


def acc_pineapple():
    out = []
    for i in range(7):
        a = 2 * math.pi * i / 7
        tilt = 0.5
        out.append(sphere(1.0, (math.sin(a) * 0.05, 1.42, math.cos(a) * 0.05), scale=(0.05, 0.16, 0.018), rot=(tilt * math.cos(a), a, 0), mat=P_A(0.2) if i % 2 == 0 else P_B(0.2)))
    out.append(sphere(1.0, (0, 1.46, 0), scale=(0.035, 0.18, 0.035), mat=P_A(0.2)))
    return join(out)


def acc_dino():
    out = []
    for i, (y, sz) in enumerate([(0.68, 0.07), (0.58, 0.08), (0.48, 0.07), (0.38, 0.055)]):
        out.append(cyl(sz, sz * 1.6, (0, y, -0.2), rot=(-math.pi / 2 - 0.2, 0, 0), mat=P_A(0.2) if i % 2 == 0 else P_B(0.2), r2=0.0, seg=5))
    return join(out)


def acc_unicorn():
    p, d = on_head(0.0, 0.66, 0.75, -0.02)
    pts = []
    out = [cyl(0.05, 0.24, (p[0], p[1] + 0.09, p[2] + 0.04), rot=(0.55, 0, 0), mat=P_A(0.35), r2=0.0, seg=16)]
    for k in range(3):
        out.append(torus(0.045 - k * 0.012, 0.008, (p[0], p[1] + 0.04 + k * 0.05, p[2] + 0.015 + k * 0.03), rot=(0.55 + math.pi / 2, 0, 0), mat=P_B(0.3), seg=16, side=6))
    return join(out)


def acc_octo():
    out = []
    for i in range(6):
        a = math.pi * 0.15 + i * (math.pi * 1.7 / 5)
        base, d = on_head(math.sin(a) * 0.9, -0.55, -math.cos(a) * 0.9 - 0.1, -0.03)
        pts = [base]
        for k in range(1, 5):
            t = k / 4
            pts.append((base[0] + math.sin(a) * 0.05 * t, base[1] - 0.06 * t + 0.03 * t * t, base[2] - math.cos(a) * 0.05 * t + 0.02 * math.sin(t * 6)))
        out.append(tube(pts, 0.03, mat=P_A(), seg=8, res=4, taper=[1.0, 0.85, 0.7, 0.55, 0.4]))
        out.append(sphere(0.012, (pts[1][0] + math.sin(a) * 0.022, pts[1][1], pts[1][2] - math.cos(a) * 0.022), mat=P_B()))
    return join(out)


def claw(s):
    c = (0.39 * s, 0.37, 0.05)
    a = sphere(1.0, (c[0], c[1] + 0.05, c[2] + 0.04), scale=(0.07, 0.04, 0.09), rot=(0.4, 0, 0), mat=P_A(0.3))
    b = sphere(1.0, (c[0], c[1] - 0.04, c[2] + 0.03), scale=(0.06, 0.035, 0.08), rot=(-0.3, 0, 0), mat=P_A(0.3))
    r = torus(0.075, 0.022, (0.37 * s, 0.4, 0.0), rot=(0, 0, math.pi / 2), mat=P_B())
    return join([a, b, r])


def acc_crab_eyes():
    out = []
    for s in (1, -1):
        a, _ = on_head(0.2 * s, 0.95, 0.2, -0.01)
        top = (a[0] + 0.03 * s, a[1] + 0.14, a[2] + 0.03)
        out.append(capsule(a, top, 0.02, mat=P_A()))
        out.append(sphere(0.045, top, mat=P_B(0.3)))
        out.append(sphere(0.02, (top[0], top[1] + 0.005, top[2] + 0.035), mat=paint(INK, 0, 0.6)))
    return join(out)


def acc_turtle():
    sh = sphere(0.24, (0, 0.53, -0.12), scale=(1.0, 1.05, 0.55), mat=P_A(0.3))
    rim = torus(0.215, 0.03, (0, 0.53, -0.08), rot=(math.pi / 2, 0, 0), mat=P_B(0.2))
    transform(rim, scale=(1, 1.05, 1))
    plates = []
    for (x, y) in [(0, 0.6), (-0.1, 0.5), (0.1, 0.5), (0, 0.43), (-0.1, 0.63), (0.1, 0.63)]:
        plates.append(sphere(1.0, (x, y, -0.235), scale=(0.055, 0.05, 0.02), mat=P_B(0.3)))
    return join([sh, rim] + plates)


def acc_robot():
    p, d = on_head(0.0, 1.0, 0.0, -0.02)
    stalk = cyl(0.015, 0.16, (p[0], p[1] + 0.07, p[2]), mat=P_A(0.6))
    ball = sphere(0.04, (p[0], p[1] + 0.17, p[2]), mat=paint(WHITE, TINT_B, 0.5, 0.6))
    bolts = []
    for s in (1, -1):
        q, dd = on_head(s, 0.0, 0.0, -0.01)
        bolts.append(cyl(0.05, 0.05, q, rot=(0, 0, math.pi / 2), mat=P_A(0.6), seg=12))
    return join([stalk, ball] + bolts)


def acc_alien():
    out = []
    for s in (1, -1):
        a, _ = on_head(0.3 * s, 0.92, 0.0, -0.01)
        pts = [a, (a[0] + 0.05 * s, a[1] + 0.12, a[2]), (a[0] + 0.12 * s, a[1] + 0.2, a[2] + 0.02)]
        out.append(tube(pts, 0.016, mat=P_A(), seg=8, res=4))
        out.append(sphere(0.042, pts[-1], mat=paint(WHITE, TINT_B, 0.4, 0.3)))
    return join(out)


def acc_bandana():
    cap = sphere(HEAD_R + 0.018, tuple(HEAD_C), mat=P_A(0.15), seg=32, rings=20)
    # keep only the upper back part
    import bmesh as bm_
    bm = bm_.new()
    bm.from_mesh(cap.data)
    kill = [v for v in bm.verts if (v.co.z < 0.12 + 1.0 * 0) or ((-v.co.y) > 0.18 and v.co.z < 1.18)]
    # (Blender coords: z = GL y, -y = GL z)
    kill = [v for v in bm.verts if v.co.z < 1.07 or ((-v.co.y) > 0.16 and v.co.z < 1.22)]
    bm_.ops.delete(bm, geom=kill, context='VERTS')
    bm.to_mesh(cap.data)
    bm.free()
    sol = cap.modifiers.new("sol", 'SOLIDIFY')
    sol.thickness = 0.012
    apply_mods(cap)
    knot = sphere(0.05, (0.1, 1.08, -0.3), mat=P_A(0.15))
    tails = [capsule((0.1, 1.06, -0.32), (0.16, 0.92, -0.36), 0.03, mat=P_A(0.15), r2=0.018),
             capsule((0.1, 1.06, -0.32), (0.04, 0.9, -0.38), 0.03, mat=P_A(0.15), r2=0.018)]
    dots = []
    for i in range(10):
        a = i * 2.4
        dp, dd = on_head(math.sin(a) * 0.6, 0.75 + 0.2 * math.cos(i), math.cos(a) * 0.6 - 0.2, 0.02)
        if dp[1] > 1.12:
            dots.append(sphere(0.02, dp, scale=(1, 1, 0.4), rot=orient_to(dd), mat=P_B()))
    return join([cap, knot] + tails + dots)


def acc_eye_patch():
    p, d = on_head(-0.34, 0.07, 0.94, 0.0)
    patch = sphere(1.0, p, scale=(0.075, 0.07, 0.02), rot=orient_to(d), mat=paint(0x1E1E24, 0, 0.3))
    pts = []
    for k in range(9):
        t = k / 8
        dx, dy, dz = -0.34 - 0.62 * t, 0.07 + 0.55 * t, 0.94 - 1.2 * t
        q, _ = on_head(dx, dy, dz, 0.012)
        pts.append(q)
    strap = tube(pts, 0.011, mat=paint(0x1E1E24, 0, 0.2), seg=6, res=3)
    return join([patch, strap])


def acc_astro():
    collar = torus(0.2, 0.06, (0, 0.72, 0), mat=P_B(0.5), seg=32)
    p, d = on_head(0.0, 1.0, -0.2, -0.01)
    ant = cyl(0.012, 0.14, (p[0], p[1] + 0.06, p[2]), mat=P_B(0.6))
    light = sphere(0.032, (p[0], p[1] + 0.14, p[2]), mat=paint(0xFF5A5A, 0, 0.5, 0.8))
    vis = []
    for s in (1, -1):
        q, dd = on_head(s, 0.05, 0.0, -0.015)
        vis.append(cyl(0.075, 0.045, q, rot=(0, 0, math.pi / 2), mat=P_A(0.6), seg=16))
    return join([collar, ant, light] + vis)


def acc_dragon_horns():
    out = []
    for s in (1, -1):
        a, _ = on_head(0.35 * s, 0.85, -0.15, -0.03)
        pts = [a, (a[0] + 0.05 * s, a[1] + 0.1, a[2] - 0.04), (a[0] + 0.08 * s, a[1] + 0.17, a[2] - 0.13)]
        out.append(tube(pts, 0.04, mat=P_A(0.3), seg=10, res=4, taper=[1.0, 0.6, 0.12]))
    spikes = [cyl(0.04, 0.08, on_head(0, 0.6 - k * 0.3, -0.8, -0.02)[0], rot=(-1.2 + k * 0.3, 0, 0), mat=P_B(0.3), r2=0.0, seg=5) for k in range(3)]
    return join(out + spikes)


def acc_dragon_wings():
    out = []
    for s in (1, -1):
        w = extrude_shape([(0, 0), (0.32, 0.14), (0.36, -0.04), (0.26, -0.02), (0.2, -0.1), (0.12, -0.03), (0.05, -0.08)], 0.02, mat=P_A(0.25), bevel=0.006)
        if s < 0:
            transform(w, scale=(-1, 1, 1))
            import bmesh as bm_
            bm = bm_.new(); bm.from_mesh(w.data); bm_.ops.reverse_faces(bm, faces=bm.faces); bm.to_mesh(w.data); bm.free()
        transform(w, loc=(0.08 * s, 0.6, -0.18), rot=(0.1, -0.5 * s, 0.25 * s))
        out.append(w)
    return join(out)


def acc_shell_crown():
    out = []
    for i, (x, sc) in enumerate([(-0.12, 0.7), (0.0, 1.0), (0.12, 0.7)]):
        p, d = on_head(x, 0.8, 0.45, -0.01)
        sh = sphere(1.0, p, scale=(0.06 * sc, 0.07 * sc, 0.02), rot=orient_to(d), mat=P_A(0.4))
        out.append(sh)
        out.append(sphere(0.016 * sc, (p[0], p[1] - 0.04 * sc, p[2] + 0.02), mat=P_B(0.8, ) if False else paint(0xFFFFFF, 0, 0.9, 0.3)))
    return join(out)


def acc_star_halo():
    ring = torus(0.2, 0.014, (0, 1.42, 0), mat=paint(WHITE, TINT_A, 0.6, 0.5), seg=36, side=8)
    stars = []
    for i in range(5):
        a = 2 * math.pi * i / 5
        pts = []
        for k in range(10):
            rr = 0.05 if k % 2 == 0 else 0.022
            b = math.pi * k / 5
            pts.append((math.sin(b) * rr, math.cos(b) * rr))
        st = extrude_shape(pts, 0.018, mat=paint(WHITE, TINT_A, 0.6, 0.6))
        transform(st, loc=(math.sin(a) * 0.2, 1.42, math.cos(a) * 0.2), rot=(0, a, 0))
        stars.append(st)
    return join([ring] + stars)


# ----------------------------------------------------------------------------- hats (sit on the hood top: y = 1.33)
TOP = HEAD_C.y + HEAD_R


def hat_cap():
    dome = sphere(0.25, (0, TOP - 0.09, 0.0), scale=(1.0, 0.62, 1.0), mat=P_A(0.15), seg=28, rings=16)
    import bmesh as bm_
    bm = bm_.new(); bm.from_mesh(dome.data)
    bm_.ops.delete(bm, geom=[v for v in bm.verts if v.co.z < TOP - 0.1], context='VERTS')
    bm.to_mesh(dome.data); bm.free()
    sol = dome.modifiers.new("s", 'SOLIDIFY'); sol.thickness = 0.015; apply_mods(dome)
    visor = sphere(1.0, (0, TOP - 0.1, 0.26), scale=(0.2, 0.018, 0.16), mat=P_B(0.2))
    btn = sphere(0.03, (0, TOP + 0.06, 0), mat=P_B(0.2))
    return join([dome, visor, btn])


def hat_bucket():
    body = cyl(0.21, 0.16, (0, TOP - 0.01, 0), mat=P_A(0.1), r2=0.18, seg=28, bevel=0.03)
    brim = cyl(0.34, 0.025, (0, TOP - 0.085, 0), mat=P_A(0.1), r2=0.3, seg=32, bevel=0.01)
    band = cyl(0.213, 0.04, (0, TOP - 0.06, 0), mat=P_B(0.2), r2=0.205, seg=28, caps=False)
    return join([body, brim, band])


def hat_straw():
    crown = cyl(0.2, 0.14, (0, TOP - 0.01, 0), mat=P_A(0.05), r2=0.17, seg=28, bevel=0.03)
    brim = lathe([(0.12, -0.02), (0.3, -0.01), (0.46, -0.05), (0.47, -0.06), (0.3, -0.03), (0.12, -0.04)], mat=P_A(0.05), seg=36, loc=(0, TOP - 0.06, 0))
    ribbon = cyl(0.203, 0.05, (0, TOP - 0.045, 0), mat=P_B(0.3), r2=0.195, seg=28, caps=False)
    bow = sphere(0.04, (0.19, TOP - 0.04, 0.06), scale=(0.5, 1, 1.4), mat=P_B(0.3))
    return join([crown, brim, ribbon, bow])


def hat_flower():
    p, d = on_head(0.62, 0.62, 0.3, 0.0)
    out = []
    for i in range(5):
        a = 2 * math.pi * i / 5
        off = Vector((math.cos(a), math.sin(a), 0)) * 0.06
        out.append(sphere(1.0, (p[0] + off.x * 0.9, p[1] + off.y, p[2] + 0.02), scale=(0.07, 0.05, 0.015), rot=(0, 0.5, a), mat=P_A(0.25)))
    out.append(sphere(0.03, (p[0], p[1], p[2] + 0.04), mat=P_B(0.3)))
    out.append(cyl(0.008, 0.08, (p[0], p[1], p[2] + 0.07), rot=(1.2, 0, 0), mat=paint(0xFFE36B, 0, 0.3, 0.2)))
    return join(out)


def hat_visor():
    band = torus(0.3, 0.025, (0, TOP - 0.14, 0), mat=P_A(0.2), seg=36)
    vis = sphere(1.0, (0, TOP - 0.15, 0.33), scale=(0.24, 0.02, 0.17), rot=(-0.12, 0, 0), mat=P_A(0.3))
    stripe = sphere(1.0, (0, TOP - 0.135, 0.33), scale=(0.08, 0.021, 0.17), rot=(-0.12, 0, 0), mat=P_B(0.3))
    return join([band, vis, stripe])


def hat_party():
    cone = cyl(0.12, 0.34, (0.05, TOP + 0.12, 0), rot=(0, 0, -0.25), mat=P_A(0.3), r2=0.0, seg=24)
    pom = sphere(0.05, (0.05 + math.sin(0.25) * 0.17 + 0.0, TOP + 0.3, 0), mat=P_B(0.2))
    dots = []
    for i in range(6):
        a = i * 1.9
        h = 0.06 + 0.04 * (i % 3)
        r = 0.12 * (1 - h / 0.34) + 0.004
        dots.append(sphere(0.02, (0.05 + math.cos(a) * r - math.sin(0.25) * (h - 0.0) * 0 + h * 0.25, TOP - 0.05 + h, math.sin(a) * r), scale=(1, 1, 1), mat=P_B(0.3)))
    return join([cone, pom] + dots)


def hat_propeller():
    cap = sphere(0.24, (0, TOP - 0.07, 0), scale=(1, 0.6, 1), mat=P_A(0.2), seg=24, rings=12)
    stem = cyl(0.015, 0.08, (0, TOP + 0.08, 0), mat=paint(0xFFFFFF, 0, 0.4))
    blades = [sphere(1.0, (0.1 * s, TOP + 0.12, 0), scale=(0.11, 0.012, 0.035), rot=(0, 0, 0.15 * s), mat=P_B(0.4)) for s in (1, -1)]
    hub = sphere(0.025, (0, TOP + 0.12, 0), mat=paint(0xFF5A5A, 0, 0.4))
    return join([cap, stem, hub] + blades)


def hat_headphones():
    band = torus(0.355, 0.03, (0, HEAD_C.y + 0.02, 0), rot=(0, 0, math.pi / 2), mat=P_A(0.4), seg=40)
    import bmesh as bm_
    bm = bm_.new(); bm.from_mesh(band.data)
    bm_.ops.delete(bm, geom=[v for v in bm.verts if v.co.z < HEAD_C.y + 0.02], context='VERTS')
    bm.to_mesh(band.data); bm.free()
    cups = []
    for s in (1, -1):
        cups.append(cyl(0.11, 0.08, (0.35 * s, HEAD_C.y + 0.0, 0), rot=(0, 0, math.pi / 2), mat=P_A(0.4), seg=24, bevel=0.025))
        cups.append(cyl(0.08, 0.02, (0.395 * s, HEAD_C.y + 0.0, 0), rot=(0, 0, math.pi / 2), mat=paint(WHITE, TINT_B, 0.5, 0.4), seg=24))
    return join([band] + cups)


def hat_snorkel():
    p, d = on_head(0.0, 0.62, 0.78, 0.04)
    mask = rbox(0.3, 0.13, 0.08, (p[0], p[1] + 0.02, p[2] - 0.01), rot=(-0.6, 0, 0), mat=P_A(0.4), r=0.05)
    glass = rbox(0.25, 0.09, 0.02, (p[0], p[1] + 0.045, p[2] + 0.025), rot=(-0.6, 0, 0), mat=paint(WHITE, TINT_B, 0.9, 0.15), r=0.03)
    strap = torus(HEAD_R + 0.01, 0.014, (0, HEAD_C.y + 0.15, 0), rot=(0.55, 0, 0), mat=P_A(0.3), seg=40, side=6)
    tube_ = tube([(0.3, 1.0, 0.1), (0.36, 1.2, 0.05), (0.35, 1.42, 0.0)], 0.025, mat=P_A(0.4), seg=8, res=4)
    tip = cyl(0.035, 0.05, (0.35, 1.44, 0.0), mat=paint(0xFF6B57, 0, 0.4))
    return join([mask, glass, strap, tube_, tip])


def hat_chef():
    band = cyl(0.21, 0.12, (0, TOP - 0.02, 0), mat=P_A(0.1), seg=28)
    puffs = [sphere(0.15, (math.cos(a) * 0.09, TOP + 0.16, math.sin(a) * 0.09), mat=P_A(0.1)) for a in [0, 2.1, 4.2]]
    puffs.append(sphere(0.16, (0, TOP + 0.22, 0), mat=P_A(0.1)))
    return join([band] + puffs)


def hat_pirate():
    base = sphere(0.24, (0, TOP - 0.05, 0), scale=(1.1, 0.5, 1.0), mat=P_A(0.15))
    brims = []
    for i in range(3):
        a = 2 * math.pi * i / 3 + math.pi / 3
        brims.append(sphere(1.0, (math.sin(a) * 0.17, TOP + 0.02, math.cos(a) * 0.17), scale=(0.2, 0.09, 0.04), rot=(0.3, a, 0), mat=P_A(0.15)))
    trim = torus(0.25, 0.012, (0, TOP - 0.03, 0), mat=P_B(0.6), seg=36, side=6)
    badge = sphere(1.0, (0, TOP + 0.03, 0.25), scale=(0.04, 0.04, 0.01), mat=P_B(0.6))
    return join([base, trim, badge] + brims)


def hat_cowboy():
    crown = sphere(0.2, (0, TOP + 0.04, 0), scale=(0.95, 0.75, 1.1), mat=P_A(0.1))
    brim = lathe([(0.1, 0.0), (0.3, -0.005), (0.42, 0.04), (0.43, 0.03), (0.3, -0.02), (0.1, -0.02)], mat=P_A(0.1), seg=36, loc=(0, TOP - 0.06, 0))
    transform(brim, scale=(1.0, 1.0, 1.15))
    band = cyl(0.19, 0.04, (0, TOP - 0.03, 0), mat=P_B(0.2), seg=28, caps=False)
    return join([crown, brim, band])


def hat_tophat():
    tube_ = cyl(0.17, 0.32, (0, TOP + 0.12, 0), mat=P_A(0.3), seg=28, bevel=0.01)
    brim = cyl(0.29, 0.025, (0, TOP - 0.035, 0), mat=P_A(0.3), seg=32, bevel=0.01)
    band = cyl(0.173, 0.06, (0, TOP + 0.0, 0), mat=P_B(0.4), seg=28, caps=False)
    return join([tube_, brim, band])


def hat_viking():
    helm = sphere(0.3, (0, TOP - 0.13, 0), scale=(1.12, 0.82, 1.12), mat=P_A(0.6), seg=28, rings=14)
    import bmesh as bm_
    bm = bm_.new(); bm.from_mesh(helm.data)
    bm_.ops.delete(bm, geom=[v for v in bm.verts if v.co.z < TOP - 0.13], context='VERTS')
    bm.to_mesh(helm.data); bm.free()
    sol = helm.modifiers.new("s", 'SOLIDIFY'); sol.thickness = 0.02; apply_mods(helm)
    rim = torus(0.335, 0.03, (0, TOP - 0.12, 0), mat=P_A(0.6), seg=36)
    horns = []
    for s in (1, -1):
        horns.append(tube([(0.3 * s, TOP - 0.03, 0), (0.42 * s, TOP + 0.05, 0), (0.46 * s, TOP + 0.2, 0.0)], 0.055, mat=P_B(0.3), seg=10, res=4, taper=[1.0, 0.7, 0.1]))
    return join([helm, rim] + horns)


def hat_crown():
    ring = cyl(0.2, 0.12, (0, TOP + 0.0, 0), mat=P_A(0.8), seg=32, caps=False)
    sol = ring.modifiers.new("s", 'SOLIDIFY'); sol.thickness = 0.02; apply_mods(ring)
    pts = []
    for i in range(6):
        a = 2 * math.pi * i / 6
        pts.append(cyl(0.045, 0.1, (math.sin(a) * 0.2, TOP + 0.1, math.cos(a) * 0.2), mat=P_A(0.8), r2=0.0, seg=8))
        pts.append(sphere(0.024, (math.sin(a) * 0.2, TOP + 0.16, math.cos(a) * 0.2), mat=P_A(0.8)))
        pts.append(sphere(1.0, (math.sin(a) * 0.205, TOP, math.cos(a) * 0.205), scale=(0.025, 0.03, 0.025), mat=P_B(0.9, ) if False else paint(WHITE, TINT_B, 0.9, 0.25)))
    return join([ring] + pts)


def hat_halo():
    return torus(0.2, 0.025, (0, TOP + 0.14, 0), mat=paint(WHITE, TINT_A, 0.6, 0.85), seg=36, side=10)


def hat_wizard():
    cone = lathe([(0.24, 0.0), (0.18, 0.12), (0.1, 0.3), (0.04, 0.46), (0.0, 0.52)], mat=P_A(0.15), seg=28, loc=(0, TOP - 0.06, 0))
    transform(cone, rot=(-0.15, 0, 0.05))
    brim = cyl(0.38, 0.02, (0, TOP - 0.06, 0), mat=P_A(0.15), seg=36, bevel=0.008)
    stars = []
    for i, (x, y, z) in enumerate([(0.12, TOP + 0.08, 0.14), (-0.1, TOP + 0.2, 0.08), (0.05, TOP + 0.3, 0.02)]):
        stars.append(sphere(0.03, (x, y, z), scale=(1, 1, 0.4), mat=paint(WHITE, TINT_B, 0.5, 0.5)))
    return join([cone, brim] + stars)


ACCESSORIES = {
    "acc_cat_ears": acc_cat, "acc_bear_ears": acc_bear, "acc_bunny_ears": acc_bunny, "acc_frog_eyes": acc_frog_eyes,
    "acc_fox_ears": acc_fox, "acc_panda_ears": acc_panda, "acc_duck_bill": acc_duck_bill, "acc_penguin_beak": acc_penguin_beak,
    "acc_bee_antennae": acc_bee_antennae, "acc_bee_wings": acc_bee_wings, "acc_shark_fin": acc_shark,
    "acc_pineapple_leaves": acc_pineapple, "acc_dino_spikes": acc_dino, "acc_unicorn_horn": acc_unicorn,
    "acc_octo_tentacles": acc_octo, "acc_crab_claw": lambda: claw(1), "acc_crab_claw_r": lambda: claw(-1),
    "acc_crab_eyes": acc_crab_eyes, "acc_turtle_shell": acc_turtle, "acc_robot_antenna": acc_robot,
    "acc_alien_antennae": acc_alien, "acc_pirate_bandana": acc_bandana, "acc_eye_patch": acc_eye_patch,
    "acc_astro_helmet": acc_astro, "acc_dragon_horns": acc_dragon_horns, "acc_dragon_wings": acc_dragon_wings,
    "acc_shell_crown": acc_shell_crown, "acc_star_halo": acc_star_halo,
}

HATS = {
    "hat_cap": hat_cap, "hat_bucket": hat_bucket, "hat_straw": hat_straw, "hat_flower": hat_flower,
    "hat_visor": hat_visor, "hat_party": hat_party, "hat_propeller": hat_propeller, "hat_headphones": hat_headphones,
    "hat_snorkel": hat_snorkel, "hat_chef": hat_chef, "hat_pirate": hat_pirate, "hat_cowboy": hat_cowboy,
    "hat_tophat": hat_tophat, "hat_viking": hat_viking, "hat_crown": hat_crown, "hat_halo": hat_halo, "hat_wizard": hat_wizard,
}


def build_all(outdir, only=None):
    stats = {}
    if only is None or "dashling" in only:
        stats["dashling"] = build_dashling(outdir)
    if only is None or any(n.startswith("face_") for n in only):
        stats.update(build_face(outdir))
    for name, fn in list(ACCESSORIES.items()) + list(HATS.items()):
        if only is None or name in only:
            try:
                stats[name] = build_and_export(name, fn, outdir, ao_dist=0.12, ao_samples=12)
            except Exception as e:
                print("FAILED", name, e)
                import traceback; traceback.print_exc()
    return stats
