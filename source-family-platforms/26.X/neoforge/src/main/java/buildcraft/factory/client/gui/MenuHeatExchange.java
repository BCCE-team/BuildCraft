//? source if >=26.3
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

public class MenuHeatExchange extends MenuBC_Neptune implements IMenuBCTile {

    private static final int TANK_COUNT = 4;
    private static final int TANK_DATA_COUNT = TANK_COUNT * TankContainerData.LEN;

    private final ContainerLevelAccess access;
    protected ContainerData data;
    @Nullable
    public final TileHeatExchange tile;

    public MenuHeatExchange(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        this(containerId, playerInventory, new SimpleContainerData(TANK_DATA_COUNT), DataSlot.standalone(), createLevelAccess(playerInventory, buf));
    }

    public MenuHeatExchange(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, new SimpleContainerData(TANK_DATA_COUNT), DataSlot.standalone(), ContainerLevelAccess.NULL);
    }

    public MenuHeatExchange(int containerId, Inventory playerInventory,
        ContainerData tank, DataSlot bg, ContainerLevelAccess access) {
        super(playerInventory, BCFactoryGuis.MENU_HEAT_EXCHANGE.get(), containerId);
        this.access = access;
        this.data = tank;
        this.tile = access.evaluate((level, pos) -> {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof TileHeatExchange heatExchange) {
                if (!level.isClientSide()) {
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

    public ItemStack quickMoveStack(Player player, int index) {
        return buildcraft.lib.gui.BCMenuUtil.quickMoveStack(this, player, index);
    }

    public void removed(Player player) {
        super.removed(player);
        if (tile != null) {
            tile.onPlayerClose(player);
        }
    }

    public void broadcastChanges() {
        super.broadcastChanges();
        if (tile != null) {
            tile.sendNetworkGuiTick(playerInventory.player);
        }
    }

    public boolean stillValid(Player player) {
        if (tile != null) {
            return tile.canInteractWith(player);
        }
        return buildcraft.lib.gui.BCMenuUtil.stillValidBlock(this.access, player, BCFactoryBlocks.HEATEXCHANGE_BLOCK.get());
    }

    public boolean clickMenuButton(Player player, int index) {
        if (player != null && player.isSpectator()) return false;
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

    public TileBC_Neptune getBCTile() {
        return tile;
    }
}
