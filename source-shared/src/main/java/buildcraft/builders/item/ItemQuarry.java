package buildcraft.builders.item;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
//? if <1.21.4 {
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
//? }
//? if >=1.21.1 && <1.21.4 {
import net.minecraft.core.Holder;
//? }

/** A placeable Quarry with the enchanting rules of a diamond pickaxe, but no durability. */
public class ItemQuarry extends BlockItem {
    public ItemQuarry(Block block, Item.Properties properties) {
        super(block, properties);
    }

//? if <1.21.1 {
    @Override
    public boolean isEnchantable(ItemStack stack) {
        return !stack.isEmpty() && !stack.isEnchanted();
    }

    @Override
    public int getEnchantmentValue() {
        return Items.DIAMOND_PICKAXE.getEnchantmentValue();
    }

    /** Delegate eligibility to the real tool; do not make unrelated enchantments available. */
    @Override
    public boolean canApplyAtEnchantingTable(ItemStack stack, Enchantment enchantment) {
        return enchantment.canApplyAtEnchantingTable(new ItemStack(Items.DIAMOND_PICKAXE));
    }

    @Override
    public boolean isBookEnchantable(ItemStack stack, ItemStack book) {
        // Forge's anvil handles enchantment compatibility, levels and costs per book entry.
        return true;
    }
//? }

//? if >=1.21.1 && <1.21.4 {
    // MC 1.21.1 has no Item.Properties.enchantable(int) / ENCHANTABLE component.
    // Its table still consults Item.isEnchantable and NeoForge's stack-sensitive
    // enchantability hook, even for an unbreakable item like the Quarry.
    @Override
    public boolean isEnchantable(ItemStack stack) {
        return !stack.isEmpty() && !stack.isEnchanted();
    }

    @Override
    public int getEnchantmentValue(ItemStack stack) {
        return Items.DIAMOND_PICKAXE.getEnchantmentValue(new ItemStack(Items.DIAMOND_PICKAXE));
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getEnchantmentValue() {
        return Items.DIAMOND_PICKAXE.getEnchantmentValue();
    }

    // Both the enchantment table and the anvil use the real diamond pickaxe's
    // eligibility. Their vanilla conflict rules are not replaced or bypassed.
    @Override
    public boolean isPrimaryItemFor(ItemStack stack, Holder<Enchantment> enchantment) {
        return Items.DIAMOND_PICKAXE.isPrimaryItemFor(new ItemStack(Items.DIAMOND_PICKAXE), enchantment);
    }

    @Override
    public boolean supportsEnchantment(ItemStack stack, Holder<Enchantment> enchantment) {
        return Items.DIAMOND_PICKAXE.supportsEnchantment(new ItemStack(Items.DIAMOND_PICKAXE), enchantment);
    }
//? }
}
