package buildcraft.lib.misc.collect;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

/** A {@link TypedMap} instance that only maps classes directly to the classes - that is {@link #get(Class)} will return
 * either null, or an object whose {@link #getClass()} equals the argument class. */
public class TypedMapDirect<V> implements TypedMap<V> {

    private final Map<Class<?>, V> internalMap = new HashMap<>();

    @Nullable
    public <T extends V> T get(Class<T> clazz) {
        T val = clazz.cast(internalMap.get(clazz));
        if (val != null) {
            return val;
        }
        return null;
    }

    public void put(V value) {
        internalMap.put(value.getClass(), value);
    }

    public void clear() {
        internalMap.clear();
    }

    public void remove(V value) {
        internalMap.remove(value.getClass(), value);
    }
}
