/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.fabric;

import buildcraft.lib.net.MessageManager;
import buildcraft.transport.block.BlockPipeHolder;
import buildcraft.transport.net.MessagePipePartInteraction;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;

/** Use the rendered crosshair target before vanilla starts to mine the entire block. */
public final class PipePartClientInteraction {
    private static boolean interceptedAttack;
    private PipePartClientInteraction() {}

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || !client.options.keyAttack.isDown()) interceptedAttack = false;
        });
        ClientPreAttackCallback.EVENT.register((client, player, clickCount) -> {
            if (player.isSpectator() || client.level == null || client.gameMode == null) return false;
            // Every new press is a new choice; holding after a detach may not mine the holder.
            if (clickCount > 0) interceptedAttack = false;
            if (interceptedAttack) return true;
            if (!(client.hitResult instanceof BlockHitResult hit)) return false;
            BlockPos pos = hit.getBlockPos();
            if (!(client.level.getBlockState(pos).getBlock() instanceof BlockPipeHolder pipe)) return false;
            int part = pipe.targetedPart(client.level, pos, hit);
            if (part <= 6) return false;
            if (client.gameMode.isDestroying()) client.gameMode.stopDestroyBlock();
            interceptedAttack = true;
            MessageManager.sendToServer(new MessagePipePartInteraction(pos, hit.getLocation(), part, false));
            return true;
        });
    }

    /** Called at the normal vanilla middle-click entry point. */
    public static boolean pick(BlockPos pos, boolean includeData) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.player.isSpectator() || client.level == null
            || !(client.hitResult instanceof BlockHitResult hit) || !pos.equals(hit.getBlockPos())) return false;
        if (!(client.level.getBlockState(pos).getBlock() instanceof BlockPipeHolder pipe)) return false;
        int part = pipe.targetedPart(client.level, pos, hit);
        if (part < 0 || (includeData && part <= 6)) return false;
        MessageManager.sendToServer(new MessagePipePartInteraction(pos, hit.getLocation(), part, true));
        return true;
    }
}
