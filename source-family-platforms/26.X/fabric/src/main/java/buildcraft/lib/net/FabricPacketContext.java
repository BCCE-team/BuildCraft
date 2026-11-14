package buildcraft.lib.net;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.world.entity.player.Player;

/**
 * The packet callback does not expose Fabric networking internals to shared
 * BuildCraft handlers. It is safe to use on both logical sides.
 */
public final class FabricPacketContext implements BCPacketContext {
    private final BCNetworkSide side;
    private final Player player;
    private final Consumer<Runnable> scheduler;

    public FabricPacketContext(BCNetworkSide side, @Nullable Player player, Consumer<Runnable> scheduler) {
        this.side = Objects.requireNonNull(side, "side");
        this.player = player;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public BCNetworkSide side() { return side; }
    public @Nullable Player player() { return player; }
    public void enqueueWork(Runnable task) { scheduler.accept(Objects.requireNonNull(task)); }
    public void setPacketHandled(boolean handled) { /* Fabric handles packets automatically. */ }
}
