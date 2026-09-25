package buildcraft.api.v2.pipe;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;

/** Result of an item-entry hook without exposing BuildCraft travelling-item internals. */
public final class ItemTransitDecision {
    public enum Action { PASS, CONSUME, REPLACE }

    private static final ItemTransitDecision PASS = new ItemTransitDecision(Action.PASS, null, null);
    private static final ItemTransitDecision CONSUME = new ItemTransitDecision(Action.CONSUME, null, null);

    private final Action action;
    private final ItemStack replacement;
    private final ItemTransitData transit;

    private ItemTransitDecision(Action action, ItemStack replacement, ItemTransitData transit) {
        this.action = Objects.requireNonNull(action, "action");
        this.replacement = replacement == null ? null : replacement.copy();
        this.transit = transit;
    }

    public static ItemTransitDecision pass() { return PASS; }
    public static ItemTransitDecision consume() { return CONSUME; }
    public static ItemTransitDecision replace(ItemStack replacement, ItemTransitData transit) {
        Objects.requireNonNull(replacement, "replacement");
        Objects.requireNonNull(transit, "transit");
        return new ItemTransitDecision(Action.REPLACE, replacement, transit);
    }

    public Action action() { return action; }
    public Optional<ItemStack> replacement() { return replacement == null ? Optional.empty() : Optional.of(replacement.copy()); }
    public Optional<ItemTransitData> transit() { return Optional.ofNullable(transit); }
}
