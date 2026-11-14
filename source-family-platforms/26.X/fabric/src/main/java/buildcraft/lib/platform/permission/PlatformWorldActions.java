/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.platform.permission;

import javax.annotation.Nullable;
import buildcraft.lib.misc.FakePlayerProvider;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Protection-aware machine actions for Fabric 26.3.
 *
 * Calls happen before the block is modified: a denied action consumes neither
 * the tool durability nor the stored energy at this boundary. A supplied
 * player/fake-player is never silently replaced with BuildCraft's system actor.
 *
 * Fabric has no exact equivalent of NeoForge's post-placement BlockSnapshot
 * event. UseBlockCallback is the standard Fabric pre-action protection hook.
 * Event handlers that require Forge's post-placement event need an explicit
 * compatibility implementation before cross-loader protection parity is met.
 */
public final class PlatformWorldActions {
    private PlatformWorldActions() {}

    public static boolean canBreakBlock(ServerLevel world, BlockPos pos, Player actor) {
        if (world == null || pos == null || actor == null || world.getBlockState(pos).isAir()) {
            return false;
        }
        if (!world.getWorldBorder().isWithinBounds(pos)
            || !actor.mayBuild()
            || !actor.mayUseItemAt(pos, Direction.UP, ItemStack.EMPTY)) {
            return false;
        }
        return PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(
            world, actor, pos, world.getBlockState(pos), world.getBlockEntity(pos)
        );
    }

    public static boolean placeBlock(Level level, BlockPos pos, BlockState state,
                                     @Nullable Player actor, Direction placedAgainst, int flags) {
        if (level == null || pos == null || state == null) return false;
        if (!(level instanceof ServerLevel serverLevel)) {
            return level.setBlock(pos, state, flags);
        }

        Player placementActor = actor != null ? actor
            : FakePlayerProvider.INSTANCE.getFakePlayer(serverLevel, FakePlayerProvider.NULL_PROFILE, pos);

        if (!serverLevel.getWorldBorder().isWithinBounds(pos)
            || !placementActor.mayBuild()
            || !placementActor.mayUseItemAt(pos, placedAgainst, ItemStack.EMPTY)) {
            return false;
        }

        // Notify Fabric protection integrations BEFORE making the world change.
        // Only a FAIL response cancels the synthetic placement.
        BlockPos clicked = pos.relative(placedAgainst.getOpposite());
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), placedAgainst, clicked, false);
        InteractionResult result = UseBlockCallback.EVENT.invoker().interact(
            placementActor, serverLevel, InteractionHand.MAIN_HAND, hit
        );
        if (result == InteractionResult.FAIL) return false;

        return serverLevel.setBlock(pos, state, flags);
    }
}
