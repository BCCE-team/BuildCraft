/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.lib;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import buildcraft.lib.misc.ItemStackKey;

import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

public class CreativeTabManager {
    private static final Map<String, CreativeTabBC> tabMap = new HashMap<>();
    private static final Map<String, Map<String, Integer>> ITEM_ORDERS = createItemOrders();

    public static CreativeModeTab getTab(String name) {
        if (name.startsWith("vanilla.")) {
            String after = name.substring("vanilla.".length());
            switch (after) {
                case "misc":
                    return CreativeModeTab.TAB_MISC;
                case "materials":
                    return CreativeModeTab.TAB_MATERIALS;
            }
        }
        if (tabMap.containsKey(name)) {
            return tabMap.get(name);
        } else {
            throw new IllegalArgumentException("Unknown tab " + name);
        }
    }

    public static CreativeTabBC createTab(String name) {
        CreativeTabBC tab = tabMap.get(name);
        if (tab != null) {
            return tab;
        }
        tab = new CreativeTabBC(name);
        tabMap.put(name, tab);
        return tab;
    }

    public static void setItem(String name, Item item) {
        if (item != null) {
            setItemStack(name, new ItemStack(item));
        }
    }

    public static void setItemStack(String name, ItemStack item) {
        CreativeTabBC tab = tabMap.get(name);
        if (tab != null) {
            tab.setItem(item);
        }
    }

    private static Map<String, Map<String, Integer>> createItemOrders() {
        Map<String, Map<String, Integer>> orders = new HashMap<>();

        // BuildCraft 8 main-tab order, limited to items registered by this target.
        // Target-specific items are placed beside the closest original group.
        addOrder(orders, "buildcraft.main",
            // Core blocks and area-planning tools
            "buildcraftcore:marker_volume",
            "buildcraftcore:marker_path",
            "buildcraftbuilders:marker_construction",
            "buildcraftrobotics:zone_planner",
            "buildcraftcore:engine_redstone",
            "buildcraftenergy:engine_stone",
            "buildcraftenergy:engine_iron",
            "buildcraftcore:engine_creative",

            // Core items
            "buildcraftlib:guide",
            "buildcraftcore:wrench",
            "buildcraftcore:gears/gear_wood",
            "buildcraftcore:gears/gear_stone",
            "buildcraftcore:gears/gear_iron",
            "buildcraftcore:gears/gear_gold",
            "buildcraftcore:gears/gear_diamond",
            "buildcraftcore:paintbrush/clean",
            "buildcraftcore:paintbrush/black",
            "buildcraftcore:paintbrush/red",
            "buildcraftcore:paintbrush/green",
            "buildcraftcore:paintbrush/brown",
            "buildcraftcore:paintbrush/blue",
            "buildcraftcore:paintbrush/purple",
            "buildcraftcore:paintbrush/cyan",
            "buildcraftcore:paintbrush/light_gray",
            "buildcraftcore:paintbrush/gray",
            "buildcraftcore:paintbrush/pink",
            "buildcraftcore:paintbrush/lime",
            "buildcraftcore:paintbrush/yellow",
            "buildcraftcore:paintbrush/light_blue",
            "buildcraftcore:paintbrush/magenta",
            "buildcraftcore:paintbrush/orange",
            "buildcraftcore:paintbrush/white",
            "buildcraftcore:list",
            "buildcraftcore:map_location",
            "buildcraftcore:marker_connector",

            // Builders items, then blocks
            "buildcraftbuilders:blueprint",
            "buildcraftbuilders:template",
            "buildcraftbuilders:schematic_single",
            "buildcraftbuilders:filler",
            "buildcraftbuilders:builder",
            "buildcraftbuilders:architect",
            "buildcraftbuilders:library",
            "buildcraftbuilders:replacer",
            "buildcraftbuilders:frame",
            "buildcraftbuilders:quarry",

            // Factory blocks, then items
            "buildcraftfactory:autoworkbench_item",
            "buildcraftfactory:mining_well",
            "buildcraftfactory:pump",
            "buildcraftfactory:flood_gate",
            "buildcraftfactory:tank",
            "buildcraftfactory:chute",
            "buildcraftfactory:distiller",
            "buildcraftfactory:heat_exchange",
            "buildcraftfactory:water_gel",
            "buildcraftfactory:gel",

            // Transport and logistics blocks
            "buildcrafttransport:filtered_buffer",
            "buildcraftrobotics:requester",

            // Silicon blocks, then items
            "buildcraftsilicon:laser",
            "buildcraftsilicon:assembly_table",
            "buildcraftsilicon:advanced_crafting_table",
            "buildcraftsilicon:integration_table",
            "buildcraftsilicon:charging_table",
            "buildcraftsilicon:programming_table",
            "buildcraftsilicon:redstone_chipset/red",
            "buildcraftsilicon:redstone_chipset/iron",
            "buildcraftsilicon:redstone_chipset/gold",
            "buildcraftsilicon:redstone_chipset/quartz",
            "buildcraftsilicon:redstone_chipset/diamond",
            "buildcraftsilicon:redstone_crystal",
            "buildcraftsilicon:gate_copier"
        );

        addOrder(orders, "buildcraft.pipes",
            "buildcrafttransport:waterproof",
            "buildcrafttransport:structure",

            // Item pipes
            "buildcrafttransport:wood_item",
            "buildcrafttransport:cobblestone_item",
            "buildcrafttransport:stone_item",
            "buildcrafttransport:quartz_item",
            "buildcrafttransport:iron_item",
            "buildcrafttransport:gold_item",
            "buildcrafttransport:clay_item",
            "buildcrafttransport:sandstone_item",
            "buildcrafttransport:void_item",
            "buildcrafttransport:obsidian_item",
            "buildcrafttransport:diamond_item",
            "buildcrafttransport:diamond_wood_item",
            "buildcrafttransport:lapis_item",
            "buildcrafttransport:daizuli_item",
            "buildcrafttransport:emzuli_item",
            "buildcrafttransport:stripes_item",
            "buildcraftcompat:pipe_item_propolis",

            // Fluid pipes
            "buildcrafttransport:wood_fluid",
            "buildcrafttransport:cobblestone_fluid",
            "buildcrafttransport:stone_fluid",
            "buildcrafttransport:quartz_fluid",
            "buildcrafttransport:gold_fluid",
            "buildcrafttransport:iron_fluid",
            "buildcrafttransport:clay_fluid",
            "buildcrafttransport:sandstone_fluid",
            "buildcrafttransport:void_fluid",
            "buildcrafttransport:diamond_fluid",
            "buildcrafttransport:diamond_wood_fluid",

            // Power pipes
            "buildcrafttransport:wood_power",
            "buildcrafttransport:cobblestone_power",
            "buildcrafttransport:stone_power",
            "buildcrafttransport:quartz_power",
            "buildcrafttransport:iron_power",
            "buildcrafttransport:gold_power",
            "buildcrafttransport:sandstone_power",
            "buildcrafttransport:diamond_power",
            "buildcrafttransport:diamond_wood_power"
        );

        addOrder(orders, "buildcraft.plugs",
            "buildcrafttransport:plug_blocker",
            "buildcrafttransport:plug_power_adaptor",

            // Original wire metadata order: black -> white.
            "buildcrafttransport:wire/black",
            "buildcrafttransport:wire/red",
            "buildcrafttransport:wire/green",
            "buildcrafttransport:wire/brown",
            "buildcrafttransport:wire/blue",
            "buildcrafttransport:wire/purple",
            "buildcrafttransport:wire/cyan",
            "buildcrafttransport:wire/light_gray",
            "buildcrafttransport:wire/gray",
            "buildcrafttransport:wire/pink",
            "buildcrafttransport:wire/lime",
            "buildcrafttransport:wire/yellow",
            "buildcrafttransport:wire/light_blue",
            "buildcrafttransport:wire/magenta",
            "buildcrafttransport:wire/orange",
            "buildcrafttransport:wire/white",

            "buildcraftsilicon:plug/gate",
            "buildcraftsilicon:plug/lens",
            "buildcraftsilicon:plug/pulsar",
            "buildcraftsilicon:plug/light_sensor",
            "buildcraftsilicon:plug/timer"
        );

        // The facade tab contains one item with many generated stacks. Their internal order remains untouched.
        addOrder(orders, "buildcraft.facades", "buildcraftsilicon:plug/facade");

        return orders;
    }

    private static void addOrder(Map<String, Map<String, Integer>> orders, String tabName, String... itemIds) {
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < itemIds.length; i++) {
            order.put(itemIds[i], i);
        }
        orders.put(tabName, order);
    }

    private static String getRegistryName(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id == null ? "" : id.toString();
    }

    private static int getFluidTabGroup(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && id.getPath().startsWith("ic2_cell/") ? 1 : 0;
    }

    public static class CreativeTabBC extends CreativeModeTab {
        private final String name;
        private final List<Supplier<? extends Collection<ItemStack>>> itemProviders = new CopyOnWriteArrayList<>();
        private ItemStack item = new ItemStack(Items.COMPARATOR); // Fallback until the module assigns its icon.

        private CreativeTabBC(String name) {
            super(name);
            this.name = name;
        }

        public void setItem(Item item) {
            if (item != null) {
                this.item = new ItemStack(item);
            }
        }

        public void setItem(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return;
            item = stack;
        }

        @Override
        public ItemStack makeIcon() {
            return item;
        }

        /** Adds lazily generated entries supplied by optional integrations. */
        public void addItemProvider(Supplier<? extends Collection<ItemStack>> provider) {
            if (provider != null && !itemProviders.contains(provider)) {
                itemProviders.add(provider);
            }
        }

        @Override
        public void fillItemList(NonNullList<ItemStack> items) {
            super.fillItemList(items);

            Set<ItemStackKey> seen = new HashSet<>();
            for (ItemStack existing : items) {
                ItemStack normalized = existing.copy();
                normalized.setCount(1);
                seen.add(new ItemStackKey(normalized));
            }

            for (Supplier<? extends Collection<ItemStack>> provider : itemProviders) {
                Collection<ItemStack> provided;
                try {
                    provided = provider.get();
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (provided == null) {
                    continue;
                }
                for (ItemStack stack : provided) {
                    addUnique(items, seen, stack);
                }
            }

            if ("buildcraft.fluid".equals(name)) {
                // Keep all BuildCraft buckets first and append the optional IC2 filled cells afterwards.
                items.sort(Comparator.comparingInt(CreativeTabManager::getFluidTabGroup));
            }

            Map<String, Integer> order = ITEM_ORDERS.get(name);
            if (order != null) {
                // List.sort is stable, so all generated variants of one item retain their original order.
                items.sort(Comparator.comparingInt(stack -> order.getOrDefault(getRegistryName(stack), Integer.MAX_VALUE)));
            }
        }

        private static void addUnique(List<ItemStack> items, Set<ItemStackKey> seen, ItemStack stack) {
            if (stack == null || stack.isEmpty()) {
                return;
            }
            ItemStack normalized = stack.copy();
            normalized.setCount(1);
            if (seen.add(new ItemStackKey(normalized))) {
                items.add(stack.copy());
            }
        }
    }
}
