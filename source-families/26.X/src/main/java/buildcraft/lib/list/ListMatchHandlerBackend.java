package buildcraft.lib.list;

import buildcraft.api.v2.list.ListMatchAdapter;
import buildcraft.api.v2.list.ListMatchContext;
import buildcraft.api.v2.list.ListMatchType;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;
import net.minecraft.world.item.ItemStack;

/** Internal bridge that keeps the classic matcher implementations while API2 owns registration and dispatch. */
public abstract class ListMatchHandlerBackend implements ListMatchAdapter {
    public abstract boolean matches(ListMatchType type, @Nonnull ItemStack stack, @Nonnull ItemStack target, boolean precise);

    public abstract boolean isValidSource(ListMatchType type, @Nonnull ItemStack stack);

    /** Null means the caller should discover examples by enumeration. */
    public List<ItemStack> getClientExamples(ListMatchType type, @Nonnull ItemStack stack) {
        return null;
    }

    public final boolean supports(ListMatchContext context) {
        return isValidSource(context.type(), context.source());
    }

    public final boolean matches(ListMatchContext context) {
        return matches(context.type(), context.source(), context.target(), context.precise());
    }

    public final Optional<List<ItemStack>> examples(ListMatchContext context) {
        List<ItemStack> examples = getClientExamples(context.type(), context.source());
        return examples == null ? Optional.empty() : Optional.of(List.copyOf(examples));
    }
}
