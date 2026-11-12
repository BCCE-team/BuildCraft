package buildcraft.builders.item;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
//? if >=1.21.1 {
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.enchantment.ItemEnchantments;
//? }

/** Keeps the Quarry's item enchantments separate from its energy-driven mining mechanics. */
public final class QuarryEnchantments {
    private QuarryEnchantments() {}

    /** A fresh diamond pickaxe is used only to determine the target block's drops. */
    public static ItemStack miningTool(ItemStack quarryItem) {
        ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        if (!quarryItem.isEmpty()) {
//? if <1.21.1 {
            EnchantmentHelper.setEnchantments(EnchantmentHelper.getEnchantments(quarryItem), pickaxe);
//? } else {
            ItemEnchantments enchantments = quarryItem.get(DataComponents.ENCHANTMENTS);
            if (enchantments != null && !enchantments.isEmpty()) {
                pickaxe.set(DataComponents.ENCHANTMENTS, enchantments);
            }
//? }
        }
        return pickaxe;
    }

    public static int efficiencyLevel(ItemStack quarryItem, Level world) {
        if (quarryItem.isEmpty() || world == null) return 0;
//? if <1.21.1 {
        return EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY, quarryItem);
//? } else {
        return EnchantmentHelper.getItemEnchantmentLevel(
            world.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY),
            quarryItem
        );
//? }
    }

    /**
     * Efficiency improves Quarry work per MJ, not the vanilla diamond pickaxe's dig speed.
     * Battery input limits, task scheduling and quarryMaxBlockMineRate still apply.
     */
    public static long breakPowerTarget(long normalTarget, int efficiencyLevel) {
        if (normalTarget <= 0 || efficiencyLevel <= 0) return normalTarget;
        int level = Math.min(5, efficiencyLevel);
        double multiplier = 1.0 + 0.12 * level * level;
        return Math.max(1L, (long) Math.ceil(normalTarget / multiplier));
    }
}
