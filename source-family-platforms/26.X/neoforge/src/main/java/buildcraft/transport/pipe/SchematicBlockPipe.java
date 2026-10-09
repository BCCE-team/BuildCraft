//? source if >=26.3
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
import net.minecraft.resources.Identifier;
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
import net.neoforged.neoforge.transfer.fluid.FluidUtil;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.minecraft.core.NonNullList;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

import net.minecraft.core.registries.BuiltInRegistries;
import buildcraft.lib.compat.NbtCompat;
public class SchematicBlockPipe implements ISchematicBlock {
    private CompoundTag tileNbt;
    private Rotation tileRotation = Rotation.NONE;
    
    private List<ItemStack> requiredItems = null;
    private List<FluidStack> requFluidStacks = null;

    public static boolean predicate(SchematicBlockContext context) {
        return context.world.getBlockState(context.pos).getBlock() == BCTransportBlocks.pipeHolder.get();
    }

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
    public List<ItemStack> computeRequiredItems(Level level) {
    	if(requiredItems == null)
    		buildRequireCache(level);
    	return requiredItems;
    }
    
    

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

        List<ItemStack> items = new ArrayList<>();
        List<FluidStack> fluids = new ArrayList<>();
        for (ItemStack original : require) {
            if (original.isEmpty()) continue;
            ItemStacksResourceHandler stackHolder = new ItemStacksResourceHandler(NonNullList.of(ItemStack.EMPTY, original.copy()));
            var access = ItemAccess.forHandlerIndexStrict(stackHolder, 0).oneByOne();
            var handler = access.getCapability(Capabilities.Fluid.ITEM);
            if (handler != null) {
                try (Transaction tx = Transaction.openRoot()) {
                    for (int i = 0; i < handler.size(); ++i) {
                        FluidResource fluid = handler.getResource(i);
                        if (fluid.isEmpty()) continue;
                        int amount = handler.getAmountAsInt(i);
                        int extracted = handler.extract(i, fluid, amount, tx);
                        if (extracted > 0) fluids.add(fluid.toStack(extracted));
                    }
                    tx.commit();
                }
            }
            var remainder = stackHolder.getResource(0);
            if (!remainder.isEmpty()) items.add(remainder.toStack(stackHolder.getAmountAsInt(0)));
        }
        requiredItems = items;
        requFluidStacks = fluids;
    }

    public SchematicBlockPipe getRotated(Rotation rotation) {
        SchematicBlockPipe schematicBlock = new SchematicBlockPipe();
        schematicBlock.tileNbt = tileNbt == null ? null : tileNbt.copy();
        schematicBlock.tileRotation = tileRotation.getRotated(rotation);
        return schematicBlock;
    }

    public boolean canBuild(Level world, BlockPos blockPos) {
        return world.isEmptyBlock(blockPos);
    }

    @SuppressWarnings("Duplicates")
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
        CompoundTag expectedPipe = tileNbt == null ? new CompoundTag() : NbtCompat.getCompound(tileNbt, "pipe");
        String expectedDefinition = NbtCompat.getString(expectedPipe, "def");
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
        if (!NbtCompat.contains(copy, "id", Tag.TAG_STRING)) {
            Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(BCTransportBlocks.PIPE_HOLDER_BE.get());
            if (id != null) {
                copy.putString("id", id.toString());
            }
        }
        return copy;
    }

    public CompoundTag serializeNBT() {
        CompoundTag nbt = new CompoundTag();
        nbt.put("tileNbt", ensurePipeBlockEntityId(tileNbt));
        nbt.put("tileRotation", NBTUtilBC.writeEnum(tileRotation));
        return nbt;
    }

    public void deserializeNBT(CompoundTag nbt) throws InvalidInputDataException {
        tileNbt = ensurePipeBlockEntityId(NbtCompat.getCompound(nbt, "tileNbt"));
        tileRotation = NBTUtilBC.readEnum(nbt.get("tileRotation"), Rotation.class);
        requiredItems = null;
        requFluidStacks = null;
    }
}
