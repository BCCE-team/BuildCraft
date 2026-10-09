package buildcraft.core.item;

import javax.annotation.Nonnull;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** Internal persisted map-location discriminator. The save encoding intentionally remains unchanged. */
public enum MapLocationType {
    CLEAN,
    SPOT,
    AREA,
    PATH,
    ZONE,
    PATH_REPEATING;

    public final int meta = ordinal();

    public static MapLocationType getFromStack(@Nonnull ItemStack stack) {
        CompoundTag tag = stack.getTag();
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
        CompoundTag tag = stack.getTag();
        if (this == CLEAN) {
            if (tag != null) {
                tag.remove("kind");
                tag.remove("Damage");
                if (tag.isEmpty()) stack.setTag(null);
            }
            return;
        }
        tag = stack.getOrCreateTag();
        tag.putByte("kind", switch (this) {
            case SPOT -> (byte) 0;
            case AREA -> (byte) 1;
            case PATH -> (byte) 2;
            case ZONE -> (byte) 3;
            case PATH_REPEATING -> (byte) 4;
            case CLEAN -> (byte) -1;
        });
    }
}
