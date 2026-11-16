# Autowalk
A simple Fabric mod for Minecraft which adds keybinds to walk, mine and jump automatically.

A Fabric port of [wwrpg's AutoWalk](https://www.curseforge.com/minecraft/mc-mods/autowalk) mod (originally Forge 1.12.2).

## Supported versions
One jar covers **Minecraft 26.1, 26.1.1, 26.1.2, 26.2 and 26.3**.

Requires [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api).

## Keys
All keys are rebindable under **Options → Controls → Key Binds → Auto Key**.

| Key | Action |
| --- | --- |
| `R` | **Auto Walk** — toggles walking forward. Tapping your forward key also stops it. |
| `B` | **Auto Pick** — toggles holding attack (mining / hitting). |
| `G` | **Auto Jump** — toggles holding jump. |

## Config
`config/autowalk.properties` is created on first launch. Restart the game after editing it.

| Option | Default | Description |
| --- | --- | --- |
| `autoSprint` | `true` | Sprint while auto walking. |
| `autoWalkWithGUI` | `true` | Keep walking while a menu or inventory is open. |

## Building
```
./gradlew build
```
The jar is written to `build/libs/`. Java 25 is required.

## Credits
- **wwrpg** — original AutoWalk mod
- **IcanLevitate** — Fabric port
