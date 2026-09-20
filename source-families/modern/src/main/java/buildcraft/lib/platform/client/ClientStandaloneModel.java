//? source if >=1.21.11
package buildcraft.lib.platform.client;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.QuadCollection;
import net.minecraft.resources.Identifier;

/** Stable BCCE model descriptor. A reload binds a loader key; later bakes use only this lookup. */
public final class ClientStandaloneModel {
    private final Identifier location;
    private volatile Function<ModelManager, QuadCollection> lookup;
    public ClientStandaloneModel(Identifier location) { this.location = Objects.requireNonNull(location); }
    public Identifier location() { return location; }
    public void bind(Function<ModelManager, QuadCollection> lookup) { this.lookup = Objects.requireNonNull(lookup); }
    public QuadCollection get(ModelManager manager) {
        Function<ModelManager, QuadCollection> current = lookup;
        return current == null ? null : current.apply(manager);
    }
}
