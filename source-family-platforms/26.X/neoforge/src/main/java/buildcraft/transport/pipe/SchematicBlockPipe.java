/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.pipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.core.InvalidInputDataException;
import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.builders.internal.schematic.legacy.SchematicBlockContext;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.transport.BCTransportBlocks;
import buildcraft.transport.block.BlockPipeHolder;
import buildcraft.transport.tile.TilePipeHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import net.minecraft.core.registries.BuiltInRegistries;
public class SchematicBlockPipe implements ISchematicBlock {
    private CompoundTag tileNbt;
    private Rotation tileRotation = Rotation.NONE;
    
    private List<ItemStack> requiredItems = null;
    private List<FluidStack> requFluidStacks = null;

    public static boolean predicate(SchematicBlockContext context) {
        return context.world.getBlockState(context.pos).getBlock() == BCTransportBlocks.pipeHolder.get();
    }

    @Override
    public void init(SchematicBlockContext context) {
        BlockEntity tileEntity = context.world.getBlockEntity(context.pos);
        if (!(tileEntity instanceof TilePipeHolder)) {
            throw new IllegalStateException("Pipe schematic was created without a pipe block entity at " + context.pos);
        }
        tileNbt = ensurePipeBlockEntityId(tileEntity.saveWithFullMetadata(context.world.registryAccess()));
        requiredItems = null;
        requFluidStacks = null;
    }

    @Nonnull
    @Override
    public List<ItemStack> computeRequiredItems(Level level) {
    	if(requiredItems == null)
    		buildRequireCache(level);
    	return requiredItems;
    }
    
    

    @Override
	public List<FluidStack> computeRequiredFluids(Level level) {
		if(requFluidStacks == null)
			buildRequireCache(level);
		return requFluidStacks;
	}
    
    private void buildRequireCache(Level level) {
        requiredItems = List.of();
        requFluidStacks = List.of();
        if (!(level instanceof ServerLevel serverLevel) || tileNbt == null) {
            return;
        }

        BlockPipeHolder pipeBlock = BCTransportBlocks.pipeHolder.get();
        BlockState defaultBlockState = pipeBlock.defaultBlockState();
        BlockEntity tile = BlockEntity.loadStatic(BlockPos.ZERO, defaultBlockState, ensurePipeBlockEntityId(tileNbt), serverLevel.registryAccess());
        if (tile == null) {
            BCLog.logger.warn("Unable to restore pipe block entity while calculating schematic requirements");
            return;
        }

        tile.setLevel(serverLevel);
        List<ItemStack> require = pipeBlock.getDrops(defaultBlockState, new LootParams.Builder(serverLevel)
            .withOptionalParameter(LootContextParams.ORIGIN, Vec3.ZERO)
            .withOptionalParameter(LootContextParams.BLOCK_ENTITY, tile));
        tile.setRemoved();

        List<ItemStack> nonFluidItems = require.stream()
            .filter(item -> FluidUtil.getFluidHandler(item).isEmpty())
            .toList();
        List<ItemStack> containerItems = new ArrayList<>();

        requFluidStacks = require.stream()
            .map(FluidUtil::getFluidHandler)
            .map(optional -> optional.map(fluidHandler -> {
                FluidStack fluid = fluidHandler.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
                ItemStack container = fluidHandler.getContainer();
                if (!container.isEmpty()) {
                    containerItems.add(container);
                }
                return fluid;
            }).orElse(FluidStack.EMPTY))
            .filter(fluid -> !fluid.isEmpty())
            .toList();

        containerItems.addAll(nonFluidItems);
        requiredItems = containerItems;
    }

	@Override
    public SchematicBlockPipe getRotated(Rotation rotation) {
        SchematicBlockPipe schematicBlock = new SchematicBlockPipe();
        schematicBlock.tileNbt = tileNbt == null ? null : tileNbt.copy();
        schematicBlock.tileRotation = tileRotation.getRotated(rotation);
        return schematicBlock;
    }

    @Override
    public boolean canBuild(Level world, BlockPos blockPos) {
        return world.isEmptyBlock(blockPos);
    }

    @SuppressWarnings("Duplicates")
    @Override
    public boolean build(Level world, BlockPos blockPos) {
        if (world.setBlock(blockPos, BCTransportBlocks.pipeHolder.get().defaultBlockState(), 11)) {
            BlockEntity tileEntity = BlockEntity.loadStatic(blockPos, BCTransportBlocks.pipeHolder.get().defaultBlockState(), ensurePipeBlockEntityId(tileNbt), world.registryAccess());
            if (tileEntity != null) {
                tileEntity.setLevel(world);
                world.setBlockEntity(tileEntity);
                if (tileRotation != Rotation.NONE && tileEntity instanceof TilePipeHolder pipeTile) {
                	pipeTile.rotate(tileRotation);
                }
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("Duplicates")
    @Override
    public boolean buildWithoutChecks(Level world, BlockPos blockPos) {
        if (world.setBlock(blockPos, BCTransportBlocks.pipeHolder.get().defaultBlockState(), 0)) {
            BlockEntity tileEntity = BlockEntity.loadStatic(blockPos, BCTransportBlocks.pipeHolder.get().defaultBlockState(), ensurePipeBlockEntityId(tileNbt), world.registryAccess());
            if (tileEntity != null) {
                tileEntity.setLevel(world);
                world.setBlockEntity(tileEntity);
                world.updateNeighbourForOutputSignal(blockPos, BCTransportBlocks.pipeHolder.get());
                if (tileRotation != Rotation.NONE && tileEntity instanceof TilePipeHolder pipeTile) {
                	pipeTile.rotate(tileRotation);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isBuilt(Level world, BlockPos blockPos) {
        if (world.getBlockState(blockPos).getBlock() != BCTransportBlocks.pipeHolder.get()) {
            return false;
        }

        BlockEntity worldTile = world.getBlockEntity(blockPos);
        if (!(worldTile instanceof TilePipeHolder tile)) {
            return false;
        }

        Pipe worldPipe = tile.getPipe();
        if (worldPipe == null || worldPipe == Pipe.EMPTY || worldPipe.getDefinition() == null) {
            return false;
        }

        // Runtime connection/flow state changes after placement. Comparing the complete tile NBT would make a
        // correctly placed pipe immediately become "wrong" and cause builders to replace it forever.
        CompoundTag expectedPipe = tileNbt == null ? new CompoundTag() : tileNbt.getCompound("pipe");
        String expectedDefinition = expectedPipe.getString("def");
        if (!expectedDefinition.isEmpty()
                && !expectedDefinition.equals(worldPipe.getDefinition().identifier.toString())) {
            return false;
        }

        Tag expectedColour = expectedPipe.get("col");
        if (expectedColour == null) {
            expectedColour = NBTUtilBC.writeEnum(null);
        }
        return expectedColour.equals(NBTUtilBC.writeEnum(worldPipe.getColour()));
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SchematicBlockPipe that)) {
            return false;
        }
        return tileRotation == that.tileRotation &&
            Objects.equals(normalizeTileNbt(tileNbt), normalizeTileNbt(that.tileNbt));
    }

    @Override
    public int hashCode() {
        return Objects.hash(normalizeTileNbt(tileNbt), tileRotation);
    }

    private static CompoundTag normalizeTileNbt(CompoundTag tag) {
        CompoundTag copy = ensurePipeBlockEntityId(tag);
        copy.remove("x");
        copy.remove("y");
        copy.remove("z");
        return copy;
    }

    private static CompoundTag ensurePipeBlockEntityId(CompoundTag tag) {
        CompoundTag copy = tag == null ? new CompoundTag() : tag.copy();
        if (!copy.contains("id", Tag.TAG_STRING)) {
            ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(BCTransportBlocks.PIPE_HOLDER_BE.get());
            if (id != null) {
                copy.putString("id", id.toString());
            }
        }
        return copy;
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag nbt = new CompoundTag();
        nbt.put("tileNbt", ensurePipeBlockEntityId(tileNbt));
        nbt.put("tileRotation", NBTUtilBC.writeEnum(tileRotation));
        return nbt;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) throws InvalidInputDataException {
        tileNbt = ensurePipeBlockEntityId(nbt.getCompound("tileNbt"));
        tileRotation = NBTUtilBC.readEnum(nbt.get("tileRotation"), Rotation.class);
        requiredItems = null;
        requFluidStacks = null;
    }
}
