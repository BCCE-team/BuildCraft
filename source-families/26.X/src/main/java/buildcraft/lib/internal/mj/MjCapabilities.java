package buildcraft.lib.internal.mj;

import javax.annotation.Nonnull;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.capabilities.BlockCapability;

/** Loader-specific MJ capability tokens kept strictly inside BCCE Lib. */
public final class MjCapabilities {
    private MjCapabilities() {}

    @Nonnull public static final BlockCapability<IMjConnector, Direction> CAP_CONNECTOR =
        BlockCapability.createSided(id("mj_connector"), IMjConnector.class);
    @Nonnull public static final BlockCapability<IMjReceiver, Direction> CAP_RECEIVER =
        BlockCapability.createSided(id("mj_receiver"), IMjReceiver.class);
    @Nonnull public static final BlockCapability<IMjRedstoneReceiver, Direction> CAP_REDSTONE_RECEIVER =
        BlockCapability.createSided(id("mj_redstone_receiver"), IMjRedstoneReceiver.class);
    @Nonnull public static final BlockCapability<IMjReadable, Direction> CAP_READABLE =
        BlockCapability.createSided(id("mj_readable"), IMjReadable.class);
    @Nonnull public static final BlockCapability<IMjPassiveProvider, Direction> CAP_PASSIVE_PROVIDER =
        BlockCapability.createSided(id("mj_passive_provider"), IMjPassiveProvider.class);

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("buildcraftlib", path);
    }
}
