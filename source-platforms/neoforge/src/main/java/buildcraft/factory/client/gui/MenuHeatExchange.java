package buildcraft.factory.client.gui;

import javax.annotation.Nullable;

import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.BCFactoryGuis;
import buildcraft.factory.tile.TileHeatExchange;
import buildcraft.lib.fluid.Tank;
import buildcraft.lib.gui.IMenuBCTile;
import buildcraft.lib.gui.MenuBC_Neptune;
import buildcraft.lib.gui.TankContainerData;
import buildcraft.lib.tile.TileBC_Neptune;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
//? if mc_26_x && (>=26.3) {
//? } else {
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
//?}

public class MenuHeatExchange extends MenuBC_Neptune implements IMenuBCTile {

    private static final int TANK_COUNT = 4;
    private static final int TANK_DATA_COUNT = TANK_COUNT * TankContainerData.LEN;

    private final ContainerLevelAccess access;
    protected ContainerData data;
    @Nullable
    public final TileHeatExchange tile;

    public MenuHeatExchange(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
//? if mc_26_x && (>=26.3) {
        this(containerId, playerInventory, new SimpleContainerData(TANK_DATA_COUNT), DataSlot.standalone(), createLevelAccess(playerInventory, buf));
//? } else {
        this(containerId, playerInventory, new ItemStackHandler(TANK_COUNT),
            new SimpleContainerData(TANK_DATA_COUNT), DataSlot.standalone(), createLevelAccess(playerInventory, buf));
//?}
    }

    public MenuHeatExchange(int containerId, Inventory playerInventory) {
//? if mc_26_x && (>=26.3) {
        this(containerId, playerInventory, new SimpleContainerData(TANK_DATA_COUNT), DataSlot.standalone(), ContainerLevelAccess.NULL);
//? } else {
        this(containerId, playerInventory, new ItemStackHandler(TANK_COUNT),
            new SimpleContainerData(TANK_DATA_COUNT), DataSlot.standalone(), ContainerLevelAccess.NULL);
//?}
    }

//? if mc_26_x && (>=26.3) {
    public MenuHeatExchange(int containerId, Inventory playerInventory,
//? } else {
    public MenuHeatExchange(int containerId, Inventory playerInventory, IItemHandler item,
//?}
        ContainerData tank, DataSlot bg, ContainerLevelAccess access) {
        super(playerInventory, BCFactoryGuis.MENU_HEAT_EXCHANGE.get(), containerId);
        this.access = access;
        this.data = tank;
        this.tile = access.evaluate((level, pos) -> {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof TileHeatExchange heatExchange) {
//? if mc_26_x && (>=26.3) {
                if (!level.isClientSide()) {
//? } else {
                if (!level.isClientSide) {
//?}
                    heatExchange.onPlayerOpen(playerInventory.player);
                }
                return heatExchange;
            }
            return null;
        }, null);

        for (int i = 0; i < 3; ++i) {
            for (int j = 0; j < 9; ++j) {
                this.addSlot(new Slot(playerInventory, j + i * 9 + 9, 8 + j * 18, 89 + i * 18));
            }
        }

        for (int k = 0; k < 9; ++k) {
            this.addSlot(new Slot(playerInventory, k, 8 + k * 18, 147));
        }
        addDataSlots(tank);
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public ItemStack quickMoveStack(Player player, int index) {
        return buildcraft.lib.gui.BCMenuUtil.quickMoveStack(this, player, index);
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public void removed(Player player) {
        super.removed(player);
        if (tile != null) {
            tile.onPlayerClose(player);
        }
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public void broadcastChanges() {
        super.broadcastChanges();
        if (tile != null && stillValid(playerInventory.player)) {
            tile.sendNetworkGuiTick(playerInventory.player);
        }
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public boolean stillValid(Player player) {
        if (tile != null) {
            if (!tile.canInteractWith(player)) return false;
            // The server passed this exact TankContainerData on opening. A structure rebuild
            // creates another instance; closing this menu avoids displaying or editing dead tanks.
            return access.evaluate((level, pos) -> {
//? if mc_26_x && (>=26.3) {
                if (level.isClientSide()) return true; // Client menu data is a local synchronisation mirror.
//? } else {
                if (level.isClientSide) return true; // Client menu data is a local synchronisation mirror.
//?}
                return level.hasChunkAt(pos) && level.getBlockEntity(pos) == tile
                    && tile.getGuiTankData() == data;
            }).orElse(false);
        }
        return buildcraft.lib.gui.BCMenuUtil.stillValidBlock(this.access, player, BCFactoryBlocks.HEATEXCHANGE_BLOCK.get());
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public boolean clickMenuButton(Player player, int index) {
        if (player == null || player.isSpectator() || index < 0 || index >= TANK_DATA_COUNT
            || !stillValid(player)) return false;
        return access.evaluate((level, pos) -> {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof TileHeatExchange heatExchange) {
                Tank tank = heatExchange.getSectionTank(index / TankContainerData.LEN);
                if (tank != null) {
                    tank.onGuiClicked(this, player);
                }
            }
            return false;
        }).orElse(false);
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public TileBC_Neptune getBCTile() {
        return tile;
    }
}
