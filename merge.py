"""Combines the 26.x build and the 1.21 build into one jar.

Minecraft ships unobfuscated from 26.1 and is obfuscated before that, so a mod built for 1.21
refers to Minecraft as class_310 where a mod built for 26.2 refers to it as Minecraft. Those
are two different names, not two spellings of one, and no single compiled class can hold both.
The code is therefore compiled twice and both copies go in one jar, in different packages,
with Bootstrap choosing between them at startup. Classes load when first used, so the copy
that does not match the running version is never looked at.

Run after building both projects. Checks the result rather than trusting it, because every
way this can go wrong is silent until somebody launches the game.
"""
import io
import json
import os
import shutil
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
MODERN = os.path.join(HERE, "build", "libs", "nanoui-1.2.0.jar")
LEGACY = os.path.join(HERE, "..", "NanoUI-1.21", "build", "libs", "nanoui-1.21-1.2.0.jar")
OUT = os.path.join(HERE, "build", "libs", "Nan0UI.jar")

LEGACY_PREFIX = "gg/nano/ui/legacy/"

# The blocks 26.2 has and 1.21 does not, and everything needed to draw them. Only the 1.21
# build carries these: a 26.2 client already has all of it and would be downloading two
# megabytes of its own textures back.
LEGACY_ASSETS = ("assets/holdsmp/", "holdsmp-blocks.json", "holdsmp-statue.json",
                  "holdsmp-mobs.json",
                  "holdsmp-animations.json")

# Java 21. Both halves must be this: a 1.21 client runs on Java 21 and cannot read a class
# compiled for 25, and Fabric walks the whole jar looking for entry points.
WANT_BYTECODE = 65

problems = []


def check(condition, message):
    if not condition:
        problems.append(message)


def bytecode_version(data):
    # Class files start ca fe ba be, then two bytes of minor and two of major.
    return int.from_bytes(data[6:8], "big")


def main():
    for path, label in ((MODERN, "26.x"), (LEGACY, "1.21")):
        if not os.path.exists(path):
            sys.exit("Missing the " + label + " build: " + os.path.normpath(path)
                     + "\nBuild both projects first.")

    shutil.copyfile(MODERN, OUT)

    with zipfile.ZipFile(LEGACY) as source:
        names = [n for n in source.namelist()
                 if n.startswith(LEGACY_PREFIX) or n.startswith(LEGACY_ASSETS)]
        check(names, "the 1.21 jar has nothing under " + LEGACY_PREFIX
              + " - did port.py run before it was built?")
        check(any(n == "holdsmp-blocks.json" for n in names),
              "holdsmp-blocks.json is missing, so no 26.2 blocks would be registered")
        blockstates = sum(1 for n in names
                          if n.startswith("assets/holdsmp/blockstates/"))
        check(blockstates >= 137,
              "only " + str(blockstates) + " blockstates made it across, expected 137")
        check(any(n == "holdsmp-statue.json" for n in names),
              "holdsmp-statue.json is missing, so statues would render as nothing")
        check(any(n == "holdsmp-mobs.json" for n in names),
              "holdsmp-mobs.json is missing, so the new mobs would stay magma cubes")
        check(any(n == "holdsmp-animations.json" for n in names),
              "holdsmp-animations.json is missing, so the mobs would not move")
        with zipfile.ZipFile(OUT, "a", zipfile.ZIP_DEFLATED) as out:
            existing = set(out.namelist())
            for name in names:
                check(name not in existing, "both jars contain " + name)
                out.writestr(name, source.read(name))

    verify()

    if problems:
        print("FAILED")
        for line in problems:
            print("  " + line)
        os.remove(OUT)
        sys.exit(1)

    print("wrote " + os.path.normpath(OUT) + "  " + str(os.path.getsize(OUT)) + " bytes")


def verify():
    with zipfile.ZipFile(OUT) as jar:
        names = jar.namelist()

        mod = json.loads(jar.read("fabric.mod.json"))
        entry = mod["entrypoints"]["client"]
        check(entry == ["gg.nano.ui.Bootstrap"],
              "the entry point should be Bootstrap alone, found " + repr(entry))
        check(isinstance(mod["depends"]["minecraft"], list),
              "minecraft should be a list of ranges so both families are claimed")

        # The two halves must be in the namespaces they were built for. If Loom ever stopped
        # remapping, the legacy half would silently carry real names and match nothing.
        modern = [n for n in names if n.startswith("gg/nano/ui/")
                  and not n.startswith(LEGACY_PREFIX) and n.endswith(".class")]
        legacy = [n for n in names if n.startswith(LEGACY_PREFIX) and n.endswith(".class")]
        check(modern, "no 26.x classes in the jar")
        check(legacy, "no 1.21 classes in the jar")

        blob = b"".join(jar.read(n) for n in legacy)
        check(b"class_" in blob,
              "the 1.21 half has no intermediary names, so it was not remapped")

        blob = b"".join(jar.read(n) for n in modern)
        check(b"net/minecraft/client" in blob,
              "the 26.x half has no real Minecraft names, so it was remapped when it should "
              "not have been")

        # Bootstrap decides which half runs, so it must not name Minecraft at all. If it did,
        # loading it would resolve a name that is absent on one of the two versions and the
        # mod would fail on that version before it could choose anything.
        boot = jar.read("gg/nano/ui/Bootstrap.class")
        check(b"net/minecraft" not in boot and b"class_" not in boot,
              "Bootstrap references Minecraft, which defeats the whole arrangement")

        for name in names:
            if name.endswith(".class"):
                got = bytecode_version(jar.read(name))
                check(got == WANT_BYTECODE,
                      name + " is bytecode " + str(got) + ", want " + str(WANT_BYTECODE))

        print("checked " + str(len(modern)) + " classes for 26.x, "
              + str(len(legacy)) + " for 1.21")


main()
