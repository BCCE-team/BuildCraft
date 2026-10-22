package buildcraft.api.v2.reload;

import java.util.Map;

/** Converts loader-neutral structured data to an addon-owned immutable definition. */
@FunctionalInterface
public interface DefinitionDecoder<T> {
    T decode(Map<String, Object> data) throws Exception;
}
