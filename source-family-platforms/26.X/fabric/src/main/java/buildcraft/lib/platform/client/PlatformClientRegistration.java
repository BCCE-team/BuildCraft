package buildcraft.lib.platform.client;

import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.minecraft.client.color.item.ItemTintSources;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperties;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import java.util.List;

/**
 * Fabric 26.3 registration backend for BCCE's loader-neutral client catalogue.
 *
 * All methods are client-only. Call them only from Fabric's ClientModInitializer
 * before resource/model loading; no NeoForge event or client class enters the
 * shared BCCE registration contracts.
 */
public final class PlatformClientRegistration {
    private PlatformClientRegistration() {}

    /** Native Fabric initializer already runs at registration time. */
    public static ClientRegistration.Setup setup() {
        return Runnable::run;
    }

    public static ClientRegistration.Renderers renderers() {
        return new ClientRegistration.Renderers() {
            public <T extends BlockEntity, S extends BlockEntityRenderState> void registerBlockEntityRenderer(
                BlockEntityType<? extends T> type, BlockEntityRendererProvider<T, S> factory
            ) {
                BlockEntityRenderers.register(type, factory);
            }

            public <T extends Entity> void registerEntityRenderer(
                EntityType<? extends T> type, EntityRendererProvider<T> factory
            ) {
                EntityRenderers.register(type, factory);
            }
        };
    }

    public static ClientRegistration.Screens screens() {
        return new ClientRegistration.Screens() {
            public <M extends AbstractContainerMenu, S extends Screen & MenuAccess<M>> void register(
                MenuType<? extends M> type, ClientRegistration.ScreenFactory<M, S> factory
            ) {
                MenuScreens.register(type, factory::create);
            }
        };
    }

    /** Minecraft 26.3 block coloring uses one BlockTintSource per tint index. */
    public static ClientRegistration.BlockColours blockColours() {
        return (colour, blocks) -> BlockColorRegistry.register(List.of(colour), blocks);
    }

    // Minecraft 26.3 derives CUTOUT/TRANSLUCENT from the baked model's
    // render-type metadata. There is deliberately no legacy layers() setter:
    // on 26.3 NeoForge this helper is also absent, and faking a no-op would
    // conceal incorrect renderer registration on older paths.

    public static ClientModelProperties.Ranges rangeProperties() {
        return RangeSelectItemModelProperties.ID_MAPPER::put;
    }

    public static ClientModelProperties.Tints tintSources() {
        return ItemTintSources.ID_MAPPER::put;
    }

    /**
     * Fabric supplies atlas state at a reload boundary rather than the
     * NeoForge TextureAtlasStitchedEvent. Preserve the BCCE After contract.
     */
    public static ClientAtlas.After atlas(TextureAtlas atlas) {
        return new ClientAtlas.After(atlas);
    }
}
