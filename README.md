# NanoUI

A small client mod for Hold SMP. It replaces the chest menus with proper screens, so the
market, your homes, perks and settings all look like part of the game instead of a box of
items you have to click around.

## Should I install it

Yes, if you can. The server does fall back to chest menus without it and everything still
works, but those menus get far less attention than the real screens do. They are not tested
as carefully, and some of them probably look worse than they should or behave in ways nobody
has noticed yet. If something looks broken on the chest menus, installing this is the first
thing to try.

Bedrock players can ignore all of this. You already get native forms through Geyser.

## Installing

You need [Fabric](https://fabricmc.net/use/installer) and
[Fabric API](https://modrinth.com/mod/fabric-api).

The easy way is the pack. Grab `HoldSMP-26.2-*.mrpack` from
[Releases](../../releases/latest) and open it with the
[Modrinth launcher](https://modrinth.com/app), which installs Minecraft, Fabric, Fabric API
and this mod together. Nothing else to do. There is a `HoldSMP-1.21.1-*.mrpack` as well if
you would rather play on 1.21.

Doing it by hand instead: download `Nan0UI.jar` from Releases, put it in your `mods` folder
and start the game. Nothing to configure, and there is only one file to pick.

It runs on **1.21, 1.21.1, 26.1 and 26.2**. Same jar for all of them.

Anything between 1.21.2 and 26.0 is not covered yet. Nothing breaks if you are on one of
those, the mod just does not load and you get the chest menus, and the server still sends you
the new blocks and mobs with the right textures. Same for Bedrock, where you already have
native forms through Geyser.

## Updating

From 1.1.1 onward the mod does this for you. When the server has a newer version it offers
one, you press download, and a progress bar fills while it fetches the file into your mods
folder. Restart Minecraft and you are on the new one. Nothing to find, nothing to delete.

If you tick the box when you accept, later versions just install themselves and you stop
being asked. Say no with the box ticked and you never hear about it again. Either way you
can change your mind under Settings, Visuals, Mod Prompts.

The file is checked against a checksum the server gives it before anything is installed. If
what arrives is not what was described, it gets thrown away and nothing changes.

Coming from an older version you have to do it once by hand. Download `Nan0UI.jar` from
Releases, delete whatever jar you already had, and put the new one in. Two copies of the mod
in one folder will stop Minecraft from starting, which is why the file always has the same
name from now on.

## What it does

Menus are drawn as real screens with buttons you can read, search boxes that work and lists
that scroll properly. Item prices show up in tooltips, so you can see what something is worth
without opening the market.

The server decides what goes on each screen and the mod just draws it. New menus and new
features turn up on their own, without you needing a new version of this.

## Does it give me an advantage

No. It cannot see or do anything the server does not already tell every player. Anyone on the
chest menus has the same commands, the same market and the same prices.

## Building it yourself

```
gradle build
```

The jar lands in `build/libs`. You need JDK 21 or newer.

That builds the 26.x half. The full jar needs the 1.21 half as well:

```
cd 1.21
python3 ../generators/genreal.py
python3 port.py
gradle build
cd ..
python3 merge.py
```

`Nan0UI.jar` lands in `build/libs`.

`genreal.py` reads your Minecraft 26.2 jar and writes out the 137 blocks a 1.21 client does
not have, as models, textures and a manifest. Those files are Mojang's, so they are not in
this repository and you generate them yourself. Without that step the 1.21 half builds but
registers nothing.

### Why there are two halves

Minecraft ships unobfuscated from 26.1 and is obfuscated before that. A mod built for 1.21
calls Minecraft `class_310`; a mod built for 26.2 calls it `Minecraft`. Those are two
different names, not two spellings of one, so no single compiled class can hold both.

So the code is compiled twice, into `gg.nano.ui` and `gg.nano.ui.legacy`, and both go in the
jar. `Bootstrap` reads the version at startup and loads one of them. Java loads a class the
first time something uses it, so the half that does not match your version is never looked at,
and a name that is never looked up cannot fail.

There is still only one copy of the source. `port.py` rewrites the calls Mojang renamed and
generates `1.21/src/main/java`, which is why that folder is not in the repository. `merge.py`
combines the two jars and checks the result: right namespaces, right bytecode version, and
`Bootstrap` naming no Minecraft class at all.

## Licence

MIT. Do what you like with it.
