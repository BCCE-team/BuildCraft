package buildcraft.compat.forestry.pipe;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.IPipeHolder.PipeMessageReceiver;
import buildcraft.transport.internal.pipe.PipeBehaviour;
import buildcraft.transport.internal.pipe.PipeEventHandler;
import buildcraft.transport.internal.pipe.PipeEventItem;
import buildcraft.transport.tile.TilePipeHolder;
import forestry.api.core.ILocationProvider;
import forestry.api.core.genetics.filter.IFilterLogic;
import forestry.core.content.sorting.DefaultFilterRuleType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import java.io.IOException;
import javax.annotation.Nullable;

/** Original Apiarist's Pipe routing semantics using Forestry CE 3.0 filter rules. */
public final class PipeBehaviourPropolis extends PipeBehaviour
        implements MenuProvider, ILocationProvider, IFilterLogic.INetworkHandler {
    private final PropolisFilterLogic filter;

    public PipeBehaviourPropolis(IPipe pipe) {
        super(pipe);
        filter = new PropolisFilterLogic(this, this);
    }

    public PipeBehaviourPropolis(IPipe pipe, CompoundTag nbt) {
        this(pipe);
        if (nbt.contains("filter")) {
            filter.read(nbt.getCompound("filter"), TilePipeHolder.getRegistryAccess(pipe));
        }
    }

    public PropolisFilterLogic getFilter() { return filter; }

    public CompoundTag filterTag() {
        return filter.write(new CompoundTag(), TilePipeHolder.getRegistryAccess(pipe));
    }

    @Override
    public CompoundTag writeToNbt() {
        CompoundTag nbt = super.writeToNbt();
        nbt.put("filter", filterTag());
        return nbt;
    }

    @Override
    public void writePayload(FriendlyByteBuf buf, BCNetworkSide side) {
        if (side == BCNetworkSide.SERVER) buf.writeNbt(filterTag());
    }

    @Override
    public void readPayload(FriendlyByteBuf buf, BCNetworkSide side, BCPacketContext context) throws IOException {
        if (side == BCNetworkSide.CLIENT) {
            CompoundTag tag = buf.readNbt();
            if (tag != null) filter.read(tag, TilePipeHolder.getRegistryAccess(pipe));
        }
    }

    @Override
    public int getTextureIndex(@Nullable Direction face) {
        return face == null ? 0 : face.ordinal() + 1;
    }

    @Override
    public boolean onPipeActivate(Player player, BlockHitResult hit, Level level, EnumPipePart part) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer
                && pipe.getHolder().canPlayerInteract(player)) {
            serverPlayer.openMenu(this, buf -> {
                buf.writeBlockPos(getBlockPos());
                buf.writeNbt(filterTag());
            });
        }
        return true;
    }

    @PipeEventHandler
    public void route(PipeEventItem.SideCheck event) {
        for (Direction face : Direction.values()) {
            if (!event.isAllowed(face)) continue;
            if (!filter.isValid(event.stack, face)) {
                event.disallow(face);
            } else if (filter.getRule(face) == DefaultFilterRuleType.ANYTHING) {
                event.decreasePriority(face);
            }
        }
    }

    @Override
    public BlockPos getBlockPos() { return pipe.getHolder().getPipePos(); }

    @Override
    public Level getLevel() { return pipe.getHolder().getPipeWorld(); }

    @Override
    public Component getDisplayName() {
        return Component.translatable("pipe.buildcraftcompat.forestry_propolis");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new ContainerPropolisPipe(id, inventory, this);
    }

    @Override
    public void sendToPlayers(IFilterLogic logic, ServerLevel level, Player player) {
        // Only the authenticated BCCE filter messages invoke this callback.
        pipe.getHolder().getPipeTile().setChanged();
        pipe.getHolder().scheduleNetworkUpdate(PipeMessageReceiver.BEHAVIOUR);
        MessagePropolisFilterState.sendToViewers(level, getBlockPos(), filterTag());
    }
}
