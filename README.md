# BuildCraft Community Edition

## About the project

BuildCraft Community Edition combines the newer foundation of **BuildCraft 8.0.0** with mechanics that were present in **BuildCraft 7.1.27** but were not completed or carried over into BC8, including robotics and building-related features.

The goal of the project is to preserve and continue the classic BuildCraft experience on newer Minecraft versions, restoring missing content and completing unfinished ideas from the original mod without changing its core identity.

## Issue reports

When reporting a problem, always include:

- the Minecraft version;
- the mod loader and its version;
- the exact BuildCraft Community Edition build;
- the relevant log or crash report.

## Roadmap 2.0

- Drop support for 1.21.1 Forge [✔]
- New API system [✔]
- FE compatibility [✔]
- Port to 1.20.1 Fabric
- Port to 1.21.11 Fabric/NeoForge [X/✔]
- Port to 26.X Fabric/NeoForge

## Supported versions

- Minecraft 1.19.2 — Forge
- Minecraft 1.20.1 — Forge
- Minecraft 1.21.1 — NeoForge
- Minecraft 1.21.11 — NeoForge

## Multi-version build and source architecture

BCCE is split into two independent Stonecutter/Gradle build generations:

- **legacy** — Minecraft 1.19.2 and 1.20.1;
- **modern** — Minecraft 1.21.1+ targets.

Each generation has its own Gradle Wrapper and Stonecutter controller under `builds/legacy` or `builds/modern`. This allows the modern build to move to newer Gradle, Java and loader toolchains without breaking the older Forge targets.

Every target is assembled from five source layers, with each later layer able to override an earlier one:

```text
source-shared
+ source-families/<family>
+ source-platforms/<loader>
+ source-family-platforms/<family>/<loader>
+ version-src/<target>
```

Small Minecraft-version differences may use localized Stonecutter conditions inside family/family-platform files. Loader-wide code belongs in `source-platforms`; loader code tied to one source family belongs in `source-family-platforms`. `version-src` is reserved for irreducible target-specific files and resources. Per-target metadata is centralized in `build-config/targets.properties`.

The 1.19.2 implementation is the gameplay reference, but source code is allowed to differ when newer Minecraft APIs require another implementation. The compatibility target is player-visible behaviour: **different implementation, indistinguishable BuildCraft**.

See [`SOURCE_FAMILIES.md`](SOURCE_FAMILIES.md) for layout rules, parity policy and build commands.

## Addons developed by BCCE team:

- **BuildCraft Community Edition Localizations**
  - [CurseForge](https://www.curseforge.com/minecraft/mc-mods/buildcraft-community-edition-localizations) [Modrinth](https://modrinth.com/mod/buildcraft-community-edition-localizations) [GitHub](https://github.com/CurativeTree/BuildCraft/tree/Localizations)
- **IronTanks Community Edition**
   - [CurseForge](https://www.curseforge.com/minecraft/mc-mods/iron-tanks-community-edition) [Modrinth](https://modrinth.com/mod/irontanks-community-edition) [GitHub](https://github.com/shipovskijkorp/IronTanks-Community-Edition)
## Credits

### Original BuildCraft

- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/buildcraft) [Modrinth](https://modrinth.com/mod/buildcraft) [GitHub](https://github.com/BuildCraft/BuildCraft) 

Special thanks to the original BuildCraft team and all contributors who made BuildCraft one of the most iconic technical Minecraft mods.

BuildCraft Community Edition is an unofficial community port based on BuildCraft 8.0.0 and BuildCraft 7.1.27. All original BuildCraft work belongs to its respective authors and contributors.

### Community Edition port

Developed and ported by:

- CurativeTree
- ShipovskijKorp

Thanks for helping with development:

- nightovl
- pietruszka
- Jimmy