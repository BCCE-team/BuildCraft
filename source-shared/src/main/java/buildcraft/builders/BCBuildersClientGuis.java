package buildcraft.builders;

import buildcraft.builders.gui.GuiArchitectTable;
import buildcraft.builders.gui.GuiBuilder;
import buildcraft.builders.gui.GuiElectronicLibrary;
import buildcraft.builders.gui.GuiElectronicLibraryLegacy;
import buildcraft.builders.menu.ContainerElectronicLibrary;
import buildcraft.lib.gui.GuiBC8;
import buildcraft.lib.client.gui.ClientGuiModePreferences;
import buildcraft.builders.gui.GuiFiller;
import buildcraft.builders.gui.GuiFillerPlanner;
import buildcraft.builders.gui.ScreenReplacer;
import buildcraft.lib.platform.client.ClientRegistration;

public final class BCBuildersClientGuis {
    private BCBuildersClientGuis() {
    }

    public static void clientInit(ClientRegistration.Screens event) {
        event.register(BCBuildersGuis.MENU_ARCHITECT_TABLE.get(), GuiArchitectTable::new);
        event.register(BCBuildersGuis.MENU_BUILDER.get(), GuiBuilder::new);
        event.<ContainerElectronicLibrary, GuiBC8<ContainerElectronicLibrary>>register(
            BCBuildersGuis.MENU_ELIBRARY.get(), (menu, inventory, title) ->
            ClientGuiModePreferences.get(ClientGuiModePreferences.ELECTRONIC_LIBRARY)
                == ClientGuiModePreferences.Mode.LEGACY
                ? new GuiElectronicLibraryLegacy(menu, inventory, title)
                : new GuiElectronicLibrary(menu, inventory, title));
        event.register(BCBuildersGuis.MENU_FILLER.get(), GuiFiller::new);
        event.register(BCBuildersGuis.MENU_FILLER_PLANNER.get(), GuiFillerPlanner::new);
        event.register(BCBuildersGuis.MENU_REPLACER.get(), ScreenReplacer::new);
    }
}
