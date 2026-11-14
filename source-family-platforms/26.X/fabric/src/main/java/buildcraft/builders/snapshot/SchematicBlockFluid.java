/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.snapshot;

import buildcraft.lib.compat.NbtCompat;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.core.InvalidInputDataException;
import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.builders.internal.schematic.legacy.SchematicBlockContext;
import buildcraft.lib.misc.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import buildcraft.lib.compat.SavedBlockStateNbt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import buildcraft.lib.fluid.FluidStack;
import buildcraft.lib.fluid.FabricFluidProperties;

public class SchematicBlockFluid implements ISchematicBlock {
    @Override
    public BlockState getBlockStateForRender() {
        return blockState;
    }

    private BlockState blockState;
    private boolean isFlowing;

    @SuppressWarnings("unused")
    public static boolean predicate(SchematicBlockContext context) {
        // Waterlogged ordinary blocks must stay default block schematics so their block state is rebuilt.
        // This schematic is only for actual fluid blocks, matching the BC7/BC8 split.
        return BlockUtil.getFluidWithFlowing(context.world, context.pos) != Fluids.EMPTY
            && BlockUtil.getFluid(context.block) != Fluids.EMPTY;
    }

    @Override
    public void init(SchematicBlockContext context) {
        blockState = context.blockState;
        isFlowing = BlockUtil.getFluid(context.world, context.pos) == Fluids.EMPTY;
    }

    @Nonnull
    @Override
    public Set<BlockPos> getRequiredBlockOffsets() {
        return Stream.concat((Direction.Plane.HORIZONTAL.stream()), Stream.of(Direction.DOWN))
            .map(Direction::getUnitVec3i)
            .map(v -> new BlockPos(v.getX(), v.getY(), v.getZ()))
            .collect(Collectors.toSet());
    }

    @Nonnull
    @Override
    public List<FluidStack> computeRequiredFluids(Level level) {
        Fluid fluid = BlockUtil.getFluidWithoutFlowing(blockState);
        if (fluid == Fluids.EMPTY) {
            return Collections.emptyList();
        }
        return Collections.singletonList(new FluidStack(fluid, 1000));
    }

    @Override
    public SchematicBlockFluid getRotated(Rotation rotation) {
        SchematicBlockFluid schematicBlock = SchematicBlockManager.createCleanCopy(this);
        schematicBlock.blockState = blockState;
        schematicBlock.isFlowing = isFlowing;
        return schematicBlock;
    }

    @Override
    public boolean canBuild(Level world, BlockPos blockPos) {
        return world.isEmptyBlock(blockPos) ||
            BlockUtil.getFluidWithFlowing(world, blockPos) == BlockUtil.getFluidWithFlowing(blockState.getBlock()) &&
                BlockUtil.getFluid(world, blockPos) == Fluids.EMPTY;
    }

    @Override
    public boolean build(Level world, BlockPos blockPos) {
        return buildInternal(world, blockPos, null, false);
    }

    @Override
    public boolean build(Level world, BlockPos blockPos, Player actor) {
        return buildInternal(world, blockPos, actor, true);
    }

    private boolean buildInternal(Level world, BlockPos blockPos, Player actor, boolean firePlaceEvent) {
        if (blockState == null || BlockUtil.getFluid(blockState.getBlock()) == Fluids.EMPTY
            || blockState.getFluidState().isEmpty()
            || isFlowing == blockState.getFluidState().isSource()) return false;
        if (isFlowing) {
            return true;
        }
        boolean placed = firePlaceEvent
            ? BlockUtil.placeBlock(world, blockPos, blockState, actor, Direction.UP, 11)
            : world.setBlock(blockPos, blockState, 11);
        if (placed) {
            Stream.concat(
                Stream.of(Direction.values())
                    .map(Direction::getUnitVec3i)
                    .map(v -> new BlockPos(v.getX(), v.getY(), v.getZ())),
                Stream.of(BlockPos.ZERO)
            )
                .map(blockPos::offset)
                .forEach(updatePos -> world.updateNeighborsAt(updatePos, blockState.getBlock()));
            return true;
        }
        return false;
    }

    @Override
    public boolean buildWithoutChecks(Level world, BlockPos blockPos) {
        return world.setBlock(blockPos, blockState, 0);
    }

    @Override
    public boolean isBuilt(Level world, BlockPos blockPos) {
        return isFlowing || BlockUtil.blockStatesEqual(blockState, world.getBlockState(blockPos));
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag nbt = new CompoundTag();
        nbt.put("blockState", SavedBlockStateNbt.write(blockState));
        nbt.putBoolean("isFlowing", isFlowing);
        return nbt;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) throws InvalidInputDataException {
        blockState = SavedBlockStateNbt.read(NbtCompat.getCompound(nbt, "blockState"));
        isFlowing = NbtCompat.getBoolean(nbt, "isFlowing");
        // The snapshot's schematic-type discriminator is not trusted. Re-run the
        // fluid factory invariant on loaded data, including the source/flowing bit.
        if (blockState == null || BlockUtil.getFluid(blockState.getBlock()) == Fluids.EMPTY
            || blockState.getFluidState().isEmpty()
            || isFlowing == blockState.getFluidState().isSource()) {
            throw new InvalidInputDataException("Fluid schematic requires an actual source/flowing fluid block");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        SchematicBlockFluid that = (SchematicBlockFluid) o;

        return isFlowing == that.isFlowing && blockState.equals(that.blockState);
    }

    @Override
    public int hashCode() {
        int result = blockState.hashCode();
        result = 31 * result + (isFlowing ? 1 : 0);
        return result;
    }
}
