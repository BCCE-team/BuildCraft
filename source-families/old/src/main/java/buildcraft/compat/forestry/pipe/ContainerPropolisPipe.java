package buildcraft.compat.forestry.pipe;

import javax.annotation.Nullable;

import forestry.api.core.ILocationProvider;
import forestry.core.gui.ContainerForestry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/** Forestry-style genetic filter menu backed by an Apiarist's Pipe. */
public final class ContainerPropolisPipe extends ContainerForestry {
    private final BlockPos pos;
    @Nullable
    private final PipeBehaviourPropolis behaviour;
    private final PropolisFilterLogic logic;

    public static ContainerPropolisPipe fromNetwork(int windowId, Inventory inventory, FriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        CompoundTag filterTag = buffer.readNbt();
        //? if <1.20 {
        PipeBehaviourPropolis behaviour = ForestryPropolisNetwork.findBehaviour(inventory.player.level, pos);
        //?} else {
        /*?
        PipeBehaviourPropolis behaviour = ForestryPropolisNetwork.findBehaviour(inventory.player.level(), pos);
        ?*/
        //?}
        if (behaviour != null) {
            if (filterTag != null) {
                behaviour.getFilter().read(filterTag);
            }
            return new ContainerPropolisPipe(windowId, inventory, behaviour, false);
        }

        //? if <1.20 {
        DetachedLocation location = new DetachedLocation(inventory.player.level, pos);
        //?} else {
        /*?
        DetachedLocation location = new DetachedLocation(inventory.player.level(), pos);
        ?*/
        //?}
        PropolisFilterLogic logic = new PropolisFilterLogic(location, (filter, level, player) -> {
        });
        if (filterTag != null) {
            logic.read(filterTag);
        }
        return new ContainerPropolisPipe(windowId, inventory, pos, logic);
    }

    public ContainerPropolisPipe(int windowId, Inventory inventory, PipeBehaviourPropolis behaviour) {
        this(windowId, inventory, behaviour, true);
    }

    private ContainerPropolisPipe(int windowId, Inventory inventory, PipeBehaviourPropolis behaviour,
            boolean notifyOpen) {
        super(windowId, ForestryPipes.PROPOLIS_PIPE_MENU.get(), inventory.player);
        this.pos = behaviour.getCoordinates();
        this.behaviour = behaviour;
        this.logic = behaviour.getFilter();
        addInventory(inventory, 26, 140);
        if (notifyOpen) {
            behaviour.pipe.getHolder().onPlayerOpen(inventory.player);
        }
    }

    private ContainerPropolisPipe(int windowId, Inventory inventory, BlockPos pos, PropolisFilterLogic logic) {
        super(windowId, ForestryPipes.PROPOLIS_PIPE_MENU.get(), inventory.player);
        this.pos = pos;
        this.behaviour = null;
        this.logic = logic;
        addInventory(inventory, 26, 140);
    }

    private void addInventory(Inventory inventory, int x, int y) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new SlotPropolisPipe(inventory, column + row * 9 + 9,
                    x + column * 18, y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new SlotPropolisPipe(inventory, column, x + column * 18, y + 58));
        }
    }

    public BlockPos getPos() {
        return pos;
    }

    public PropolisFilterLogic getLogic() {
        return logic;
    }

    public boolean hasSamePipe(BlockPos otherPos) {
        return pos.equals(otherPos);
    }

    public void applyFilterState(CompoundTag tag) {
        logic.read(tag);
    }

    @Override
    protected boolean canAccess(Player player) {
        return stillValid(player);
    }

    @Override
    public boolean stillValid(Player player) {
        if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > 64.0D) {
            return false;
        }
        //? if <1.20 {
        if (player.level.isClientSide) {
        //?} else {
        /*?
        if (player.level().isClientSide) {
        ?*/
        //?}
            return true;
        }
        //? if <1.20 {
        PipeBehaviourPropolis current = ForestryPropolisNetwork.findBehaviour(player.level, pos);
        //?} else {
        /*?
        PipeBehaviourPropolis current = ForestryPropolisNetwork.findBehaviour(player.level(), pos);
        ?*/
        //?}
        return current != null && current.pipe.getHolder().canPlayerInteract(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (behaviour != null) {
            behaviour.pipe.getHolder().onPlayerClose(player);
        }
    }

    private record DetachedLocation(Level level, BlockPos pos) implements ILocationProvider {
        @Override
        public BlockPos getCoordinates() {
            return pos;
        }

        @Override
        public Level getWorldObj() {
            return level;
        }
    }
}
