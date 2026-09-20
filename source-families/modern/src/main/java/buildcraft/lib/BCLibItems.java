//? source if >=1.21.1
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
import buildcraft.lib.compat.RegistryCompat;

/** Items owned by the BuildCraft library module. */
public final class BCLibItems {
    public static final BCDeferredRegister<Item> ITEMS = BCDeferredRegister.create("minecraft:item", BCLib.MODID);

    public static final BCRegistryEntry<ItemGuide> GUIDE = RegistryCompat.registerItem(ITEMS, "guide",
        () -> new ItemGuide(RegistryCompat.itemProperties(new Item.Properties()).stacksTo(1).overrideDescription("item.buildcraft.guide.name"))
    );

    private BCLibItems() {
    }

    static void registry(BCRegistryBinder bus) {
        ITEMS.register(bus);
    }
}
