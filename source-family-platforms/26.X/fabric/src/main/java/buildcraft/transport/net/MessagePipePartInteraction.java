/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.transport.net;

import java.util.function.BiConsumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;
import buildcraft.lib.platform.permission.PlatformWorldActions;
import buildcraft.transport.block.BlockPipeHolder;
import buildcraft.transport.tile.TilePipeHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Server-authoritative multipart attack/pick; the client supplies a hit, not a stack. */
public record MessagePipePartInteraction(BlockPos pos, Vec3 point, int part, boolean pick) {
    private record PickRequest(Player player, BlockPos pos, ItemStack stack) {}
    private static final ThreadLocal<PickRequest> PICK = new ThreadLocal<>();

    public MessagePipePartInteraction(FriendlyByteBuf buf) {
        this(buf.readBlockPos(), new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
            buf.readVarInt(), buf.readBoolean());
    }

    public static void toBytes(MessagePipePartInteraction message, FriendlyByteBuf buf) {
        buf.writeBlockPos(message.pos);
        buf.writeDouble(message.point.x);
        buf.writeDouble(message.point.y);
        buf.writeDouble(message.point.z);
        buf.writeVarInt(message.part);
        buf.writeBoolean(message.pick);
    }

    @Nullable
    public static ItemStack pickOverride(Player player, BlockPos pos) {
        PickRequest request = PICK.get();
        return request != null && request.player == player && request.pos.equals(pos) ? request.stack.copy() : null;
    }

    public static final BiConsumer<MessagePipePartInteraction, Supplier<BCPacketContext>> HANDLER = (message, supplier) -> {
        BCPacketContext context = supplier.get();
        if (context.side() != BCNetworkSide.SERVER) return;
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || player.isSpectator()) return;
            var level = player.level();
            if (!level.hasChunkAt(message.pos) || message.part < 0 || (!message.pick && message.part <= 6)) return;
            if (!(level.getBlockState(message.pos).getBlock() instanceof BlockPipeHolder pipe)
                || !(level.getBlockEntity(message.pos) instanceof TilePipeHolder tile)) return;
            Vec3 hit = message.point;
            if (!Double.isFinite(hit.x) || !Double.isFinite(hit.y) || !Double.isFinite(hit.z)) return;
            Vec3 local = hit.subtract(message.pos.getX(), message.pos.getY(), message.pos.getZ());
            if (local.x < -0.01 || local.x > 1.01 || local.y < -0.01 || local.y > 1.01
                || local.z < -0.01 || local.z > 1.01) return;

            // Validate reach and the first visible surface, not the client-supplied part alone.
            Vec3 eye = player.getEyePosition();
            double reach = player.blockInteractionRange() + 0.1D;
            // Prefer a native ray: integrations may transform it into a moving sublevel.
            BlockHitResult verified = level.clip(new ClipContext(eye, eye.add(player.getLookAngle().normalize().scale(reach)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (!matches(pipe, level, message, verified)) {
                // The client crosshair and last server look can differ slightly. For ordinary
                // world-space hits, validate the actual requested point against the same outline.
                Vec3 delta = hit.subtract(eye);
                if (delta.lengthSqr() > reach * reach || delta.lengthSqr() < 1.0E-12) return;
                verified = level.clip(new ClipContext(eye, hit.add(delta.normalize().scale(0.01)),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
                if (!matches(pipe, level, message, verified)) return;
            }

            if (message.pick) {
                ItemStack stack = pipe.pickPart(level, message.pos, message.part);
                if (stack.isEmpty()) return;
                // Reuse vanilla inventory selection/creative permissions. The event override
                // lives only for this synchronous invocation and never leaks into later picks.
                PICK.set(new PickRequest(player, message.pos, stack));
                try {
                    player.connection.handlePickItemFromBlock(new ServerboundPickItemFromBlockPacket(message.pos, false));
                } finally {
                    PICK.remove();
                }
            } else {
                if (!player.mayBuild() || !level.mayInteract(player, message.pos) || !tile.canPlayerInteract(player)) return;
                if (!player.mayUseItemAt(message.pos, verified.getDirection(), ItemStack.EMPTY)
                    || !PlatformWorldActions.canBreakBlock(level, message.pos, player)) return;
                pipe.removeTargetedAttachment(level, message.pos, player, message.part);
            }
        });
    };

    private static boolean matches(BlockPipeHolder pipe, net.minecraft.world.level.Level level,
        MessagePipePartInteraction message, BlockHitResult verified) {
        return pipe.targetedPart(level, message.pos, verified) == message.part
            && verified.getLocation().distanceToSqr(message.point) <= 0.25D * 0.25D;
    }
}
