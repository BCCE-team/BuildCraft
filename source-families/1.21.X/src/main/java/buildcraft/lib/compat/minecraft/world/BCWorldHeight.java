//? source if >=1.21.1
package buildcraft.lib.compat.minecraft.world;

import net.minecraft.world.level.LevelHeightAccessor;

/** Server-safe world bounds. Upper bound is exclusive, including in negative-height dimensions. */
public final class BCWorldHeight {
    private BCWorldHeight() {}
//? if >=1.21.11 {
    public static int min(LevelHeightAccessor world) { return world.getMinY(); }
//? } else {
    public static int min(LevelHeightAccessor world) { return world.getMinBuildHeight(); }
//? }
    public static int maxExclusive(LevelHeightAccessor world) { return min(world) + world.getHeight(); }
}
