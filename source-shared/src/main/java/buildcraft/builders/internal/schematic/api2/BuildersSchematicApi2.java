package buildcraft.builders.internal.schematic.api2;

import buildcraft.api.v2.BuildCraftServices;
import buildcraft.lib.internal.api.v2.BuildCraftApiRuntime;

/** Installs the Builder-owned schematic runtime before API2 registry freeze. */
public final class BuildersSchematicApi2 {
    private static boolean bootstrapped;

    private BuildersSchematicApi2() {}

    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        BuildCraftApiRuntime.INSTANCE.installService(BuildCraftServices.SCHEMATICS, SchematicServiceImpl.INSTANCE);
        bootstrapped = true;
    }
}
