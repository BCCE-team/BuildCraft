package buildcraft.energy.client;

import buildcraft.energy.BCEnergyClientRenderers;
import buildcraft.energy.BCEnergyGuis;
import buildcraft.energy.BCEnergySprites;
import buildcraft.energy.client.gui.GuiDynamoMJ;
import buildcraft.energy.client.gui.GuiEngineFE;
import buildcraft.energy.client.gui.GuiEngineIron_BC8;
import buildcraft.energy.client.gui.GuiEngineStone_BC8;
import buildcraft.lib.platform.client.PlatformClientRegistration;

/** Native Fabric 26.3 Energy client bootstrap, never loaded on a dedicated server. */
public final class FabricEnergyClient {
    private static boolean bootstrapped;
    private FabricEnergyClient() {}

    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        bootstrapped = true;
        PlatformClientRegistration.screens().register(BCEnergyGuis.MENU_STONE.get(), GuiEngineStone_BC8::new);
        PlatformClientRegistration.screens().register(BCEnergyGuis.MENU_IRON.get(), GuiEngineIron_BC8::new);
        PlatformClientRegistration.screens().register(BCEnergyGuis.MENU_FE.get(), GuiEngineFE::new);
        PlatformClientRegistration.screens().register(BCEnergyGuis.MENU_DYNAMO_MJ.get(), GuiDynamoMJ::new);
        BCEnergyClientRenderers.register(PlatformClientRegistration.renderers());
        BCEnergySprites.init();
        FabricEnergyFluidRendering.bootstrap();
    }
}
