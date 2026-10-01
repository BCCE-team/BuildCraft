//? source if >=1.21.1
package buildcraft.lib.compat.minecraft.components;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
//? if >=1.21.11 {
import net.minecraft.nbt.NbtOps;
//? }

/** Registry-aware optional item serialization. Loader-specific tool/fuel APIs do not belong here. */
public final class BCItemData {
    private BCItemData() {}
//? if >=1.21.11 {
    public static CompoundTag save(ItemStack stack, HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty() || registries == null) return new CompoundTag();
        Tag encoded = ItemStack.OPTIONAL_CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack)
            .result().orElse(null);
        return encoded instanceof CompoundTag compound ? compound : new CompoundTag();
    }
    public static ItemStack load(HolderLookup.Provider registries, Tag tag) {
        if (registries == null || tag == null) return ItemStack.EMPTY;
        return ItemStack.OPTIONAL_CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag)
            .result().orElse(ItemStack.EMPTY);
    }
//? } else {
    public static CompoundTag save(ItemStack stack, HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty() || registries == null) return new CompoundTag();
        Tag encoded = stack.saveOptional(registries);
        return encoded instanceof CompoundTag compound ? compound : new CompoundTag();
    }
    public static ItemStack load(HolderLookup.Provider registries, Tag tag) {
        return registries == null || !(tag instanceof CompoundTag compound)
            ? ItemStack.EMPTY : ItemStack.parseOptional(registries, compound);
    }
//? }
}
