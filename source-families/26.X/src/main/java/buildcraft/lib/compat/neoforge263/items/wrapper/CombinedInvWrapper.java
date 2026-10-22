//? source if >=26.3
package buildcraft.lib.compat.neoforge263.items.wrapper;

import buildcraft.lib.compat.neoforge263.items.IItemHandlerModifiable;
import java.util.Objects;
import net.minecraft.world.item.ItemStack;

/** Concatenates backing slots, preserving the original per-handler mutation contract. */
public class CombinedInvWrapper implements IItemHandlerModifiable {
    private final IItemHandlerModifiable[] handlers;
    public CombinedInvWrapper(IItemHandlerModifiable... handlers) {
        this.handlers = Objects.requireNonNull(handlers).clone();
    }
    public int getSlots() {
        int count=0;
        for (IItemHandlerModifiable h : handlers) count += h.getSlots();
        return count;
    }
    protected int getIndexForSlot(int slot) {
        Objects.checkIndex(slot,getSlots());
        for(int i=0;i<handlers.length;i++) {
            if(slot<handlers[i].getSlots())return i;
            slot-=handlers[i].getSlots();
        }
        throw new IndexOutOfBoundsException();
    }
    protected IItemHandlerModifiable getHandlerFromIndex(int index) {return handlers[index];}
    protected int getSlotFromIndex(int slot,int handlerIndex) {
        for(int i=0;i<handlerIndex;i++)slot-=handlers[i].getSlots();
        return slot;
    }
    private IItemHandlerModifiable handler(int slot){return handlers[getIndexForSlot(slot)];}
    private int local(int slot){return getSlotFromIndex(slot,getIndexForSlot(slot));}
    public ItemStack getStackInSlot(int slot){return handler(slot).getStackInSlot(local(slot));}
    public ItemStack insertItem(int slot,ItemStack stack,boolean simulate){return handler(slot).insertItem(local(slot),stack,simulate);}
    public ItemStack extractItem(int slot,int count,boolean simulate){return handler(slot).extractItem(local(slot),count,simulate);}
    public int getSlotLimit(int slot){return handler(slot).getSlotLimit(local(slot));}
    public boolean isItemValid(int slot,ItemStack stack){return handler(slot).isItemValid(local(slot),stack);}
    public void setStackInSlot(int slot,ItemStack stack){handler(slot).setStackInSlot(local(slot),stack);}
}
