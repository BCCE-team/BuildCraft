package buildcraft.api.v2.pipe;

import net.minecraft.world.level.Level;

/** Valid only inside a resource-tick callback. No client-side mutations are permitted. */
public interface PipeResourceTickContext {
    PipeView pipe();
    Level world();
    PipeMedium medium();
    PipeResourceService transfers();
}
