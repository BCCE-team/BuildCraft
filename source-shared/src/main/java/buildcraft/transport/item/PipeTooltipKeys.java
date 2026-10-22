/* Copyright (c) 2026 the BuildCraft team. MPL-2.0. */
package buildcraft.transport.item;

/** Shares pipe tooltip translations when multiple registered pipes have identical semantics.
 *  Only tooltip keys are redirected; item/pipe registry names remain unchanged.
 */
public final class PipeTooltipKeys {
    private PipeTooltipKeys() { }

    public static String canonical(String key) {
        return switch (key) {
            case "tip.pipe.buildcrafttransport.clay_item" -> "tip.pipe.buildcrafttransport.clay_fluid";
            case "tip.pipe.buildcrafttransport.diamond_wood_fluid" -> "tip.pipe.buildcrafttransport.wood_item";
            case "tip.pipe.buildcrafttransport.quartz_fe" -> "tip.pipe.buildcrafttransport.cobblestone_fe";
            case "tip.pipe.buildcrafttransport.quartz_fluid" -> "tip.pipe.buildcrafttransport.cobblestone_fluid";
            case "tip.pipe.buildcrafttransport.quartz_item" -> "tip.pipe.buildcrafttransport.cobblestone_item";
            case "tip.pipe.buildcrafttransport.quartz_power" -> "tip.pipe.buildcrafttransport.cobblestone_power";
            case "tip.pipe.buildcrafttransport.sandstone_fe" -> "tip.pipe.buildcrafttransport.sandstone_item";
            case "tip.pipe.buildcrafttransport.sandstone_fluid" -> "tip.pipe.buildcrafttransport.sandstone_item";
            case "tip.pipe.buildcrafttransport.sandstone_power" -> "tip.pipe.buildcrafttransport.sandstone_item";
            case "tip.pipe.buildcrafttransport.stone_fe" -> "tip.pipe.buildcrafttransport.cobblestone_fe";
            case "tip.pipe.buildcrafttransport.stone_fluid" -> "tip.pipe.buildcrafttransport.cobblestone_fluid";
            case "tip.pipe.buildcrafttransport.stone_item" -> "tip.pipe.buildcrafttransport.cobblestone_item";
            case "tip.pipe.buildcrafttransport.stone_power" -> "tip.pipe.buildcrafttransport.cobblestone_power";
            case "tip.pipe.buildcrafttransport.wood_fe" -> "tip.pipe.buildcrafttransport.diamond_wood_fe";
            case "tip.pipe.buildcrafttransport.wood_fluid" -> "tip.pipe.buildcrafttransport.wood_item";
            case "tip.pipe.buildcrafttransport.wood_power" -> "tip.pipe.buildcrafttransport.diamond_wood_power";
            default -> key;
        };
    }
}
