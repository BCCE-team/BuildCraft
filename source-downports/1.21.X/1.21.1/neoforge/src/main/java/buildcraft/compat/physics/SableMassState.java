package buildcraft.compat.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.neoforged.fml.ModList;

/** Bridges a derived, bounded mass class to Sable's native BlockState properties.
 * No direct Sable classes, reflection, mixins, or hard dependency are needed.
 */
public final class SableMassState {
    public static final IntegerProperty MASS = IntegerProperty.create("bc_physics_mass", 0, PhysicalMassBalance.MAX_STEP);
    private SableMassState() {}

    private static final class Availability {
        private static final boolean PRESENT = ModList.get().isLoaded("sable");
    }

    public static boolean due(BlockEntity tile, boolean initialized) {
        Level level = tile.getLevel();
        if (level == null || level.isClientSide || tile.isRemoved() || !Availability.PRESENT) return false;
        // Spread scans over 20 ticks. Recompute once immediately after loading/placement.
        return !initialized || Math.floorMod(level.getGameTime(), 20) == Math.floorMod(tile.getBlockPos().asLong(), 20);
    }

    /** Keep every other state property intact and never replace the block entity. */
    public static boolean sync(BlockEntity tile, int step) {
        Level level = tile.getLevel();
        if (level == null || level.isClientSide || tile.isRemoved() || !Availability.PRESENT) return false;
        BlockPos pos = tile.getBlockPos();
        if (level.getBlockEntity(pos) != tile) return false;
        BlockState state = level.getBlockState(pos);
        if (!state.hasProperty(MASS)) return false;
        if (state.getValue(MASS) == step) return true;
        // Same block, new state. Sable observes the chunk state transition and
        // applies the mass/inertia delta. No neighbour, redstone or topology update.
        return level.setBlock(pos, state.setValue(MASS, step), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }
}
