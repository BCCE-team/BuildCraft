package buildcraft.api.v2.event;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.AutomationActor;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Typed, immutable event snapshot; never contains a mutable pipe/tile implementation. */
public record WorldEventContext(
    WorldEventKind kind,
    Level level,
    BlockPos pos,
    AutomationActor actor,
    Optional<BlockState> state,
    Optional<ResourceLocation> contentId,
    OperationMode mode
) {
    public WorldEventContext {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(level, "level");
        pos = Objects.requireNonNull(pos, "pos").immutable();
        Objects.requireNonNull(actor, "actor");
        state = Objects.requireNonNull(state, "state");
        contentId = Objects.requireNonNull(contentId, "contentId");
        Objects.requireNonNull(mode, "mode");
    }
}
