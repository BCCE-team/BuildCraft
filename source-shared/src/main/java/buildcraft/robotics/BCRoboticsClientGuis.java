package buildcraft.robotics;

import buildcraft.robotics.gui.GuiRequester;
import buildcraft.robotics.gui.GuiZonePlanner;
import buildcraft.lib.platform.client.ClientRegistration;

public final class BCRoboticsClientGuis {
    private BCRoboticsClientGuis() {
    }

    public static void clientInit(ClientRegistration.Screens event) {
        event.register(BCRoboticsGuis.MENU_ZONE_PLANNER.get(), GuiZonePlanner::new);
        event.register(BCRoboticsGuis.MENU_REQUESTER.get(), GuiRequester::new);
    }
}
