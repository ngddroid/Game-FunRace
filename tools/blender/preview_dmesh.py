"""
Renders .dmesh files (as the engine would see their vertex data: colour x AO, tints shown with
sample colours) into a PNG contact sheet for quick visual checks.

    Blender -b --factory-startup --python tools/blender/preview_dmesh.py -- out.png name1 name2 ...
"""
import bpy
import math
import os
import struct
import sys
from mathutils import Vector

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
MODELS = os.path.join(ROOT, "app", "src", "main", "assets", "models")

argv = sys.argv[sys.argv.index("--") + 1:]
out_path = argv[0]
names = argv[1:]
TINTS = {1: (0.10, 0.78, 0.76), 2: (0.97, 0.97, 0.92), 3: (1.0, 0.85, 0.7), 4: (1.0, 1.0, 1.0)}


def load(name):
    data = open(os.path.join(MODELS, name + ".dmesh"), "rb").read()
    off = 4
    ver, flags, vc, ic = struct.unpack_from("<IIII", data, off); off += 16
    off += 24
    stride = 24
    if flags & 1:
        nb = struct.unpack_from("<I", data, off)[0]; off += 4 + nb * 16
        stride = 32
    pos, cols = [], []
    for i in range(vc):
        x, y, z = struct.unpack_from("<3f", data, off)
        r, g, b, a = struct.unpack_from("<4B", data, off + 16)
        t, gl, em, wb = struct.unpack_from("<4B", data, off + 20)
        off += stride
        c = Vector((r / 255, g / 255, b / 255))
        sel = (t + 25) // 51
        if sel in TINTS:
            c = Vector((c.x * TINTS[sel][0], c.y * TINTS[sel][1], c.z * TINTS[sel][2]))
        ao = a / 255
        pos.append((x, -z, y))
        cols.append((c.x * ao, c.y * ao, c.z * ao, 1.0))
    idx = struct.unpack_from("<%dI" % ic, data, off)
    me = bpy.data.meshes.new(name)
    faces = [tuple(idx[k:k + 3]) for k in range(0, ic, 3)]
    me.from_pydata(pos, [], faces)
    attr = me.color_attributes.new("Col", 'FLOAT_COLOR', 'POINT')
    for i, c in enumerate(cols):
        attr.data[i].color = c
    for p in me.polygons:
        p.use_smooth = True
    o = bpy.data.objects.new(name, me)
    bpy.context.collection.objects.link(o)
    return o


bpy.ops.wm.read_factory_settings(use_empty=True)
mat = bpy.data.materials.new("vc")
mat.use_nodes = True
nt = mat.node_tree
bsdf = nt.nodes.get("Principled BSDF")
vc = nt.nodes.new("ShaderNodeVertexColor")
vc.layer_name = "Col"
nt.links.new(vc.outputs["Color"], bsdf.inputs["Base Color"])
bsdf.inputs["Roughness"].default_value = 0.55

cols = max(1, min(4, len(names)))
rows = (len(names) + cols - 1) // cols
objs = []
for k, n in enumerate(names):
    parts = [load(x) for x in n.split("+")]
    if len(parts) > 1:
        bpy.ops.object.select_all(action='DESELECT')
        for pp in parts:
            pp.select_set(True)
        bpy.context.view_layer.objects.active = parts[0]
        bpy.ops.object.join()
    o = parts[0]
    o.data.materials.append(mat)
    bb = [Vector(v) for v in o.bound_box]
    size = max((max(v[i] for v in bb) - min(v[i] for v in bb)) for i in range(3))
    s = 1.6 / max(size, 1e-3)
    o.scale = (s, s, s)
    cx = sum(v.x for v in bb) / 8; cy = sum(v.y for v in bb) / 8; cz = sum(v.z for v in bb) / 8
    o.location = (-cx * s + (k % cols) * 2.2, -cy * s, -cz * s - (k // cols) * 2.2)
    o.rotation_euler = (0, 0, math.radians(-25))
    objs.append(o)

scn = bpy.context.scene
scn.render.engine = 'CYCLES'
scn.cycles.samples = 24
scn.cycles.device = 'CPU'
scn.render.resolution_x = (360 if len(names) > 1 else 640) * cols
scn.render.resolution_y = (360 if len(names) > 1 else 640) * rows
scn.render.film_transparent = False
world = bpy.data.worlds.new("w")
scn.world = world
world.use_nodes = True
world.node_tree.nodes["Background"].inputs[0].default_value = (0.75, 0.88, 1.0, 1)
world.node_tree.nodes["Background"].inputs[1].default_value = 0.9
cam_data = bpy.data.cameras.new("cam")
cam_data.type = 'ORTHO'
cam_data.ortho_scale = 2.25 * max(cols, rows)
cam = bpy.data.objects.new("cam", cam_data)
bpy.context.collection.objects.link(cam)
cx = (cols - 1) * 2.2 / 2
cz = -(rows - 1) * 2.2 / 2
cam.location = (cx, -12, cz + 1.2)
cam.rotation_euler = (math.radians(84), 0, 0)
scn.camera = cam
sun = bpy.data.lights.new("sun", 'SUN')
sun.energy = 3.5
so = bpy.data.objects.new("sun", sun)
so.rotation_euler = (math.radians(50), math.radians(10), math.radians(30))
bpy.context.collection.objects.link(so)
scn.render.filepath = out_path
bpy.ops.render.render(write_still=True)
print("preview ->", out_path)
