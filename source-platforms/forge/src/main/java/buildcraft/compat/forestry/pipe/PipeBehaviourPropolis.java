package buildcraft.compat.forestry.pipe;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.IPipeHolder.PipeMessageReceiver;
import buildcraft.transport.internal.pipe.PipeBehaviour;
import buildcraft.transport.internal.pipe.PipeEventHandler;
import buildcraft.transport.internal.pipe.PipeEventItem;
import forestry.api.ForestryCapabilities;
import forestry.api.core.ILocationProvider;
import forestry.api.genetics.filter.IFilterLogic;
import forestry.sorting.DefaultFilterRuleType;
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
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;
import net.minecraftforge.network.NetworkHooks;

/** Apiarist's Pipe behaviour based on the original BuildCraft Compat implementation. */
public final class PipeBehaviourPropolis extends PipeBehaviour
        implements MenuProvider, ILocationProvider, IFilterLogic.INetworkHandler {
    private final PropolisFilterLogic filter;
    private final LazyOptional<IFilterLogic> filterCapability;

    public PipeBehaviourPropolis(IPipe pipe) {
        super(pipe);
        this.filter = new PropolisFilterLogic(this, this);
        this.filterCapability = LazyOptional.of(() -> filter);
    }

    public PipeBehaviourPropolis(IPipe pipe, CompoundTag nbt) {
        this(pipe);
        if (nbt.contains("filter")) {
            filter.read(nbt.getCompound("filter"));
        }
    }

    public PropolisFilterLogic getFilter() {
        return filter;
    }

    @Override
    public CompoundTag writeToNbt() {
        CompoundTag nbt = super.writeToNbt();
        nbt.put("filter", filter.write(new CompoundTag()));
        return nbt;
    }

    @Override
    public void writePayload(FriendlyByteBuf buffer, BCNetworkSide side) {
        if (side == BCNetworkSide.SERVER) {
            buffer.writeNbt(filter.write(new CompoundTag()));
        }
    }

    @Override
    public void readPayload(FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        if (side == BCNetworkSide.CLIENT) {
            CompoundTag tag = buffer.readNbt();
            if (tag != null) {
                filter.read(tag);
            }
        }
    }

    @Override
    public int getTextureIndex(@Nullable Direction face) {
        return face == null ? 0 : face.ordinal() + 1;
    }

    @Override
    public boolean onPipeActivate(Player player, BlockHitResult trace, Level level, EnumPipePart part) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(serverPlayer, this, buffer -> {
                buffer.writeBlockPos(getCoordinates());
                buffer.writeNbt(filter.write(new CompoundTag()));
            });
        }
        return true;
    }

    @PipeEventHandler
    public void sideCheck(PipeEventItem.SideCheck event) {
        for (Direction face : Direction.values()) {
            if (!event.isAllowed(face)) {
                continue;
            }
            if (!filter.isValid(event.stack, face)) {
                event.disallow(face);
            } else if (filter.getRule(face) == DefaultFilterRuleType.ANYTHING) {
                // A specifically matched rule always wins over the catch-all route.
                event.decreasePriority(face);
            }
        }
    }

    public void filterChanged(Player player) {
        pipe.getHolder().getPipeTile().setChanged();
        pipe.getHolder().scheduleNetworkUpdate(PipeMessageReceiver.BEHAVIOUR);
        Level level = getWorldObj();
        if (level instanceof ServerLevel serverLevel) {
            sendToPlayers(filter, serverLevel, player);
        }
    }

    @Override
    public BlockPos getCoordinates() {
        return pipe.getHolder().getPipePos();
    }

    @Override
    public Level getWorldObj() {
        return pipe.getHolder().getPipeWorld();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("pipe.buildcraftcompat.forestry_propolis");
    }

    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory inventory, Player player) {
        return new ContainerPropolisPipe(windowId, inventory, this);
    }

    @Override
    public void sendToPlayers(IFilterLogic logic, ServerLevel level, Player player) {
        ForestryPropolisNetwork.sendStateToViewers(level, getCoordinates(), filter);
    }

    @Override
    public <T> @Nonnull LazyOptional<T> getCapability(@Nonnull Capability<T> capability, @Nullable Direction facing) {
        if (capability == ForestryCapabilities.FILTER_LOGIC) {
            return filterCapability.cast();
        }
        return super.getCapability(capability, facing);
    }
}
