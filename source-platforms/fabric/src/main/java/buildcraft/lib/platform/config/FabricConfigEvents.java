package buildcraft.lib.platform.config;
// Loader boundary owner: net.fabricmc (implementation may delegate to vanilla/common contracts).

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Config lifecycle callbacks fired by the Fabric server bootstrap. */
public final class FabricConfigEvents {
    private static final List<Consumer<String>> LOAD = new ArrayList<>();
    private static final List<Consumer<String>> RELOAD = new ArrayList<>();
    private FabricConfigEvents() { }
    static void add(Consumer<String> load, Consumer<String> reload) { LOAD.add(load); RELOAD.add(reload); }
    public static void fireLoad(String modId) { LOAD.forEach(c -> c.accept(modId)); }
    public static void fireReload(String modId) { RELOAD.forEach(c -> c.accept(modId)); }
}
