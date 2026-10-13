package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.list.ItemList;
import buildcraft.api.v2.list.ItemListAdapter;
import buildcraft.api.v2.list.ItemListService;
import java.util.Collection;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Runtime bridge for stack-backed BuildCraft lists plus optional named API2 definitions. */
public final class ItemListServiceImpl implements ItemListService {

    public Optional<ItemList> list(Identifier id) { return Optional.ofNullable(BuildCraftApi.registry(BuildCraftRegistries.NAMED_ITEM_LISTS).get(id)); }
    public Collection<Identifier> ids() { return BuildCraftApi.registry(BuildCraftRegistries.NAMED_ITEM_LISTS).entries().stream().map(e -> e.id()).toList(); }
    public boolean matches(Identifier listId, ItemStack stack) {
        ItemList list = BuildCraftApi.registry(BuildCraftRegistries.NAMED_ITEM_LISTS).get(listId);
        return list != null && list.matches(stack);
    }

    public Optional<ItemListAdapter> adapter(ItemStack listStack) {
        if (listStack == null || listStack.isEmpty()) return Optional.empty();
        if (listStack.getItem() instanceof ItemListAdapter direct && direct.supports(listStack)) {
            return Optional.of(direct);
        }
        return Optional.empty();
    }
}
