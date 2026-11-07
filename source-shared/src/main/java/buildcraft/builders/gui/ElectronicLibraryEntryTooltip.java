/* Copyright (c) 2026 BuildCraft Community Edition contributors; MPL 2.0. */
package buildcraft.builders.gui;

import java.util.List;

import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.snapshot.Snapshot;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Resolves saved names and builds compact hover text for Electronic Library entries. */
public final class ElectronicLibraryEntryTooltip {
    private ElectronicLibraryEntryTooltip() { }

    public static String displayName(Snapshot.Key key) {
        Snapshot.Header header = key == null ? null : key.header;
        if (header == null || header.name == null || header.name.isBlank()
            || "<unnamed>".equals(header.name)) {
            return Component.translatable("item.blueprint.unnamed").getString();
        }
        return header.name;
    }

    public static ItemStack inventoryItem(Snapshot.Key key, Snapshot snapshot) {
        if (key == null || snapshot == null) {
            return ItemStack.EMPTY;
        }
        Snapshot.Header header = key.header != null ? key.header : snapshot.key.header;
        return header == null ? ItemStack.EMPTY : ItemSnapshot.getUsed(snapshot.getType(), header);
    }

    /** Library-only tooltip: saved name and content hash, with no inventory-item extras. */
    public static List<Component> compactLines(Snapshot.Key key) {
        return List.of(Component.literal(displayName(key)),
            Component.translatable("item.buildcraftbuilders.snapshot.hash", key.toString())
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    /** Retained for callers handling headerless legacy entries. */
    public static List<Component> fallbackLines(Snapshot.Key key) {
        return compactLines(key);
    }
}
