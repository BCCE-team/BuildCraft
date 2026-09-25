package buildcraft.lib.net;
// Loader boundary owner: net.fabricmc (implementation may delegate to vanilla/common contracts).

import javax.annotation.Nullable;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** Fabric packet context. Fabric networking already invokes handlers on the game thread after enqueue. */
public final class FabricPacketContext implements BCPacketContext {
    private final BCNetworkSide side;
    private final @Nullable Player player;
    private final java.util.function.Consumer<Runnable> executor;

    public FabricPacketContext(BCNetworkSide side, @Nullable Player player, java.util.function.Consumer<Runnable> executor) {
        this.side = java.util.Objects.requireNonNull(side, "side");
        this.player = player;
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
    }

    @Override
    public BCNetworkSide side() {
        return side;
    }

    @Override
    public @Nullable Player player() {
        return player;
    }

    @Override
    public @Nullable ServerPlayer getSender() {
        return player instanceof ServerPlayer serverPlayer ? serverPlayer : null;
    }

    @Override
    public void enqueueWork(Runnable task) {
        executor.accept(java.util.Objects.requireNonNull(task, "task"));
    }

    @Override
    public void setPacketHandled(boolean handled) {
        // Fabric play networking has no explicit handled flag.
    }
}
