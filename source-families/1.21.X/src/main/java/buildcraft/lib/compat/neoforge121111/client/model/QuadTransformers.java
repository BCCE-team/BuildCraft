//? source if >=1.21.11
package buildcraft.lib.compat.neoforge121111.client.model;

import java.util.List;

/** No-op quad-transformer compatibility facade for NeoForge 1.21.11. */
public final class QuadTransformers {
    private QuadTransformers() {}
    public static Transformer settingMaxEmissivity() { return Transformer.INSTANCE; }
    public enum Transformer {
        INSTANCE;
        public void processInPlace(List<?> quads) {}
    }
}
