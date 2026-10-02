# 26.1.2 API and library baseline

`buildcraft.api.v2` remains loader-neutral in `source-shared`. 26.1.2 has one
explicit public overlay, `CountedIngredient`, because vanilla removed
`Ingredient.of(ItemStack)` and changed tag lookup.

`buildcraft.lib` has a separate 26.X family copy because it owns Minecraft-facing compatibility facades, client model/render bridges, registries, menus, persistence and recipe serialization. Versioned facades use the `mc2612` / `neoforge2612` namespace so later 26.X changes cannot silently modify the 1.21.11 implementation.

The target inherits gameplay modules only as compile context. They are not part
of this API/lib slice and will receive their own 26.X family ports.
