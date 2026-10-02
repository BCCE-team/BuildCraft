package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.AutomationActor;
import buildcraft.api.v2.permission.PermissionVerdict;
import buildcraft.api.v2.permission.WorldOperationContext;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.api.v2.permission.WorldOperationTarget;
import buildcraft.api.v2.world.WorldRuleService;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Default world-rule backend shared by BuildCraft automation and addon callers. */
final class WorldRuleServiceImpl implements WorldRuleService {
    private static final Identifier REASON = Objects.requireNonNull(Identifier.tryParse("buildcraft:world_rules"));

    public boolean isSoft(Level level, BlockPos pos, BlockState state) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(state, "state");
        return level.isLoaded(pos) && (state.isAir() || state.getCollisionShape(level, pos).isEmpty());
    }

    public boolean isReplaceable(Level level, BlockPos pos, BlockState state) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(state, "state");
        if (!level.isLoaded(pos)) return false;
        return state.isAir() || state.canBeReplaced();
    }

    public boolean mayBreak(Level level, BlockPos pos, BlockState state, AutomationActor actor) {
        if (state == null || state.isAir()) return false;
        return permitted(level, pos, actor, WorldOperationKind.BLOCK_BREAK);
    }

    public boolean mayPlace(Level level, BlockPos pos, BlockState state, AutomationActor actor) {
        Objects.requireNonNull(state, "state");
        return permitted(level, pos, actor, WorldOperationKind.BLOCK_PLACE);
    }

    private static boolean permitted(Level level, BlockPos pos, AutomationActor actor, WorldOperationKind operation) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(actor, "actor");
        if (!level.isLoaded(pos)) return false;
        var decision = BuildCraftApi.service(BuildCraftServices.PERMISSIONS).decide(new WorldOperationContext(
            actor,
            level,
            pos,
            WorldOperationTarget.block(pos),
            operation,
            OperationMode.SIMULATE,
            REASON
        ));
        return decision.verdict() != PermissionVerdict.DENY;
    }
}
