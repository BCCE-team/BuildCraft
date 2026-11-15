package buildcraft.core;

import buildcraft.lib.misc.WrenchUtil;
import buildcraft.lib.tile.TileBC_Neptune;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
//? if >=1.21.2 {
import net.minecraft.util.TriState;
//?} else {
/*?
import net.neoforged.neoforge.common.util.TriState;
?*/
//?}

/** Process upgrade interactions before vanilla block activation or the wrench rotation callback. */
public final class RedstoneModuleInteractions {
    private RedstoneModuleInteractions() { }

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        BlockEntity blockEntity = event.getLevel().getBlockEntity(event.getPos());
        if (!(blockEntity instanceof TileBC_Neptune tile) || !tile.supportsRedstoneControlModule()) return;
        ItemStack held = event.getItemStack();
        boolean install = held.is(BCCoreItems.REDSTONE_CONTROL_MODULE.get());
        boolean remove = !install && tile.hasRedstoneControlModule() && WrenchUtil.isWrench(held);
        if (!install && !remove) return;

        event.setUseBlock(TriState.FALSE);
        event.setUseItem(TriState.FALSE);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getLevel().isClientSide()) return;

        if (install) {
            if (tile.installRedstoneControlModule() && !event.getEntity().getAbilities().instabuild) {
                held.shrink(1);
            }
        } else if (tile.removeRedstoneControlModule()) {
            ItemStack returned = new ItemStack(BCCoreItems.REDSTONE_CONTROL_MODULE.get());
            if (!event.getEntity().getInventory().add(returned)) {
                Containers.dropItemStack(event.getLevel(), event.getPos().getX() + 0.5,
                    event.getPos().getY() + 0.5, event.getPos().getZ() + 0.5, returned);
            }
            WrenchUtil.wrenchUsed(event.getEntity(), event.getHand(), held, event.getHitVec());
        }
    }
}
