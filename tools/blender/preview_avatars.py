"""
Renders full avatars (body + hair/accessories + face) with their real catalog colours, front and
back, into one contact sheet. The spec is a JSON list:
    [{"name": "Kai", "parts": [{"mesh": "human", "tints": {"1": "FF8A3D", "2": "2B3A66", "3": "F2C29B", "4": "FFFFFF"}},
                               {"mesh": "hair_spiky", "tints": {"1": "3B2A20", "2": "FF5FA8"}}]}, ...]

    Blender -b --factory-startup --python tools/blender/preview_avatars.py -- out.png spec.json
    Blender ... preview_avatars.py -- out.png spec.json portrait   # first avatar only, front, transparent

The portrait mode renders the launch-screen avatar (see tools/make_icons.py --splash).
"""
import bpy
import json
import math
import os
import struct
import sys
from mathutils import Vector

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
MODELS = os.path.join(ROOT, "app", "src", "main", "assets", "models")
FACE = ["face_eye_l", "face_eye_r", "face_brow_l", "face_brow_r", "face_blush_l", "face_blush_r", "face_mouth_smile"]

argv = sys.argv[sys.argv.index("--") + 1:]
out_path, spec_path = argv[0], argv[1]
PORTRAIT = len(argv) > 2 and argv[2] == "portrait"
spec = json.load(open(spec_path))
if PORTRAIT:
    spec = spec[:1]


def lin(v):
    return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4


def hexf(h):
    v = int(h, 16)
    return ((v >> 16 & 255) / 255.0, (v >> 8 & 255) / 255.0, (v & 255) / 255.0)


# Dashling head space -> human head (must match Bodies.HUMAN in CharacterRig.kt)
FIT = {"head": ((0.0, 1.0, 0.0), (0.0, 1.235, 0.0), (0.606, 0.636, 0.59))}


def load(name, tints, dx, dz, yaw, fit=None):
    data = open(os.path.join(MODELS, name + ".dmesh"), "rb").read()
    off = 4
    _, flags, vc, ic = struct.unpack_from("<IIII", data, off); off += 16 + 24
    stride = 24
    if flags & 1:
        nb = struct.unpack_from("<I", data, off)[0]; off += 4 + nb * 16
        stride = 32
    pos, cols = [], []
    cy, sy = math.cos(yaw), math.sin(yaw)
    for _ in range(vc):
        x, y, z = struct.unpack_from("<3f", data, off)
        if fit:
            src, dst, sc = FIT[fit]
            x, y, z = dst[0] + (x - src[0]) * sc[0], dst[1] + (y - src[1]) * sc[1], dst[2] + (z - src[2]) * sc[2]
        r, g, b, a = struct.unpack_from("<4B", data, off + 16)
        t = struct.unpack_from("<B", data, off + 20)[0]
        off += stride
        c = [r / 255, g / 255, b / 255]
        sel = (t + 25) // 51
        if str(sel) in tints:
            k = hexf(tints[str(sel)])
            c = [c[0] * k[0], c[1] * k[1], c[2] * k[2]]
        ao = a / 255
        rx, rz = x * cy + z * sy, -x * sy + z * cy
        pos.append((rx + dx, -rz, y + dz))
        # colours are sRGB (as the game shades them); Blender's float colour attribute is linear
        cols.append(tuple(lin(v * ao) for v in c) + (1.0,))
    idx = struct.unpack_from("<%dI" % ic, data, off)
    me = bpy.data.meshes.new(name)
    me.from_pydata(pos, [], [tuple(idx[k:k + 3]) for k in range(0, ic, 3)])
    attr = me.color_attributes.new("Col", 'FLOAT_COLOR', 'POINT')
    for i, c in enumerate(cols):
        attr.data[i].color = c
    for p in me.polygons:
        p.use_smooth = True
    o = bpy.data.objects.new(name, me)
    bpy.context.collection.objects.link(o)
    o.data.materials.append(MAT)
    return o


bpy.ops.wm.read_factory_settings(use_empty=True)
MAT = bpy.data.materials.new("vc")
MAT.use_nodes = True
nt = MAT.node_tree
bsdf = nt.nodes.get("Principled BSDF")
vcn = nt.nodes.new("ShaderNodeVertexColor")
vcn.layer_name = "Col"
nt.links.new(vcn.outputs["Color"], bsdf.inputs["Base Color"])
bsdf.inputs["Roughness"].default_value = 0.6

cols = 1 if PORTRAIT else 6
step = 1.15
ROW = 2.1  # vertical spacing between rows (m)
n = len(spec)
rows = 1 if PORTRAIT else ((n + cols - 1) // cols) * 2  # a front row and a back row for every group
for k, av in enumerate(spec):
    grp, col = divmod(k, cols)
    for view in ((0,) if PORTRAIT else (0, 1)):
        dx = col * step
        dz = -(grp * 2 + view) * ROW
        yaw = 0.0 if view == 0 else math.pi
        for part in av["parts"]:
            load(part["mesh"], part.get("tints", {}), dx, dz, yaw + (0.35 if view == 0 else -0.35), part.get("fit"))
        if view == 0:
            for f in FACE:
                load(f, {}, dx, dz, yaw + 0.35, av.get("faceFit"))

scn = bpy.context.scene
scn.render.engine = 'CYCLES'
scn.cycles.samples = 64 if PORTRAIT else 20
scn.cycles.device = 'CPU'
scn.render.resolution_x = 900 if PORTRAIT else 220 * cols
scn.render.resolution_y = 1640 if PORTRAIT else int(330 * ROW / 1.75) * rows
scn.render.film_transparent = PORTRAIT
world = bpy.data.worlds.new("w")
scn.world = world
world.use_nodes = True
world.node_tree.nodes["Background"].inputs[0].default_value = (0.86, 0.9, 0.95, 1)
world.node_tree.nodes["Background"].inputs[1].default_value = 0.45 if PORTRAIT else 0.75
cam_data = bpy.data.cameras.new("cam")
cam_data.type = 'ORTHO'
W, H = cols * step, rows * ROW
rx, ry = scn.render.resolution_x, scn.render.resolution_y
cam_data.ortho_scale = 1.03 * (max(H, W * ry / rx) if ry >= rx else max(W, H * rx / ry))
cam = bpy.data.objects.new("cam", cam_data)
bpy.context.collection.objects.link(cam)
cam.location = ((cols - 1) * step / 2, -10, -(rows - 1) * ROW / 2 + 0.75)
cam.rotation_euler = (math.radians(88), 0, 0)
if PORTRAIT:
    # frame the one avatar from its real bounds (hair included) with a little margin
    pts = [o.matrix_world @ v.co for o in bpy.context.scene.objects if o.type == 'MESH' for v in o.data.vertices]
    z0, z1 = min(p.z for p in pts), max(p.z for p in pts)
    x0, x1 = min(p.x for p in pts), max(p.x for p in pts)
    # the camera looks 2 degrees down from 10 m away: lift it so the view centre lands on the avatar
    cam.location = ((x0 + x1) / 2, -10, (z0 + z1) / 2 + 10 * math.tan(math.radians(2)))
    cam_data.ortho_scale = 1.08 * max(z1 - z0, (x1 - x0) * ry / rx)
scn.camera = cam
sun = bpy.data.lights.new("sun", 'SUN')
sun.energy = 2.0 if PORTRAIT else 2.6
so = bpy.data.objects.new("sun", sun)
so.rotation_euler = (math.radians(55), math.radians(10), math.radians(25))
bpy.context.collection.objects.link(so)
scn.view_settings.view_transform = 'Standard'
scn.render.filepath = out_path
bpy.ops.render.render(write_still=True)
print("avatars ->", out_path)
