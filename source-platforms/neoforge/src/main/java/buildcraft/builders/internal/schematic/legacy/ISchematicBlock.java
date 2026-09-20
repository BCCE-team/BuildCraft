package buildcraft.builders.internal.schematic.legacy;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import buildcraft.lib.internal.core.InvalidInputDataException;
import buildcraft.api.v2.schematic.SnapshotElement;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.fluids.FluidStack;

public interface ISchematicBlock extends SnapshotElement {
    @Override
    default ResourceLocation typeId() {
        return SchematicBlockFactoryRegistry.getFactoryByInstance(this).name;
    }

    void init(SchematicBlockContext context);

    default boolean isAir() {
        return false;
    }

    @Nonnull
    default Set<BlockPos> getRequiredBlockOffsets() {
        return Collections.emptySet();
    }

    @Nonnull
    default List<ItemStack> computeRequiredItems(Level level) {
        return Collections.emptyList();
    }

    /** Items required before the block itself can be placed. Inventory contents should not be returned here. */
    @Nonnull
    default List<ItemStack> computeRequiredItemsForPlacement(Level level) {
        return computeRequiredItems(level);
    }

    /** Items that should be inserted into the block after it has been placed. */
    @Nonnull
    default List<ItemStack> computeDeferredRequiredItems(Level level) {
        return Collections.emptyList();
    }

    /** Returns the deferred items that are still absent from an already placed block. */
    @Nonnull
    default List<ItemStack> computeMissingDeferredRequiredItems(Level level, BlockPos blockPos) {
        return computeDeferredRequiredItems(level);
    }

    /**
     * Attempts to insert a deferred item into an already placed block.
     *
     * @return the part of {@code stack} that was not accepted.
     */
    @Nonnull
    default ItemStack insertDeferredItem(Level level, BlockPos blockPos, ItemStack stack, boolean simulate) {
        return stack;
    }

    @Nonnull
    default List<FluidStack> computeRequiredFluids(Level level) {
        return Collections.emptyList();
    }

    ISchematicBlock getRotated(Rotation rotation);

    boolean canBuild(Level world, BlockPos blockPos);

    default boolean isReadyToBuild(Level world, BlockPos blockPos) {
        return true;
    }

    boolean build(Level world, BlockPos blockPos);

    /**
     * Places this schematic block as an automated player action. Implementations that do not need an actor use
     * the actor-independent build path through the default bridge.
     */
    default boolean build(Level world, BlockPos blockPos, @Nullable Player actor) {
        return build(world, blockPos);
    }

    boolean buildWithoutChecks(Level world, BlockPos blockPos);

    boolean isBuilt(Level world, BlockPos blockPos);

    CompoundTag serializeNBT();

    /** @throws InvalidInputDataException If the input data wasn't correct or didn't make sense. */
    void deserializeNBT(CompoundTag nbt) throws InvalidInputDataException;
}
