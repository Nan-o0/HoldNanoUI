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
[Fabric API](https://modrinth.com/mod/fabric-api). Works on Minecraft 26.1 and 26.2.

On 1.21 it will not load, and it does not need to. The server sends those players the new
blocks and mobs with the right textures anyway, so the only thing missing there is the nicer
looking menus.

Download `Nan0UI.jar` from [Releases](../../releases/latest), put it in your `mods` folder
and start the game. Nothing to configure.

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

## Licence

MIT. Do what you like with it.
