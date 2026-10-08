//? source if >=26.3
package buildcraft.lib.compat;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import buildcraft.lib.compat.minecraft.components.BCItemData;
import buildcraft.lib.misc.ItemStackUtil;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;
import net.neoforged.neoforge.common.ItemAbility;

/** Compatibility helpers for the current ItemStack APIs used by shared BuildCraft code. */
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
            && (stack.is(ItemTags.AXES) || stack.canPerformAction(AXE_DIG));
    }

    public static boolean isPickaxe(ItemStack stack) {
        return stack != null && !stack.isEmpty()
            && (stack.is(ItemTags.PICKAXES) || stack.canPerformAction(PICKAXE_DIG));
    }

    public static boolean isShovel(ItemStack stack) {
        return stack != null && !stack.isEmpty()
            && (stack.is(ItemTags.SHOVELS) || stack.canPerformAction(SHOVEL_DIG));
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

    /** Item classification does not require a server or evaluate random/contextual fuel providers. */
    public static boolean isFuel(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.has(DataComponents.COOKING_FUEL);
    }

    /** Evaluate the component against the caller's actual container-processing context. */
    public static int getBurnTime(ItemStack stack, net.minecraft.world.level.storage.loot.LootContext context) {
        if (!isFuel(stack)) return 0;
        return Math.max(0, net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt.getFromItem(
            stack, DataComponents.COOKING_FUEL, fuel -> fuel.burnTime(),
            java.util.Objects.requireNonNull(context), 0));
    }

    /** Build the same context shape as a vanilla furnace, using the BuildCraft machine and its inventory. */
    public static int getBurnTime(ItemStack stack, net.minecraft.world.level.block.entity.BlockEntity machine,
        net.minecraft.world.Container inventory) {
        if (!isFuel(stack) || !(machine.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return 0;
        var params = new net.minecraft.world.level.storage.loot.LootParams.Builder(level)
            .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_STATE, machine.getBlockState())
            .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY, machine)
            .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN,
                net.minecraft.world.phys.Vec3.atCenterOf(machine.getBlockPos()))
            .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.CONTAINER, inventory)
            .withOptionalParameter(net.neoforged.neoforge.common.loot.NeoForgeLootContextParams.QUERIED_STACK, stack)
            .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.CONTAINER_PROCESS);
        return getBurnTime(stack, new net.minecraft.world.level.storage.loot.LootContext.Builder(params)
            .create(java.util.Optional.empty()));
    }

    @Nonnull
    public static ItemStack getCraftingRemainingItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        @Nullable ItemStackTemplate remainder = stack.getCraftingRemainder();
        if (remainder == null) return ItemStack.EMPTY;
        return remainder.create();
    }

    /** Serialize an ItemStack with the registry-aware ItemStack codec. Empty stacks encode as an empty compound. */
    public static CompoundTag saveOptional(ItemStack stack, HolderLookup.Provider registries) {
        return BCItemData.save(stack, registries);
    }

    /** Decode the current ItemStack codec representation. Legacy normalization is handled by ItemStackUtil. */
    public static ItemStack parseOptional(HolderLookup.Provider registries, CompoundTag tag) {
        return parseOptional(registries, (Tag) tag);
    }

    public static ItemStack parseOptional(HolderLookup.Provider registries, Tag tag) {
        return BCItemData.load(registries, tag);
    }
}
