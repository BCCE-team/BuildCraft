package buildcraft.lib.gui.json;

import java.util.ArrayList;
import java.util.List;

import buildcraft.lib.gui.slot.SlotBase;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

public class InventorySlotHolder {

    public final Slot[] slots;

    public InventorySlotHolder(AbstractContainerMenu inv, Container container) {
        List<Slot> list = new ArrayList<>();
        for (Slot s : inv.slots) {
            if (s.container == container) {
                list.add(s);
            }
        }
        slots = list.toArray(new Slot[0]);
    }

    public InventorySlotHolder(AbstractContainerMenu container, IItemHandler inventory) {
        List<Slot> list = new ArrayList<>();
        for (Slot slot : container.slots) {
            // NeoForge 1.21.x SlotBase uses ItemHandlerCopySlot so vanilla/NeoForge can safely
            // mutate copied stacks. Do not assume every item-handler slot is a SlotItemHandler.
            if (slot instanceof SlotBase baseSlot && baseSlot.itemHandler == inventory) {
                list.add(slot);
            } else if (slot instanceof SlotItemHandler itemSlot && itemSlot.getItemHandler() == inventory) {
                list.add(slot);
            }
        }
        slots = list.toArray(new Slot[0]);
    }
}
