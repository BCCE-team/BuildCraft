package buildcraft.api.v2.statement;

import buildcraft.api.v2.context.ContextKey;
import buildcraft.api.v2.gate.GateView;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Views supplied to callbacks dispatched by actual BuildCraft gates. */
public final class StatementContexts {
    public static final ContextKey<GateView> GATE =
        ContextKey.of(id("gate"), GateView.class);
    public static final ContextKey<BlockPos> GATE_POSITION =
        ContextKey.of(id("gate_position"), BlockPos.class);
    /** Present for an external statement only when a neighbour exists. */
    public static final ContextKey<BlockEntity> EXTERNAL_TARGET =
        ContextKey.of(id("external_target"), BlockEntity.class);

    private StatementContexts() {}

    private static ResourceLocation id(String path) {
        return java.util.Objects.requireNonNull(ResourceLocation.tryParse("buildcraft:" + path));
    }
}
