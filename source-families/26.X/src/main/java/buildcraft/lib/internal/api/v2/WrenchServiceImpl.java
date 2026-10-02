package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.tool.WrenchService;
import buildcraft.lib.BCLibConfig;
import buildcraft.lib.internal.tool.IToolWrench;
import net.minecraft.world.item.ItemStack;

/** Loader-neutral wrench detection used by built-in gameplay and addons. */
final class WrenchServiceImpl implements WrenchService {
    private static final String WRENCH_TAG_NAMESPACE = "c";
    private static final String WRENCH_TAG_PATH = "tools/wrench";

    public boolean isWrench(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getItem() instanceof IToolWrench) return true;
        return BCLibConfig.useWrenchTag && stack.getTags().anyMatch(tag ->
            WRENCH_TAG_NAMESPACE.equals(tag.location().getNamespace())
                && WRENCH_TAG_PATH.equals(tag.location().getPath()));
    }
}
