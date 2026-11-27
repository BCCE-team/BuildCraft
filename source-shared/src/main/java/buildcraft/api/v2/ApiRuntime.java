package buildcraft.api.v2;

import buildcraft.api.v2.registry.ApiRegistry;
import buildcraft.api.v2.registry.RegistryKey;
import buildcraft.api.v2.service.ServiceKey;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * Runtime facade. Production runtime lookup, lifecycle and feature queries may be made from any thread.
 * This does not make the returned services, registry values, or Minecraft objects thread-safe.
 * See the package threading contract and individual domain contracts before invoking them.
 */
public interface ApiRuntime {
    ApiRuntime EMPTY = new ApiRuntime() {
        @Override public <T> Optional<ApiRegistry<T>> registry(ResourceLocation id) { return Optional.empty(); }
        @Override public <T> Optional<T> service(ServiceKey<T> key) { return Optional.empty(); }
        @Override public ApiVersion version() { return new ApiVersion(0, 0, 0); }
        @Override public ApiFeatureSet features() { return ApiFeatureSet.EMPTY; }
        @Override public ApiLifecycle lifecycle() { return ApiLifecycle.DISCOVERY; }
    };

    static ApiRuntime empty() { return EMPTY; }

    <T> Optional<ApiRegistry<T>> registry(ResourceLocation id);
    <T> Optional<T> service(ServiceKey<T> key);

    default <T> Optional<ApiRegistry<T>> registry(RegistryKey<T> key) {
        return registry(key.id());
    }

    default <T> ApiRegistry<T> requireRegistry(RegistryKey<T> key) {
        return registry(key).orElseThrow(() -> new IllegalStateException("BuildCraft API registry is unavailable: " + key.id()));
    }

    default <T> T requireService(ServiceKey<T> key) {
        return service(key).orElseThrow(() -> new IllegalStateException("BuildCraft API service is unavailable: " + key.id()));
    }

    default ApiVersion version() { return BuildCraftApi.VERSION; }
    /** Immutable capability snapshot. During bootstrap reacquire it after modules install services.
     * After freeze, installed service/registry availability is stable for the process. */
    default ApiFeatureSet features() { return ApiFeatureSet.EMPTY; }
    default ApiLifecycle lifecycle() { return ApiLifecycle.DISCOVERY; }
}
