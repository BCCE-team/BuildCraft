package buildcraft.compat.rei;

import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.BCRoboticsItems;
import buildcraft.silicon.BCSiliconItems;
import buildcraft.silicon.item.ItemPluggableGate;
import buildcraft.silicon.item.ItemPluggableLens;
import me.shedaniel.rei.api.common.entry.comparison.ItemComparatorRegistry;

/** Subtype identity mirrors JEI: charge and pipe dye are not recipe identities. */
final class ReiItemComparators {
    private ReiItemComparators() {}
    static void register(ItemComparatorRegistry registry) {
        registry.register((context, stack) -> BCRoboticsBoards.getBoard(stack).key().hashCode(), BCRoboticsItems.REDSTONE_BOARD.get());
        registry.register((context, stack) -> BCRoboticsBoards.getRobotBoard(stack).key().hashCode(), BCRoboticsItems.ROBOT.get());
        registry.register((context, stack) -> ItemPluggableGate.getVariant(stack).material.tag.hashCode(), BCSiliconItems.PLUG_GATE_ITEM.get());
        registry.register((context, stack) -> ItemPluggableLens.getData(stack).getItemDamage(), BCSiliconItems.PLUG_LENS_ITEM.get());
        //? if <1.21 {
        registry.registerNbt(BCSiliconItems.PLUG_FACADE_ITEM.get());
        //?} else {
        registry.registerComponents(BCSiliconItems.PLUG_FACADE_ITEM.get());
        //?}
    }
}
