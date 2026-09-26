# Polymer Patcher++

A server-side Fabric mod that lets players **without** your server's mods join and see modded content anyway.
It uses [Polymer](https://modrinth.com/mod/polymer) to turn modded blocks, items, entities, particles, sounds,
fluids and menus into things a vanilla client can already draw, and generates the resource pack that makes them
look right.

Players who *do* have the mods at the server's version are detected when they join and get the real content.

> **Status:** built and used on one heavily modded Minecraft 26.2 server. It works well there, but it has not
> been tested against every mod out there. Expect rough edges with mods it hasn't met yet, and please report them.

## Requirements

- Minecraft **26.2**, Fabric Loader **0.19.3+**, Java **25**
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Polymer](https://modrinth.com/mod/polymer) **0.17.5+** (the bundled jar)
- GeckoLib is optional; mobs drawn with GeckoLib are supported when it is installed

Server only. Players do not need to install anything.

## Setup

1. Put `polymer-patcher-plus-plus-<version>.jar`, Polymer and Fabric API in the server's `mods` folder with your other mods.
2. **Turn on Polymer's resource pack auto-hosting.** Without it, players never receive the pack and every
   modded block, item and mob shows as its plain vanilla stand-in. In the server's `config/polymer/auto-host.json`,
   set `"enabled": true`.
3. **Take a render dump.** Modded mobs are drawn from a description of their models that has to come from a
   client, because a server has no rendering code.
   1. Install this mod on a client that has *the same mods as the server*.
   2. Open any single-player world and run `/pp-dump`.
   3. Wait for the chat message `[Polymer Patcher++] Saved. You can close this world and start the server.`
      That writes `polymer-patcher-dump.json` to `~/.polymer-patcher/` and to the client's `config` folder.

   - Hosting on the same machine: the server finds the copy in your home folder on its own.
   - Rented host: upload `polymer-patcher-dump.json` into the server's `config` folder.
   - Take a new dump whenever you add or update a mod with mobs. The server log names any mod missing from it.
4. Start the server. The resource pack is generated at startup and sent to players automatically.

## What it handles

- **Blocks** go on spare vanilla block states ("carriers") re-skinned by the pack. When a kind of carrier runs out,
  the block is drawn with a display entity instead. The blocks your world uses most are learned over time and
  given carriers first (`config/polymer-patcher-block-usage.json`).
- **GeckoLib and Citadel block entities** (blocks drawn by a renderer rather than a model) are drawn from their
  models. GeckoLib blocks are animated.
- **Items** are shown with their own models, tints, armour and held-item transforms.
- **Mobs** are drawn from the render dump: vanilla-style models, Citadel models (Alex's Mobs / Alex's Caves) and
  GeckoLib models, animated.
- **Fluids** are drawn and behave like fluids (swimming, flow levels).
- **Particles and sounds**: modded particle types are shown as close vanilla particles. Particles and sounds a mod
  only makes on the client (block ambience, projectile trails, effect clouds) are replayed from the server.
- **Menus**: simple modded containers open as vanilla menus.
- **Recipe book**: modded recipes appear, and the server decides what you can craft, so they light up correctly.
- **Entity data** is renumbered for clients that lack the mods adding fields to vanilla entities, so nobody is
  kicked for a mismatch.
- **Registry data** a vanilla client can't read (biome effects, sounds, tags) is made readable instead of
  disconnecting the player.

Mod-specific support exists for Alex's Caves, Alex's Mobs, Enderscape, Borrowed Echo and Sculk Horde. Other mods
are handled by the generic systems above.

## Commands (operator level 2)

| Command | What it does |
| --- | --- |
| `/pp count-displays block` | Counts display entities around you by block - the best way to find what is costing frames |
| `/pp count-displays entity` | The same, by entity type |
| `/pp block <pos>` | Explains how one block is being shown: its carrier, what is sent, what the client reads |
| `/pp inspect <entities>` | Explains how an entity is being drawn, and why it might be invisible |
| `/mods-check <player>` | Lists which of the server's mods a player has, at which version |
| `/effects` | Lists the modded effects you currently have |

## Configuration

`config/polymer-patcher.json`. Some useful settings:

| Setting | Default | Meaning |
| --- | --- | --- |
| `entities.showPlaceholders` | `true` | Draw a named red box for a mob with no model, instead of nothing |
| `entities.hideRecipesForMissingMods` | `false` | Leave recipes from mods a player lacks out of their recipe book |
| `blocks.displayViewRange` | `1.5` | How far block displays are drawn (1.0 is about 64 blocks) |
| `blocks.replayModdedBlockAmbience` | `true` | Replay modded blocks' ambient particles |
| `blocks.blockAmbienceExcludedMods` | `[]` | Mods whose block ambience is left out |
| `blocks.vanillaSlabsWhenOutOfCarriers` | `false` | Show the nearest vanilla slab instead of a display when slab carriers run out |

## Known limitations

- A vanilla client can only be given what vanilla can draw. Custom shaders, screens with custom layouts, and
  anything else that needs the mod's own client code cannot be reproduced.
- Carriers are limited. On a server with many mods, some blocks become display entities, which cost frame rate
  when placed by the thousand. `/pp count-displays block` shows which.
- Mobs need a current render dump (see Setup).

## Building

```
./gradlew build
```

The jar is written to `build/libs/`. The build also runs several verification tasks (block-state mapping,
fluid models, registry data) that fail the build if something is wrong.

## Credits and license

Polymer Patcher++ is based on Polymer Patcher, originally created by **Drex** ([DrexHD](https://github.com/DrexHD)). This version has been
extensively modified and extended by me, **Bananaman**.

Licensed under the **GNU LGPL v3** - see [LICENSE](LICENSE). Built on [Polymer](https://github.com/Patbox/polymer)
and FactoryTools by Patbox.
