//? source if >=1.21.1
package buildcraft.lib.list;

import java.util.EnumSet;
import javax.annotation.Nonnull;

import buildcraft.api.v2.list.ListMatchType;
import buildcraft.lib.compat.ItemCompat;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

public class ListMatchHandlerArmor extends ListMatchHandlerBackend {
    private static EnumSet<EquipmentSlot> getArmorTypes(ItemStack stack) {
        EnumSet<EquipmentSlot> types = EnumSet.noneOf(EquipmentSlot.class);
        EquipmentSlot slot = ItemCompat.getArmorSlot(stack);
        if (slot != null) {
            types.add(slot);
        }
        return types;
    }

    public boolean matches(ListMatchType type, @Nonnull ItemStack stack, @Nonnull ItemStack target, boolean precise) {
        if (type == ListMatchType.TYPE) {
            EnumSet<EquipmentSlot> source = getArmorTypes(stack);
            if (!source.isEmpty()) {
                EnumSet<EquipmentSlot> targetTypes = getArmorTypes(target);
                if (precise) return source.equals(targetTypes);
                source.removeAll(EnumSet.complementOf(targetTypes));
                return !source.isEmpty();
            }
        }
        return false;
    }

    public boolean isValidSource(ListMatchType type, @Nonnull ItemStack stack) {
        return type == ListMatchType.TYPE && !getArmorTypes(stack).isEmpty();
    }
}
