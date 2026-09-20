package buildcraft.builders;

import buildcraft.lib.platform.client.ClientRegistration;
import buildcraft.builders.client.render.RenderArchitectTable;
import buildcraft.builders.client.render.RenderBuilder;
import buildcraft.builders.client.render.RenderConstructionMarker;
import buildcraft.builders.client.render.RenderFiller;
import buildcraft.builders.client.render.RenderQuarry;

/** Descriptor catalogue; no loader event or render implementation is duplicated here. */
public final class BCBuildersClientRenderers {
    private BCBuildersClientRenderers() {}
    public static void register(ClientRegistration.Renderers registry) {
        registry.registerBlockEntityRenderer(BCBuildersBlocks.QUARRY_TILE_BC8.get(), RenderQuarry::new);
        registry.registerBlockEntityRenderer(BCBuildersBlocks.ARCHITECT_TILE_BC8.get(), RenderArchitectTable::new);
        registry.registerBlockEntityRenderer(BCBuildersBlocks.FILLER_TILE_BC8.get(), RenderFiller::new);
        registry.registerBlockEntityRenderer(BCBuildersBlocks.BUILDER_TILE_BC8.get(), RenderBuilder::new);
        registry.registerBlockEntityRenderer(BCBuildersBlocks.CONSTRUCTION_MARKER_TILE_BC8.get(), RenderConstructionMarker::new);
    }
}
