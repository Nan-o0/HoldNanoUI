"""Draws the dumped mob geometry to a picture, so it can be looked at.

Everything about these models has been checked by counting so far: the right number of parts,
the right number of cubes, a texture file that exists. None of that would notice a model built
inside out, a texture mapped to the wrong face, or a copper golem the size of a house. The
only way to know is to look at one.

This is the same data the mod loads - the geometry read out of the 26.2 client and the texture
next to it - put through the same cuboid unwrap Minecraft uses, and drawn.
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image

RES = "C:/Users/cupy/Desktop/Dev/Work/Minecraft/HOLD SMP/NanoUI-1.21/src/main/resources"
OUT = ("C:/Users/cupy/AppData/Local/Temp/claude/C--Users-cupy-Downloads/"
       "78acc219-efd0-4f81-81e6-7a525ecc8919/scratchpad/render")

SIZE = 320


def rotation(x, y, z):
    """Minecraft applies part rotation in Z, Y, X order."""
    cx, sx = math.cos(x), math.sin(x)
    cy, sy = math.cos(y), math.sin(y)
    cz, sz = math.cos(z), math.sin(z)
    rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return rx @ ry @ rz


def faces_of(cube):
    """The six faces of a cuboid, with the texture rectangle Minecraft assigns each one.

    <p>The unwrap is fixed: a strip of the four sides with the top and bottom above it, laid
    out from the cube's texture offset. Getting this wrong is exactly the kind of thing that
    only shows up as a face wearing another face's picture.
    """
    ox, oy, oz = cube["origin"]
    dx, dy, dz = cube["size"]
    gx, gy, gz = cube.get("grow", [0, 0, 0])
    u, v = cube["uv"]
    x0, y0, z0 = ox - gx, oy - gy, oz - gz
    x1, y1, z1 = ox + dx + gx, oy + dy + gy, oz + dz + gz

    def q(a, b, c, d, uv):
        return {"pts": [a, b, c, d], "uv": uv}

    return [
        # west, east: the two ends of the strip
        q((x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0), (u, v + dz, dz, dy)),
        q((x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1),
          (u + dz + dx, v + dz, dz, dy)),
        # north, south
        q((x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0), (u + dz, v + dz, dx, dy)),
        q((x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1),
          (u + dz + dx + dz, v + dz, dx, dy)),
        # up, down
        q((x0, y0, z1), (x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (u + dz, v, dx, dz)),
        q((x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (u + dz + dx, v, dx, dz)),
    ]


def collect(part, parent, out):
    """Walks the tree, carrying each part's transform down to its children."""
    pose = part["pose"]
    px, py, pz, rx, ry, rz = pose[:6]
    # A part can be scaled as well as moved and turned. The happy ghast is built at a quarter
    # size and scaled up by four at the root, so ignoring this drew it a quarter of the size
    # it should be.
    sx, sy, sz = (pose[6:9] if len(pose) >= 9 else (1.0, 1.0, 1.0))
    local = rotation(rx, ry, rz) @ np.diag([sx, sy, sz])
    offset = np.array([px, py, pz], dtype=float)
    world_rot = parent[0] @ local
    world_off = parent[1] + parent[0] @ offset

    for cube in part["cubes"]:
        for face in faces_of(cube):
            pts = [world_off + world_rot @ np.array(p, dtype=float) for p in face["pts"]]
            out.append({"pts": pts, "uv": face["uv"]})
    for child in part["children"].values():
        collect(child, (world_rot, world_off), out)


def draw(name, geometry, texture_path, out_path, yaw=-30.0, pitch=-12.0):
    tex = Image.open(texture_path).convert("RGBA")
    tw, th = geometry["texture"]
    # Some skins are supplied at a multiple of the model's texture size.
    sx, sy = tex.width / tw, tex.height / th
    pix = np.asarray(tex, dtype=np.uint8)

    quads = []
    collect(geometry["root"], (np.eye(3), np.zeros(3)), quads)
    if not quads:
        return None, 0

    # Model space has Y pointing down, with the feet at 24 and the head near 0. The screen
    # wants Y down too, so no flip: negating it stood every model on its head, which took a
    # creeper as a control to notice because a creeper is nearly symmetric top to bottom.
    for q in quads:
        q["pts"] = [np.array([p[0], p[1], p[2]]) for p in q["pts"]]

    ry = math.radians(yaw)
    rp = math.radians(pitch)
    view = rotation(rp, ry, 0.0)
    for q in quads:
        q["view"] = [view @ p for p in q["pts"]]

    allpts = np.array([p for q in quads for p in q["view"]])
    lo, hi = allpts.min(axis=0), allpts.max(axis=0)
    span = max(hi[0] - lo[0], hi[1] - lo[1]) or 1.0
    scale = (SIZE - 40) / span
    cx, cy = (lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2

    canvas = np.zeros((SIZE, SIZE, 4), dtype=np.uint8)
    depth = np.full((SIZE, SIZE), 1e9)

    for q in quads:
        screen = [((p[0] - cx) * scale + SIZE / 2, (p[1] - cy) * scale + SIZE / 2, p[2])
                  for p in q["view"]]
        u, v, w, h = q["uv"]
        rect = [(u * sx, v * sy), ((u + w) * sx, v * sy),
                ((u + w) * sx, (v + h) * sy), (u * sx, (v + h) * sy)]
        # Two triangles, each with a flat mapping from texture to screen.
        for tri in ((0, 1, 2), (0, 2, 3)):
            raster(canvas, depth, [screen[i] for i in tri], [rect[i] for i in tri], pix)

    img = Image.fromarray(canvas, "RGBA")
    bg = Image.new("RGBA", img.size, (32, 34, 38, 255))
    bg.alpha_composite(img)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    bg.convert("RGB").save(out_path)
    return out_path, len(quads)


def raster(canvas, depth, tri, uvs, pix):
    (x0, y0, z0), (x1, y1, z1), (x2, y2, z2) = tri
    minx, maxx = int(max(0, min(x0, x1, x2))), int(min(canvas.shape[1] - 1, max(x0, x1, x2)) + 1)
    miny, maxy = int(max(0, min(y0, y1, y2))), int(min(canvas.shape[0] - 1, max(y0, y1, y2)) + 1)
    if minx >= maxx or miny >= maxy:
        return
    det = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
    if abs(det) < 1e-9:
        return

    ys, xs = np.mgrid[miny:maxy, minx:maxx]
    px, py = xs + 0.5, ys + 0.5
    a = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) / det
    b = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) / det
    c = 1.0 - a - b
    inside = (a >= 0) & (b >= 0) & (c >= 0)
    if not inside.any():
        return

    z = a * z0 + b * z1 + c * z2
    nearer = inside & (z < depth[miny:maxy, minx:maxx])
    if not nearer.any():
        return

    tu = a * uvs[0][0] + b * uvs[1][0] + c * uvs[2][0]
    tv = a * uvs[0][1] + b * uvs[1][1] + c * uvs[2][1]
    su = np.clip(tu.astype(int), 0, pix.shape[1] - 1)
    sv = np.clip(tv.astype(int), 0, pix.shape[0] - 1)
    texel = pix[sv, su]
    opaque = nearer & (texel[..., 3] > 0)
    if not opaque.any():
        return

    # Flat shade by facing, so the shape reads instead of being one silhouette.
    nz = abs(z1 - z0) + abs(z2 - z0)
    shade = 0.72 + 0.28 * min(1.0, nz / 40.0)
    lit = texel.copy()
    lit[..., :3] = np.clip(texel[..., :3] * shade, 0, 255).astype(np.uint8)

    view = canvas[miny:maxy, minx:maxx]
    view[opaque] = lit[opaque]
    dv = depth[miny:maxy, minx:maxx]
    dv[opaque] = z[opaque]


def main(angles=((-30.0, -12.0, ""),)):
    geo = json.load(open(RES + "/holdsmp-mobs.json"))
    skins = {
        "copper_golem": "entity/copper_golem/copper_golem.png",
        "creaking": "entity/creaking/creaking.png",
        "happy_ghast": "entity/ghast/happy_ghast.png",
        "nautilus": "entity/nautilus/nautilus.png",
        "zombie_nautilus": "entity/nautilus/zombie_nautilus.png",
        "sulfur_cube": "entity/sulfur_cube/sulfur_cube_outer.png",
    }
    for mob, skin in skins.items():
        if mob not in geo:
            print("  %-18s no geometry" % mob)
            continue
        tex = RES + "/assets/holdsmp/textures/" + skin
        if not os.path.exists(tex):
            print("  %-18s no texture at %s" % (mob, skin))
            continue
        for yaw, pitch, tag in angles:
            path, quads = draw(mob, geo[mob], tex,
                               OUT + "/" + mob + tag + ".png", yaw, pitch)
            print("  %-18s %d faces -> %s" % (mob, quads, os.path.basename(path)))


main(((0.0, 0.0, '-front'), (-30.0, -12.0, '')))
