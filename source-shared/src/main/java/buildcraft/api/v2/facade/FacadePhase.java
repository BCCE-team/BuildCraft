package buildcraft.api.v2.facade;

import java.util.Objects;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockState;

/** A material selected when the active paint colour matches; null means uncoloured. */
public record FacadePhase(BlockState state, DyeColor activeColour) {
    public FacadePhase {
        Objects.requireNonNull(state, "state");
    }
}
