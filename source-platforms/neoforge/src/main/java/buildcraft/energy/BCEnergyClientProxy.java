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
//? if mc_26_x {
import buildcraft.lib.fluid.BCFluid;
//? } else {
//?}
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
//? if mc_26_x {
import net.neoforged.neoforge.client.event.RegisterFluidModelsEvent;
//? } else {
//?}
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
//? if mc_26_x {
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.resources.model.sprite.Material;
//? } else {
//?}
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
//? if mc_26_x {
import net.neoforged.neoforge.client.fluid.FluidTintSources;
//? } else {
//?}
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
//?}

@EventBusSubscriber(modid = BCEnergy.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public abstract class BCEnergyClientProxy {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        BCEnergySprites.init();

    }


    //? if >=1.21.9 {
//? if mc_26_x {
    @SubscribeEvent
    public static void registerFluidModels(RegisterFluidModelsEvent event) {
        int count = Math.min(BCEnergyFluids.OIL_TYPE.size(), BCEnergyFluids.OIL_SOURCE.size());
        for (int i = 0; i < count; i++) {
            BCFluidType type = BCEnergyFluids.OIL_TYPE.get(i).get();
            BCFluid source = BCEnergyFluids.OIL_SOURCE.get(i).get();
            FluidModel.Unbaked model = new FluidModel.Unbaked(
                new Material(type.getStillTextureLocation()),
                new Material(type.getFlowTextureLocation()),
                null,
                FluidTintSources.constant(type.getFluidTintColor())
            );
            event.register(model, source, source.getFlowing());
        }

        if (BCEnergyFluids.SPOUT_OIL_SOURCE != null && BCEnergyFluids.SPOUT_OIL_FLOWING != null
            && !BCEnergyFluids.OIL_TYPE.isEmpty()) {
            BCFluidType type = BCEnergyFluids.OIL_TYPE.get(0).get();
            FluidModel.Unbaked model = new FluidModel.Unbaked(
                new Material(type.getStillTextureLocation()),
                new Material(type.getFlowTextureLocation()),
                null,
                FluidTintSources.constant(type.getFluidTintColor())
            );
            event.register(model, BCEnergyFluids.SPOUT_OIL_SOURCE.get(), BCEnergyFluids.SPOUT_OIL_FLOWING.get());
        }
    }

//? } else {
//?}
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
//? if mc_26_x {
                    // Preserve BuildCraft fluid block rendering: these fluids use their own
//? } else {
                    // Keep block-side rendering identical to 1.21.1: BuildCraft fluids use their own
//?}
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
//? if mc_26_x {
                public void modifyFogColor(Camera camera, float partialTick, ClientLevel level,
//? } else {
                public Vector4f modifyFogColor(Camera camera, float partialTick, ClientLevel level,
//?}
                        int renderDistance, float darkenWorldAmount, Vector4f fluidFogColor) {
//? if mc_26_x {
                    // BuildCraft deliberately uses a neutral grey immersion fog for every
//? } else {
                    // 1.21.1 deliberately uses a neutral grey immersion fog for every BuildCraft
//?}
                    // oil/fuel variant. Preserve the incoming alpha used by the modern fog pipeline.
//? if mc_26_x {
                    fluidFogColor.set(0.5f, 0.5f, 0.5f, fluidFogColor.w);
//? } else {
                    return new Vector4f(0.5f, 0.5f, 0.5f, fluidFogColor.w);
//?}
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
