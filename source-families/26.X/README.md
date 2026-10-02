# 26.1.2 API, library and core baseline

`buildcraft.api.v2` remains loader-neutral in `source-shared`. 26.1.2 has one
explicit public overlay, `CountedIngredient`, because vanilla removed
`Ingredient.of(ItemStack)` and changed tag lookup.

`buildcraft.lib` has a separate 26.X family copy because it owns Minecraft-facing compatibility facades, client model/render bridges, registries, menus, persistence and recipe serialization. Versioned facades use the `mc2612` / `neoforge2612` namespace so later 26.X changes cannot silently modify the 1.21.11 implementation.

`buildcraft.core` has target-specific source for 26.1.2 screen dimensions,
render extraction, saved-data identifiers and recipe generation. The core slice
also compiles its map-zone implementation. Compile-only bridges in
`version-src/26.1.2-neoforge/core-port-stubs` preserve source compatibility
with energy implementations until that module receives its own port; they are
not runtime or release sources.

Other gameplay modules remain outside this slice and will receive their own
26.X family ports.
