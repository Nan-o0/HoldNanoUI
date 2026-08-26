"""Builds the Modrinth packs that install Hold SMP in one click.

A .mrpack is a small manifest: which Minecraft, which loader, and a list of files with their
hashes and where to fetch them. The launcher downloads them itself, so nothing here contains a
mod, only the description of one.

A pack pins one Minecraft version, which is the one thing the single jar does not change. The
mod runs on 1.21 and 26.x from the same file, but somebody installing a pack is choosing a
version at the same time, so there is a pack per version people actually play.

The previous pack was written by hand and still pointed at v1.1.1 long after v1.1.2 existed.
Hence a script.

Usage:  python3 mrpack.py <release-tag>          e.g. python3 mrpack.py v1.1.2
"""
import hashlib
import io
import json
import os
import sys
import urllib.parse
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
JAR = os.path.join(HERE, "build", "libs", "Nan0UI.jar")
OUT_DIR = os.path.join(HERE, "build", "libs")

REPO = "Nan-o0/HoldNanoUI"
LOADER = "0.19.3"

# One per Minecraft version worth handing somebody. The jar is the same file in every pack;
# only the Minecraft version and the matching Fabric API differ.
TARGETS = ["26.2", "1.21.1"]

AGENT = {"User-Agent": "HoldSMP-packbuilder (github.com/" + REPO + ")"}


def fabric_api(mc):
    """The newest Fabric API for this Minecraft, straight from Modrinth."""
    query = urllib.parse.urlencode({
        "game_versions": json.dumps([mc]),
        "loaders": json.dumps(["fabric"]),
    })
    url = "https://api.modrinth.com/v2/project/fabric-api/version?" + query
    with urllib.request.urlopen(urllib.request.Request(url, headers=AGENT), timeout=30) as r:
        versions = json.load(r)
    if not versions:
        sys.exit("Modrinth lists no Fabric API for Minecraft " + mc)

    file = versions[0]["files"][0]
    return {
        "path": "mods/" + file["filename"],
        "hashes": {"sha1": file["hashes"]["sha1"], "sha512": file["hashes"]["sha512"]},
        "env": {"client": "required", "server": "unsupported"},
        "downloads": [file["url"]],
        "fileSize": file["size"],
    }


def mod_entry(tag):
    """Nan0UI, hashed from the jar that is about to be uploaded under this tag."""
    data = io.open(JAR, "rb").read()
    return {
        "path": "mods/Nan0UI.jar",
        "hashes": {
            "sha1": hashlib.sha1(data).hexdigest(),
            "sha512": hashlib.sha512(data).hexdigest(),
        },
        "env": {"client": "required", "server": "unsupported"},
        "downloads": ["https://github.com/" + REPO + "/releases/download/" + tag
                      + "/Nan0UI.jar"],
        "fileSize": len(data),
    }


def build(mc, tag, version, mod):
    index = {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": version,
        "name": "Hold SMP" if mc == TARGETS[0] else "Hold SMP (" + mc + ")",
        "summary": "Everything you need to play on Hold SMP. Installs Fabric, Fabric API and "
                   "Nan0UI in one go.",
        "files": [mod, fabric_api(mc)],
        "dependencies": {"minecraft": mc, "fabric-loader": LOADER},
    }

    name = "HoldSMP-" + mc + "-" + version + ".mrpack"
    path = os.path.join(OUT_DIR, name)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as pack:
        pack.writestr("modrinth.index.json", json.dumps(index, indent=2))
    print("  " + name + "  (" + index["files"][1]["path"].split("/")[-1] + ")")
    return path


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    tag = sys.argv[1]
    version = tag[1:] if tag.lower().startswith("v") else tag

    if not os.path.exists(JAR):
        sys.exit("No Nan0UI.jar in build/libs. Run merge.py first.")

    mod = mod_entry(tag)
    print("Nan0UI.jar  " + str(mod["fileSize"]) + " bytes  sha1 " + mod["hashes"]["sha1"])
    print("Packs:")
    for mc in TARGETS:
        build(mc, tag, version, mod)

    print()
    print("The packs name a file that has to exist at that exact URL, with that exact hash.")
    print("Publish the " + tag + " release with this Nan0UI.jar before handing them out, or")
    print("the launcher will fail the download.")


main()
