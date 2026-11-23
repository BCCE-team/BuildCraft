package buildcraft.api.v2.robot;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Stable identifiers for the built-in BuildCraft robot boards. */
public final class BuildCraftRobotBoards {
    public static final ResourceLocation EMPTY = id("empty");
    public static final ResourceLocation PICKER = id("picker");
    public static final ResourceLocation CARRIER = id("carrier");
    public static final ResourceLocation FLUID_CARRIER = id("fluid_carrier");
    public static final ResourceLocation LUMBERJACK = id("lumberjack");
    public static final ResourceLocation HARVESTER = id("harvester");
    public static final ResourceLocation MINER = id("miner");
    public static final ResourceLocation PLANTER = id("planter");
    public static final ResourceLocation FARMER = id("farmer");
    public static final ResourceLocation LEAVE_CUTTER = id("leave_cutter");
    public static final ResourceLocation BUTCHER = id("butcher");
    public static final ResourceLocation SHEPHERD = id("shepherd");
    public static final ResourceLocation FISHER = id("fisher");
    public static final ResourceLocation BEEKEEPER = id("beekeeper");
    public static final ResourceLocation SHOVELMAN = id("shovelman");
    public static final ResourceLocation PUMP = id("pump");
    public static final ResourceLocation DELIVERY = id("delivery");
    public static final ResourceLocation KNIGHT = id("knight");
    public static final ResourceLocation BOMBER = id("bomber");
    public static final ResourceLocation STRIPES = id("stripes");
    public static final ResourceLocation BUILDER = id("builder");

    private BuildCraftRobotBoards() {}

    public static ResourceLocation id(String key) {
        return Objects.requireNonNull(ResourceLocation.tryParse("buildcraft:robot_board/" + key));
    }
}
