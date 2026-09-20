package buildcraft.transport.internal.pluggable;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.internal.pipe.IPipeHolder.PipeMessageReceiver;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.capabilities.BlockCapability;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public abstract class PipePluggable {
	public final static PipePluggable EMPTY = new PipePluggable(null,null,null) {
		public VoxelShape getBoundingBox() {return Shapes.empty();}};
	
    public final PluggableDefinition definition;
    public final IPipeHolder holder;
    public /*final*/ Direction side;

    public PipePluggable(PluggableDefinition definition, IPipeHolder holder, Direction side) {
        this.definition = definition;
        this.holder = holder;
        this.side = side;
    }

    public CompoundTag writeToNbt() {
        CompoundTag nbt = new CompoundTag();
        return nbt;
    }

    /** Writes the payload that will be passed into
     * {@link PluggableDefinition#loadFromBuffer(IPipeHolder, Direction, FriendlyByteBuf)} on the client. (This is called
     * on the server and sent to the client). Note that this will be called *instead* of write and read payload. */
    public void writeCreationPayload(FriendlyByteBuf buffer) {

    }

    public void writePayload(FriendlyByteBuf buffer, BCNetworkSide side) {

    }

    /**
     * Optional structured state-sync contract. Returning non-null makes PluggableHolder send this tag instead of the
     * legacy ad-hoc FriendlyByteBuf payload. Existing pluggables remain source/binary compatible through the null
     * default, while addons can opt into a versionable named-state format.
     */
    @Nullable
    public CompoundTag writeSyncState(BCNetworkSide side) {
        return null;
    }

    public void readPayload(FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {

    }

    public void readSyncState(CompoundTag state, BCNetworkSide side, BCPacketContext ctx) throws IOException {
    }

    public final void scheduleNetworkUpdate() {
        holder.scheduleNetworkUpdate(PipeMessageReceiver.PLUGGABLES[side.ordinal()]);
    }

    public void onTick() {}

    /** @return A bounding box that will be used for collisions and raytracing. */
    public abstract VoxelShape getBoundingBox();

    /** @return True if the pipe cannot connect outwards (it is blocked), or False if this does not block the pipe. */
    public boolean isBlocking() {
        return false;
    }

    /** Gets the value of a specified capability key, or null if the given capability is not supported at the call time.
     * This is the internal BuildCraft equivalent of a sided block-capability provider. */
    public <T> @Nullable T getCapability(@Nonnull BlockCapability<T, Direction> cap) {
        return null;
    }

    /** Gets the capability that is accessible from the pipe that this is attached to.
     * 
     * @param cap
     * @return */
    public <T> @Nullable T getInternalCapability(@Nonnull BlockCapability<T, Direction> cap) {
        return null;
    }

    /** Called whenever this pluggable is removed from the pipe. */
    public void onRemove() {}

    /** @param toDrop A list containing all the items to drop (so you should add your items to this list) */
    public void addDrops(NonNullList<ItemStack> toDrop, int fortune) {
        ItemStack stack = getPickStack();
        if (!stack.isEmpty()) {
            toDrop.add(stack);
        }
    }

    /** Called whenever this pluggable is picked by the player (similar to Block.getPickBlock)
     * 
     * @return The stack that should be picked, or ItemStack.EMPTY if no stack can be picked from this pluggable. */
    public ItemStack getPickStack() {
        return ItemStack.EMPTY;
    }

    public InteractionResult onPluggableActivate(Player player, BlockHitResult trace, Level level) {
        return InteractionResult.PASS;
    }

    @Nullable
    public PluggableModelKey getModelRenderKey(RenderType layer) {
        return null;
    }

    /** Called if the {@link IPluggableStaticBaker} returns quads with tint indexes set to
     * <code>data * 6 + key.side.ordinal()</code>. <code>"data"</code> is passed in here as <code>"tintIndex"</code>.
     * 
     * @return The tint index to render the quad with, or -1 for default. */
    public int getBlockColor(int tintIndex) {
        return -1;
    }

    /** Legacy compatibility hook for checking whether this pluggable can be connected. */
    @Deprecated
    public boolean canBeConnected() {
        return false;
    }

    /** Returns whether this pluggable provides a solid face to the pipe holder. */
    public boolean isSideSolid() {
        return false;
    }

    /** Returns the explosion resistance contributed by this pluggable. */
    public float getExplosionResistance(@Nullable Entity exploder, Explosion explosion) {
        return 0;
    }

    /** Returns whether this pluggable exposes a redstone connection on the given side. */
    public boolean canConnectToRedstone(@Nullable Direction to) {
        return false;
    }
    public void onPlacedBy(Player player) {

    }

	public void rotate(Rotation axis) {
		if(this != PipePluggable.EMPTY)
			this.side = axis.rotate(side);
	}

}
