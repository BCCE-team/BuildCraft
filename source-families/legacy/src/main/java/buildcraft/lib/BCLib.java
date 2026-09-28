package buildcraft.lib;

import buildcraft.lib.net.BuildCraftTarget;

/** Loader-neutral legacy BCLib facade used by unified-loader targets. */
public final class BCLib {
    public static final String MODID = LegacyLibModule.MODID;
    public static final String VERSION = BuildCraftTarget.MOD_VERSION;
    public static final String MC_VERSION = BuildCraftTarget.MINECRAFT_VERSION;
    public static final String GIT_BRANCH = BuildCraftTarget.GIT_BRANCH;
    public static final String GIT_COMMIT_HASH = BuildCraftTarget.GIT_COMMIT_HASH;
    public static final String GIT_COMMIT_MSG = BuildCraftTarget.GIT_COMMIT_MESSAGE;
    public static final String GIT_COMMIT_AUTHOR = BuildCraftTarget.GIT_COMMIT_AUTHOR;
    public static final boolean DEV = Boolean.getBoolean("buildcraft.dev");

    private BCLib() {
    }

    public static Error throwBadClass(Error error, Class<?> cls) throws Error {
        throw new Error(
            "Bad " + cls + " loaded from " + cls.getClassLoader() + " domain: " + cls.getProtectionDomain(), error
        );
    }
}
