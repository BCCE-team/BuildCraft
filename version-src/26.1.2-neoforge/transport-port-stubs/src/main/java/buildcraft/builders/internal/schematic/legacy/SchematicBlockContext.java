package buildcraft.builders.internal.schematic.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Compile-only builders bridge; excluded from the production artifact. */
public final class SchematicBlockContext {
    public final Level world;
    public final BlockPos pos;

    public SchematicBlockContext(Level world, BlockPos pos) {
        this.world = world;
        this.pos = pos;
    }
}
