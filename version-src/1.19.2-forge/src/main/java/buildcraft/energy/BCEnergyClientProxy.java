/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
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
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.TextureStitchEvent.Pre;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = BCEnergy.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public abstract class BCEnergyClientProxy {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            registerLiquidRenderLayers();
            PlatformClientRegistration.directScreens().register(BCEnergyGuis.MENU_STONE.get(), GuiEngineStone_BC8::new);
            PlatformClientRegistration.directScreens().register(BCEnergyGuis.MENU_IRON.get(), GuiEngineIron_BC8::new);
            PlatformClientRegistration.directScreens().register(BCEnergyGuis.MENU_FE.get(), GuiEngineFE::new);
            PlatformClientRegistration.directScreens().register(BCEnergyGuis.MENU_DYNAMO_MJ.get(), GuiDynamoMJ::new);
        });
    }

    /** Use the vanilla water transparency pass so boat water masks also hide BC liquids. */
    private static void registerLiquidRenderLayers() {
        int count = Math.min(BCEnergyFluids.OIL_SOURCE.size(), BCEnergyFluids.OIL_TYPE.size());
        for (int i = 0; i < count; i++) {
            if (BCEnergyFluids.OIL_TYPE.get(i).get().getDensity() <= 0) continue;
            var source = BCEnergyFluids.OIL_SOURCE.get(i).get();
            ItemBlockRenderTypes.setRenderLayer(source, RenderType.translucent());
            ItemBlockRenderTypes.setRenderLayer(source.getFlowing(), RenderType.translucent());
        }
        // The world-generation spout is a separate fluid registration using cool crude oil.
        if (BCEnergyFluids.SPOUT_OIL_SOURCE != null && BCEnergyFluids.SPOUT_OIL_FLOWING != null) {
            ItemBlockRenderTypes.setRenderLayer(BCEnergyFluids.SPOUT_OIL_SOURCE.get(), RenderType.translucent());
            ItemBlockRenderTypes.setRenderLayer(BCEnergyFluids.SPOUT_OIL_FLOWING.get(), RenderType.translucent());
        }
    }

    @SubscribeEvent
    public static void registryRender(EntityRenderersEvent.RegisterRenderers event) {
        BCEnergyClientRenderers.register(PlatformClientRegistration.renderers(event));
    }

    @SubscribeEvent
    public static void registrtTexture(Pre event) {
        BCEnergySprites.onTextureStitchPre(PlatformClientRegistration.atlas(event));
    }
}
