package buildcraft.factory.client.gui;

import javax.annotation.Nullable;

import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.BCFactoryGuis;
import buildcraft.factory.tile.TileHeatExchange;
import buildcraft.lib.fluid.Tank;
import buildcraft.lib.gui.IMenuBCTile;
import buildcraft.lib.gui.MenuBC_Neptune;
import buildcraft.lib.gui.TankContainerData;
import buildcraft.lib.tile.item.ItemHandlerSimple;
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
import buildcraft.lib.compat.neoforge263.items.SlotItemHandler;

public class MenuHeatExchange extends MenuBC_Neptune implements IMenuBCTile {

    private static final int TANK_COUNT = 4;
    private static final int TANK_DATA_COUNT = TANK_COUNT * TankContainerData.LEN;

    private final ContainerLevelAccess access;
    protected ContainerData data;
    /** Synced animation state/progress packed into 16 bits. */
    public final DataSlot progressData;
    @Nullable
    public final TileHeatExchange tile;
    /** Snapshot of the actual four tanks of the multiblock at menu creation. */
    private final Tank[] openedTanks = new Tank[TANK_COUNT];

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
        this.progressData = bg;
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
        if (this.tile != null && !playerInventory.player.level().isClientSide()) {
            for (int i = 0; i < TANK_COUNT; i++) {
                openedTanks[i] = this.tile.getSectionTank(i);
            }
        }

        // BCU: four one-item fluid-container slots, placed before player slots for shift-click logic.
        ItemHandlerSimple fluidItems = tile == null ? new ItemHandlerSimple(TANK_COUNT, 1) : tile.containerSlots;
        this.addSlot(new SlotItemHandler(fluidItems, 0, 8, 23));   // hot input
        this.addSlot(new SlotItemHandler(fluidItems, 1, 8, 64));   // cold input
        this.addSlot(new SlotItemHandler(fluidItems, 2, 152, 12)); // heated output
        this.addSlot(new SlotItemHandler(fluidItems, 3, 152, 54)); // cooled output

        for (int i = 0; i < 3; ++i) {
            for (int j = 0; j < 9; ++j) {
                this.addSlot(new Slot(playerInventory, j + i * 9 + 9, 8 + j * 18, 89 + i * 18));
            }
        }

        for (int k = 0; k < 9; ++k) {
            this.addSlot(new Slot(playerInventory, k, 8 + k * 18, 147));
        }
        addDataSlots(tank);
        addDataSlot(bg);
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
        if (tile != null && stillValid(playerInventory.player)) {
            tile.sendNetworkGuiTick(playerInventory.player);
        }
    }

    public boolean stillValid(Player player) {
        if (tile != null) {
            if (!tile.canInteractWith(player)) return false;
            // Neighbour updates may recreate the TankContainerData wrapper around the same four tanks.
            // Close only when the controller or any *actual tank* has changed. The bound ContainerData
            // is still valid if all four tank identities match; no stale multiblock may be edited.
            return access.evaluate((level, pos) -> {
                if (level.isClientSide()) return true;
                if (!level.hasChunkAt(pos) || level.getBlockEntity(pos) != tile
                    || tile.getGuiTankData() == null) return false;
                for (int i = 0; i < TANK_COUNT; i++) {
                    if (openedTanks[i] == null || openedTanks[i] != tile.getSectionTank(i)) {
                        return false;
                    }
                }
                return true;
            }).orElse(false);
        }
        return buildcraft.lib.gui.BCMenuUtil.stillValidBlock(this.access, player, BCFactoryBlocks.HEATEXCHANGE_BLOCK.get());
    }

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

    public TileBC_Neptune getBCTile() {
        return tile;
    }
}
