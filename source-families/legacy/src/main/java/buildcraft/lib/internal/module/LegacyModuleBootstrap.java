package buildcraft.lib.internal.module;

import java.util.List;

import buildcraft.builders.LegacyBuildersModule;
import buildcraft.core.LegacyCoreModule;
import buildcraft.energy.LegacyEnergyModule;
import buildcraft.factory.LegacyFactoryModule;
import buildcraft.lib.LegacyLibModule;
import buildcraft.robotics.LegacyRoboticsModule;
import buildcraft.silicon.LegacySiliconModule;
import buildcraft.transport.LegacyTransportModule;

/**
 * Canonical legacy-family module bootstrap used when a loader exposes BuildCraft as one unified mod container.
 * Loader entrypoints provide only registry/config/network/lifecycle mechanics through {@link BCModuleBootstrapContext}.
 */
public final class LegacyModuleBootstrap {
    private static final List<BCModules> UNIFIED_MODULES = List.of(
        BCModules.LIB,
        BCModules.CORE,
        BCModules.BUILDERS,
        BCModules.ENERGY,
        BCModules.FACTORY,
        BCModules.ROBOTICS,
        BCModules.SILICON,
        BCModules.TRANSPORT
    );
    private static boolean bootstrapped;

    private LegacyModuleBootstrap() {
    }

    public static synchronized void bootstrap(BCModuleBootstrapContext context) {
        if (bootstrapped) {
            throw new IllegalStateException("Legacy BuildCraft modules were already bootstrapped");
        }
        bootstrapped = true;

        BCModules.installLoadedModules(UNIFIED_MODULES);

        // Registration is split from setup by the context. This lets every catalogue bind before any setup code
        // dereferences entries, matching Forge's mod-construction -> common-setup lifecycle on Fabric as well.
        LegacyLibModule.bootstrap(context);
        LegacyCoreModule.bootstrap(context);
        LegacyBuildersModule.bootstrap(context);
        LegacyEnergyModule.bootstrap(context);
        LegacyFactoryModule.bootstrap(context);
        LegacyTransportModule.bootstrap(context);
        LegacySiliconModule.bootstrap(context);
        LegacyRoboticsModule.bootstrap(context);
    }
}
