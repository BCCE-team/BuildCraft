/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.energy;

import buildcraft.lib.platform.client.PlatformClientRegistration;
import buildcraft.energy.BCEnergyClientRenderers;
import buildcraft.core.client.render.RenderEngine_BC8;
import buildcraft.energy.client.gui.GuiDynamoMJ;
import buildcraft.energy.client.gui.GuiEngineFE;
import buildcraft.energy.client.gui.GuiEngineIron_BC8;
import buildcraft.energy.client.gui.GuiEngineStone_BC8;
import buildcraft.energy.client.render.RenderDynamoMJ;
import buildcraft.energy.fluid.BCFluidType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
//? if >=1.21.9 {
import org.joml.Vector4f;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
//?}

@EventBusSubscriber(modid = BCEnergy.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public abstract class BCEnergyClientProxy {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        BCEnergySprites.init();

    }


    //? if >=1.21.9 {
    @SubscribeEvent
    public static void registerFluidClientExtensions(RegisterClientExtensionsEvent event) {
        for (var holder : BCEnergyFluids.OIL_TYPE) {
            BCFluidType type = holder.get();
            event.registerFluidType(new IClientFluidTypeExtensions() {
                private static final ResourceLocation UNDERWATER_LOCATION =
                    ResourceLocation.withDefaultNamespace("textures/misc/underwater.png");

                @Override
                public ResourceLocation getStillTexture() {
                    return type.getStillTextureLocation();
                }

                @Override
                public ResourceLocation getFlowingTexture() {
                    return type.getFlowTextureLocation();
                }

                @Override
                public ResourceLocation getOverlayTexture() {
                    // Keep block-side rendering identical to 1.21.1: BuildCraft fluids use their own
                    // still/flow textures instead of borrowing the vanilla water overlay.
                    return null;
                }

                @Override
                public ResourceLocation getRenderOverlayTexture(Minecraft mc) {
                    return UNDERWATER_LOCATION;
                }

                @Override
                public int getTintColor() {
                    return type.getFluidTintColor();
                }

                @Override
                public Vector4f modifyFogColor(Camera camera, float partialTick, ClientLevel level,
                        int renderDistance, float darkenWorldAmount, Vector4f fluidFogColor) {
                    // 1.21.1 deliberately uses a neutral grey immersion fog for every BuildCraft
                    // oil/fuel variant. Preserve the incoming alpha used by the modern fog pipeline.
                    return new Vector4f(0.5f, 0.5f, 0.5f, fluidFogColor.w);
                }
            }, type);
        }
    }
    //?}

    @SubscribeEvent
    public static void registerMenuScreens(RegisterMenuScreensEvent event) {
        PlatformClientRegistration.screens(event).register(BCEnergyGuis.MENU_STONE.get(), GuiEngineStone_BC8::new);
        PlatformClientRegistration.screens(event).register(BCEnergyGuis.MENU_IRON.get(), GuiEngineIron_BC8::new);
        PlatformClientRegistration.screens(event).register(BCEnergyGuis.MENU_FE.get(), GuiEngineFE::new);
        PlatformClientRegistration.screens(event).register(BCEnergyGuis.MENU_DYNAMO_MJ.get(), GuiDynamoMJ::new);
    }

    @SubscribeEvent
    public static void registryRender(EntityRenderersEvent.RegisterRenderers event) {
        BCEnergyClientRenderers.register(PlatformClientRegistration.renderers(event));
    }
}
