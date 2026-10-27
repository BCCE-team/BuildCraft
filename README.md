# BuildCraft Community Edition

## About the project

BuildCraft Community Edition (BCCE) combines the newer foundation of **BuildCraft 8.0.0** with mechanics that were present in **BuildCraft 7.1.27** but were not completed or carried over into BC8, including robotics and building-related features.

The goal of the project is to preserve and continue the classic BuildCraft experience on newer Minecraft versions, restoring missing content and completing unfinished ideas from the original mod without changing its core identity.

## Downloads

[CurseForge](https://www.curseforge.com/minecraft/mc-mods/buildcraft-community-edition) | [Modrinth](https://modrinth.com/mod/buildcraft-community-edition) | [GitHub Releases](https://github.com/BCCE-team/BuildCraft/releases)

Make sure to download the version matching your Minecraft version and mod loader.

## Supported versions

| Minecraft | Loader |
| --- | --- |
| 1.19.2 | Forge |
| 1.20.1 | Forge |
| 1.21.1 | NeoForge |
| 1.21.11 | NeoForge |
| 26.1.2 | NeoForge |
| 26.2 | NeoForge |
| 26.3 | NeoForge |

## Roadmap 2.1

- [x] Drop support for 1.21.1 Forge
- [x] Introduce the new API v2 system
- [x] Add Forge Energy (FE) compatibility
- [x] Port to 1.21.11 NeoForge
- [x] Port to 26.x NeoForge
- [x] Extend API v2 to version 2.1
- [ ] Port to 1.20.1 Fabric
- [ ] Port to 1.21.1 Fabric
- [ ] Port to 1.21.11 Fabric
- [ ] Port to 26.x Fabric

## For Modpack Creators

If you're a modpack creator using BCCE, please don't hesitate to reach out to me, no matter how small your modpack is. I'm always happy to hear from modpack authors and receive feedback about how BuildCraft fits into their projects.

Whether you're experiencing compatibility issues, need integrations with other mods, or would benefit from additional configuration options, I'm more than willing to discuss your needs and work on improvements to make BCCE a better fit for your modpack.

You can contact me through [GitHub Issues](https://github.com/BCCE-team/BuildCraft/issues), Discord DMs (`@shipovskijkorp`), or the [My Discord server](https://discord.gg/PTy9m4wxj3).

## Localizations

Starting with **BCCE 8.0.24**, translations are included directly in the main mod. The separate BCCE Localizations addon is no longer required and supported.

If you want to improve existing translations or add a new language, you can contribute to the [BCCE Localizations repository](https://github.com/BCCE-team/Localizations).

## API v2

BCCE provides a modern **API v2** designed to offer a stable, loader-neutral interface for addons and integrations.

The current API version is **2.1.0**, exposed through the `buildcraft.api.v2` namespace.

Loader-specific implementation details remain outside the public API, while common concepts such as energy, fluids, pipes, robotics, machines, schematics, statements, automation, registration and debugging are exposed through shared contracts.

API v2.1 significantly expands addon capabilities, including custom pipe behaviours and flows, robot programs, machine integrations and interactions with BuildCraft's internal systems through supported public interfaces.

Forge Energy (FE) compatibility is integrated without making the public API depend on Forge or NeoForge classes.

Addons should use the public `buildcraft.api.v2` contracts rather than relying on internal implementation packages such as `buildcraft.lib`, `buildcraft.transport` or `buildcraft.factory`, which are not covered by the public API compatibility guarantees.

The long-term goal is to make addons easier to develop and maintain across BCCE's supported Minecraft versions and future loader ports.

Documentation: [BCCE API Documentation](https://bcce-team.github.io/BuildCraft/)

## Multi-version build and source architecture

BCCE has three active Stonecutter/Gradle build generations:

- **old** — Minecraft 1.19.2 and 1.20.1;
- **1.21.X** — Minecraft 1.21.1 and 1.21.11;
- **26.X** — Minecraft 26.1.2, 26.2 and 26.3.

Each generation has its own Gradle Wrapper and Stonecutter controller under `builds/<generation>`.

Every target is assembled from five primary source layers, with each later layer able to override an earlier one:

```text
source-shared
+ source-families/<family>
+ source-platforms/<loader>
+ source-family-platforms/<family>/<loader>
+ version-src/<target>
```

Small Minecraft-version differences may use localized Stonecutter conditions inside family/family-platform files. Larger version-specific adaptations may use explicit `source-downports`, while some source families can inherit implementations from earlier generations.

Loader-wide code belongs in `source-platforms`; loader code tied to one source family belongs in `source-family-platforms`. `version-src` is reserved for irreducible target-specific files and resources. Per-target metadata is centralized in `build-config/targets.properties`.

The 1.19.2 implementation is the gameplay reference, but source code is allowed to differ when newer Minecraft APIs require another implementation.

The compatibility target is player-visible behaviour: **different implementation, indistinguishable BuildCraft**.

CI builds and tests each maintained target independently. Every target has its own build, GameTest, dedicated-server smoke and client smoke run, while cross-target architecture and parity checks remain global.

See [`SOURCE_FAMILIES.md`](SOURCE_FAMILIES.md) for layout rules, parity policy and build commands.

## Issue reports

Please use the repository [issue forms](https://github.com/BCCE-team/BuildCraft/issues/new/choose) when reporting problems or suggesting changes.

For bug reports, include:

- the Minecraft version;
- the mod loader and its version;
- the BuildCraft Community Edition version (`latest` is accepted when testing the newest release);
- clear reproduction steps;
- relevant logs when available.

Crash reports require a crash log in addition to the information above.

## Addons developed by the BCCE team

### IronTanks Community Edition

[CurseForge](https://www.curseforge.com/minecraft/mc-mods/iron-tanks-community-edition) | [Modrinth](https://modrinth.com/mod/irontanks-community-edition) | [GitHub](https://github.com/BCCE-team/IronTanks)

### Additional Pipes for BuildCraft Community Edition (in dev)

[GitHub](https://github.com/BCCE-team/AdditionalPipes)

## Credits

### Original BuildCraft

[CurseForge](https://www.curseforge.com/minecraft/mc-mods/buildcraft) | [Modrinth](https://modrinth.com/mod/buildcraft) | [GitHub](https://github.com/BuildCraft/BuildCraft)

Special thanks to the original BuildCraft team and all contributors who made BuildCraft one of the most iconic technical Minecraft mods.

BuildCraft Community Edition is an unofficial community port based on BuildCraft 8.0.0 and BuildCraft 7.1.27. All original BuildCraft work belongs to its respective authors and contributors.

### Community Edition port

Developed and ported by:

- CurativeTree
- ShipovskijKorp
- Memesis414
- Smail

Thanks for helping with development:

- nightovl
- pietruszka
- Jimmy
- Louazgre
- RainBat