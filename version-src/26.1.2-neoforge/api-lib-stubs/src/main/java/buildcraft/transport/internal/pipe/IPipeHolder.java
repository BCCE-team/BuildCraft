package buildcraft.transport.internal.pipe;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;

/** Verification-only gameplay bridge; not included in the production JAR. */
public interface IPipeHolder {
    IPipe getPipe();
    Level getPipeWorld();
    BlockPos getPipePos();
    default boolean canPlayerInteract(Player player) { return true; }
}
