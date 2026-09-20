package buildcraft.builders;

import buildcraft.builders.gui.GuiArchitectTable;
import buildcraft.builders.gui.GuiBuilder;
import buildcraft.builders.gui.GuiElectronicLibrary;
import buildcraft.builders.gui.GuiFiller;
import buildcraft.builders.gui.ScreenReplacer;
import buildcraft.lib.platform.client.ClientRegistration;

public final class BCBuildersClientGuis {
    private BCBuildersClientGuis() {
    }

    public static void clientInit(ClientRegistration.Screens event) {
        event.register(BCBuildersGuis.MENU_ARCHITECT_TABLE.get(), GuiArchitectTable::new);
        event.register(BCBuildersGuis.MENU_BUILDER.get(), GuiBuilder::new);
        event.register(BCBuildersGuis.MENU_ELIBRARY.get(), GuiElectronicLibrary::new);
        event.register(BCBuildersGuis.MENU_FILLER.get(), GuiFiller::new);
        event.register(BCBuildersGuis.MENU_REPLACER.get(), ScreenReplacer::new);
    }
}
