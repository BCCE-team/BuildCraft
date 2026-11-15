/* Copyright (c) 2026 BuildCraft Community Edition contributors. MPL 2.0. */
package buildcraft.core;

import buildcraft.lib.block.RedstoneModuleState;
import buildcraft.lib.misc.WrenchUtil;
import buildcraft.lib.tile.TileBC_Neptune;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Fabric equivalent of the Forge/NeoForge pre-block-activation module interaction. */
public final class RedstoneModuleInteractions {
    private RedstoneModuleInteractions() { }

    /**
     * Register the Fabric-specific interaction before the normal block-use/paintbrush handler.
     * The upgrade must consume a successful click before machine menus or wrench rotation.
     */
    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (player.isSpectator() || !player.mayBuild()
                || !level.mayInteract(player, hit.getBlockPos())) {
                return InteractionResult.PASS;
            }
            return onUse(player, level, hand, hit, player.getItemInHand(hand));
        });
    }

    public static InteractionResult onUse(Player player, Level level, InteractionHand hand,
                                          BlockHitResult hit, ItemStack held) {
        BlockPos pos = hit.getBlockPos();
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof TileBC_Neptune tile) || !tile.supportsRedstoneControlModule()) {
            return InteractionResult.PASS;
        }
        boolean install = held.is(BCCoreItems.REDSTONE_CONTROL_MODULE.get());
        // The upgrade is persisted in the server BE; on clients its blockstate is the synced source.
        BlockState state = level.getBlockState(pos);
        boolean installed = level.isClientSide()
            ? state.hasProperty(RedstoneModuleState.INSTALLED)
                && state.getValue(RedstoneModuleState.INSTALLED)
            : tile.hasRedstoneControlModule();
        boolean remove = !install && installed && WrenchUtil.isWrench(held);
        if (!install && !remove) return InteractionResult.PASS;

        // Fabric's pre-use callback must consume the interaction on both sides,
        // otherwise Builder/Filler menus and the vanilla wrench handler win.
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        if (install) {
            if (tile.installRedstoneControlModule() && !player.getAbilities().instabuild) {
                held.shrink(1);
            }
        } else if (tile.removeRedstoneControlModule()) {
            ItemStack returned = new ItemStack(BCCoreItems.REDSTONE_CONTROL_MODULE.get());
            if (!player.getInventory().add(returned)) {
                Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, returned);
            }
            WrenchUtil.wrenchUsed(player, hand, held, hit);
        }
        return InteractionResult.SUCCESS_SERVER;
    }
}
