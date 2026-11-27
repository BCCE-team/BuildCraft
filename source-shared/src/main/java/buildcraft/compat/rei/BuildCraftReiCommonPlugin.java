//? if >=1.21.11 {
package buildcraft.compat.rei;

import me.shedaniel.rei.api.common.plugins.REICommonPlugin;
import me.shedaniel.rei.api.common.entry.comparison.ItemComparatorRegistry;

//? if forge || neoforge {
@me.shedaniel.rei.forge.REIPluginCommon
//?}
public final class BuildCraftReiCommonPlugin implements REICommonPlugin {
    @Override public void registerItemComparators(ItemComparatorRegistry registry) {
        ReiItemComparators.register(registry);
    }
}
//?}
