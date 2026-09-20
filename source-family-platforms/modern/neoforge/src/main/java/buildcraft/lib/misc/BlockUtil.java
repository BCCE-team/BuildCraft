//? source if >=1.21.1
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.misc;

import buildcraft.lib.platform.permission.PlatformWorldActions;
import buildcraft.api.v2.energy.MjAmount;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import buildcraft.core.BCCoreConfig;
import buildcraft.lib.BCLibConfig;
import buildcraft.lib.compat.CompatManager;
import buildcraft.lib.inventory.TransactorEntityItem;
import buildcraft.lib.inventory.filter.StackFilter;
import buildcraft.lib.platform.storage.FluidStorage;
import buildcraft.lib.platform.storage.PlatformStorage;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.NonNullList;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.fml.loading.FMLEnvironment;
import buildcraft.lib.misc.CapUtil;

public final class BlockUtil {

    /** @return A list of itemstacks that are dropped from the block, or null if the block is air */
    @Nullable
    public static NonNullList<ItemStack> getItemStackFromBlock(ServerLevel world, BlockPos pos, GameProfile owner) {
        BlockState state = world.getBlockState(pos);
//    w    Block block = state.getBlock();
        if (state.isAir()) {
            return null;
        }

        // Use Block.getDrops with block-entity context so mod-defined loot hooks remain compatible.
        List<ItemStack> drops = Block.getDrops(state, world, pos, world.getBlockEntity(pos));
        NonNullList<ItemStack> returnList = NonNullList.create();
        returnList.addAll(drops);
        return returnList;
    }

    public static boolean breakBlock(ServerLevel world, BlockPos pos, BlockPos ownerPos, GameProfile owner) {
        return breakBlock(world, pos, BCLibConfig.itemLifespan * 20, ownerPos, owner);
    }

    public static boolean breakBlock(ServerLevel world, BlockPos pos, int forcedLifespan, BlockPos ownerPos,
                                     GameProfile owner) {
        NonNullList<ItemStack> items = NonNullList.create();

        if (breakBlock(world, pos, items, ownerPos, owner)) {
            for (ItemStack item : items) {
                dropItem(world, pos, forcedLifespan, item);
            }
            return true;
        }
        return false;
    }

    /** Posts NeoForge's block break event for the supplied actor. Protection mods use this event for access checks. */
    public static boolean canBreakBlock(ServerLevel world, BlockPos pos, Player actor) {
        return PlatformWorldActions.canBreakBlock(world, pos, actor);
    }

    /** Places as the supplied actor and rolls back when NeoForge's place hook is cancelled. */
    public static boolean placeBlock(Level level, BlockPos pos, BlockState state, @Nullable Player actor,
                                     Direction placedAgainst, int flags) {
        return PlatformWorldActions.placeBlock(level, pos, state, actor, placedAgainst, flags);
    }

    public static boolean harvestBlock(ServerLevel world, BlockPos pos, @Nonnull ItemStack tool, GameProfile owner) {
        return buildcraft.lib.platform.actor.BCActors.withTool(world, owner, pos, tool, fakePlayer -> {
            if (!canBreakBlock(world, pos, fakePlayer)) {
                return false;
            }

            BlockState state = world.getBlockState(pos);
            if (!state.getBlock().canHarvestBlock(state, world, pos, fakePlayer)) {
                return false;
            }

            BlockEntity blockEntity = world.getBlockEntity(pos);
            state.getBlock().playerWillDestroy(world, pos, state, fakePlayer);
            if (!world.removeBlock(pos, false)) {
                return false;
            }
            state.getBlock().destroy(world, pos, state);
            if (!tool.isEmpty()) {
                tool.mineBlock(world, state, pos, fakePlayer);
            }
            state.getBlock().playerDestroy(world, fakePlayer, pos, state, blockEntity, tool);
            return true;
        });
    }

    public static boolean destroyBlock(ServerLevel world, BlockPos pos, @Nonnull ItemStack tool, GameProfile owner) {
        return buildcraft.lib.platform.actor.BCActors.withTool(world, owner, pos, tool, fakePlayer -> {
            if (!canBreakBlock(world, pos, fakePlayer)) {
                return false;
            }

            world.destroyBlock(pos, true);
            return true;
        });
    }

    public static ServerPlayer getFakePlayerWithTool(ServerLevel world, @Nonnull ItemStack tool, GameProfile owner) {
        return getFakePlayerWithTool(world, tool, owner, BlockPos.ZERO);
    }

    public static ServerPlayer getFakePlayerWithTool(ServerLevel world, @Nonnull ItemStack tool, GameProfile owner,
                                                      BlockPos pos) {
        ServerPlayer player = buildcraft.lib.misc.FakePlayerProvider.INSTANCE.getFakePlayer(world, owner, pos);
        int i = 0;

        while (player.getItemInHand(InteractionHand.MAIN_HAND) != tool && i < 9) {
            if (i > 0) {
                player.getInventory().setItem(i-1, ItemStack.EMPTY);;
            }

            player.getInventory().setItem(i, tool);
            i++;
        }

        return player;
    }

    public static boolean breakBlock(ServerLevel world, BlockPos pos, NonNullList<ItemStack> drops, BlockPos ownerPos,
        GameProfile owner) {
        ServerPlayer fakePlayer = buildcraft.lib.misc.FakePlayerProvider.INSTANCE.getFakePlayer(world, owner, ownerPos);
        if (!canBreakBlock(world, pos, fakePlayer)) {
            return false;
        }

        if (!world.getBlockState(pos).isAir() && !world.isClientSide() && world.getGameRules().get(GameRules.BLOCK_DROPS)) {
            drops.addAll(getItemStackFromBlock(world, pos, owner));
        }
        world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        return true;
    }

    public static void dropItem(ServerLevel world, BlockPos pos, int forcedLifespan, ItemStack stack) {
        float var = 0.7F;
        double dx = world.getRandom().nextFloat() * var + (1.0F - var) * 0.5D;
        double dy = world.getRandom().nextFloat() * var + (1.0F - var) * 0.5D;
        double dz = world.getRandom().nextFloat() * var + (1.0F - var) * 0.5D;
        ItemEntity entityitem = new ItemEntity(world, pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz, stack);

        entityitem.lifespan = forcedLifespan;
        entityitem.setDefaultPickUpDelay();;

        world.addFreshEntity(entityitem);
    }

    public static Optional<List<ItemStack>> breakBlockAndGetDrops(ServerLevel world, BlockPos pos,
                                                                  @Nonnull ItemStack tool, GameProfile owner) {
        return breakBlockAndGetDrops(world, pos, tool, owner, false);
    }

    /** @param grabAll If true then this will pickup every item in range of the position, false to only get the items
     *            that the dropped while breaking the block. */
    public static Optional<List<ItemStack>> breakBlockAndGetDrops(ServerLevel world, BlockPos pos,
                                                                  @Nonnull ItemStack tool, GameProfile owner, boolean grabAll) {
        AABB aabb = new AABB(pos).inflate(1);
        Set<Entity> entities;
        if (grabAll) {
            entities = Collections.emptySet();
        } else {
            entities = Sets.newIdentityHashSet();
            entities.addAll(world.getEntitiesOfClass(ItemEntity.class, aabb));
        }
        if (!harvestBlock(world, pos, tool, owner)) {
            // BC8 fell back to a normal destroy when the supplied tool could not harvest the target. Quarry uses a
            // diamond pickaxe for drops, but frame clearing can encounter blocks that require a different tool.
            // Without the fallback those positions are retried forever and look like a lost red-laser task.
            if (!destroyBlock(world, pos, tool, owner)) {
                return Optional.empty();
            }
        }
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemEntity entity : world.getEntitiesOfClass(ItemEntity.class, aabb)) {
            if (entities.contains(entity)) {
                continue;
            }
            TransactorEntityItem transactor = new TransactorEntityItem(entity);
            ItemStack stack;
            while (!(stack = transactor.extract(StackFilter.ALL, 0, Integer.MAX_VALUE, false)).isEmpty()) {
                stacks.add(stack);
            }
        }
        return Optional.of(stacks);
    }

    public static boolean canChangeBlock(Level world, BlockPos pos, GameProfile owner) {
        return canChangeBlock(world.getBlockState(pos), world, pos, owner);
    }

    public static boolean canChangeBlock(BlockState state, Level world, BlockPos pos, GameProfile owner) {
        if (state == null) return true;

        Block block = state.getBlock();
        if (state.isAir()) {
            return true;
        }

        if (isUnbreakableBlock(world, pos, state, owner)) {
            return false;
        }

        if (block == Blocks.LAVA) {
            return false;
        }
        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty() && fluidState.getType().getFluidType().getDensity() >= 3000) {
            return false;
        }

        return true;
    }

    public static float getBlockHardnessMining(Level world, BlockPos pos, BlockState state, GameProfile owner) {
        if (world instanceof ServerLevel) {
            Player fakePlayer = buildcraft.lib.misc.FakePlayerProvider.INSTANCE.getFakePlayer((ServerLevel) world, owner);
            float relativeHardness = state.getDestroyProgress(fakePlayer, world, pos);
            if (relativeHardness <= 0.0F) {
                // Forge's getPlayerRelativeBlockHardness hook returns 0.0F if the hardness is < 0.0F.
                return -1.0F;
            }
        }
        return state.getDestroySpeed(world, pos);
    }

    public static boolean isUnbreakableBlock(Level world, BlockPos pos, BlockState state, GameProfile owner) {
        return getBlockHardnessMining(world, pos, state, owner) < 0;
    }

    public static boolean isUnbreakableBlock(Level world, BlockPos pos, GameProfile owner) {
        return isUnbreakableBlock(world, pos, world.getBlockState(pos), owner);
    }

    /*    /** Returns true if a block cannot be harvested without a tool. */
  /*  public static boolean isToughBlock(Level world, BlockPos pos) {
        return !world.getBlockState(pos).getMaterial().;
    }*/

    public static boolean isFullFluidBlock(Level world, BlockPos pos) {
        return isFullFluidBlock(world.getBlockState(pos), world, pos);
    }

    public static boolean isFullFluidBlock(BlockState state, Level world, BlockPos pos) {
        FluidState fluidState = state.getFluidState();
        return !fluidState.isEmpty() && fluidState.isSource();
    }

    //Only get Source or WaterLogged block
    /** Returns whether normal block placement may replace the current state without excavation. */
    public static boolean isReplaceable(Level world, BlockPos pos) {
        return world.getBlockState(pos).canBeReplaced();
    }

    public static Fluid getFluid(Level world, BlockPos pos) {
        FluidStack fluid = drainBlock(world, pos, false);
        return fluid != null && !fluid.isEmpty() ? fluid.getFluid() : Fluids.EMPTY;
    }

    //check for Source block
    public static Fluid getFluidWithFlowing(Level world, BlockPos pos) {
        FluidState fluidState = world.getFluidState(pos);
        return fluidState.isEmpty() ? Fluids.EMPTY : fluidState.getType();
    }

    /**
     * check is block is FluidBlock
     * */
    public static Fluid getFluid(Block block) {
        if (block instanceof LiquidBlock liquidBlock) {
            return liquidBlock.fluid;
        }
        return Fluids.EMPTY;
    }

    public static Fluid getFluidWithoutFlowing(BlockState state) {
        FluidState fs = state.getFluidState();
        if(!fs.isEmpty()&&fs.isSource()) {
            return fs.getType();
        }
        return Fluids.EMPTY;

    }

    public static Fluid getFluidWithFlowing(Block block) {
        if (block instanceof LiquidBlock) {
            return ((LiquidBlock) block).fluid;
        }
        return Fluids.EMPTY;
    }

    public static FluidStack drainBlock(Level world, BlockPos pos, boolean doDrain) {
        BlockState state = world.getBlockState(pos);
        FluidState fluidState = state.getFluidState();
        if (fluidState.isEmpty() || !fluidState.isSource()) {
            return FluidStack.EMPTY;
        }

        FluidStorage<FluidStack> handler = PlatformStorage.fluids(world, pos, null);
        if (handler != null) {
            return handler.drain(FluidType.BUCKET_VOLUME, !doDrain);
        }

        if (state.getBlock() instanceof BucketPickup bucketPickup) {
            FluidStack result = new FluidStack(fluidState.getType(), FluidType.BUCKET_VOLUME);
            if (!doDrain) {
                return result;
            }
            ItemStack bucket = bucketPickup.pickupBlock(null, world, pos, state);
            return bucket.isEmpty() ? FluidStack.EMPTY : result;
        }

        return FluidStack.EMPTY;
    }
    /** Create an explosion which only affects a single block. */
    public static void explodeBlock(Level world, BlockPos pos) {
    }

    public static long computeBlockBreakPower(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        float hardness = state.getDestroySpeed(world, pos);
        long a =  (long) Math.floor(16 * MjAmount.MICRO_MJ_PER_MJ * ((hardness + 1) * 2) * BCCoreConfig.miningMultiplier);
        return a;
    }

    /** The following functions let you avoid unnecessary chunk loads, which is nice. */
    public static BlockEntity getBlockEntity(Level world, BlockPos pos) {
        return getBlockEntity(world, pos, false);
    }

    public static BlockEntity getBlockEntity(Level world, BlockPos pos, boolean force) {
        return CompatManager.getTile(world, pos, force);
    }

    public static BlockState getBlockState(Level world, BlockPos pos) {
        return getBlockState(world, pos, false);
    }

    public static BlockState getBlockState(Level world, BlockPos pos, boolean force) {
        return CompatManager.getState(world, pos, force);
    }

    public static boolean useItemOnBlock(Level world, Player player, ItemStack stack, BlockPos pos,
                                         Direction direction) {
        if (stack.isEmpty()) {
            return false;
        }

        ItemStack previous = player.getItemInHand(InteractionHand.MAIN_HAND);
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), direction, pos, false);
            UseOnContext context = new UseOnContext(world, player, InteractionHand.MAIN_HAND, stack, hit);

            InteractionResult result = stack.getItem().onItemUseFirst(stack, context);
            if (result == InteractionResult.PASS) {
                result = stack.useOn(context);
            }
            if (result == InteractionResult.PASS) {
                result = stack.getItem().use(world, player, InteractionHand.MAIN_HAND);
            }
            return result.consumesAction();
        } finally {
            player.setItemInHand(InteractionHand.MAIN_HAND, previous);
        }
    }

    public static void onComparatorUpdate(Level world, BlockPos pos, Block block) {
        world.updateNeighbourForOutputSignal(pos, block);
    }

    public static @Nullable ChestBlockEntity getOtherDoubleChest(BlockEntity inv) {
        if (inv instanceof ChestBlockEntity) {
            ChestBlockEntity chest = (ChestBlockEntity) inv;
            BlockState chestb = chest.getBlockState();
            if(chestb.getValue(BlockStateProperties.CHEST_TYPE) == ChestType.SINGLE)
                return null;
            BlockEntity adjacent = inv.getLevel().getBlockEntity(inv.getBlockPos().offset(ChestBlock.getConnectedDirection(chestb).getUnitVec3i()));
            return adjacent instanceof ChestBlockEntity ? (ChestBlockEntity) adjacent : null ;
        }
        return null;
    }

    public static @Nullable Container getCombinedDoubleChestContainer(BlockEntity inv) {
        if (!(inv instanceof ChestBlockEntity chest)
                || chest.getLevel() == null
                || !chest.getBlockState().hasProperty(BlockStateProperties.CHEST_TYPE)) {
            return null;
        }

        ChestBlockEntity other = getOtherDoubleChest(chest);
        if (other == null || other.isRemoved()) {
            return null;
        }

        ChestType type = chest.getBlockState().getValue(BlockStateProperties.CHEST_TYPE);
        return type == ChestType.RIGHT ? new CompoundContainer(other, chest) : new CompoundContainer(chest, other);
    }

    public static <T extends Comparable<T>> BlockState copyProperty(Property<T> property, BlockState dst,
                                                                    BlockState src) {
        return dst.getProperties().contains(property) ? dst.setValue(property, src.getValue(property)) : dst;
    }

    public static <T extends Comparable<T>> int compareProperty(Property<T> property, BlockState a, BlockState b) {
        return a.getValue(property).compareTo(b.getValue(property));
    }

    public static <T extends Comparable<T>> String getPropertyStringValue(BlockState blockState,
                                                                          Property<T> property) {
        return property.getName(blockState.getValue(property));
    }

    public static Map<String, String> getPropertiesStringMap(BlockState blockState,
                                                             Collection<Property<?>> properties) {
        ImmutableMap.Builder<String, String> mapBuilder = new ImmutableMap.Builder<>();
        for (Property<?> property : properties) {
            mapBuilder.put(property.getName(), getPropertyStringValue(blockState, property));
        }
        return mapBuilder.build();
    }

    public static Map<String, String> getPropertiesStringMap(BlockState blockState) {
        return getPropertiesStringMap(blockState, blockState.getProperties());
    }

    public static Comparator<BlockState> blockStateComparator() {
        return (blockStateA, blockStateB) -> {
            Block blockA = blockStateA.getBlock();
            Block blockB = blockStateB.getBlock();
            if (blockA != blockB) {
                return blockA.getDescriptionId().toString().compareTo(blockB.getDescriptionId().toString());
            }
            for (Property<?> property : Sets.intersection(new HashSet<>(blockStateA.getProperties()),
                    new HashSet<>(blockStateB.getProperties()))) {
                int compareResult = BlockUtil.compareProperty(property, blockStateA, blockStateB);
                if (compareResult != 0) {
                    return compareResult;
                }
            }
            return 0;
        };
    }

    public static boolean blockStatesWithoutBlockEqual(BlockState a, BlockState b,
                                                       Collection<Property<?>> ignoredProperties) {
        return Sets.intersection(new HashSet<>(a.getProperties()), new HashSet<>(b.getProperties())).stream()
                .filter(property -> !ignoredProperties.contains(property))
                .allMatch(property -> Objects.equals(a.getValue(property), b.getValue(property)));
    }

    public static boolean blockStatesWithoutBlockEqual(BlockState a, BlockState b) {
        return Sets.intersection(new HashSet<>(a.getProperties()), new HashSet<>(b.getProperties())).stream()
                .allMatch(property -> Objects.equals(a.getValue(property), b.getValue(property)));
    }

    public static boolean blockStatesEqual(BlockState a, BlockState b, Collection<Property<?>> ignoredProperties) {
        return a.getBlock() == b.getBlock()
                && Sets.intersection(new HashSet<>(a.getProperties()), new HashSet<>(b.getProperties())).stream()
                .filter(property -> !ignoredProperties.contains(property))
                .allMatch(property -> Objects.equals(a.getValue(property), b.getValue(property)));
    }

    public static boolean blockStatesEqual(BlockState a, BlockState b) {
        return a.getBlock() == b.getBlock()
                && Sets.intersection(new HashSet<>(a.getProperties()), new HashSet<>(b.getProperties())).stream()
                .allMatch(property -> Objects.equals(a.getValue(property), b.getValue(property)));
    }

    public static Comparator<BlockPos> uniqueBlockPosComparator(Comparator<BlockPos> parent) {
        return (a, b) -> {
            int parentValue = parent.compare(a, b);
            if (parentValue != 0) {
                return parentValue;
            } else if (a.getX() != b.getX()) {
                return Integer.compare(a.getX(), b.getX());
            } else if (a.getY() != b.getY()) {
                return Integer.compare(a.getY(), b.getY());
            } else if (a.getZ() != b.getZ()) {
                return Integer.compare(a.getZ(), b.getZ());
            } else {
                return 0;
            }
        };
    }


/*	public static @Nullable FlowingFluid getFlowingFluidWithState(BlockState state) {
        FluidState fluidState = state.getFluidState();
        if(fluidState.isEmpty())
            return null;
        Fluid fluid = fluidState.getType();
        return fluidState.isSource() : fluid.isSource(fluidState)
    }*/
}
