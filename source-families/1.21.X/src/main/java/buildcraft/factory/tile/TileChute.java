/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.factory.tile;

import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;
import buildcraft.api.v2.energy.MjAmount;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import java.util.stream.Stream;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.mj.MjBattery;
import buildcraft.lib.internal.mj.MjCapabilityHelper;
import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.block.BlockChute;
import buildcraft.factory.container.ContainerChute;
import buildcraft.lib.block.BlockBCBase_Neptune;
import buildcraft.lib.inventory.ItemTransactorHelper;
import buildcraft.lib.inventory.NoSpaceTransactor;
import buildcraft.lib.inventory.TransactorEntityItem;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.misc.BoundingBoxUtil;
import buildcraft.lib.internal.mj.MjBatteryReceiver;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.lib.tile.item.ItemHandlerManager.EnumAccess;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

public class TileChute extends TileBC_Neptune implements IDebuggable, MenuProvider {
    private static final ResourceLocation ADVANCEMENT_DID_INSERT = ResourceLocation.parse("buildcraftfactory:retired_hopper");

    private static final int PICKUP_MAX = 3;

    public final ItemHandlerSimple inv = itemManager.addInvHandler(
        "inv",
        4,
        EnumAccess.INSERT,
        EnumPipePart.VALUES
    );

    private final MjBattery battery = new MjBattery(1 * MjAmount.MICRO_MJ_PER_MJ);
    private int progress = 0;

    public TileChute(BlockPos pos, BlockState state) {
        super(BCFactoryBlocks.ENTITYBLOCKCHUTE.get(), pos, state);
        caps.addProvider(new MjCapabilityHelper(new MjBatteryReceiver(battery)));
    }

    public static boolean hasInventoryAtPosition(BlockGetter world, BlockPos pos, Direction side) {
        BlockEntity tile = world.getBlockEntity(pos);
        return ItemTransactorHelper.getTransactor(tile, side.getOpposite()) != NoSpaceTransactor.INSTANCE;
    }

    private void pickupItems(Direction currentSide) {
        AABB aabb = BoundingBoxUtil.extrudeFace(getBlockPos(), currentSide, 0.25);
        int count = PICKUP_MAX;
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, aabb, EntitySelector.ENTITY_STILL_ALIVE)) {
            int moved = ItemTransactorHelper.move(new TransactorEntityItem(entity), inv, count);
            count -= moved;
            if (count <= 0) {
                return;
            }
        }
    }

    private void putInNearInventories(Direction currentSide) {
        boolean[] didWork = { false };
        List<Direction> sides = new ArrayList<>(Arrays.asList(Direction.values()));
        Collections.shuffle(sides, new Random());
        sides.removeIf(Predicate.isEqual(currentSide));
        Stream.concat(
            sides.stream().map(side -> ItemTransactorHelper.getTransactor(
                level.getBlockEntity(worldPosition.offset(side.getNormal())),
                side.getOpposite()
            )),
            sides.stream().flatMap(side ->
                level.getEntitiesOfClass(Entity.class, new AABB(worldPosition.offset(side.getNormal()))).stream()
                    .filter(entity -> !(entity instanceof LivingEntity))
                    .map(entity -> ItemTransactorHelper.getTransactorForEntity(entity, side.getOpposite()))
            )
        )
            .filter(Predicate.isEqual(NoSpaceTransactor.INSTANCE).negate())
            .forEach(transactor -> {
                if (ItemTransactorHelper.move(inv, transactor, 1) > 0) {
                    didWork[0] = true;
                }
            });
        if (didWork[0]) {
            AdvancementUtil.unlockAdvancement(getOwner().getId(), ADVANCEMENT_DID_INSERT);
        }
    }

    @Override
    public void update() {
        if (level == null || level.isClientSide) {
            return;
        }

        if (!(level.getBlockState(worldPosition).getBlock() instanceof BlockChute)) {
            return;
        }

        battery.tick(getLevel(), getBlockPos());

        Direction currentSide = level.getBlockState(worldPosition).getValue(BlockBCBase_Neptune.BLOCK_FACING_6);

        int target = 100000;
        if (currentSide == Direction.UP) {
            progress += 1000; // can be free because of gravity
        }
        progress += battery.extractPower(0, target - progress);

        if (progress >= target) {
            progress = 0;
            pickupItems(currentSide);
        }

        putInNearInventories(currentSide);
    }

    @Override
    protected void readData(BCValueInput bcData) {
        HolderLookup.Provider registries = bcData.registries();
        super.readData(bcData);
        progress = bcData.readInt("progress");
        battery.deserializeNBT(registries, bcData.readCompound("battery"));
    }

    @Override
    protected void writeData(BCValueOutput bcData) {
        CompoundTag nbt = bcData.tag();
        HolderLookup.Provider registries = bcData.registries();
        super.writeData(bcData);
        bcData.writeInt("progress", progress);
        nbt.put("battery", battery.serializeNBT(registries));
    }

    @Override
    public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(this, buffer -> buffer.writeBlockPos(worldPosition));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new ContainerChute(id, inventory, inv, ContainerLevelAccess.create(level, worldPosition));
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        left.add("battery = " + battery.getDebugString());
        left.add("progress = " + progress);
    }
}
