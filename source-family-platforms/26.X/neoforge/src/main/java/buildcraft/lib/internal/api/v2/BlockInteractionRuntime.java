package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.block.PaintContext;
import buildcraft.api.v2.block.PaintResult;
import buildcraft.api.v2.block.RotationContext;
import buildcraft.api.v2.block.RotationResult;
import buildcraft.api.v2.permission.AutomationActor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import buildcraft.lib.compat.GameProfileCompat;

/** Small implementation-side adapter so built-in tools exercise the public API2 dispatch path. */
public final class BlockInteractionRuntime {
    private BlockInteractionRuntime() {}

    public static InteractionResult rotate(Level level, BlockPos pos, BlockState state, Direction face, Player player) {
        RotationResult result = BuildCraftApi.service(BuildCraftServices.BLOCK_INTERACTIONS).rotate(
            new RotationContext(level, pos, state, face, actor(player), OperationMode.EXECUTE)
        );
        return switch (result) {
            case PASS -> InteractionResult.PASS;
            case ROTATED -> InteractionResult.SUCCESS;
            case DENIED, FAILED -> InteractionResult.FAIL;
        };
    }

    public static InteractionResult paint(Level level, BlockPos pos, BlockState state, Vec3 hitPosition,
            Direction hitSide, DyeColor color, Player player) {
        PaintResult result = BuildCraftApi.service(BuildCraftServices.BLOCK_INTERACTIONS).paint(
            new PaintContext(level, pos, state, color, hitPosition, hitSide, actor(player), OperationMode.EXECUTE)
        );
        return switch (result) {
            case PASS -> InteractionResult.PASS;
            case PAINTED -> InteractionResult.SUCCESS;
            case DENIED, FAILED -> InteractionResult.FAIL;
        };
    }

    private static AutomationActor actor(Player player) {
        if (player == null) return BuildCraftApi.service(BuildCraftServices.ACTORS).unknown();
        return BuildCraftApi.service(BuildCraftServices.ACTORS).player(
            player.getUUID(), GameProfileCompat.name(player.getGameProfile())
        );
    }
}
