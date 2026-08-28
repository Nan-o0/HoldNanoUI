"""Builds a resource pack that shows 26.2 blocks to clients that do not have them.

The idea: a 1.21 client cannot be sent a block it has no id for, but it can be sent a block
it DOES have, and a resource pack can give that block the new block's model and texture. Pick
a carrier whose shape already matches - a stair for a stair, a full cube for a full cube - and
the client renders the right thing and collides with the right thing.

Every model and texture needed already ships inside the 26.2 client jar, so nothing is drawn
by hand. This just repackages them under the carrier's name.
"""
import io, json, os, re, shutil, zipfile

MC = ("/c/Users/cupy/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/"
      "minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar").replace("/c/", "C:/")
OUT = ("C:/Users/cupy/Desktop/Dev/Work/Minecraft/HOLD SMP/NanoEssentials/legacy-pack")

# A second, smaller pack for players who have the mod.
#
# A server pack is applied on top of whatever packs the player chose, so it always wins. Every
# minecraft: path this overrides is one the player's own texture pack no longer controls. For
# somebody running the mod most of that is wasted anyway: they get the new blocks as real
# blocks in our own namespace and never need a carrier. So they get only the part that cannot
# be done any other way - the blocks 1.21 has and draws the old way, like the redstone torch -
# and keep their own textures for everything else.
SLIM = ("C:/Users/cupy/Desktop/Dev/Work/Minecraft/HOLD SMP/NanoEssentials/legacy-pack-slim")

# new block -> carrier that already exists in 1.21 with the same collision shape.
#
# Full cubes ride note block NOTE values rather than instruments. Instrument is decided by the
# block underneath, so using it would repaint note blocks people already placed; note is only
# set by clicking one, and almost every note block in the world sits at note=0. Carriers start
# at note=1 so untuned note blocks keep looking like note blocks.
CUBES = ["cinnabar", "chiseled_cinnabar", "polished_cinnabar", "cinnabar_bricks",
         "sulfur", "chiseled_sulfur", "polished_sulfur", "sulfur_bricks", "potent_sulfur"]

SHAPED = {
    "cinnabar_stairs": "cut_copper_stairs",
    "polished_cinnabar_stairs": "exposed_cut_copper_stairs",
    "cinnabar_brick_stairs": "weathered_cut_copper_stairs",
    "sulfur_stairs": "oxidized_cut_copper_stairs",
    "polished_sulfur_stairs": "waxed_cut_copper_stairs",
    "sulfur_brick_stairs": "waxed_exposed_cut_copper_stairs",

    "cinnabar_slab": "cut_copper_slab",
    "polished_cinnabar_slab": "exposed_cut_copper_slab",
    "cinnabar_brick_slab": "weathered_cut_copper_slab",
    "sulfur_slab": "oxidized_cut_copper_slab",
    "polished_sulfur_slab": "waxed_cut_copper_slab",
    "sulfur_brick_slab": "waxed_exposed_cut_copper_slab",

    "cinnabar_wall": "red_nether_brick_wall",
    "polished_cinnabar_wall": "end_stone_brick_wall",
    "cinnabar_brick_wall": "prismarine_wall",
    "sulfur_wall": "granite_wall",
    "polished_sulfur_wall": "diorite_wall",
    "sulfur_brick_wall": "andesite_wall",

    "golden_dandelion": "torchflower",
    "potted_golden_dandelion": "potted_torchflower",
    "sulfur_spike": "dead_bush",
}

jar = zipfile.ZipFile(MC)
names = set(jar.namelist())


def read(path):
    return jar.read(path).decode("utf-8") if path in names else None


copied_models, copied_textures, missing = set(), set(), []


def copy_texture(ref):
    """minecraft:block/cinnabar -> assets/minecraft/textures/block/cinnabar.png"""
    ref = ref.split(":")[-1]
    src = f"assets/minecraft/textures/{ref}.png"
    if src not in names or ref in copied_textures:
        return
    dst = os.path.join(OUT, "assets/minecraft/textures", ref + ".png")
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "wb") as f:
        f.write(jar.read(src))
    copied_textures.add(ref)
    # .mcmeta carries animation frames, and a missing one turns an animated texture static.
    if src + ".mcmeta" in names:
        with open(dst + ".mcmeta", "wb") as f:
            f.write(jar.read(src + ".mcmeta"))


# Models the target client already has. Nothing here may be overwritten.
#
# This pack writes into assets/minecraft, so a model copied out of 26.2 replaces the client's
# own copy of the same name. That is fine for a model only 26.2 has and catastrophic for a
# shared one: block/block gained a display slot called on_shelf in 26.x, 1.21 parses display
# slots against a fixed list, and the model fails. Every block model inherits from block/block,
# so overwriting it turned most of the game into missing textures - which is exactly what
# happened, and what this list prevents.
OLD_JAR = ("C:/Users/cupy/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/"
           "minecraft-merged/1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2/"
           "minecraft-merged-1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2.jar")
with zipfile.ZipFile(OLD_JAR) as _old:
    THEIRS = {n[len("assets/minecraft/models/"):-len(".json")]
              for n in _old.namelist() if n.startswith("assets/minecraft/models/")}
kept_theirs = set()


def copy_model(ref):
    """Copies a model and everything it points at, parents and textures both."""
    ref = ref.split(":")[-1]
    if ref in copied_models:
        return
    if ref in THEIRS:
        # Theirs is already correct for their version. Leave it alone and let the model that
        # referred to it point at the one they have.
        kept_theirs.add(ref)
        return
    src = f"assets/minecraft/models/{ref}.json"
    raw = read(src)
    if raw is None:
        return
    copied_models.add(ref)
    dst = os.path.join(OUT, "assets/minecraft/models", ref + ".json")
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "w", encoding="utf-8") as f:
        f.write(raw)

    data = json.loads(raw)
    # The parent is followed too. Most resolve to vanilla shapes the old client already has,
    # and copying those over does no harm; the ones that do not exist there are the reason
    # this recursion is here at all.
    if "parent" in data:
        copy_model(data["parent"])
    for tex in (data.get("textures") or {}).values():
        if isinstance(tex, str) and not tex.startswith("#"):
            copy_texture(tex)


def blockstate_models(raw):
    """Every model a blockstate file points at, variants or multipart."""
    data = json.loads(raw)
    found = []
    for entry in (data.get("variants") or {}).values():
        for one in (entry if isinstance(entry, list) else [entry]):
            if "model" in one:
                found.append(one["model"])
    for part in (data.get("multipart") or []):
        applied = part.get("apply", {})
        for one in (applied if isinstance(applied, list) else [applied]):
            if "model" in one:
                found.append(one["model"])
    return found


shutil.rmtree(OUT, ignore_errors=True)
os.makedirs(OUT, exist_ok=True)

# --- blocks that exist in both versions and were redrawn ----------------------------------
#
# Separate from the carrier trick, and the bigger half of what makes an old client look old.
# A redstone torch exists in 1.21 and was redesigned in 26.x; a 1.21 client draws the one it
# shipped with, which is correct for it and wrong for this server. Same for stained glass,
# comparators, fences and a few hundred others.
#
# These are safe to replace outright because the model format did not change. The only thing
# 26.2 adds is a display slot called on_shelf, which 1.21 rejects the whole model over, so it
# is stripped on the way through. With that gone the two versions parse the same files.
old_jar = zipfile.ZipFile(OLD_JAR)
old_names = set(old_jar.namelist())


def strip_unknown(data):
    """Removes what 1.21 cannot parse. Only on_shelf, checked across every model in 26.2."""
    if "display" in data:
        data["display"] = {k: v for k, v in data["display"].items() if k != "on_shelf"}
        if not data["display"]:
            del data["display"]
    return data


def refresh_model(ref, wrote):
    """Writes the 26.2 model and everything under it, under its own name."""
    ref = ref.split(":")[-1]
    if ref in wrote:
        return
    src = "assets/minecraft/models/" + ref + ".json"
    if src not in names:
        return
    wrote.add(ref)
    data = strip_unknown(json.loads(jar.read(src)))
    dst = os.path.join(OUT, "assets/minecraft/models", ref + ".json")
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(data, f, separators=(",", ":"))
    if "parent" in data:
        refresh_model(data["parent"], wrote)
    for tex in (data.get("textures") or {}).values():
        if isinstance(tex, str) and not tex.startswith("#"):
            refresh_texture(tex)


def refresh_texture(ref):
    ref = ref.split(":")[-1]
    src = "assets/minecraft/textures/" + ref + ".png"
    if src not in names:
        return
    old = "assets/minecraft/textures/" + ref + ".png"
    if old in old_names and old_jar.read(old) == jar.read(src):
        return  # identical in both, so the client's own is already right
    dst = os.path.join(OUT, "assets/minecraft/textures", ref + ".png")
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "wb") as f:
        f.write(jar.read(src))
    redrawn.add(ref)
    if src + ".mcmeta" in names:
        with open(dst + ".mcmeta", "wb") as f:
            f.write(jar.read(src + ".mcmeta"))


redrawn = set()
written_models = set()

# every model that really is different, once on_shelf is discounted
for name in sorted(names):
    if not name.startswith("assets/minecraft/models/") or not name.endswith(".json"):
        continue
    if name not in old_names:
        continue
    if old_jar.read(name) == jar.read(name):
        continue
    try:
        if strip_unknown(json.loads(jar.read(name))) == json.loads(old_jar.read(name)):
            continue  # only ever differed by the slot we strip
    except Exception:
        continue
    refresh_model(name[len("assets/minecraft/models/"):-len(".json")], written_models)

# and the blockstates that point at them
refreshed_states = 0
for name in sorted(names):
    if not name.startswith("assets/minecraft/blockstates/"):
        continue
    if name not in old_names or old_jar.read(name) == jar.read(name):
        continue
    raw = jar.read(name)
    for model in blockstate_models(raw):
        refresh_model(model, written_models)
    dst = os.path.join(OUT, "assets/minecraft/blockstates",
                       os.path.basename(name))
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "wb") as f:
        f.write(raw)
    refreshed_states += 1

print(f"redrawn     : {len(written_models)} models, {len(redrawn)} textures, "
      f"{refreshed_states} blockstates")

# --- shaped blocks: the carrier's blockstate file becomes the new block's -----------------
for new, carrier in SHAPED.items():
    raw = read(f"assets/minecraft/blockstates/{new}.json")
    if raw is None:
        missing.append(new)
        continue
    for model in blockstate_models(raw):
        copy_model(model)
    dst = os.path.join(OUT, "assets/minecraft/blockstates", carrier + ".json")
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "w", encoding="utf-8") as f:
        f.write(raw)

# --- full cubes: one note value each, every other note left as a note block ---------------
variants = {}
for note in range(25):
    if 1 <= note <= len(CUBES):
        block = CUBES[note - 1]
        raw = read(f"assets/minecraft/blockstates/{block}.json")
        if raw is None:
            missing.append(block)
            continue
        for model in blockstate_models(raw):
            copy_model(model)
        variants[f"note={note}"] = {"model": f"minecraft:block/{block}"}
    else:
        variants[f"note={note}"] = {"model": "minecraft:block/note_block"}

os.makedirs(os.path.join(OUT, "assets/minecraft/blockstates"), exist_ok=True)
with open(os.path.join(OUT, "assets/minecraft/blockstates/note_block.json"),
          "w", encoding="utf-8") as f:
    json.dump({"variants": variants}, f, indent=2)

# --- the one new mob, riding a magma cube puppet ---------------------------------------
#
# Magma cube keeps its inner and outer faces in a single texture; sulfur cube splits them into
# two files. So this is not a clean swap and the outer shell is what gets used, because that is
# the part actually visible from outside. The core ends up wearing the shell's texture, which
# is a shade wrong at the very centre and invisible in practice.
ENTITY = {"entity/sulfur_cube/sulfur_cube_outer": "entity/slime/magmacube"}
for src_ref, dst_ref in ENTITY.items():
    src = f"assets/minecraft/textures/{src_ref}.png"
    if src in names:
        dst = os.path.join(OUT, "assets/minecraft/textures", dst_ref + ".png")
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with open(dst, "wb") as f:
            f.write(jar.read(src))
        print(f"entity      : {src_ref} -> {dst_ref}")
    else:
        print(f"entity      : MISSING {src_ref}")

# --- everything NanoEssentials ships, folded in ------------------------------------------
#
# The main pack declares min_format 88, which is 26.2 and nothing else, so every client older
# than that refuses it outright - 26.1 as much as 1.21. Those are exactly the clients this
# pack is sent to, so the content rides along here instead of the main pack being widened.
# Widening that one would have meant changing the file every 26.2 player already loads
# correctly, to fix players who are not on 26.2.
ESSENTIALS = os.path.join(os.path.dirname(OUT), "pack")
folded = 0
for root, _, files in os.walk(ESSENTIALS):
    for name in files:
        if name == "pack.mcmeta":
            continue  # this pack declares its own, wider, range
        src = os.path.join(root, name)
        rel = os.path.relpath(src, ESSENTIALS)
        dst = os.path.join(OUT, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(src, dst)
        folded += 1
print(f"essentials  : {folded} files folded in")

with open(os.path.join(OUT, "pack.mcmeta"), "w", encoding="utf-8") as f:
    json.dump({"pack": {
        "description": "Hold SMP: shows 26.2 blocks on older clients",
        # Old clients read pack_format. 26.2 clients never receive this pack, so the modern
        # min_format/max_format pair is not what matters here.
        "pack_format": 34,
        "supported_formats": {"min_inclusive": 22, "max_inclusive": 99},
    }}, f, indent=2)

print(f"blockstates : {len(SHAPED)} shaped + note_block")
print(f"models      : {len(copied_models)} copied, "
      f"{len(kept_theirs)} left as the client's own")
print(f"textures    : {len(copied_textures)}")
print(f"cubes       : {len(CUBES)} on note=1..{len(CUBES)}")
if missing:
    print("MISSING     :", missing)


# --- zip it, and print the sha1 the server config needs -----------------------------------
import hashlib

# The slim pack is the redesigned blocks only: everything written before the carrier section
# ran. Copied out at that point rather than rebuilt.
shutil.rmtree(SLIM, ignore_errors=True)
os.makedirs(SLIM, exist_ok=True)

# Geometry only, and nothing else.
#
# A model says what shape a block is; a texture says what it looks like. The shape is what
# changed and what a 1.21 client cannot work out for itself, so that is shipped. The pixels
# are left to whatever pack the player chose, which is the whole point: a redstone torch comes
# out the right shape wearing their textures rather than ours.
#
# Carriers are left out too. A player with the mod has the new blocks for real and has no use
# for a stair pretending to be a shelf.
for sub in ("models", "blockstates"):
    src = os.path.join(OUT, "assets/minecraft", sub)
    if not os.path.isdir(src):
        continue
    for root, _, files in os.walk(src):
        for name in files:
            full = os.path.join(root, name)
            rel = os.path.relpath(full, OUT)
            if sub == "blockstates":
                # only the ones that changed shape, not the carrier substitutions
                plain = os.path.splitext(name)[0]
                if plain in SHAPED.values() or plain == "note_block":
                    continue
            dst = os.path.join(SLIM, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(full, dst)
shutil.copyfile(os.path.join(OUT, "pack.mcmeta"), os.path.join(SLIM, "pack.mcmeta"))

zip_path = os.path.join(os.path.dirname(OUT), "HoldSMP-Legacy-Blocks.zip")
# Written with a fixed timestamp so the same content always gives the same hash. A zip
# normally stores each file's modification time, so building twice from identical sources
# produced two different sha1s - and the sha1 is what the server tells the client to expect,
# so a rebuild that changed nothing still looked like a different pack.
entries = []
for root, _, files in os.walk(OUT):
    for name in files:
        full = os.path.join(root, name)
        entries.append((os.path.relpath(full, OUT).replace("\\", "/"), full))

with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
    for rel, full in sorted(entries):
        info = zipfile.ZipInfo(rel, date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        with open(full, "rb") as f:
            z.writestr(info, f.read())

blob = open(zip_path, "rb").read()
print()
print(f"zip         : {zip_path}")
print(f"size        : {len(blob)} bytes")
print(f"sha1        : {hashlib.sha1(blob).hexdigest()}")
slim_path = os.path.join(os.path.dirname(OUT), "HoldSMP-Legacy-Slim.zip")
slim_entries = []
for root, _, files in os.walk(SLIM):
    for name in files:
        full = os.path.join(root, name)
        slim_entries.append((os.path.relpath(full, SLIM).replace("\\", "/"), full))
with zipfile.ZipFile(slim_path, "w", zipfile.ZIP_DEFLATED) as z:
    for rel, full in sorted(slim_entries):
        info = zipfile.ZipInfo(rel, date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        with open(full, "rb") as f:
            z.writestr(info, f.read())
blob = open(slim_path, "rb").read()
print()
print(f"slim zip    : {slim_path}")
print(f"slim size   : {len(blob)} bytes")
print(f"slim sha1   : {hashlib.sha1(blob).hexdigest()}")
print("The slim one goes to players who have the mod; it leaves their own textures alone.")
print("Upload it, then put the URL and that sha1 in ui.legacy-pack-url / ui.legacy-pack-sha1.")
