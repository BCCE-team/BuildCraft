/** Copyright (c) 2011-2015, SpaceToad and the BuildCraft Team
 * The BuildCraft API is distributed under the terms of the MIT License.
 */
package buildcraft.lib.internal.core;

import buildcraft.lib.BCLib;
import java.util.Objects;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

/**
 * Equivalent of NeoForge's active registration module context.
 *
 * Fabric has no implicit ModLoadingContext active container. Addons that use
 * unqualified names MUST supply their own mod namespace explicitly through
 * withModule. A missing context throws rather than silently registering an
 * addon recipe under buildcraftlib (and breaking saved registry IDs).
 */
public final class BuildCraftAPI {
    private static final ThreadLocal<String> ACTIVE_NAMESPACE = new ThreadLocal<>();

    private BuildCraftAPI() {}

    public static String getVersion() {
        return FabricLoader.getInstance().getModContainer(BCLib.MODID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("UNKNOWN VERSION");
    }

    public static <T> T withModule(String modId, Supplier<T> body) {
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(body, "body");
        if (!modId.matches("[a-z0-9_.-]+")) throw new IllegalArgumentException("Invalid modId " + modId);
        String previous = ACTIVE_NAMESPACE.get();
        ACTIVE_NAMESPACE.set(modId);
        try {
            return body.get();
        } finally {
            if (previous == null) ACTIVE_NAMESPACE.remove();
            else ACTIVE_NAMESPACE.set(previous);
        }
    }

    public static void withModule(String modId, Runnable work) {
        withModule(modId, () -> { work.run(); return null; });
    }

    public static Identifier nameToResourceLocation(String name) {
        Objects.requireNonNull(name, "name");
        if (name.indexOf(':') > 0) return Identifier.parse(name);
        String context = ACTIVE_NAMESPACE.get();
        if (context == null) {
            throw new IllegalStateException("Illegal unqualified registration name '" + name
                + "' on Fabric. Provide your addon namespace or call withModule.");
        }
        return Identifier.fromNamespaceAndPath(context, name);
    }
}
