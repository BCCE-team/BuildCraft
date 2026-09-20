package buildcraft.transport.internal.pipe;

import java.io.IOException;

import javax.annotation.Nonnull;

import org.jetbrains.annotations.NotNull;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.transport.client.render.RenderPipeHolder;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public abstract class PipeBehaviour implements ICapabilityProvider {
    public final IPipe pipe;

    public PipeBehaviour(IPipe pipe) {
        this.pipe = pipe;
    }

    public PipeBehaviour(IPipe pipe, CompoundTag nbt) {
        this.pipe = pipe;
    }

    public CompoundTag writeToNbt() {
        CompoundTag nbt = new CompoundTag();
        return nbt;
    }

    public void writePayload(FriendlyByteBuf buffer, BCNetworkSide side) {}

    public void readPayload(FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {}

    /** @deprecated Replaced by {@link #getTextureData(Direction)}. */
    @Deprecated
    public int getTextureIndex(Direction face) {
        return 0;
    }

    public PipeFaceTex getTextureData(Direction face) {
        return PipeFaceTex.get(getTextureIndex(face));
    }
    
    public int[] getTextureUVs(Direction face) {
        return RenderPipeHolder.DOWN_UV;
    }

    // Event handling

    public boolean canConnect(Direction face, PipeBehaviour other) {
        return true;
    }

    public boolean canConnect(Direction face, BlockEntity oTile) {
        return true;
    }

    /** Used to force a connection to a given tile, even if the {@link PipeFlow} wouldn't normally connect to it. */
    public boolean shouldForceConnection(Direction face, BlockEntity oTile) {
        return false;
    }

    public boolean onPipeActivate(Player player, BlockHitResult trace, Level level,
        EnumPipePart part) {
        return false;
    }

//    public void onEntityCollide(Entity entity) {}

    public void onTick() {}

    /** Returns true while behaviour-local runtime state needs periodic chunk persistence. */
    public boolean requiresPeriodicSave() {
        return false;
    }



    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@Nonnull Capability<T> capability, Direction facing) {
        return LazyOptional.empty();
    }

    public void addDrops(NonNullList<ItemStack> toDrop, int fortune) {}

	public void onEntityCollide(Entity entity) {}
	
	public void rotate(Rotation rot) {}
	
}
