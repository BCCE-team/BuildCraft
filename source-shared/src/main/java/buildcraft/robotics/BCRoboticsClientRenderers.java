package buildcraft.robotics;

import buildcraft.lib.platform.client.ClientRegistration;
import buildcraft.robotics.client.render.RenderRobot;
import buildcraft.robotics.client.render.RenderZonePlanner;

/** Descriptor catalogue; no loader event or render implementation is duplicated here. */
public final class BCRoboticsClientRenderers {
    private BCRoboticsClientRenderers() {}
    public static void register(ClientRegistration.Renderers registry) {
        registry.registerEntityRenderer(BCRoboticsEntities.ROBOT.get(), RenderRobot::new);
        registry.registerBlockEntityRenderer(BCRoboticsBlocks.ZONE_PLANNER_TILE.get(), RenderZonePlanner::new);
    }
}
