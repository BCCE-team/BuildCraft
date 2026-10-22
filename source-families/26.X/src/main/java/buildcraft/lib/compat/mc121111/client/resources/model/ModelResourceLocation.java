//? source if >=1.21.11
package buildcraft.lib.compat.mc121111.client.resources.model;

import java.util.Objects;

import net.minecraft.resources.Identifier;

/** Minimal ModelResourceLocation compatibility facade for 1.21.11. */
public final class ModelResourceLocation {
    private final Identifier id;
    private final String variant;

    public ModelResourceLocation(Identifier id, String variant) {
        this.id = id;
        this.variant = variant == null ? "" : variant;
    }

    public ModelResourceLocation(String combined) {
        int hash = combined.indexOf('#');
        String idPart = hash >= 0 ? combined.substring(0, hash) : combined;
        this.id = Identifier.parse(idPart);
        this.variant = hash >= 0 ? combined.substring(hash + 1) : "";
    }

    public Identifier id() { return id; }
    public String variant() { return variant; }

    public boolean equals(Object other) {
        return other instanceof ModelResourceLocation that && id.equals(that.id) && variant.equals(that.variant);
    }

    public int hashCode() { return Objects.hash(id, variant); }
    public String toString() { return id + "#" + variant; }
}
