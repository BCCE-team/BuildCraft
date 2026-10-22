package dev.bcce.apifixture;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.facade.*;
import buildcraft.api.v2.list.*;
import buildcraft.api.v2.map.*;
import buildcraft.api.v2.tool.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.List;
import java.util.Optional;

/** Independent API2 fixture, without BuildCraft Lib or transport imports. */
public final class MiscParityFixtureAddon {
    private MiscParityFixtureAddon() { }
    public static ResourceLocation FILTER = ResourceLocation.tryParse("fixture:ore_filter");
    public static void register() {
        BuildCraftApi.registry(BuildCraftRegistries.NAMED_ITEM_LISTS).register(FILTER,
            new ItemList(List.of(new ItemListEntry(s -> s.is(Items.IRON_INGOT), true)), ListMatchMode.ANY));
        BuildCraftApi.registry(BuildCraftRegistries.FACADE_MATERIAL_ADAPTERS).register(
            ResourceLocation.tryParse("fixture:facades"), new FacadeMaterialAdapter() {
                @Override public Optional<ItemStack> createPhased(List<FacadePhase> phases, boolean hollow) {
                    return Optional.of(new ItemStack(Items.PAPER));
                }
            });
        BuildCraftApi.registry(BuildCraftRegistries.MAP_LOCATION_ADAPTERS).register(
            ResourceLocation.tryParse("fixture:maps"), new MapLocationAdapter() {
                @Override public boolean supports(ItemStack stack) { return stack.is(Items.MAP); }
                @Override public Optional<MapLocationView> read(ItemStack stack) { return Optional.of(MapLocationView.clean("fixture")); }
                @Override public boolean write(ItemStack stack, MapLocationView v, buildcraft.api.v2.OperationMode mode) {
                    return v != null;
                }
                @Override public boolean clear(ItemStack stack, buildcraft.api.v2.OperationMode mode) {
                    return true;
                }
            });
        BuildCraftApi.registry(BuildCraftRegistries.WRENCH_ADAPTERS).register(
            ResourceLocation.tryParse("fixture:wrenches"), new WrenchAdapter() {
                @Override public boolean recognizes(ItemStack stack) { return stack.is(Items.STICK); }
                @Override public void onUsed(WrenchUseContext context) { WrenchObservations.useCount++; }
            });
    }
    public static final class WrenchObservations { public static int useCount; }
}
