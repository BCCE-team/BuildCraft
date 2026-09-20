//? source if >=1.21.1
package buildcraft.lib.item;

import buildcraft.lib.platform.registry.BCDeferredRegister;
import java.util.EnumMap;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import buildcraft.lib.compat.RegistryCompat;

public class ItemByEnum<E extends Enum<E> & StringRepresentable> extends Item{

    protected final E type;

    public ItemByEnum(Properties p_40566_, E type) {
        super(p_40566_);
        this.type = type;
    }

    public E getType() {
        return type;
    }

    public static <E extends Enum<E> & StringRepresentable, P extends ItemByEnum<?>> EnumMap<E, P> creatItems(IEunmItemCreator<E, P> creator, Properties p, E[] types, Class<E> clazz, String id, BCDeferredRegister<Item> register) {
        EnumMap<E, P> items = new EnumMap<>(clazz);
        for(final E e : types) {
            RegistryCompat.registerItem(register, id+"/"+e.getSerializedName(), () -> {
                P i = creator.create(RegistryCompat.itemProperties(p), e);
                items.put(e, i);
                return i;
            });
        }
        //items.forEach(register);
        return items;
    }

}
