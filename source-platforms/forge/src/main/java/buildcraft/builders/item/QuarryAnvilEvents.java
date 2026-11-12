package buildcraft.builders.item;

import java.util.HashMap;
import java.util.Map;

import buildcraft.builders.BCBuilders;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.event.AnvilUpdateEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Older Forge's vanilla anvil checks EnchantmentCategory against the BlockItem
 * itself. Treat the Quarry as a diamond pickaxe for enchantment compatibility,
 * without making the Quarry a damageable pickaxe or changing mining mechanics.
 * Newer Minecraft versions express the same rule with enchantable item tags.
 */
@Mod.EventBusSubscriber(modid = BCBuilders.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class QuarryAnvilEvents {
    private QuarryAnvilEvents() {}

    @SubscribeEvent
    public static void onAnvilUpdate(AnvilUpdateEvent event) {
        ItemStack left = event.getLeft();
        ItemStack right = event.getRight();
        if (!(left.getItem() instanceof ItemQuarry)
            || (!right.is(Items.ENCHANTED_BOOK) && !(right.getItem() instanceof ItemQuarry))) {
            return;
        }

        Map<Enchantment, Integer> merged = new HashMap<>(EnchantmentHelper.getEnchantments(left));
        Map<Enchantment, Integer> offered = EnchantmentHelper.getEnchantments(right);
        if (offered.isEmpty()) return;

        ItemStack diamondPickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        int enchantCost = 0;
        boolean changed = false;
        for (Map.Entry<Enchantment, Integer> entry : offered.entrySet()) {
            Enchantment enchantment = entry.getKey();
            if (!enchantment.canEnchant(diamondPickaxe)) continue;

            boolean compatible = true;
            for (Enchantment existing : merged.keySet()) {
                if (existing != enchantment && !existing.isCompatibleWith(enchantment)) {
                    compatible = false;
                    break;
                }
            }
            if (!compatible) continue;

            int previous = merged.getOrDefault(enchantment, 0);
            int proposed = previous == entry.getValue()
                ? previous + 1 : Math.max(previous, entry.getValue());
            int next = Math.min(enchantment.getMaxLevel(), proposed);
            if (next <= previous) continue;
            merged.put(enchantment, next);
            changed = true;

            int rarityCost = switch (enchantment.getRarity()) {
                case COMMON -> 1;
                case UNCOMMON -> 2;
                case RARE -> 4;
                case VERY_RARE -> 8;
            };
            if (right.is(Items.ENCHANTED_BOOK)) rarityCost = Math.max(1, rarityCost / 2);
            enchantCost += rarityCost * next;
        }
        if (!changed) return;

        ItemStack output = left.copy();
        EnchantmentHelper.setEnchantments(merged, output);
        int nameCost = 0;
        String name = event.getName();
        if (name != null) {
            if (name.isBlank()) {
                if (output.hasCustomHoverName()) {
                    output.resetHoverName();
                    nameCost = 1;
                }
            } else if (!name.equals(output.getHoverName().getString())) {
                output.setHoverName(Component.literal(name));
                nameCost = 1;
            }
        }
        int totalCost = Math.max(1, event.getCost() + enchantCost + nameCost);
        int baseRepairCost = Math.max(left.getBaseRepairCost(), right.getBaseRepairCost());
        output.setRepairCost((int) Math.min(Integer.MAX_VALUE, (long) baseRepairCost * 2L + 1L));
        event.setOutput(output);
        event.setCost(totalCost);
        event.setMaterialCost(1);
    }
}
