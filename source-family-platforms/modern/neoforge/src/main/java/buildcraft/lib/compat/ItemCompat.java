//? source if >=1.21.11
package buildcraft.lib.compat;

import buildcraft.lib.compat.minecraft.components.BCItemData;
import buildcraft.lib.misc.ItemStackUtil;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.FuelValues;
import net.neoforged.neoforge.common.ItemAbility;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** 1.21.11 compatibility helpers for ItemStack APIs changed since 1.21.1. */
public final class ItemCompat {
    private static final ItemAbility AXE_DIG = ItemAbility.get("axe_dig");
    private static final ItemAbility PICKAXE_DIG = ItemAbility.get("pickaxe_dig");
    private static final ItemAbility SHOVEL_DIG = ItemAbility.get("shovel_dig");
    private static final ItemAbility SWORD_DIG = ItemAbility.get("sword_dig");

    private ItemCompat() {}

    public static boolean isArmor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.is(ItemTags.HEAD_ARMOR) || stack.is(ItemTags.CHEST_ARMOR)
            || stack.is(ItemTags.LEG_ARMOR) || stack.is(ItemTags.FOOT_ARMOR)) {
            return true;
        }
        // Modern data-driven/modded armour may not use the removed ArmorItem subclass.
        // An equippable armour slot plus a positive armour attribute is the closest semantic
        // equivalent without accidentally accepting elytra/pumpkins as robot armour.
        return getArmorSlot(stack) != null && getArmorDefense(stack) > 0;
    }

    public static int getArmorDefense(ItemStack stack) {
        EquipmentSlot slot = getArmorSlot(stack);
        if (slot == null) return 0;
        ItemAttributeModifiers modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        return Math.max(0, (int) Math.round(modifiers.compute(Attributes.ARMOR, 0.0D, slot)));
    }

    public static boolean isSword(ItemStack stack) {
        return stack != null && !stack.isEmpty()
            && (stack.is(ItemTags.SWORDS) || stack.canPerformAction(SWORD_DIG));
    }

    public static boolean isAxe(ItemStack stack) {
        return stack != null && !stack.isEmpty()
            && (stack.getItem() instanceof AxeItem || stack.canPerformAction(AXE_DIG));
    }

    public static boolean isPickaxe(ItemStack stack) {
        return stack != null && !stack.isEmpty()
            && (stack.is(ItemTags.PICKAXES) || stack.canPerformAction(PICKAXE_DIG));
    }

    public static boolean isShovel(ItemStack stack) {
        return stack != null && !stack.isEmpty()
            && (stack.getItem() instanceof ShovelItem || stack.canPerformAction(SHOVEL_DIG));
    }

    public static EquipmentSlot getArmorSlot(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null) return null;
        return switch (equippable.slot()) {
            case HEAD, CHEST, LEGS, FEET -> equippable.slot();
            default -> null;
        };
    }

    /**
     * 1.21.11 exposes furnace fuel data through the level fuel-values table rather than a one-argument stack helper.
     * Prefer the live server FuelValues so datapack/NeoForge fuel overrides behave like 1.21.1.
     * During early/client-only calls fall back to vanilla values built from the active registry lookup.
     */
    public static int getBurnTime(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        FuelValues values = server != null
            ? server.overworld().fuelValues()
            : FuelValues.vanillaBurnTimes(ItemStackUtil.requireActiveRegistryProvider(), FeatureFlags.DEFAULT_FLAGS);
        return stack.getBurnTime(RecipeType.SMELTING, values);
    }

    public static ItemStack getCraftingRemainingItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        return stack.getCraftingRemainder();
    }

    /** Serialize an ItemStack with the registry-aware 1.21.11 codec. Empty stacks encode as an empty compound. */
    public static CompoundTag saveOptional(ItemStack stack, HolderLookup.Provider registries) {
        return BCItemData.save(stack, registries);
    }

    /** Decode the current 1.21.11 ItemStack codec representation. Legacy normalization is handled by ItemStackUtil. */
    public static ItemStack parseOptional(HolderLookup.Provider registries, CompoundTag tag) {
        return parseOptional(registries, (Tag) tag);
    }

    public static ItemStack parseOptional(HolderLookup.Provider registries, Tag tag) {
        return BCItemData.load(registries, tag);
    }
}
