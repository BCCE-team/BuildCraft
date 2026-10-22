/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.lib.item;

import java.util.function.Consumer;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class ItemBC_Neptune extends Item implements ICreativeTabItemProvider {
    /** Stable BuildCraft identifier used by the registration helpers. */
    public final String id;

    public ItemBC_Neptune(String id, Item.Properties properties) {
        super(properties);
        this.id = id;
    }

    /**
     * Adds the variants exposed by this item. In 1.20.1 creative tab contents
     * are assembled through tab output/event callbacks rather than
     * {@code Item#fillItemCategory}.
     */
    public void addCreativeTabItems(Consumer<ItemStack> output) {
        output.accept(getDefaultInstance());
    }
}
