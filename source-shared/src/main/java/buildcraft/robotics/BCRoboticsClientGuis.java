package buildcraft.robotics;

import buildcraft.robotics.gui.GuiRequester;
import buildcraft.robotics.gui.GuiRobotProcessor;
import buildcraft.robotics.gui.GuiZonePlanner;
import buildcraft.robotics.gui.GuiZonePlannerLegacy;
import buildcraft.robotics.container.ContainerZonePlanner;
import buildcraft.lib.gui.GuiBC8;
import buildcraft.lib.client.gui.ClientGuiModePreferences;
import buildcraft.lib.platform.client.ClientRegistration;

public final class BCRoboticsClientGuis {
    private BCRoboticsClientGuis() {
    }

    public static void clientInit(ClientRegistration.Screens event) {
        event.<ContainerZonePlanner, GuiBC8<ContainerZonePlanner>>register(
            BCRoboticsGuis.MENU_ZONE_PLANNER.get(), (menu, inventory, title) ->
            ClientGuiModePreferences.get(ClientGuiModePreferences.ZONE_PLANNER)
                == ClientGuiModePreferences.Mode.LEGACY
                ? new GuiZonePlannerLegacy(menu, inventory, title)
                : new GuiZonePlanner<>(menu, inventory, title));
        event.register(BCRoboticsGuis.MENU_REQUESTER.get(), GuiRequester::new);
        event.register(BCRoboticsGuis.MENU_ROBOT_PROCESSOR.get(), GuiRobotProcessor::new);
    }
}
