package dev.bcce.apifixture;

import buildcraft.api.v2.content.ContentRegistrar;
import buildcraft.api.v2.robot.RobotBoardProgram;
import buildcraft.api.v2.robot.RobotBoardType;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** Standalone addon board registered exclusively through the public API. */
public final class RobotBoardFixtureAddon {
    public static final ResourceLocation BOARD_ID = ResourceLocation.tryParse("api_fixture:inspection_board");
    public static final RobotBoardType TYPE = new RobotBoardType(
        BOARD_ID, 2, Set.of(RobotTaskConformanceAddon.ID)
    );
    public static final RobotBoardProgram PROGRAM = new RobotBoardProgram(
        BOARD_ID, 3_200, "blue",
        ResourceLocation.tryParse("api_fixture:textures/entity/inspection_robot.png"),
        robot -> new RobotTaskConformanceAddon(0)
    );

    private RobotBoardFixtureAddon() {}

    public static void register(ContentRegistrar registrar) {
        registrar.robotTask(RobotTaskConformanceAddon.TYPE);
        registrar.robotBoard(TYPE);
        registrar.robotBoardProgram(PROGRAM);
    }
}
