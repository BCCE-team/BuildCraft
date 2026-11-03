package buildcraft.robotics.internal.api2;

import buildcraft.api.v2.item.ItemMatcher;
import buildcraft.api.v2.request.ItemRequest;
import buildcraft.lib.misc.StackUtil;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Internal adapters between the classic requester storage model and the public API2 request contracts. */
public final class RequestSupport {
    private static final String SLOT_PREFIX = "slot/";

    private RequestSupport() {}

    public static ResourceLocation slotId(int slot) {
        if (slot < 0) throw new IllegalArgumentException("slot must be non-negative");
        return Objects.requireNonNull(ResourceLocation.tryParse("buildcraft:" + SLOT_PREFIX + slot));
    }

    public static OptionalInt slot(ResourceLocation id) {
        if (id == null || !"buildcraft".equals(id.getNamespace()) || !id.getPath().startsWith(SLOT_PREFIX)) {
            return OptionalInt.empty();
        }
        try {
            int slot = Integer.parseInt(id.getPath().substring(SLOT_PREFIX.length()));
            return slot < 0 ? OptionalInt.empty() : OptionalInt.of(slot);
        } catch (NumberFormatException ignored) {
            return OptionalInt.empty();
        }
    }

    public static ItemRequest request(int slot, ItemStack requested, int priority) {
        Objects.requireNonNull(requested, "requested");
        ItemStack template = requested.copy();
        ItemMatcher matcher = new ItemMatcher() {
            @Override
            public boolean matches(ItemStack candidate) {
                return candidate != null && !candidate.isEmpty() && StackUtil.matchesStackOrList(template, candidate);
            }

            @Override
            public List<ItemStack> examples() {
                return template.isEmpty() ? List.of() : List.of(template.copy());
            }
        };
        return new ItemRequest(slotId(slot), matcher, template.getCount(), template.getCount(), priority);
    }
}
