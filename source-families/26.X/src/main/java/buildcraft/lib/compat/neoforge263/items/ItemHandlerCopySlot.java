//? source if >=26.3
package buildcraft.lib.compat.neoforge263.items;

/** Copying semantics follow SlotItemHandler; all returned stack values belong to the caller. */
public class ItemHandlerCopySlot extends SlotItemHandler {
    public ItemHandlerCopySlot(IItemHandler handler,int index,int x,int y){super(handler,index,x,y);}
    @Override public net.minecraft.world.item.ItemStack getItem(){return super.getItem().copy();}
}
