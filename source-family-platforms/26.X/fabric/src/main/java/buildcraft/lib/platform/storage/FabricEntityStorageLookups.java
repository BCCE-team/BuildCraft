/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.lib.platform.storage;

import net.fabricmc.fabric.api.lookup.v1.entity.EntityApiLookup;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

/**
 * Fabric has no built-in entity transfer lookups analogous to NeoForge's
 * ENTITY_AUTOMATION and entity fluid capabilities. Addons can register native
 * Storage providers for their entity types here, preserving Fabric transactions.
 * Query context is the side; null means unsided access.
 */
public final class FabricEntityStorageLookups {
    public static final EntityApiLookup<Storage<ItemVariant>, Direction> ITEMS = lookup("entity_items");
    public static final EntityApiLookup<Storage<FluidVariant>, Direction> FLUIDS = lookup("entity_fluids");

    private FabricEntityStorageLookups() {}

    private static <T> EntityApiLookup<Storage<T>, Direction> lookup(String id) {
        @SuppressWarnings("unchecked")
        Class<Storage<T>> type = (Class<Storage<T>>) (Class<?>) Storage.class;
        return EntityApiLookup.get(Identifier.fromNamespaceAndPath("buildcraftlib", id),
            type, Direction.class);
    }
}
