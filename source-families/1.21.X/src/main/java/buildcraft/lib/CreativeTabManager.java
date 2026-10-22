/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.lib;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

import buildcraft.lib.item.ICreativeTabItemProvider;
import buildcraft.lib.misc.ItemStackKey;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Version-neutral BuildCraft creative-tab descriptor. Since Minecraft 1.19.3
 * creative tabs are registry objects and their contents are populated through
 * callbacks; subclassing {@code CreativeModeTab} is no longer supported.
 */
public final class CreativeTabManager {
    private static final Map<String, CreativeTabBC> TAB_MAP = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Integer>> ITEM_ORDERS = createItemOrders();

    private CreativeTabManager() {
    }

    public static CreativeTabBC getTab(String name) {
        CreativeTabBC tab = TAB_MAP.get(name);
        if (tab == null) {
            throw new IllegalArgumentException("Unknown tab " + name);
        }
        return tab;
    }

    public static CreativeTabBC createTab(String name) {
        return TAB_MAP.computeIfAbsent(name, CreativeTabBC::new);
    }

    public static Collection<CreativeTabBC> getTabs() {
        return List.copyOf(TAB_MAP.values());
    }

    public static void setItem(String name, Item item) {
        if (item != null) {
            setItemStack(name, item.getDefaultInstance());
        }
    }

    public static void setItemStack(String name, ItemStack item) {
        CreativeTabBC tab = TAB_MAP.get(name);
        if (tab != null) {
            tab.setItem(item);
        }
    }

    /** Adds all variants known to the 1.20.1-compatible BuildCraft item API. */
    public static void addItemVariants(Item item, Consumer<ItemStack> output) {
        if (item instanceof ICreativeTabItemProvider provider) {
            provider.addCreativeTabItems(output);
        } else {
            ItemStack stack = item.getDefaultInstance();
            if (!stack.isEmpty()) {
                output.accept(stack);
            }
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
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.toString();
    }

    public static final class CreativeTabBC {
        private final String name;
        private final List<Supplier<? extends Collection<ItemStack>>> itemProviders = new CopyOnWriteArrayList<>();
        private ItemStack icon = new ItemStack(Items.COMPARATOR);
        private String recipeFolderName;

        private CreativeTabBC(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public ResourceLocation getId() {
            String namespace = "buildcraftcore";
            String path = name;
            int separator = name.indexOf('.');
            if (separator >= 0) {
                namespace = name.substring(0, separator);
                path = name.substring(separator + 1);
            }
            return ResourceLocation.fromNamespaceAndPath(namespace, path);
        }

        public ItemStack makeIcon() {
            return icon.copy();
        }

        public void setItem(Item item) {
            if (item != null) {
                setItem(item.getDefaultInstance());
            }
        }

        public void setItem(ItemStack stack) {
            if (stack != null && !stack.isEmpty()) {
                icon = stack.copy();
            }
        }

        public CreativeTabBC setRecipeFolderName(String recipeFolderName) {
            this.recipeFolderName = recipeFolderName;
            return this;
        }

        public String getRecipeFolderName() {
            return recipeFolderName;
        }

        public void addItemProvider(Supplier<? extends Collection<ItemStack>> provider) {
            if (provider != null && !itemProviders.contains(provider)) {
                itemProviders.add(provider);
            }
        }

        /**
         * Populates a 1.20.1 creative-tab output callback. Base items are
         * supplied by the module registration layer; optional/generated stacks
         * are added here and then sorted according to BuildCraft's canonical
         * order.
         */
        public void accept(Collection<ItemStack> baseItems, Consumer<ItemStack> output) {
            List<ItemStack> items = new ArrayList<>();
            Set<ItemStackKey> seen = new HashSet<>();
            if (baseItems != null) {
                for (ItemStack stack : baseItems) {
                    addUnique(items, seen, stack);
                }
            }
            for (Supplier<? extends Collection<ItemStack>> provider : itemProviders) {
                try {
                    Collection<ItemStack> provided = provider.get();
                    if (provided != null) {
                        for (ItemStack stack : provided) {
                            addUnique(items, seen, stack);
                        }
                    }
                } catch (RuntimeException ignored) {
                    // Optional integrations must not break creative-tab assembly.
                }
            }
            Map<String, Integer> order = ITEM_ORDERS.get(name);
            if (order != null) {
                items.sort(Comparator.comparingInt(stack -> order.getOrDefault(getRegistryName(stack), Integer.MAX_VALUE)));
            }
            items.forEach(output);
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
