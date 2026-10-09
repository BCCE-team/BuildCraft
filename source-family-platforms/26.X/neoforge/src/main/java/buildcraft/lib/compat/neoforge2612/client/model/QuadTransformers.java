package buildcraft.lib.compat.neoforge2612.client.model;

import java.util.List;

/** No-op quad-transformer facade used by shared BuildCraft model code. */
public final class QuadTransformers {
    private QuadTransformers() {}
    public static Transformer settingMaxEmissivity() { return Transformer.INSTANCE; }
    public enum Transformer {
        INSTANCE;
        public void processInPlace(List<?> quads) {}
    }
}
