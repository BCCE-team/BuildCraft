package buildcraft.lib.block;

import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** Visible blockstate of the optional machine Redstone Controlling Module.
 * The actual upgrade item is still stored in the block entity and returned as loot.
 */
public final class RedstoneModuleState {
    public static final BooleanProperty INSTALLED = BooleanProperty.create("redstone_module");

    private RedstoneModuleState() { }
}
