package buildcraft.compat.forestry.pipe;

import buildcraft.transport.internal.pipe.IPipeHolder;
import forestry.api.core.ILocationProvider;
import forestry.api.core.genetics.filter.IFilterLogic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import javax.annotation.Nullable;

/** Forestry genetic filter menu with the regular BuildCraft pipe access rules. */
public final class ContainerPropolisPipe extends AbstractContainerMenu {
    private final BlockPos pos;
    @Nullable private final PipeBehaviourPropolis behaviour;
    private final PropolisFilterLogic filter;

    public static ContainerPropolisPipe fromNetwork(int id, Inventory inventory, FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        CompoundTag tag = buf.readNbt();
        PipeBehaviourPropolis behaviour = findBehaviour(inventory.player.level(), pos);
        ContainerPropolisPipe menu;
        if (behaviour != null) {
            menu = new ContainerPropolisPipe(id, inventory, pos, behaviour.getFilter(), behaviour, false);
        } else {
            PropolisFilterLogic detached = new PropolisFilterLogic(
                new DetachedLocation(inventory.player.level(), pos), (logic, level, player) -> {});
            menu = new ContainerPropolisPipe(id, inventory, pos, detached, null, false);
        }
        if (tag != null) menu.applyFilterState(tag, inventory.player.level().registryAccess());
        return menu;
    }

    public ContainerPropolisPipe(int id, Inventory inventory, PipeBehaviourPropolis behaviour) {
        this(id, inventory, behaviour.getBlockPos(), behaviour.getFilter(), behaviour, true);
    }

    private ContainerPropolisPipe(int id, Inventory inventory, BlockPos pos,
                                 PropolisFilterLogic filter, @Nullable PipeBehaviourPropolis behaviour,
                                 boolean trackOpen) {
        super(ForestryPipes.PROPOLIS_PIPE_MENU.get(), id);
        this.pos = pos;
        this.filter = filter;
        this.behaviour = behaviour;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new SlotPropolisPipe(inventory, 9 + row * 9 + column,
                    26 + column * 18, 140 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new SlotPropolisPipe(inventory, column, 26 + column * 18, 198));
        }
        if (trackOpen) behaviour.pipe.getHolder().onPlayerOpen(inventory.player);
    }

    public BlockPos getPos() { return pos; }
    public IFilterLogic getLogic() { return filter; }
    public boolean hasSamePipe(BlockPos other) { return pos.equals(other); }

    public void applyFilterState(CompoundTag data, HolderLookup.Provider registries) {
        filter.read(data, registries);
    }

    @Override
    public boolean stillValid(Player player) {
        if (player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > 64) return false;
        if (player.level().isClientSide()) return true;
        PipeBehaviourPropolis live = findBehaviour(player.level(), pos);
        return live != null && live.pipe.getHolder().canPlayerInteract(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        // Inventory-only menu; there are no real pipe inventory slots.
        if (slot < 0 || slot >= slots.size()) return ItemStack.EMPTY;
        Slot from = slots.get(slot);
        if (!from.hasItem()) return ItemStack.EMPTY;
        ItemStack original = from.getItem();
        ItemStack copy = original.copy();
        int boundary = 27;
        if (!moveItemStackTo(original, slot < boundary ? boundary : 0,
                slot < boundary ? slots.size() : boundary, false)) return ItemStack.EMPTY;
        if (original.isEmpty()) from.set(ItemStack.EMPTY); else from.setChanged();
        return copy;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (behaviour != null) behaviour.pipe.getHolder().onPlayerClose(player);
    }

    @Nullable
    public static PipeBehaviourPropolis findBehaviour(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof IPipeHolder holder && holder.getPipe() != null
                && holder.getPipe().getBehaviour() instanceof PipeBehaviourPropolis result) return result;
        return null;
    }

    private record DetachedLocation(Level level, BlockPos pos) implements ILocationProvider {
        @Override public BlockPos getBlockPos() { return pos; }
        @Override public Level getLevel() { return level; }
    }
}
