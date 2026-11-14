/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.lib.platform.storage;

import buildcraft.api.v2.item.ItemMatcher;

/**
 * Optional filter metadata for a Fabric SlottedStorage. Unlike a simulated
 * insert, this matcher represents the slot's actual configuration even when
 * it is full, disabled or not accepting any items at this moment.
 */
public interface FabricSlotFilterProvider {
    ItemMatcher filter(int slot);
}
