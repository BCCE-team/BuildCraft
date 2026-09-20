package buildcraft.api.v2.block;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.AutomationActor;
import java.util.Objects;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Context for a paint interaction.
 *
 * <p>The colour may be {@code null}: BuildCraft uses a null colour to mean
 * "clear the paint". Hit information is carried explicitly so multipart blocks such as
 * pipes can preserve their classic side/part-sensitive behaviour through API2.
 */
public final class PaintContext {
    private final Level level;
    private final BlockPos pos;
    private final BlockState state;
    private final DyeColor color;
    private final Vec3 hitPosition;
    private final Direction hitSide;
    private final AutomationActor actor;
    private final OperationMode mode;

    /** Compatibility constructor for callers that do not have hit information. */
    public PaintContext(Level level, BlockPos pos, BlockState state, @Nullable DyeColor color,
            AutomationActor actor, OperationMode mode) {
        this(level, pos, state, color, Vec3.atCenterOf(pos), null, actor, mode);
    }

    public PaintContext(Level level, BlockPos pos, BlockState state, @Nullable DyeColor color,
            Vec3 hitPosition, @Nullable Direction hitSide, AutomationActor actor, OperationMode mode) {
        this.level = Objects.requireNonNull(level, "level");
        this.pos = Objects.requireNonNull(pos, "pos");
        this.state = Objects.requireNonNull(state, "state");
        this.color = color;
        this.hitPosition = Objects.requireNonNull(hitPosition, "hitPosition");
        this.hitSide = hitSide;
        this.actor = Objects.requireNonNull(actor, "actor");
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public Level level() { return level; }
    public BlockPos pos() { return pos; }
    public BlockState state() { return state; }
    @Nullable public DyeColor color() { return color; }
    public Vec3 hitPosition() { return hitPosition; }
    public Optional<Direction> hitSide() { return Optional.ofNullable(hitSide); }
    public AutomationActor actor() { return actor; }
    public OperationMode mode() { return mode; }
}
