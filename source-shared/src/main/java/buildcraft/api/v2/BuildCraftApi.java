package buildcraft.api.v2;

import buildcraft.api.v2.registry.ApiRegistry;
import buildcraft.api.v2.registry.RegistryKey;
import buildcraft.api.v2.service.ServiceKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Main entry point for BuildCraft Extension API 2.
 *
 * <p>Addons depend only on this facade and the contracts under {@code buildcraft.api.v2}.
 * The concrete runtime is a BuildCraft Lib implementation discovered as an
 * {@link ApiRuntime} service provider; the public API contains no implementation-only SPI.
 */
public final class BuildCraftApi {
    public static final ApiVersion VERSION = new ApiVersion(2, 1, 0);

    private BuildCraftApi() {
    }

    public static ApiRuntime runtime() {
        return RuntimeHolder.RUNTIME;
    }

    public static <T> ApiRegistry<T> registry(RegistryKey<T> key) {
        return runtime().requireRegistry(Objects.requireNonNull(key, "key"));
    }

    public static <T> T service(ServiceKey<T> key) {
        return runtime().requireService(Objects.requireNonNull(key, "key"));
    }

    private static ApiRuntime discoverRuntime() {
        List<ApiRuntime> runtimes = new ArrayList<>();
        for (ApiRuntime runtime : ServiceLoader.load(ApiRuntime.class, BuildCraftApi.class.getClassLoader())) {
            runtimes.add(runtime);
        }
        if (runtimes.isEmpty()) {
            return ApiRuntime.empty();
        }
        if (runtimes.size() != 1) {
            throw new IllegalStateException("Expected exactly one BuildCraft API runtime, found " + runtimes.size());
        }
        ApiRuntime runtime = Objects.requireNonNull(runtimes.get(0), "API runtime provider returned null");
        if (runtime.version().major() != VERSION.major()) {
            throw new IllegalStateException(
                "BuildCraft API runtime major version mismatch: facade=" + VERSION + ", runtime=" + runtime.version()
            );
        }
        return runtime;
    }

    private static final class RuntimeHolder {
        private static final ApiRuntime RUNTIME = discoverRuntime();
    }
}
