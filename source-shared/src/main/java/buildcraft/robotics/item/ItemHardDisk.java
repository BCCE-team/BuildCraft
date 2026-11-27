package buildcraft.robotics.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
//? if >=1.21.1 {
import buildcraft.lib.misc.ItemStackUtil;
//?}

/** Portable, non-stackable processor configuration. Never contains live world objects. */
public final class ItemHardDisk extends Item {
    public static final String DATA_KEY = "bc_processor_disk";
    public static final int FORMAT = 1;

    public ItemHardDisk(Properties properties) { super(configure(properties)); }

    private static Properties configure(Properties properties) {
        properties.stacksTo(1);
        //? if >=1.21.11 {
        properties.component(net.minecraft.core.component.DataComponents.DAMAGE_RESISTANT,
                new net.minecraft.world.item.component.DamageResistant(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION));
        //?}
        return properties;
    }

    public static boolean isDisk(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ItemHardDisk && stack.getCount() == 1;
    }

    private static CompoundTag root(ItemStack stack) {
        //? if >=1.21.1 {
        return ItemStackUtil.getCustomData(stack);
        //?} else {
        return stack.getTag() == null ? new CompoundTag() : stack.getTag().copy();
        //?}
    }

    /** A missing key denotes a genuinely blank disk, not a corrupt disk. */
    public static boolean initialized(ItemStack stack) { return root(stack).contains(DATA_KEY); }
    public static CompoundTag read(ItemStack stack) {
        CompoundTag root = root(stack);
        //? if >=1.21.11 {
        return buildcraft.lib.compat.NbtCompat.getCompound(root, DATA_KEY);
        //?} else {
        return root.getCompound(DATA_KEY);
        //?}
    }

    public static void write(ItemStack stack, CompoundTag data) {
        if (!isDisk(stack)) throw new IllegalArgumentException("Expected one HDD");
        CompoundTag root = root(stack);
        root.put(DATA_KEY, data.copy());
        //? if >=1.21.1 {
        ItemStackUtil.setCustomData(stack, root);
        //?} else {
        stack.setTag(root);
        //?}
    }
}
