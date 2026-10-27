package buildcraft.builders;

import buildcraft.lib.balance.BalanceConfig;

/** Quarry gameplay parameters are exclusively controlled by config/buildcraft-machines.ini. */
public final class BCBuildersConfig {
    private static final String QUARRY = "buildcraftbuilders:quarry";
    public static int quarryFrameMinHeight = 4;
    public static int quarryMaxTasksPerTick = 4;
    public static int quarryTaskPowerDivisor = 2;
    public static double quarryMaxFrameMoveSpeed;
    public static double quarryMaxBlockMineRate;

    private BCBuildersConfig() {}

    public static void preInit() { reloadConfig(BCBuilders.MODID); }
    public static void onReloadConfig(String modId) { reloadConfig(modId); }
    public static void onLoadConfig(String modId) { reloadConfig(modId); }

    public static void reloadConfig(String modId) {
        if (!BCBuilders.MODID.equals(modId)) return;
        quarryFrameMinHeight = BalanceConfig.machineInt(QUARRY, "frame_min_height", 4);
        quarryMaxTasksPerTick = BalanceConfig.machineInt(QUARRY, "max_tasks_per_tick", 4);
        quarryTaskPowerDivisor = BalanceConfig.machineInt(QUARRY, "power_divisor", 2);
        quarryMaxFrameMoveSpeed = BalanceConfig.machine(QUARRY, "max_frame_speed", 0);
        quarryMaxBlockMineRate = BalanceConfig.machine(QUARRY, "max_mine_rate", 0);
    }
}
