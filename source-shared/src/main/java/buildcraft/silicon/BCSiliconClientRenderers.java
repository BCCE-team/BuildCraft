package buildcraft.silicon;

import buildcraft.lib.platform.client.ClientRegistration;
import buildcraft.silicon.client.render.RenderLaser;
import buildcraft.silicon.client.render.RenderProgrammingTable;

/** Descriptor catalogue; no loader event or render implementation is duplicated here. */
public final class BCSiliconClientRenderers {
    private BCSiliconClientRenderers() {}
    public static void register(ClientRegistration.Renderers registry) {
        registry.registerBlockEntityRenderer(BCSiliconBlocks.LASER_TILE.get(), RenderLaser::new);
        registry.registerBlockEntityRenderer(BCSiliconBlocks.PROGRAMMING_TABLE_TILE.get(), RenderProgrammingTable::new);
    }
    public static void layers(ClientRegistration.Layers registry) {
        registry.set(BCSiliconBlocks.ASSEMBLY_TABLE_BLOCK.get(), ClientRegistration.BlockLayer.CUTOUT);
        registry.set(BCSiliconBlocks.ADVANCED_CRAFTING_TABLE_BLOCK.get(), ClientRegistration.BlockLayer.CUTOUT);
        registry.set(BCSiliconBlocks.INTERGRATION_TABLE_BLOCK.get(), ClientRegistration.BlockLayer.CUTOUT);
        registry.set(BCSiliconBlocks.CHARGING_TABLE_BLOCK.get(), ClientRegistration.BlockLayer.CUTOUT);
        registry.set(BCSiliconBlocks.PROGRAMMING_TABLE_BLOCK.get(), ClientRegistration.BlockLayer.TRANSLUCENT);
    }
}
