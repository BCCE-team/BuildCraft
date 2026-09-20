package buildcraft.transport.internal.pipe;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import buildcraft.lib.internal.capabilities.IBCCapabilityProvider;
import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.transport.internal.pipe.IPipeHolder.IWriter;
import buildcraft.transport.internal.pipe.IPipeHolder.PipeMessageReceiver;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.capabilities.BlockCapability;
import buildcraft.lib.net.BCNetworkSide;

public abstract class PipeFlow implements IBCCapabilityProvider {
    /** The ID for completely refreshing the state of this flow. */
    public static final int NET_ID_FULL_STATE = 0;
    /** The ID for updating what has changed since the last NET_ID_FULL_STATE or NET_ID_UPDATE has been sent. */
    // Wait, what? How is that a good idea or even sensible to make updates work this way?
    public static final int NET_ID_UPDATE = 1;

    public final IPipe pipe;

    public PipeFlow(IPipe pipe) {
        this.pipe = pipe;
    }

    public PipeFlow(IPipe pipe, CompoundTag nbt) {
        this.pipe = pipe;
    }

    public CompoundTag writeToNbt() {
        return new CompoundTag();
    }

    /** Writes a payload with the specified id. Standard ID's are NET_ID_FULL_STATE and NET_ID_UPDATE. */
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {}

    /** Reads a payload with the specified id. Standard ID's are NET_ID_FULL_STATE and NET_ID_UPDATE. */
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) throws IOException {}

    public void sendPayload(int id) {
        @SuppressWarnings("resource")
		final BCNetworkSide side = pipe.getHolder().getPipeWorld().isClientSide ? BCNetworkSide.CLIENT : BCNetworkSide.SERVER;
        sendCustomPayload(id, (buf) -> writePayload(id, buf, side));
    }

    public final void sendCustomPayload(int id, IWriter writer) {
        pipe.getHolder().sendMessage(PipeMessageReceiver.FLOW, buffer -> {
            buffer.writeBoolean(true);
            buffer.writeShort(id);
            writer.write(buffer);
        });
    }

    public abstract boolean canConnect(Direction face, PipeFlow other);

    public abstract boolean canConnect(Direction face, BlockEntity oTile);

    /**
     * Position-aware connection probe used by the modern NeoForge topology scanner.
     *
     * <p>Block capabilities are attached to a world position, not necessarily to a {@link BlockEntity}.
     * Keeping the legacy block-entity overload preserves existing flow/addon compatibility while allowing
     * 1.21.11-native handlers to connect even when the neighbouring block has no tile entity.</p>
     */
    public boolean canConnect(Direction face, Level level, BlockPos pos, @Nullable BlockEntity oTile) {
        return oTile != null && canConnect(face, oTile);
    }

    /** Used to force a connection to a given tile, even if the {@link PipeBehaviour} wouldn't normally connect to
     * it. */
    public boolean shouldForceConnection(Direction face, BlockEntity oTile) {
        return false;
    }

    /** Position-aware companion to {@link #shouldForceConnection(Direction, BlockEntity)}. */
    public boolean shouldForceConnection(Direction face, Level level, BlockPos pos, @Nullable BlockEntity oTile) {
        return oTile != null && shouldForceConnection(face, oTile);
    }

    public void onTick() {}

    /** Whether this flow has transient state that should be persisted periodically while active. */
    public boolean requiresPeriodicSave() {
        return false;
    }

    public void addDrops(NonNullList<ItemStack> toDrop, int fortune) {}

    public boolean onFlowActivate(Player player, BlockHitResult trace, Level level,
        EnumPipePart part) {
        return false;
    }
    @Override
    @Nullable
    public <T> T getCapability(BlockCapability<T, Direction> capability, @Nullable Direction facing) {
        return null;
    }
}
