package buildcraft.api.v2.machine;

import buildcraft.api.v2.energy.MjAmount;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Server-authored data usable by addon renderers without importing BuildCraft renderer classes. */
public record EngineVisualState(EngineStage stage, HeatState heat, MjAmount generatedLastTick,
    double animationPhase, ResourceLocation soundId) {
    public EngineVisualState {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(heat, "heat");
        Objects.requireNonNull(generatedLastTick, "generatedLastTick");
        if (!Double.isFinite(animationPhase) || animationPhase < 0 || animationPhase > 1) {
            throw new IllegalArgumentException("animationPhase must be in [0, 1]");
        }
    }
}
