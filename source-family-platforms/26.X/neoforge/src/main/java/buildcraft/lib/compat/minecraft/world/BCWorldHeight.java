package buildcraft.lib.compat.minecraft.world;

import net.minecraft.world.level.LevelHeightAccessor;

/** Server-safe world bounds. Upper bound is exclusive, including in negative-height dimensions. */
public final class BCWorldHeight {
    private BCWorldHeight() {}
    public static int min(LevelHeightAccessor world) { return world.getMinY(); }
    public static int maxExclusive(LevelHeightAccessor world) { return min(world) + world.getHeight(); }
}
