package buildcraft.lib.internal.recipes;

import buildcraft.lib.internal.core.IStackFilter;

/** Internal filter/count pair retained for legacy BuildCraft inventory and recipe code. */
public final class StackDefinition {
    public final IStackFilter filter;
    public final int count;

    public StackDefinition(IStackFilter filter, int count) {
        this.filter = filter;
        this.count = count;
    }

    public StackDefinition(IStackFilter filter) {
        this(filter, 1);
    }
}
