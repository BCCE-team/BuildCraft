/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.item.ItemGuide;
import net.minecraft.world.item.Item;

/** Items owned by the BuildCraft library module. */
public final class BCLibItems {
    public static final BCDeferredRegister<Item> ITEMS = BCDeferredRegister.create("minecraft:item", BCLib.MODID);

    public static final BCRegistryEntry<ItemGuide> GUIDE = ITEMS.register(
        "guide",
        () -> new ItemGuide(new Item.Properties().stacksTo(1))
    );

    private BCLibItems() {
    }

    static void registry(BCRegistryBinder bus) {
        ITEMS.register(bus);
    }
}
