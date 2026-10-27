import buildcraft.lib.balance.BalanceConfig;
import buildcraft.lib.balance.BalanceIni;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runs in a separate JVM for each mode combination so BalanceConfig initializes once. */
public class BalanceConfigRuntimeTester {
    public static void main(String[] args) throws Exception {
        boolean machinesEnabled = Boolean.parseBoolean(args[0]);
        boolean robotsEnabled = Boolean.parseBoolean(args[1]);
        Path directory = Path.of("config");
        new BalanceIni(directory).load();
        Path machines = directory.resolve("buildcraft-machines.ini");
        Path robots = directory.resolve("buildcraft-robots.ini");
        Files.writeString(machines, Files.readString(machines)
            .replace("use_config = false", "use_config = " + machinesEnabled)
            .replace("energy_per_bucket_mj = 10", "energy_per_bucket_mj = 55"));
        Files.writeString(robots, Files.readString(robots)
            .replace("use_config = false", "use_config = " + robotsEnabled)
            .replaceFirst("movement_speed_multiplier = 1.0", "movement_speed_multiplier = 3.0"));
        int pump = BalanceConfig.machineInt("buildcraftfactory:pump", "energy_per_bucket_mj", 10);
        double picker = BalanceConfig.robot("buildcraft:boardRobotPicker", "movement_speed_multiplier", 1);
        if (pump != (machinesEnabled ? 55 : 10) || picker != (robotsEnabled ? 3 : 1)) {
            throw new AssertionError("Wrong independent mode: pump=" + pump + ", picker=" + picker);
        }
        System.out.println("PASS machine=" + machinesEnabled + " robot=" + robotsEnabled);
    }
}
