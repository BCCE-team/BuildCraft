package buildcraft.silicon;

import buildcraft.silicon.gui.GuiAdvancedCraftingTable;
import buildcraft.silicon.gui.GuiAssemblyTable;
import buildcraft.silicon.gui.GuiChargingTable;
import buildcraft.silicon.gui.GuiGate;
import buildcraft.silicon.gui.GuiIntegrationTable;
import buildcraft.silicon.gui.GuiProgrammingTable;
import buildcraft.lib.platform.client.ClientRegistration;

public final class BCSiliconClientGuis {
    private BCSiliconClientGuis() {
    }

    public static void clientInit(ClientRegistration.Screens event) {
        event.register(BCSiliconGuis.MENU_AD_CRAFTING_TABLE.get(), GuiAdvancedCraftingTable::new);
        event.register(BCSiliconGuis.MENU_ASSEMBLY_TABLE.get(), GuiAssemblyTable::new);
        event.register(BCSiliconGuis.MENU_CHARGING_TABLE.get(), GuiChargingTable::new);
        event.register(BCSiliconGuis.MENU_GATE.get(), GuiGate::new);
        event.register(BCSiliconGuis.MENU_INTEGRATION_TABLE.get(), GuiIntegrationTable::new);
        event.register(BCSiliconGuis.MENU_PROGRAMMING_TABLE.get(), GuiProgrammingTable::new);
    }
}
