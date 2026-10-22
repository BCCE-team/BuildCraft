package buildcraft.lib.compat.neoforge2612.client.model.geometry;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;

public interface IGeometryLoader<T> {
    T read(JsonObject jsonObject, JsonDeserializationContext deserializationContext);
}
