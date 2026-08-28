"""Extracts every 26.2 block a 1.21 client does not have, as real registerable content.

This is not the carrier trick. That one borrows a block the old client already has and repaints
it, which is all you can do when the client is vanilla, and it caps out at whatever spare
blocks happen to be lying around with the right shape.

With Nan0UI installed the client is not vanilla, so the blocks can simply be registered. What
comes out of here is the data to do that: one manifest naming every block and its shape, plus
a resource pack holding the models and textures Mojang already wrote. Nothing is drawn by hand
and nothing is approximated.

Writes into the 1.21 mod so the assets ship inside the jar.
"""
import io
import json
import os
import shutil
import zipfile

R = "C:/Users/cupy/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/"
NEW = R + "minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar"
OLD = (R + "minecraft-merged/1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2/"
       "minecraft-merged-1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2.jar")

MOD = "C:/Users/cupy/Desktop/Dev/Work/Minecraft/HOLD SMP/NanoUI-1.21/src/main/resources"
OUT = os.path.join(MOD, "assets", "holdsmp")
MANIFEST = os.path.join(MOD, "holdsmp-blocks.json")

new_jar = zipfile.ZipFile(NEW)
names = set(new_jar.namelist())


def lang_keys(jar, prefix):
    with zipfile.ZipFile(jar) as z:
        lang = json.loads(z.read("assets/minecraft/lang/en_us.json"))
    return {k.split(".")[-1]: lang[k] for k in lang
            if k.startswith(prefix) and k.count(".") == 2}


def models_in(raw):
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



# Which vanilla block a new one behaves like.
#
# Matching a family gets the real thing for free - a stair registered as a stair has stair
# collision, stair placement and the stair properties without any of it being described here.
# Guessing shapes by hand would mean 137 chances to get a hitbox subtly wrong.
FAMILIES = [
    ("_stairs", "stairs"), ("_slab", "slab"), ("_wall", "wall"),
    ("_fence_gate", "fence_gate"), ("_fence", "fence"),
    ("_trapdoor", "trapdoor"), ("_door", "door"),
    ("_pressure_plate", "pressure_plate"), ("_button", "button"),
    ("_wall_hanging_sign", "wall_hanging_sign"), ("_hanging_sign", "hanging_sign"),
    ("_wall_sign", "wall_sign"), ("_sign", "sign"),
    ("_bars", "bars"), ("_chain", "chain"), ("_lantern", "lantern"),
    ("_wall_torch", "wall_torch"), ("_torch", "torch"),
    ("_carpet", "carpet"), ("_shelf", "shelf"), ("_chest", "chest"),
    ("_leaves", "leaves"), ("_sapling", "sapling"),
    ("_log", "pillar"), ("_wood", "pillar"), ("_planks", "cube"),
    ("_golem_statue", "statue"),
]


def classify(block, state):
    """The vanilla family this block behaves like, or a shape derived from its properties."""
    for suffix, family in FAMILIES:
        if block.endswith(suffix):
            return family
    props = _from_blockstate(state)
    names = set(props)
    if names == {"axis"}:
        return "pillar"
    if names == {"type"}:
        return "slab"
    if not names:
        # No state at all. Either a plain cube or a flat decoration, and the model says
        # which: a cross or flat parent is a plant and must not be walked on.
        return "cube"
    return "generic"


# State that the blockstate file does not mention.
#
# A chest and a statue are drawn by a renderer rather than by the model system, so their
# blockstate file names one model and no variants at all. Reading properties out of it would
# say they have none, and a chest that cannot face anywhere is a chest that always faces north.
# These come from the blocks themselves in 26.2.
RENDERER_DRAWN = {
    "chest": {
        "facing": ["east", "north", "south", "west"],
        "type": ["single", "left", "right"],
        "waterlogged": ["false", "true"],
    },
    "statue": {
        "facing": ["east", "north", "south", "west"],
        "pose": ["standing", "sitting", "running", "star"],
        "waterlogged": ["false", "true"],
    },
}


def properties_of(state, kind=None):
    """Every state property and the values seen for it, read out of the blockstate itself."""
    if kind in RENDERER_DRAWN:
        return dict(RENDERER_DRAWN[kind])
    return _from_blockstate(state)


def _from_blockstate(state):
    found = {}
    keys = []
    if "variants" in state:
        keys = [k for k in state["variants"] if k]
    if "multipart" in state:
        for part in state["multipart"]:
            when = part.get("when", {})
            for k, v in when.items():
                if k in ("OR", "AND"):
                    for sub in v:
                        for sk, sv in sub.items():
                            found.setdefault(sk, set()).update(str(sv).split("|"))
                else:
                    found.setdefault(k, set()).update(str(v).split("|"))
    for key in keys:
        for pair in key.split(","):
            name, _, value = pair.partition("=")
            found.setdefault(name, set()).add(value)
    return {k: sorted(v) for k, v in sorted(found.items())}


copied_models, copied_textures = set(), set()


def copy_texture(ref):
    ref = ref.split(":")[-1]
    if ref in copied_textures:
        return
    src = "assets/minecraft/textures/" + ref + ".png"
    if src not in names:
        return
    copied_textures.add(ref)
    dst = os.path.join(OUT, "textures", ref + ".png")
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "wb") as f:
        f.write(new_jar.read(src))
    if src + ".mcmeta" in names:
        with open(dst + ".mcmeta", "wb") as f:
            f.write(new_jar.read(src + ".mcmeta"))


def copy_model(ref):
    """Copies a model and everything it points at, rewritten into our own namespace."""
    ref = ref.split(":")[-1]
    if ref in copied_models:
        return
    src = "assets/minecraft/models/" + ref + ".json"
    if src not in names:
        return
    copied_models.add(ref)
    data = json.loads(new_jar.read(src))

    if "parent" in data:
        parent = data["parent"].split(":")[-1]
        # Vanilla shapes the 1.21 client already has are left pointing at minecraft: so the
        # client uses its own. Anything newer is pulled across with us.
        if parent in vanilla_models:
            data["parent"] = "minecraft:" + parent
        else:
            copy_model(parent)
            data["parent"] = "holdsmp:" + parent
    for slot, tex in list((data.get("textures") or {}).items()):
        if isinstance(tex, str) and not tex.startswith("#"):
            bare = tex.split(":")[-1]
            copy_texture(bare)
            data["textures"][slot] = "holdsmp:" + bare

    dst = os.path.join(OUT, "models", ref + ".json")
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(data, f, separators=(",", ":"))


with zipfile.ZipFile(OLD) as z:
    vanilla_models = {n[len("assets/minecraft/models/"):-len(".json")]
                      for n in z.namelist() if n.startswith("assets/minecraft/models/")}

old_blocks = lang_keys(OLD, "block.minecraft.")
new_blocks = lang_keys(NEW, "block.minecraft.")
todo = sorted(set(new_blocks) - set(old_blocks))

shutil.rmtree(OUT, ignore_errors=True)

manifest, lang, skipped = [], {}, []
for block in todo:
    src = "assets/minecraft/blockstates/" + block + ".json"
    if src not in names:
        skipped.append(block)
        continue

    raw = new_jar.read(src)
    for model in models_in(raw):
        copy_model(model)

    # The blockstate keeps its own name and moves namespace with its models.
    state = json.loads(raw)
    text = json.dumps(state)
    for ref in set(models_in(raw)):
        bare = ref.split(":")[-1]
        text = text.replace('"' + ref + '"', '"holdsmp:' + bare + '"')
    dst = os.path.join(OUT, "blockstates", block + ".json")
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "w", encoding="utf-8") as f:
        f.write(text)

    manifest.append({
        "id": block,
        "kind": classify(block, state),
        "properties": properties_of(state, classify(block, state)),
    })
    lang["block.holdsmp." + block] = new_blocks[block]

# Textures the renderers use. These live under entity/ rather than block/, so nothing in the
# model chain refers to them and they would otherwise be left behind.
for prefix in ("entity/chest/copper", "entity/copper_golem/",
               # The mobs drawn from dumped geometry. Their skins live under entity/
               # like the chests do, so no model refers to them and they would be
               # left behind by the model walk.
               "entity/creaking/", "entity/ghast/happy_ghast",
               # equipment skins as well, so armour and harnesses are there when the
               # server starts telling us what a mob is wearing
               "entity/equipment/nautilus", "entity/equipment/happy_ghast",
               "entity/nautilus/", "entity/sulfur_cube/"):
    for name in names:
        if name.startswith("assets/minecraft/textures/" + prefix) and name.endswith(".png"):
            copy_texture(name[len("assets/minecraft/textures/"):-len(".png")])
print("entity tex : %d total after renderer textures" % len(copied_textures))

os.makedirs(os.path.join(OUT, "lang"), exist_ok=True)
with open(os.path.join(OUT, "lang", "en_us.json"), "w", encoding="utf-8") as f:
    json.dump(lang, f, indent=1, ensure_ascii=False)

with open(MANIFEST, "w", encoding="utf-8") as f:
    json.dump({"blocks": manifest}, f, indent=1)

# The mobs a 1.21 client has no entity type for, for the server to stand in for.
#
# Boats and potions are filtered out: 26.2 split the single boat entity into one per wood
# and the single potion into splash and lingering, so a 1.21 client already draws all of
# them and ViaBackwards already maps them. Standing in for those would replace something
# that works with something worse.
old_mobs = lang_keys(OLD, "entity.minecraft.")
new_mobs = lang_keys(NEW, "entity.minecraft.")
renamed = ("_boat", "_raft", "_chest_boat", "_chest_raft")
mobs = sorted(m for m in set(new_mobs) - set(old_mobs)
              if not m.endswith(renamed)
              and m not in ("splash_potion", "lingering_potion"))
MOBS = ("C:/Users/cupy/Desktop/Dev/Work/Minecraft/HOLD SMP/NanoCore/src/main/resources/"
        "legacy-mobs.txt")
with io.open(MOBS, "w", encoding="utf-8", newline=chr(10)) as f:
    f.write("# Mobs a 1.21 client cannot be sent. Written by genreal.py, not by hand."
            + chr(10))
    for m in mobs:
        f.write(m + chr(10))
print("core mobs  : legacy-mobs.txt (%d)" % len(mobs))

# The same list, for the server. It has to know which of its blocks an old client cannot
# be sent, and that is exactly this set. Generated rather than typed twice, because two
# copies of a 137 entry list drift the moment either one is edited.
CORE = ("C:/Users/cupy/Desktop/Dev/Work/Minecraft/HOLD SMP/NanoCore/src/main/resources/"
        "legacy-blocks.txt")
with io.open(CORE, "w", encoding="utf-8", newline=chr(10)) as f:
    f.write("# Blocks a 1.21 client does not have. Written by genreal.py, not by hand."
            + chr(10))
    for entry in manifest:
        f.write(entry["id"] + chr(10))
print("core list  : legacy-blocks.txt (%d)" % len(manifest))

print("blocks     : %d registered, %d skipped %s" % (len(manifest), len(skipped), skipped))
print("models     : %d" % len(copied_models))
print("textures   : %d" % len(copied_textures))
print("lang       : %d names" % len(lang))
print("manifest   : %s" % os.path.normpath(MANIFEST))
