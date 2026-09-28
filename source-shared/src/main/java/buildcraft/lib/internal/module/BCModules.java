package buildcraft.lib.internal.module;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.annotation.Nullable;

import buildcraft.lib.platform.runtime.PlatformRuntime;
import net.minecraft.resources.ResourceLocation;

/** Canonical BuildCraft module identities. Loader presence checks are delegated to {@link PlatformRuntime}. */
public enum BCModules implements IBuildCraftMod {
    LIB,
    CORE,
    BUILDERS,
    ENERGY,
    FACTORY,
    ROBOTICS,
    SILICON,
    TRANSPORT,
    COMPAT;

    public static final BCModules[] VALUES = values();
    private static volatile boolean hasChecked = false;
    private static BCModules[] loadedModules, missingModules;

    public final String lowerCaseName = name().toLowerCase(Locale.ROOT);
    public final String camelCaseName = name().charAt(0) + lowerCaseName.substring(1);
    private final String modId = "buildcraft" + lowerCaseName;
    private boolean loaded;

    private static void checkLoadStatus() {
        if (!hasChecked) {
            load0();
        }
    }

    /**
     * Installs an explicit loaded-set for unified-loader distributions (Fabric uses one mod container for all BC modules).
     * Must run before the module service is queried. Forge/NeoForge continue to discover separate module containers.
     */
    public static synchronized void installLoadedModules(Iterable<BCModules> modules) {
        if (hasChecked) {
            throw new IllegalStateException("BuildCraft module load status was already resolved");
        }
        java.util.EnumSet<BCModules> explicit = java.util.EnumSet.noneOf(BCModules.class);
        for (BCModules module : modules) {
            explicit.add(java.util.Objects.requireNonNull(module, "module"));
        }
        List<BCModules> found = new ArrayList<>();
        List<BCModules> missing = new ArrayList<>();
        for (BCModules module : VALUES) {
            module.loaded = explicit.contains(module);
            (module.loaded ? found : missing).add(module);
        }
        loadedModules = found.toArray(new BCModules[0]);
        missingModules = missing.toArray(new BCModules[0]);
        hasChecked = true;
    }

    private static synchronized void load0() {
        if (hasChecked) {
            return;
        }
        List<BCModules> found = new ArrayList<>();
        List<BCModules> missing = new ArrayList<>();
        for (BCModules module : VALUES) {
            module.loaded = PlatformRuntime.isModLoaded(module.modId);
            (module.loaded ? found : missing).add(module);
        }
        loadedModules = found.toArray(new BCModules[0]);
        missingModules = missing.toArray(new BCModules[0]);
        hasChecked = true;
    }

    @Nullable
    public static BCModules getBcMod(String testModId) {
        for (BCModules mod : VALUES) {
            if (mod.modId.equals(testModId)) {
                return mod;
            }
        }
        return null;
    }

    public static boolean isBcMod(String testModId) {
        return getBcMod(testModId) != null;
    }

    public static BCModules[] getLoadedModules() {
        checkLoadStatus();
        return loadedModules;
    }

    public static BCModules[] getMissingModules() {
        checkLoadStatus();
        return missingModules;
    }

    @Override
    public String getModId() {
        return modId;
    }

    public boolean isLoaded() {
        checkLoadStatus();
        return loaded;
    }

    public ResourceLocation createLocation(String path) {
        return Objects.requireNonNull(ResourceLocation.tryParse(modId + ":" + path));
    }
}
