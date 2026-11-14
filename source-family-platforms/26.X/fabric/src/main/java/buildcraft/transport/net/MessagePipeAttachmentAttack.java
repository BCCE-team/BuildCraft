/* Copyright (c) 2026 BuildCraft Community Edition contributors. MPL-2.0. */
package buildcraft.transport.net;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;
import buildcraft.transport.block.BlockPipeHolder;
import buildcraft.transport.tile.TilePipeHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * A client request to detach the attachment the player is actually looking at.
 * This packet never requests breaking the underlying block. The target side,
 * item and wire type are determined again using the server's own ray trace.
 */
public final class MessagePipeAttachmentAttack {
    private final BlockPos pos;

    public MessagePipeAttachmentAttack(BlockPos pos) {
        this.pos = pos.immutable();
    }

    public MessagePipeAttachmentAttack(FriendlyByteBuf buf) {
        this(buf.readBlockPos());
    }

    public static void toBytes(MessagePipeAttachmentAttack message, FriendlyByteBuf buf) {
        buf.writeBlockPos(message.pos);
    }

    public static final BiConsumer<MessagePipeAttachmentAttack, Supplier<BCPacketContext>> HANDLER = (message, supplier) -> {
        BCPacketContext context = supplier.get();
        if (context.side() != BCNetworkSide.SERVER) return;
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || player.isSpectator()) return;
            var world = player.level();
            if (!world.hasChunkAt(message.pos)) return;
            // The client can name a position, but cannot choose a side or bypass
            // distance, ownership or current hit/attachment checks.
            double reach = player.blockInteractionRange() + 1.0D;
            if (player.distanceToSqr(message.pos.getX() + 0.5D,
                message.pos.getY() + 0.5D, message.pos.getZ() + 0.5D) > reach * reach) return;
            if (!(world.getBlockState(message.pos).getBlock() instanceof BlockPipeHolder block)) return;
            if (!(world.getBlockEntity(message.pos) instanceof TilePipeHolder tile)
                || !tile.canPlayerInteract(player)) return;
            block.removeTargetedAttachment(world, message.pos, player);
        });
    };
}
