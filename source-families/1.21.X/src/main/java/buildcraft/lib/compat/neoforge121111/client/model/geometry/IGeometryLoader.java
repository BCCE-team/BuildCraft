//? source if >=1.21.11
package buildcraft.lib.compat.neoforge121111.client.model.geometry;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;

public interface IGeometryLoader<T> {
    T read(JsonObject jsonObject, JsonDeserializationContext deserializationContext);
}
