"""
Builds every 3D model of Splash Dash into app/src/main/assets/models/*.dmesh.

Run from the project root:
    ~/Applications/Blender.app/Contents/MacOS/Blender -b --factory-startup \
        --python tools/blender/build_assets.py -- [module ...] [--only name,name]

Modules: character, course, hazards, env. With no module, everything is rebuilt.
Deterministic: same scripts -> same meshes.
"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "models")

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
only = None
mods = []
i = 0
while i < len(argv):
    if argv[i] == "--only":
        only = set(argv[i + 1].split(","))
        i += 2
    else:
        mods.append(argv[i])
        i += 1
if not mods:
    mods = ["character", "course", "hazards", "env"]

t0 = time.time()
total = {}
for m in mods:
    mod = __import__("m_" + m)
    print("== building", m)
    stats = mod.build_all(OUT, only)
    total.update(stats)
    for k, v in sorted(stats.items()):
        if isinstance(v, tuple):
            print("  %-26s %6d verts %6d tris" % (k, v[0], v[1]))
print("built %d models in %.1fs -> %s" % (len(total), time.time() - t0, OUT))
