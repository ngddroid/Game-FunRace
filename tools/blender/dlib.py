"""
Splash Dash asset toolkit for Blender (headless).

All helpers take positions/rotations in *game space* (GL: +Y up, +Z forward/toward camera,
+X = character's left) and convert to Blender space (Z up) internally:
    GL (x, y, z)  <->  Blender (x, -z, y)

Materials are "paints": colour + per-vertex material bytes the engine understands
(tint slot, gloss, emissive, wobble). AO is ray-traced per vertex and stored in colour alpha.
"""
import bpy
import bmesh
import math
import os
import random
import struct
from mathutils import Vector, Matrix
from mathutils.bvhtree import BVHTree

TINT_NONE, TINT_A, TINT_B, TINT_SKIN, TINT_SHOE = 0, 1, 2, 3, 4


# ----------------------------------------------------------------------------- space helpers
def B(x, y, z):
    """GL -> Blender vector."""
    return Vector((x, -z, y))


def rotG(rx=0.0, ry=0.0, rz=0.0):
    """Euler rotation about GL axes (X first, then Y, then Z) as a Blender 4x4 matrix."""
    return Matrix.Rotation(-rz, 4, 'Y') @ Matrix.Rotation(ry, 4, 'Z') @ Matrix.Rotation(rx, 4, 'X')


def hexrgb(h):
    return ((h >> 16 & 255) / 255.0, (h >> 8 & 255) / 255.0, (h & 255) / 255.0)


# ----------------------------------------------------------------------------- scene
def reset():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    _paints.clear()


def active(obj):
    bpy.ops.object.select_all(action='DESELECT')
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj


def apply_mods(obj):
    active(obj)
    for m in list(obj.modifiers):
        try:
            bpy.ops.object.modifier_apply(modifier=m.name)
        except RuntimeError:
            obj.modifiers.remove(m)


def apply_xform(obj):
    active(obj)
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)


def delete(obj):
    bpy.data.objects.remove(obj, do_unlink=True)


# ----------------------------------------------------------------------------- paints
_paints = {}


def paint(color, tint=TINT_NONE, gloss=0.0, emis=0.0, wob=0.0):
    """Returns a material carrying engine material data. [color] is 0xRRGGBB."""
    key = (color, tint, round(gloss, 3), round(emis, 3), round(wob, 3))
    if key in _paints:
        return _paints[key]
    m = bpy.data.materials.new("p_%06x_%d_%d_%d_%d" % (color, tint, int(gloss * 100), int(emis * 100), int(wob * 100)))
    m["dm_color"] = color
    m["dm_tint"] = tint
    m["dm_gloss"] = gloss
    m["dm_emis"] = emis
    m["dm_wob"] = wob
    m.diffuse_color = (*hexrgb(color), 1.0)
    _paints[key] = m
    return m


WHITE = 0xFFFFFF


def set_paint(obj, mat):
    obj.data.materials.clear()
    obj.data.materials.append(mat)
    for p in obj.data.polygons:
        p.material_index = 0
    return obj


def paint_where(obj, mat, pred):
    """Assigns [mat] to faces whose centre (in GL coords, object local after apply) satisfies pred(x,y,z,nx,ny,nz)."""
    me = obj.data
    if mat.name not in [m.name for m in me.materials if m]:
        me.materials.append(mat)
    idx = [m.name if m else "" for m in me.materials].index(mat.name)
    for p in me.polygons:
        c = obj.matrix_world @ p.center
        n = (obj.matrix_world.to_3x3() @ p.normal).normalized()
        if pred(c.x, c.z, -c.y, n.x, n.z, -n.y):
            p.material_index = idx
    return obj


# ----------------------------------------------------------------------------- primitives (GL space)
def _finish(obj, loc, rot, mat, smooth):
    obj.matrix_world = Matrix.Translation(B(*loc)) @ rotG(*rot) @ obj.matrix_world
    apply_xform(obj)
    if mat is not None:
        set_paint(obj, mat)
    if smooth:
        shade_smooth(obj)
    return obj


def shade_smooth(obj, angle=None):
    for p in obj.data.polygons:
        p.use_smooth = True
    if angle is not None:
        try:
            obj.data.set_sharp_from_angle(angle=math.radians(angle))
        except Exception:
            pass
    return obj


def flat(obj):
    for p in obj.data.polygons:
        p.use_smooth = False
    return obj


def sphere(r, loc=(0, 0, 0), scale=(1, 1, 1), rot=(0, 0, 0), mat=None, seg=24, rings=14, smooth=True):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=seg, ring_count=rings, radius=r)
    o = bpy.context.active_object
    o.scale = (scale[0], scale[2], scale[1])
    apply_xform(o)
    return _finish(o, loc, rot, mat, smooth)


def ico(r, loc=(0, 0, 0), scale=(1, 1, 1), rot=(0, 0, 0), mat=None, sub=2, smooth=True):
    bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=sub, radius=r)
    o = bpy.context.active_object
    o.scale = (scale[0], scale[2], scale[1])
    apply_xform(o)
    return _finish(o, loc, rot, mat, smooth)


def cyl(r, h, loc=(0, 0, 0), rot=(0, 0, 0), mat=None, seg=24, smooth=True, r2=None, caps=True, bevel=0.0):
    """Vertical (GL Y) cylinder/cone of height h centred at loc. r2 = top radius for cones."""
    if r2 is None:
        bpy.ops.mesh.primitive_cylinder_add(vertices=seg, radius=r, depth=h, end_fill_type='NGON' if caps else 'NOTHING')
    else:
        bpy.ops.mesh.primitive_cone_add(vertices=seg, radius1=r, radius2=r2, depth=h, end_fill_type='NGON' if caps else 'NOTHING')
    o = bpy.context.active_object
    if bevel > 0:
        bev(o, bevel, 3)
    o = _finish(o, loc, rot, mat, smooth)
    if smooth:
        shade_smooth(o, 50)
    return o


def torus(R, r, loc=(0, 0, 0), rot=(0, 0, 0), mat=None, seg=32, side=12, smooth=True):
    """Torus lying flat (ring in the GL XZ plane)."""
    bpy.ops.mesh.primitive_torus_add(major_radius=R, minor_radius=r, major_segments=seg, minor_segments=side)
    o = bpy.context.active_object
    return _finish(o, loc, rot, mat, smooth)


def bev(obj, width, segs=3, angle=50):
    m = obj.modifiers.new("bev", 'BEVEL')
    m.width = width
    m.segments = segs
    m.limit_method = 'ANGLE'
    m.angle_limit = math.radians(angle)
    m.harden_normals = False
    apply_mods(obj)
    return obj


def rbox(sx, sy, sz, loc=(0, 0, 0), rot=(0, 0, 0), mat=None, r=0.05, segs=3, smooth=True):
    """Rounded box with full size (sx, sy, sz) in GL axes."""
    bpy.ops.mesh.primitive_cube_add(size=1.0)
    o = bpy.context.active_object
    o.scale = (sx, sz, sy)
    apply_xform(o)
    if r > 0:
        bev(o, min(r, sx / 2.01, sy / 2.01, sz / 2.01), segs)
    o = _finish(o, loc, rot, mat, False)
    if smooth:
        shade_smooth(o, 35)
    return o


def pillow(sx, sy, sz, loc=(0, 0, 0), rot=(0, 0, 0), mat=None, puff=0.35, sub=3):
    """Inflatable cushion: a subdivided box pushed toward an ellipsoid (soft vinyl look)."""
    bpy.ops.mesh.primitive_cube_add(size=1.0)
    o = bpy.context.active_object
    m = o.modifiers.new("sub", 'SUBSURF')
    m.levels = sub
    m.render_levels = sub
    apply_mods(o)
    for v in o.data.vertices:
        p = v.co
        # superellipse inflate
        n = p.normalized()
        target = Vector((math.copysign(abs(n.x) ** 0.6, n.x), math.copysign(abs(n.y) ** 0.6, n.y), math.copysign(abs(n.z) ** 0.6, n.z))) * 0.5
        v.co = p.lerp(target, puff)
    o.scale = (sx, sz, sy)
    apply_xform(o)
    return _finish(o, loc, rot, mat, True)


def capsule(p1, p2, r, mat=None, seg=16, rings=12, r2=None):
    """Capsule between GL points p1 and p2 (optionally tapered to r2 at p2)."""
    a = B(*p1)
    b = B(*p2)
    d = b - a
    L = d.length
    bpy.ops.mesh.primitive_uv_sphere_add(segments=seg, ring_count=rings, radius=1.0)
    o = bpy.context.active_object
    rr2 = r if r2 is None else r2
    for v in o.data.vertices:
        z = v.co.z
        t = 0.5 + 0.5 * (1 if z > 0 else -1)
        rad = r if z <= 0 else rr2
        v.co.x *= rad
        v.co.y *= rad
        v.co.z = z * rad + (L if z > 0 else 0.0)
    # orient +Z along d
    q = Vector((0, 0, 1)).rotation_difference(d.normalized())
    o.matrix_world = Matrix.Translation(a) @ q.to_matrix().to_4x4()
    apply_xform(o)
    if mat is not None:
        set_paint(o, mat)
    shade_smooth(o)
    return o


def tube(points, r, mat=None, seg=12, closed=False, res=6, taper=None):
    """Tube along GL points (bezier curve with round bevel) converted to mesh."""
    cu = bpy.data.curves.new("tube", 'CURVE')
    cu.dimensions = '3D'
    cu.bevel_depth = r
    cu.bevel_resolution = max(2, seg // 4)
    cu.resolution_u = res
    cu.use_fill_caps = True
    sp = cu.splines.new('POLY' if res <= 1 else 'NURBS')
    sp.points.add(len(points) - 1)
    for i, p in enumerate(points):
        v = B(*p)
        sp.points[i].co = (v.x, v.y, v.z, 1.0)
        if taper:
            sp.points[i].radius = taper[i]
    if res > 1:
        sp.order_u = min(4, len(points))
        sp.use_endpoint_u = True
    sp.use_cyclic_u = closed
    o = bpy.data.objects.new("tube", cu)
    bpy.context.collection.objects.link(o)
    active(o)
    bpy.ops.object.convert(target='MESH')
    o = bpy.context.active_object
    if mat is not None:
        set_paint(o, mat)
    shade_smooth(o)
    return o


def lathe(profile, mat=None, seg=32, loc=(0, 0, 0), rot=(0, 0, 0), smooth=True):
    """Surface of revolution around GL Y from [(radius, y), ...] (bottom to top)."""
    bm = bmesh.new()
    rings = []
    for (rad, y) in profile:
        ring = []
        for i in range(seg):
            a = 2 * math.pi * i / seg
            ring.append(bm.verts.new((math.cos(a) * rad, math.sin(a) * rad, y)))
        rings.append(ring)
    for j in range(len(rings) - 1):
        for i in range(seg):
            a, b = rings[j][i], rings[j][(i + 1) % seg]
            c, d = rings[j + 1][(i + 1) % seg], rings[j + 1][i]
            try:
                bm.faces.new((a, b, c, d))
            except ValueError:
                pass
    if profile[0][0] > 1e-4:
        bm.faces.new(list(reversed(rings[0])))
    if profile[-1][0] > 1e-4:
        bm.faces.new(rings[-1])
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-5)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    me = bpy.data.meshes.new("lathe")
    bm.to_mesh(me)
    bm.free()
    o = bpy.data.objects.new("lathe", me)
    bpy.context.collection.objects.link(o)
    o = _finish(o, loc, rot, mat, smooth)
    if smooth:
        shade_smooth(o, 40)
    return o


def extrude_shape(pts2d, depth, mat=None, loc=(0, 0, 0), rot=(0, 0, 0), bevel=0.0):
    """Extrudes a 2D polygon (GL x, y) along GL Z by depth (centred)."""
    bm = bmesh.new()
    vs = [bm.verts.new((x, 0.0, y)) for (x, y) in pts2d]
    f = bm.faces.new(vs)
    res = bmesh.ops.extrude_face_region(bm, geom=[f])
    moved = [e for e in res['geom'] if isinstance(e, bmesh.types.BMVert)]
    for v in moved:
        v.co.y -= depth
    for v in bm.verts:
        v.co.y += depth / 2
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    me = bpy.data.meshes.new("ext")
    bm.to_mesh(me)
    bm.free()
    o = bpy.data.objects.new("ext", me)
    bpy.context.collection.objects.link(o)
    if bevel > 0:
        bev(o, bevel, 2)
    return _finish(o, loc, rot, mat, False)


def join(objs, name="obj"):
    objs = [o for o in objs if o is not None]
    bpy.ops.object.select_all(action='DESELECT')
    for o in objs:
        o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    if len(objs) > 1:
        bpy.ops.object.join()
    o = bpy.context.active_object
    o.name = name
    return o


def mirror_x(obj):
    """Duplicates obj mirrored across GL X and joins."""
    active(obj)
    bpy.ops.object.duplicate()
    d = bpy.context.active_object
    d.scale.x = -1
    apply_xform(d)
    bm = bmesh.new()
    bm.from_mesh(d.data)
    bmesh.ops.reverse_faces(bm, faces=bm.faces)
    bm.to_mesh(d.data)
    bm.free()
    return join([obj, d], obj.name)


def move(obj, dx=0, dy=0, dz=0):
    obj.matrix_world = Matrix.Translation(B(dx, dy, dz)) @ obj.matrix_world
    apply_xform(obj)
    return obj


def transform(obj, loc=(0, 0, 0), rot=(0, 0, 0), scale=(1, 1, 1)):
    S = Matrix.Diagonal((scale[0], scale[2], scale[1], 1.0))
    obj.matrix_world = Matrix.Translation(B(*loc)) @ rotG(*rot) @ S @ obj.matrix_world
    apply_xform(obj)
    return obj


def subdiv(obj, levels=1):
    m = obj.modifiers.new("sub", 'SUBSURF')
    m.levels = levels
    apply_mods(obj)
    return obj


def decimate(obj, ratio):
    m = obj.modifiers.new("dec", 'DECIMATE')
    m.ratio = ratio
    apply_mods(obj)
    return obj


def displace_noise(obj, amount, scale=1.0, seed=1):
    rnd = random.Random(seed)
    off = Vector((rnd.random() * 100, rnd.random() * 100, rnd.random() * 100))
    from mathutils import noise
    for v in obj.data.vertices:
        n = noise.noise((v.co * scale) + off)
        v.co += v.normal * n * amount
    return obj


# ----------------------------------------------------------------------------- AO
def bake_ao(obj, samples=24, dist=0.5, strength=0.85, extra=None, seed=7):
    """Ray-traced ambient occlusion per vertex, stored for the exporter.
    extra: other objects that occlude (e.g. ground plane) but are not exported."""
    dg = bpy.context.evaluated_depsgraph_get()
    targets = [obj] + (extra or [])
    bvhs = [BVHTree.FromObject(t, dg) for t in targets]
    rnd = random.Random(seed)
    dirs = []
    for i in range(samples):
        # cosine-weighted hemisphere around +Z
        u = (i + rnd.random()) / samples
        v = rnd.random()
        r = math.sqrt(u)
        th = 2 * math.pi * v
        dirs.append(Vector((r * math.cos(th), r * math.sin(th), math.sqrt(max(0.0, 1 - u)))))
    me = obj.data
    ao = []
    mw = obj.matrix_world
    for vert in me.vertices:
        n = (mw.to_3x3() @ vert.normal).normalized()
        p = mw @ vert.co + n * 0.004
        rot = Vector((0, 0, 1)).rotation_difference(n).to_matrix()
        hit = 0.0
        for d in dirs:
            wd = rot @ d
            for bvh in bvhs:
                loc, nrm, idx, dd = bvh.ray_cast(p, wd, dist)
                if loc is not None:
                    hit += 1.0 - (dd / dist) * 0.5
                    break
        occ = hit / samples
        ao.append(max(0.0, 1.0 - occ * strength))
    obj["dm_ao"] = ao
    return obj


# ----------------------------------------------------------------------------- export
def export(obj, name, outdir, arm=None, bone_order=None):
    """Writes models/<name>.dmesh. With [arm], exports a skinned mesh using vertex groups named
    after bones in [bone_order]."""
    dg = bpy.context.evaluated_depsgraph_get()
    ev = obj.evaluated_get(dg)
    me = ev.to_mesh()
    me.calc_loop_triangles()
    mw = obj.matrix_world
    nm = mw.to_3x3().inverted().transposed()
    normals = me.corner_normals
    mats = [ms.material for ms in obj.material_slots]
    ao = obj.get("dm_ao")
    skinned = arm is not None
    group_bone = {}
    if skinned:
        for g in obj.vertex_groups:
            if g.name in bone_order:
                group_bone[g.index] = bone_order.index(g.name)
    verts = []
    index = {}
    idx_out = []
    mn = [1e9] * 3
    mx = [-1e9] * 3
    for tri in me.loop_triangles:
        mat = mats[tri.material_index] if tri.material_index < len(mats) else None
        if mat is not None and "dm_color" in mat:
            col = mat["dm_color"]
            tint = int(mat["dm_tint"]); gloss = float(mat["dm_gloss"]); emis = float(mat["dm_emis"]); wob = float(mat["dm_wob"])
        else:
            col, tint, gloss, emis, wob = 0xFF00FF, 0, 0.0, 0.0, 0.0
        tri_idx = []
        for li, vi in zip(tri.loops, tri.vertices):
            co = mw @ me.vertices[vi].co
            n = (nm @ normals[li].vector).normalized()
            gx, gy, gz = co.x, co.z, -co.y
            nx, ny, nz = n.x, n.z, -n.y
            a = ao[vi] if ao is not None and vi < len(ao) else 1.0
            jw = (0, 0, 0, 0, 255, 0, 0, 0)
            if skinned:
                gs = []
                for g in me.vertices[vi].groups:
                    if g.group in group_bone and g.weight > 0.001:
                        gs.append((g.weight, group_bone[g.group]))
                gs.sort(reverse=True)
                gs = gs[:4]
                if not gs:
                    gs = [(1.0, 0)]
                tot = sum(w for w, _ in gs)
                ws = [int(round(w / tot * 255)) for w, _ in gs]
                diff = 255 - sum(ws)
                ws[0] += diff
                js = [b for _, b in gs]
                while len(js) < 4:
                    js.append(0); ws.append(0)
                jw = (js[0], js[1], js[2], js[3], ws[0], ws[1], ws[2], ws[3])
            key = (round(gx, 5), round(gy, 5), round(gz, 5), round(nx, 3), round(ny, 3), round(nz, 3), col, tint, round(gloss, 2), round(emis, 2), round(wob, 2), round(a, 2), jw)
            if key not in index:
                index[key] = len(verts)
                verts.append((gx, gy, gz, nx, ny, nz, col, a, tint, gloss, emis, wob, jw))
                for k, val in enumerate((gx, gy, gz)):
                    mn[k] = min(mn[k], val); mx[k] = max(mx[k], val)
            tri_idx.append(index[key])
        idx_out.extend(tri_idx)
    ev.to_mesh_clear()
    os.makedirs(outdir, exist_ok=True)
    path = os.path.join(outdir, name + ".dmesh")
    with open(path, "wb") as f:
        f.write(b"DMSH")
        f.write(struct.pack("<IIII", 1, 1 if skinned else 0, len(verts), len(idx_out)))
        f.write(struct.pack("<6f", *mn, *mx))
        if skinned:
            f.write(struct.pack("<I", len(bone_order)))
            for bn in bone_order:
                b = arm.data.bones[bn]
                h = arm.matrix_world @ b.head_local
                parent = bone_order.index(b.parent.name) if b.parent else -1
                f.write(struct.pack("<i3f", parent, h.x, h.z, -h.y))
        for (gx, gy, gz, nx, ny, nz, col, a, tint, gloss, emis, wob, jw) in verts:
            f.write(struct.pack("<3f", gx, gy, gz))
            f.write(struct.pack("<4b", int(round(nx * 127)), int(round(ny * 127)), int(round(nz * 127)), 0))
            f.write(struct.pack("<4B", col >> 16 & 255, col >> 8 & 255, col & 255, int(round(max(0, min(1, a)) * 255))))
            f.write(struct.pack("<4B", tint * 51, int(round(gloss * 255)), int(round(min(1, emis) * 255)), int(round(min(1, wob) * 255))))
            if skinned:
                f.write(struct.pack("<8B", *jw))
        f.write(struct.pack("<%dI" % len(idx_out), *idx_out))
    return len(verts), len(idx_out) // 3


def build_and_export(name, fn, outdir, ao=True, ao_dist=0.5, ao_samples=20, ground=None):
    """Clears the scene, runs fn() -> object, bakes AO, exports. Returns (verts, tris)."""
    reset()
    o = fn()
    extra = []
    if ground is not None:
        bpy.ops.mesh.primitive_plane_add(size=50, location=B(0, ground, 0))
        extra = [bpy.context.active_object]
    if ao:
        bake_ao(o, samples=ao_samples, dist=ao_dist, extra=extra)
    return export(o, name, outdir)
