//? source if >=1.21.11
package buildcraft.lib.compat.neoforge121111.client.model.geometry;

import net.minecraft.resources.Identifier;

public final class StandaloneGeometryBakingContext {
    public static final Identifier LOCATION = Identifier.parse("buildcraft:legacy_geometry_context");

    private StandaloneGeometryBakingContext() {}

    public static Builder builder(IGeometryBakingContext parent) { return new Builder(parent); }

    public static final class Builder {
        private final IGeometryBakingContext parent;
        private Builder(IGeometryBakingContext parent) { this.parent = parent; }
        public Builder withGui3d(boolean value) { return this; }
        public Builder withUseBlockLight(boolean value) { return this; }
        public IGeometryBakingContext build(Identifier location) { return parent; }
    }
}
