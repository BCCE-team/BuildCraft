# 26.1.2 API, library, core and energy baseline

`buildcraft.api.v2` remains loader-neutral in `source-shared`. 26.1.2 has one
explicit public overlay, `CountedIngredient`, because vanilla removed
`Ingredient.of(ItemStack)` and changed tag lookup.

`buildcraft.lib` has a separate 26.X family copy because it owns Minecraft-facing compatibility facades, client model/render bridges, registries, menus, persistence and recipe serialization. Versioned facades use the `mc2612` / `neoforge2612` namespace so later 26.X changes cannot silently modify the 1.21.11 implementation.

`buildcraft.core` has target-specific source for 26.1.2 screen dimensions,
render extraction, saved-data identifiers and recipe generation. The core slice
also compiles its map-zone implementation. Compile-only bridges in
`version-src/26.1.2-neoforge/core-port-stubs` preserve the core-only source
slice's compatibility; they are not runtime or release sources.

`buildcraft.energy` now has its 26.X GUI-extraction, fixed-size-screen,
renderer-state, worldgen and client-fluid-fog adaptations. Its verifier links
against the real API/lib/core outputs. The only later-module connection is the
`IItemPipe` marker in `energy-port-stubs`, used by the engine interaction guard;
it is compile-only and is not part of the runtime or release artifact.

Other gameplay modules remain outside this slice and will receive their own
26.X family ports.
