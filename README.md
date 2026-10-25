# Locate Plus — Forge 1.20.1 port

An **unofficial** Forge 1.20.1 port of [forestmask/Locate-Plus](https://github.com/forestmask/Locate-Plus) 2.2.0,
which upstream ships for Fabric (1.20.1 / 1.21.1). Behaviour is unchanged: the mod is still
server-side only, still adds `/locate block|entity|item|biome|structure|poi`,
`/lp analyze|inspect|visualize|config|reload|stop|clear`, `/safetp` and `/glow`, and still needs no
client install.

* Minecraft **1.20.1**
* Forge **47.x** (built against 47.2.0, runs on later 47.x)
* Java **17**

## Installing

1. Drop `locateplus-forge-2.2.0+1.20.1-forge.jar` into `mods/`.
2. Start the game or the server. Nothing goes in the client, even on a multiplayer server.
3. Type `/lp` in game for the command list.

On a single-player world the integrated server runs the same code, so it works there too without a
client mod. The first start writes `config/locate-plus/config.json` with every setting explained in
the file; `/lp config` prints what is loaded and `/lp reload` re-reads it.

## Commands

| Command | What it does |
| --- | --- |
| `/locate block <id\|#tag> [<n> chunks\|blocks] [forceload]` | Nearest placed block, then outlines it |
| `/locate entity <id\|#tag> [<n> chunks\|blocks] [forceload]` | Nearest entity, then makes it glow |
| `/locate item <id\|#tag> [<n> chunks\|blocks] [export] [forceload]` | An item in chests, on the ground, on mobs, in player inventories |
| `/locate biome\|structure\|poi <id>` | Vanilla searches, with a safe-teleport button |
| `/lp analyze blocks\|entities\|both [radius] [export]` | Count everything nearby |
| `/lp inspect <x> <y> <z>` | Redstone, light, spawning, crops, containers, entity data |
| `/lp visualize <n> chunks\|blocks [seconds]` | Outline the area a scan would cover |
| `/safetp [<targets>] <x> <y> <z>` | Teleport somewhere you can actually stand |
| `/glow <id\|#tag\|@selector> [<n> blocks\|chunks]` | Outline entities through terrain |
| `/lp stop` / `/lp clear` / `/lp reload` / `/lp config` | Manage scans, glows and settings |

A radius is always a number plus a unit (`64 blocks` or `4 chunks`). Leaving it out searches only
the chunks the server already has loaded. `export` writes the full result to
`config/locate-plus/exports/`.

Note the split that trips people up: `/locate item` finds the **item** form — in containers, on the
ground, worn by mobs, in player inventories — while a block that is standing in the world is
`/locate block`. Searching `/locate item minecraft:bone_block` for bone blocks you can see in a
fossil will correctly report nothing.

A fuller command reference, including every config key that can make a search come back empty, is
in [COMMANDS.md](COMMANDS.md) (Chinese).

## Building

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew build
```

The jar lands in `build/libs/locateplus-forge-2.2.0+1.20.1-forge.jar`.

Gradle 8.1.1 — the version the official 1.20.1 MDK uses — does not run on Java 21+, so the build
needs a JDK 17. Put it on `JAVA_HOME`, or uncomment `org.gradle.java.home` in `gradle.properties`.

To run a dev server instead:

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew runServer
```

`runServer` forwards stdin, so commands can be typed — or piped — straight into its console and
their chat replies land in the log.

## What the port changed

Only the loader layer and the naming of game classes. Every command, scan, marker, export and
message in `dev/locateplus` below the `forge` package is the upstream code.

| Upstream (Fabric) | Here (Forge) |
| --- | --- |
| `LocatePlusFabric` + `fabric.mod.json` entrypoints | `LocatePlusForge` with `@Mod`, `META-INF/mods.toml` |
| `FabricPlatform` (`FabricLoader`) | `ForgePlatform` (`ModList`, `FMLPaths`) |
| `CommandRegistrationCallback` | `RegisterCommandsEvent` |
| `ServerTickEvents.END_SERVER_TICK` | `TickEvent.ServerTickEvent`, `Phase.END` |
| `SERVER_STARTED` / `SERVER_STOPPING` | `ServerStartedEvent` / `ServerStoppingEvent` |
| `ENTITY_LOAD` / `ENTITY_UNLOAD` | `EntityJoinLevelEvent` / `EntityLeaveLevelEvent` |
| `END_DATA_PACK_RELOAD` | a reload listener on `AddReloadListenerEvent` |
| Yarn names (`ServerWorld`, `Text`, `Box`, …) | official names (`ServerLevel`, `Component`, `AABB`, …) |
| Stonecutter branches for 1.20.1 and 1.21.1 | 1.20.1 only; `Compat` keeps the 1.20.1 branches |
| Gradle + Loom, `fabric.mod.json` | Gradle 8.1.1 + ForgeGradle 6, `mods.toml` |

The mapping conversion was done against the real 1.20.1 mappings rather than by hand: yarn's
`named` names were joined to Mojang's through intermediary and the obfuscated names, then the
source tree was rewritten class by class. Anything the join could not settle — a method whose name
means two different things in two classes, an overload that moved — was left to the compiler and
fixed one error at a time, which is why the code reads the same as upstream.

## Differences you can notice

* `/lp config` and export headers say `Forge` where upstream says `Fabric`.
* The mod id is `locateplus`, the same as upstream, so an existing `config/locate-plus/config.json`
  can be copied over as is.
* Forge's `AddReloadListenerEvent` is the closest thing to a "data pack reload finished" event, so
  the block-tag cache is dropped by a listener that runs as part of the reload instead of after it.

## Testing

The release jar was exercised on a dedicated Forge 47.2.0 server as well as on the dev `runServer`
console: `/lp`, `locate block` (id and tag), `locate entity` (id and tag), `locate item` (containers,
dropped items, mob equipment, exports), `locate biome|structure|poi`, `lp analyze blocks|entities`
with exports, `lp inspect`, `glow`, `safetp`, `lp config|reload|clear|stop`, and the `block_display`
markers including their 60 second expiry. No errors or exceptions in the server log.

It has also been running in a 347 mod Forge 1.20.1 pack, where scanning containers, the ground,
mob equipment and player inventories all behave as documented.

## License

LGPL-3.0-or-later, the same licence as upstream. See [LICENSE](LICENSE).

Locate Plus is © 2026 forest_mask. This port is a derivative work and keeps the original
copyright; the Forge-specific loader layer and the name conversion are the only parts that are new.
