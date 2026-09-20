package buildcraft.transport.internal.pipe;

import java.io.IOException;

import javax.annotation.Nonnull;

import org.jetbrains.annotations.NotNull;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.transport.internal.pipe.IPipeHolder.IWriter;
import buildcraft.transport.internal.pipe.IPipeHolder.PipeMessageReceiver;

import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import buildcraft.lib.net.BCNetworkSide;

public abstract class PipeFlow implements ICapabilityProvider {
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

    /** Used to force a connection to a given tile, even if the {@link PipeBehaviour} wouldn't normally connect to
     * it. */
    public boolean shouldForceConnection(Direction face, BlockEntity oTile) {
        return false;
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
    public <T> @NotNull LazyOptional<T> getCapability(@Nonnull Capability<T> capability, Direction facing) {
        return LazyOptional.empty();
    }
}
