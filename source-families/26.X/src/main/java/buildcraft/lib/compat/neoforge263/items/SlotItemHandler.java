//? source if >=26.3
package buildcraft.lib.compat.neoforge263.items;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Vanilla Slot bridge for BuildCraft-owned handlers, never registered as a NeoForge API type. */
public class SlotItemHandler extends Slot {
    private final IItemHandler handler;
    private final int handlerIndex;
    public SlotItemHandler(IItemHandler handler,int index,int x,int y){
        super(new SimpleContainer(1),0,x,y);
        this.handler=handler;
        this.handlerIndex=index;
    }
    public IItemHandler getItemHandler(){return handler;}
    @Override public int getSlotIndex(){return handlerIndex;}
    @Override public ItemStack getItem(){return handler.getStackInSlot(handlerIndex);}
    @Override public boolean hasItem(){return !getItem().isEmpty();}
    @Override public boolean mayPlace(ItemStack stack){return handler.isItemValid(handlerIndex,stack);}
    @Override public int getMaxStackSize(){return handler.getSlotLimit(handlerIndex);}
    @Override public int getMaxStackSize(ItemStack stack){return Math.min(stack.getMaxStackSize(),getMaxStackSize());}
    @Override public void set(ItemStack stack){
        if(handler instanceof IItemHandlerModifiable mutable){mutable.setStackInSlot(handlerIndex,stack);return;}
        throw new UnsupportedOperationException("This BuildCraft item handler is read-only");
    }
    @Override public ItemStack remove(int amount){return handler.extractItem(handlerIndex,amount,false);}
    @Override public boolean mayPickup(Player player){return !handler.extractItem(handlerIndex,1,true).isEmpty();}
    @Override public void setChanged(){}
    @Override public boolean isSameInventory(Slot other){
        return other instanceof SlotItemHandler slot && slot.handler == handler;
    }
}
