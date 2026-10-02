package buildcraft.lib.gui;

import javax.annotation.Nullable;

import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.lib.tile.TileBC_Neptune;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;

public abstract class ContainerPipe extends MenuBC_Neptune implements IMenuBCTile {

    @Nullable
    public final IPipeHolder pipeHolder;

    public ContainerPipe(Inventory playerInventory, MenuType<?> type, int id, @Nullable IPipeHolder pipeHolder) {
        super(playerInventory, type, id);
        this.pipeHolder = pipeHolder;
    }

    @Nullable
    public TileBC_Neptune getBCTile() {
        return pipeHolder instanceof TileBC_Neptune tile ? tile : null;
    }

    public boolean isValidPipeMenu() {
        return pipeHolder != null;
    }

    protected void closeInvalidClientMenu() {
        playerInventory.player.closeContainer();
    }

	public boolean stillValid(Player player) {
		return pipeHolder != null && pipeHolder.canPlayerInteract(player);
	}
}
