package buildcraft.core.item;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Internal persisted map-location discriminator for component-based item stacks. */
public enum MapLocationType {
    CLEAN,
    SPOT,
    AREA,
    PATH,
    ZONE,
    PATH_REPEATING;

    public final int meta = ordinal();

    public static MapLocationType getFromStack(@Nonnull ItemStack stack) {
        CompoundTag tag = getCustomData(stack);
        if (tag != null && tag.contains("kind")) {
            return switch (tag.getByte("kind")) {
                case 0 -> SPOT;
                case 1 -> AREA;
                case 2 -> PATH;
                case 3 -> ZONE;
                case 4 -> PATH_REPEATING;
                default -> CLEAN;
            };
        }
        int damage = stack.getDamageValue();
        return damage < 0 || damage >= values().length ? CLEAN : values()[damage];
    }

    public void setToStack(@Nonnull ItemStack stack) {
        stack.setDamageValue(meta);
        stack.set(DataComponents.MAX_STACK_SIZE, this == CLEAN ? 16 : 1);
        CompoundTag tag = getCustomData(stack);
        if (this == CLEAN) {
            if (tag != null) {
                tag.remove("kind");
                tag.remove("Damage");
                setCustomData(stack, tag.isEmpty() ? null : tag);
            }
            return;
        }
        if (tag == null) tag = new CompoundTag();
        tag.putByte("kind", switch (this) {
            case SPOT -> (byte) 0;
            case AREA -> (byte) 1;
            case PATH -> (byte) 2;
            case ZONE -> (byte) 3;
            case PATH_REPEATING -> (byte) 4;
            case CLEAN -> (byte) -1;
        });
        setCustomData(stack, tag);
    }

    @Nullable
    private static CompoundTag getCustomData(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.copyTag();
    }

    private static void setCustomData(ItemStack stack, @Nullable CompoundTag tag) {
        if (tag == null || tag.isEmpty()) stack.remove(DataComponents.CUSTOM_DATA);
        else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag.copy()));
    }
}
