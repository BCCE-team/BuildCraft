package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.event.WorldEventContext;
import buildcraft.api.v2.event.WorldEventDecision;
import buildcraft.api.v2.event.WorldEventKind;
import buildcraft.api.v2.permission.AutomationActor;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import buildcraft.api.v2.block.BlockInteractionService;
import buildcraft.api.v2.block.PaintContext;
import buildcraft.api.v2.block.PaintHandler;
import buildcraft.api.v2.block.PaintResult;
import buildcraft.api.v2.block.RotationContext;
import buildcraft.api.v2.block.RotationHandler;
import buildcraft.api.v2.block.RotationResult;
import buildcraft.lib.internal.block.CustomPaintHelper;
import buildcraft.lib.internal.block.CustomRotationHelper;
import net.minecraft.world.InteractionResult;

/**
 * Live API2 block-interaction dispatcher.
 *
 * <p>Addon handlers run first. Classic BuildCraft handler tables provide the internal fallback
 * for built-in blocks and are never exposed as public API.
 */
final class BlockInteractionServiceImpl implements BlockInteractionService {
    @Override
    public RotationResult rotate(RotationContext context) {
        if (denied(context.level(), context.pos(), context.state(), context.actor(), context.mode())) return RotationResult.DENIED;
        for (RotationHandler handler : BuildCraftApi.registry(BuildCraftRegistries.ROTATION_HANDLERS).values()) {
            try {
                RotationResult result = handler.rotate(context);
                if (result != null && result != RotationResult.PASS) return result;
            } catch (RuntimeException ignored) {
                // A faulty extension must not stop the vanilla fallback.
            }
        }
        if (context.mode() == OperationMode.SIMULATE) return RotationResult.PASS;
        InteractionResult legacy = CustomRotationHelper.INSTANCE.attemptRotateBlock(
            context.level(), context.pos(), context.state(), context.face());
        return rotationResult(legacy);
    }

    @Override
    public PaintResult paint(PaintContext context) {
        if (denied(context.level(), context.pos(), context.state(), context.actor(), context.mode())) return PaintResult.DENIED;
        for (PaintHandler handler : BuildCraftApi.registry(BuildCraftRegistries.PAINT_HANDLERS).values()) {
            try {
                PaintResult result = handler.paint(context);
                if (result != null && result != PaintResult.PASS) return result;
            } catch (RuntimeException ignored) {
                // A faulty extension must not stop the vanilla fallback.
            }
        }
        if (context.mode() == OperationMode.SIMULATE) return PaintResult.PASS;
        InteractionResult legacy = CustomPaintHelper.INSTANCE.attemptPaintBlock(
            context.level(), context.pos(), context.state(), context.hitPosition(),
            context.hitSide().orElse(null), context.color());
        return paintResult(legacy);
    }

    private static boolean denied(Level level, BlockPos pos, BlockState state,
            AutomationActor actor, OperationMode mode) {
        // Client prediction retains its pre-P02 behaviour; listeners execute only on the server.
        if (level.isClientSide()) return false;
        return BuildCraftApi.service(BuildCraftServices.WORLD_EVENTS).dispatch(new WorldEventContext(
            WorldEventKind.BLOCK_INTERACT, level, pos, actor, Optional.of(state), Optional.empty(), mode))
            == WorldEventDecision.DENY;
    }

    private static RotationResult rotationResult(InteractionResult result) {
        if (result == InteractionResult.PASS) return RotationResult.PASS;
        if (result == InteractionResult.FAIL) return RotationResult.FAILED;
        return RotationResult.ROTATED;
    }

    private static PaintResult paintResult(InteractionResult result) {
        if (result == InteractionResult.PASS) return PaintResult.PASS;
        if (result == InteractionResult.FAIL) return PaintResult.FAILED;
        return PaintResult.PAINTED;
    }
}
