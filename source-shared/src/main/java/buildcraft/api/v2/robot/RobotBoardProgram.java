package buildcraft.api.v2.robot;

import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;

/** Connects a registered board type to the programming table and the persistent robot task executor. */
public record RobotBoardProgram(
    ResourceLocation id,
    int programmingCostMj,
    String color,
    ResourceLocation robotTexture,
    Function<RobotHandle, ? extends RobotTask> taskFactory
) {
    private static final Set<String> COLORS = Set.of("clean", "green", "blue", "red", "yellow");

    public RobotBoardProgram {
        Objects.requireNonNull(id, "id");
        if (programmingCostMj <= 0) {
            throw new IllegalArgumentException("Programming cost must be positive");
        }
        Objects.requireNonNull(color, "color");
        if (!COLORS.contains(color)) throw new IllegalArgumentException("Unknown board color: " + color);
        Objects.requireNonNull(robotTexture, "robotTexture");
        Objects.requireNonNull(taskFactory, "taskFactory");
    }
}
