/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.factory.container;

import java.io.IOException;

import buildcraft.factory.BCFactoryGuis;
import buildcraft.factory.tile.TileTank;
import buildcraft.lib.fluid.Tank;
import buildcraft.lib.gui.TankContainerData;
import buildcraft.lib.gui.ContainerBCTile;
import buildcraft.lib.gui.MenuBC_Neptune;
import buildcraft.lib.gui.widget.WidgetFluidTank;
import buildcraft.lib.misc.data.IdAllocator;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import buildcraft.lib.fluid.FluidStack;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

/**
 * Tank menu.
 *
 * <p>Fluid id, amount and capacity are synchronized with a BuildCraft container packet instead of vanilla
 * {@link ContainerData} synchronization. Vanilla serializes menu data values as signed 16-bit shorts, even though
 * the Java API exposes ints. That silently truncates addon tanks whose capacity is greater than 32767 mB.</p>
 *
 * <p>The public {@link #data} object remains available for the existing GUI components, but it is only a local view
 * of the three full-width synchronized values. It is deliberately not passed to {@code addDataSlots}.</p>
 */
public class ContainerTank extends ContainerBCTile<TileTank> {
    private static final IdAllocator IDS = MenuBC_Neptune.IDS.makeChild("tank");
    private static final int NET_TANK_STATE = IDS.allocId("TANK_STATE");

    public final ContainerData data = new SyncedTankData();
    public final WidgetFluidTank widgetTank;

    private int syncedFluidId;
    private int syncedFluidAmount;
    private int syncedCapacity;

    private int lastSentFluidId = Integer.MIN_VALUE;
    private int lastSentFluidAmount = Integer.MIN_VALUE;
    private int lastSentCapacity = Integer.MIN_VALUE;

    public ContainerTank(int containerId, Inventory playerInventory, FriendlyByteBuf buffer) {
        this(containerId, playerInventory, null, createLevelAccess(playerInventory, buffer));
    }

    public ContainerTank(int containerId, Inventory playerInventory, TileTank tank, ContainerLevelAccess access) {
        super(BCFactoryGuis.MENU_TANK.get(), playerInventory, containerId, access);

        addFullPlayerInventory(8, 99);

        TileTank actualTank = tile != null ? tile : tank;
        if (actualTank != null) {
            updateLocalState(actualTank);
        }

        Tank guiTank = actualTank != null
            ? actualTank.tank
            : new Tank("tank", 16 * 1000, null);
        widgetTank = addWidget(new WidgetFluidTank(this, guiTank));
    }

    public IdAllocator getIdAllocator() {
        return IDS;
    }

    public void broadcastChanges() {
        super.broadcastChanges();

        if (playerInventory.player.level().isClientSide() || tile == null) {
            return;
        }

        FluidStack fluid = tile.getFluidInTank(0);
        int fluidId = BuiltInRegistries.FLUID.getId(fluid.isEmpty() ? Fluids.EMPTY : fluid.getFluid());
        int amount = fluid.isEmpty() ? 0 : fluid.getAmount();
        int capacity = tile.getTankCapacity(0);

        if (fluidId == lastSentFluidId && amount == lastSentFluidAmount && capacity == lastSentCapacity) {
            return;
        }

        lastSentFluidId = fluidId;
        lastSentFluidAmount = amount;
        lastSentCapacity = capacity;
        updateLocalState(fluidId, amount, capacity);

        sendMessage(NET_TANK_STATE, buffer -> {
            buffer.writeVarInt(fluidId);
            buffer.writeInt(amount);
            buffer.writeInt(capacity);
        });
    }

    public void readMessage(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        if (side == BCNetworkSide.CLIENT && id == NET_TANK_STATE) {
            updateLocalState(buffer.readVarInt(), buffer.readInt(), buffer.readInt());
            return;
        }
        super.readMessage(id, buffer, side, ctx);
    }

    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }

        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }

        ItemStack before = slot.getItem().copy();
        ItemStack after = access.evaluate((level, pos) -> {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof TileTank tankTile)) {
                return slot.getItem();
            }

            ItemStack result = tankTile.tank.transferStackToTank(player, slot.getItem());
            tankTile.balanceTankFluids();
            tankTile.setChanged();
            return result;
        }).orElse(slot.getItem());

        if (!ItemStack.matches(after, before)) {
            slot.set(after);
            slot.setChanged();
            broadcastChanges();
            return before;
        }

        return ItemStack.EMPTY;
    }

    public boolean clickMenuButton(Player player, int index) {
        if (player != null && player.isSpectator()) return false;
        return access.evaluate((level, pos) -> {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof TileTank tankTile) {
                tankTile.tank.onGuiClicked(this, player);
                tankTile.balanceTankFluids();
                tankTile.setChanged();
                broadcastChanges();
                return true;
            }
            return false;
        }).orElse(false);
    }

    public boolean stillValid(Player player) {
        // A client can receive the open-screen packet one tick before the block entity is installed in the chunk.
        // The server remains authoritative and performs the normal BuildCraft interaction-range validation.
        if (player.level().isClientSide()) {
            return true;
        }
        return tile != null && tile.canInteractWith(player);
    }

    public int getFluidId() {
        return syncedFluidId;
    }

    public int getFluidAmount() {
        return syncedFluidAmount;
    }

    public int getTankCapacity() {
        return syncedCapacity;
    }
    public Fluid getFluid() {
        Fluid fluid = BuiltInRegistries.FLUID.byId(syncedFluidId);
        return fluid == null ? Fluids.EMPTY : fluid;
    }

    private void updateLocalState(TileTank tankTile) {
        FluidStack fluid = tankTile.getFluidInTank(0);
        updateLocalState(
            BuiltInRegistries.FLUID.getId(fluid.isEmpty() ? Fluids.EMPTY : fluid.getFluid()),
            fluid.isEmpty() ? 0 : fluid.getAmount(),
            tankTile.getTankCapacity(0)
        );
    }

    private void updateLocalState(int fluidId, int amount, int capacity) {
        syncedFluidId = Math.max(0, fluidId);
        syncedFluidAmount = Math.max(0, amount);
        syncedCapacity = Math.max(0, capacity);
    }

    /**
     * Client-only view of the full-width tank packet. TankComponent consumes the same
     * 16-bit word layout as TankContainerData, not three unsplit integer fields.
     * Previously slot 2 (capacity) was decoded as fluid amount, making an empty
     * 16-bucket tank display 16 buckets of minecraft:empty ("air").
     */
    private final class SyncedTankData implements ContainerData {
        public int get(int index) {
            if (index < 0 || index >= TankContainerData.LEN) {
                return 0;
            }
            int packed = switch (index / 2) {
                case 0, 2 -> syncedFluidId;       // server and render fluid IDs
                case 1, 3 -> syncedFluidAmount;   // server and render amounts
                case 4 -> syncedCapacity;
                case 5, 6 -> -1;                  // no component cache ID in this packet
                default -> 0;
            };
            return ((index & 1) == 0 ? packed : packed >>> 16) & 0xFFFF;
        }

        public void set(int index, int value) {
            // This is a read-only GUI mirror, deliberately not registered with
            // addDataSlots(). NET_TANK_STATE -> updateLocalState is authoritative.
        }

        public int getCount() {
            return TankContainerData.LEN;
        }
    }
}
