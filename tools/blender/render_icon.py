"""
Renders the app icon foreground and store hero art with Cycles from the real game models.
Writes PNGs (transparent) into the scratch dir given after "--"; tools/make_icons.py composes
them into launcher icons, the Play icon and the feature graphic.

    Blender -b --factory-startup --python tools/blender/render_icon.py -- <outdir> [icon|hero]
"""
import bpy
import math
import os
import sys
from mathutils import Vector, Euler

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from dlib import *  # noqa
import m_character as mc  # noqa
import m_hazards as mh  # noqa
import m_course as mcourse  # noqa

argv = sys.argv[sys.argv.index("--") + 1:]
OUT = argv[0]
WHAT = argv[1] if len(argv) > 1 else "icon"
os.makedirs(OUT, exist_ok=True)


def lin(c):
    return tuple(((c >> s & 255) / 255.0) ** 2.2 for s in (16, 8, 0))


def shade_materials(obj, tints):
    """Turns engine paints into Principled materials. tints: {1: primary, 2: secondary, 3: skin, 4: shoe}."""
    for slot in obj.material_slots:
        m = slot.material
        if m is None or "dm_color" not in m:
            continue
        base = m["dm_color"]
        t = int(m["dm_tint"])
        r, g, b = lin(base)
        if t in tints:
            tr, tg, tb = lin(tints[t])
            r, g, b = r * tr, g * tg, b * tb
        nm = bpy.data.materials.new(m.name + "_r")
        nm.use_nodes = True
        bsdf = nm.node_tree.nodes.get("Principled BSDF")
        bsdf.inputs["Base Color"].default_value = (r, g, b, 1)
        gloss = float(m["dm_gloss"])
        bsdf.inputs["Roughness"].default_value = max(0.12, 0.75 - gloss * 0.65)
        try:
            bsdf.inputs["Coat Weight"].default_value = gloss * 0.5
        except KeyError:
            pass
        em = float(m["dm_emis"])
        if em > 0:
            bsdf.inputs["Emission Color"].default_value = (r, g, b, 1)
            bsdf.inputs["Emission Strength"].default_value = em * 2.5
        slot.material = nm


def dashling(char_primary, char_secondary, skin=0xFFD9B3, shoe=0xFFFFFF, pose=None):
    arm = mc.make_rig()
    suit = mc.suit_mesh()
    bpy.ops.object.select_all(action='DESELECT')
    suit.select_set(True)
    arm.select_set(True)
    bpy.context.view_layer.objects.active = arm
    bpy.ops.object.parent_set(type='ARMATURE_AUTO')
    head = mc.head_mesh(); mc.assign_group(head, "HEAD")
    shl = mc.shoes_mesh("L"); mc.assign_group(shl, "FOOT_L")
    shr = mc.shoes_mesh("R"); mc.assign_group(shr, "FOOT_R")
    parts = [head, shl, shr]
    # face parts (in place, follow the head bone)
    face = []
    for n, fn in [("el", lambda: None)]:
        pass
    for side in (1, -1):
        p, d = mc.on_head(0.34 * side, 0.07, 0.94, -0.012)
        e = sphere(1.0, p, scale=(0.054, 0.074, 0.03), rot=mc.orient_to(d), mat=paint(0x1B2340, 0, 0.7), seg=24, rings=16)
        right = Vector((d.z, 0, -d.x)).normalized(); up = d.cross(right).normalized() * -1
        base = Vector(p) + d * 0.026
        s1 = sphere(0.017, tuple(base + right * (-0.018 * side) + up * 0.028), mat=paint(0xFFFFFF, 0, 0.0, 0.8), seg=10, rings=8)
        s2 = sphere(0.0085, tuple(base + right * (0.015 * side) + up * -0.022), mat=paint(0xFFFFFF, 0, 0.0, 0.8), seg=8, rings=6)
        a, _ = mc.on_head(0.25 * side, 0.36, 0.9, 0.006); b, _ = mc.on_head(0.44 * side, 0.31, 0.85, 0.006)
        brow = capsule((a[0], a[1] + 0.03, a[2]), (b[0], b[1] + 0.05, b[2]), 0.013, mat=paint(0x4A3030, 0, 0.1))
        bp, bd = mc.on_head(0.55 * side, -0.16, 0.82, -0.004)
        blush = sphere(1.0, bp, scale=(0.05, 0.028, 0.012), rot=mc.orient_to(bd), mat=paint(0xFF8FA6, 0, 0.05), seg=14, rings=8)
        face += [e, s1, s2, brow, blush]
    # big grin: a "D" shaped open mouth (top half removed) with a tongue
    import bmesh as _bm
    mp, md = mc.on_head(0.0, -0.3, 0.95, -0.01)
    mouth = sphere(1.0, (0, 0, 0), scale=(0.095, 0.07, 0.03), mat=paint(0x6E1E2E, 0, 0.3), seg=24, rings=14)
    bm = _bm.new(); bm.from_mesh(mouth.data)
    _bm.ops.delete(bm, geom=[v for v in bm.verts if v.co.z > 0.004], context='VERTS')
    bm.to_mesh(mouth.data); bm.free()
    transform(mouth, loc=mp, rot=mc.orient_to(md))
    face.append(mouth)
    tp, td = mc.on_head(0.0, -0.43, 0.9, -0.005)
    face.append(sphere(1.0, tp, scale=(0.045, 0.02, 0.012), rot=mc.orient_to(td), mat=paint(0xFF7A8A, 0, 0.3), seg=12, rings=8))
    for f in face:
        mc.assign_group(f, "HEAD")
    for m in list(suit.modifiers):
        if m.type != 'ARMATURE':
            suit.modifiers.remove(m)
    body = join([suit] + parts + face, "dash")
    if not any(m.type == 'ARMATURE' for m in body.modifiers):
        mod = body.modifiers.new("arm", 'ARMATURE'); mod.object = arm
    body.parent = arm
    shade_materials(body, {1: char_primary, 2: char_secondary, 3: skin, 4: shoe})
    for p_ in body.data.polygons:
        p_.use_smooth = True
    # pose: bones rotate in their own local frames (Y along the bone)
    bpy.context.view_layer.objects.active = arm
    bpy.ops.object.mode_set(mode='POSE')
    for name, rot in (pose or {}).items():
        pb = arm.pose.bones[name]
        pb.rotation_mode = 'XYZ'
        pb.rotation_euler = Euler(rot, 'XYZ')
    bpy.ops.object.mode_set(mode='OBJECT')
    return arm, body


def duck(scale=1.0):
    d = mh.duck()
    shade_materials(d, {1: 0xFFD23F, 2: 0xFF8A3D})
    return d


def noodle():
    n = mh.noodle()
    shade_materials(n, {1: 0xFF5FA8, 2: 0xFFFFFF})
    return n


def coin():
    c = mcourse.coin()
    shade_materials(c, {})
    return c


def droplet(loc, r):
    o = sphere(r, loc, scale=(1, 1.25, 1), mat=paint(0xE8FBFF, 0, 0.9), seg=16, rings=10)
    shade_materials(o, {})
    o.data.materials[0].node_tree.nodes["Principled BSDF"].inputs["Transmission Weight"].default_value = 0.4
    return o


def setup_render(res_x, res_y, samples=96):
    scn = bpy.context.scene
    scn.render.engine = 'CYCLES'
    scn.cycles.samples = samples
    scn.cycles.use_denoising = True
    scn.cycles.device = 'CPU'
    scn.render.resolution_x = res_x
    scn.render.resolution_y = res_y
    scn.render.film_transparent = True
    scn.view_settings.view_transform = 'Standard'
    scn.view_settings.look = 'None'
    w = bpy.data.worlds.new("w")
    scn.world = w
    w.use_nodes = True
    bg = w.node_tree.nodes["Background"]
    bg.inputs[0].default_value = (0.55, 0.85, 1.0, 1)
    bg.inputs[1].default_value = 1.1
    sun = bpy.data.lights.new("sun", 'SUN'); sun.energy = 3.8; sun.angle = math.radians(8)
    so = bpy.data.objects.new("sun", sun); so.rotation_euler = (math.radians(40), math.radians(-20), math.radians(-35))
    bpy.context.collection.objects.link(so)
    fill = bpy.data.lights.new("rim", 'AREA'); fill.energy = 600; fill.size = 6; fill.color = (1.0, 0.85, 0.7)
    fo = bpy.data.objects.new("rim", fill); fo.location = (-3, 4, 4); fo.rotation_euler = (math.radians(-60), math.radians(-30), 0)
    bpy.context.collection.objects.link(fo)


def camera(loc, target, lens=50, ortho=0.0):
    cd = bpy.data.cameras.new("cam")
    cd.lens = lens
    if ortho > 0:
        cd.type = 'ORTHO'; cd.ortho_scale = ortho
    c = bpy.data.objects.new("cam", cd)
    bpy.context.collection.objects.link(c)
    c.location = loc
    d = Vector(target) - Vector(loc)
    c.rotation_euler = d.to_track_quat('-Z', 'Y').to_euler()
    bpy.context.scene.camera = c
    return c


LEAP = {
    # bone-local rotations (radians); found by eye for a joyful leap
    "ARM_L": (math.radians(-20), 0.0, math.radians(-62)), "ARM_R": (math.radians(-20), 0.0, math.radians(62)),
    "HAND_L": (0.0, 0.0, math.radians(-30)), "HAND_R": (0.0, 0.0, math.radians(30)),
    "THIGH_L": (math.radians(55), 0, 0), "SHIN_L": (math.radians(-85), 0, 0),
    "THIGH_R": (math.radians(-25), 0, 0), "SHIN_R": (math.radians(-35), 0, 0),
    "HEAD": (math.radians(-8), 0, math.radians(6)),
}


def splash_ring(cx, cy, z, r, n=16):
    # droplets spraying outward in an arc, plus a foam ring on the water
    for k in range(n):
        a = 2 * math.pi * k / n
        rr = r * (1.0 + 0.25 * ((k * 5) % 3))
        h = 0.1 + 0.35 * abs(math.sin(a * 1.5 + 0.4))
        droplet((cx + math.cos(a) * rr, cy + math.sin(a) * rr * 0.55, z + h), 0.03 + 0.02 * ((k * 3) % 2))
    ring = torus(r * 0.75, 0.06, (cx, z, -cy), mat=paint(0xFFFFFF, 0, 0.6), seg=32, side=8)
    shade_materials(ring, {})


def render_icon():
    reset()
    setup_render(1024, 1024, 160)
    arm, body = dashling(0x19C6C2, 0xF6FFFE, pose=LEAP)
    arm.rotation_euler = (math.radians(4), 0, math.radians(-12))
    arm.location = (0, 0, 0.12)
    d = duck(); d.location = (0.62, -0.25, 0.02); d.rotation_euler = (0, 0, math.radians(-35)); d.scale = (0.3, 0.3, 0.3)
    splash_ring(0.0, 0.0, -0.15, 0.45)
    for (x, z, r) in [(-0.62, 1.05, 0.045), (0.6, 1.25, 0.04), (-0.45, 1.5, 0.035)]:
        droplet((x, -0.1, z), r)
    c1 = coin(); c1.location = (-0.58, 0.05, 1.32); c1.rotation_euler = (0, 0, math.radians(28)); c1.scale = (0.42,) * 3
    camera((0.1, -4.4, 1.05), (0.04, 0, 0.72), lens=82)
    bpy.context.scene.render.filepath = os.path.join(OUT, "icon_fg.png")
    bpy.ops.render.render(write_still=True)


def render_hero():
    reset()
    setup_render(1600, 900, 96)
    looks = [(0x19C6C2, 0xF6FFFE), (0xFF6B57, 0xFFE9B8), (0xFFD23F, 0xFF8A3D)]
    xs = [0.0, -1.5, 1.6]
    for i, ((p, s), x) in enumerate(zip(looks, xs)):
        arm, body = dashling(p, s, pose=LEAP if i == 0 else {"ARM_L": (math.radians(40), 0, 0), "ARM_R": (math.radians(-40), 0, 0), "THIGH_L": (math.radians(40), 0, 0), "THIGH_R": (math.radians(-40), 0, 0), "SHIN_L": (math.radians(-50), 0, 0)})
        arm.location = (x, 0.6 * abs(x), 0.1 if i == 0 else -0.05)
        arm.rotation_euler = (0, 0, math.radians(-10 + i * 10))
    d = duck(); d.location = (2.9, 1.5, 0.6); d.scale = (0.8,) * 3; d.rotation_euler = (0, 0, math.radians(-140))
    n = noodle(); n.location = (-3.3, 1.2, 0.5); n.scale = (2.0, 1.2, 1.2); n.rotation_euler = (0, 0, math.radians(20))
    for k in range(5):
        c = coin(); c.location = (-2.6 + k * 0.6, 0.8, 1.6 + 0.25 * math.sin(k)); c.rotation_euler = (0, 0, math.radians(15 * k - 30)); c.scale = (0.5,) * 3
    camera((0.0, -7.2, 1.6), (0.0, 0.4, 0.75), lens=40)
    bpy.context.scene.render.filepath = os.path.join(OUT, "hero.png")
    bpy.ops.render.render(write_still=True)


if WHAT in ("icon", "all"):
    render_icon()
if WHAT in ("hero", "all"):
    render_hero()
print("rendered", WHAT)
