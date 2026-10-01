//? source if >=1.21.11
package buildcraft.lib.compat;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Method;
import java.util.UUID;

/** Compatibility accessors for Mojang's record-style GameProfile fields in 1.21.11. */
public final class GameProfileCompat {
    private GameProfileCompat() {
    }

    public static UUID id(GameProfile profile) {
        if (profile == null) {
            return null;
        }
        Object value = call(profile, "id", "getId");
        return value instanceof UUID uuid ? uuid : null;
    }

    public static String name(GameProfile profile) {
        if (profile == null) {
            return null;
        }
        Object value = call(profile, "name", "getName");
        return value instanceof String name ? name : null;
    }

    private static Object call(GameProfile profile, String first, String fallback) {
        for (String methodName : new String[] { first, fallback }) {
            try {
                Method method = profile.getClass().getMethod(methodName);
                return method.invoke(profile);
            } catch (ReflectiveOperationException ignored) {
                // Try the next spelling.
            }
        }
        return null;
    }
}
