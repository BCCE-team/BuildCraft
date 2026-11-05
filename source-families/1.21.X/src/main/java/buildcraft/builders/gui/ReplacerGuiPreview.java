/* Copyright (c) 2026 BuildCraft Community Edition contributors; MPL 2.0. */
package buildcraft.builders.gui;

import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.builders.item.ItemSchematicSingle;
import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.ClientSnapshots;
import buildcraft.builders.snapshot.SchematicBlockAir;
import buildcraft.builders.snapshot.Snapshot;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Client-side, non-destructive Replacer preview. Never writes to the snapshot store. */
public final class ReplacerGuiPreview {
    private Blueprint source;
    private Blueprint edited;
    private ItemStack previousFind = ItemStack.EMPTY;
    private ItemStack previousReplacement = ItemStack.EMPTY;
    private int matches;
    private ISchematicBlock find;
    private ISchematicBlock replacement;

    /** Resolves a blueprint and caches its hypothetical post-replacement geometry. */
    public Blueprint get(ItemStack blueprintItem, ItemStack fromStack, ItemStack toStack) {
        Snapshot.Header header = ItemSnapshot.getHeader(blueprintItem);
        Snapshot snapshot = header == null ? null : ClientSnapshots.INSTANCE.getSnapshot(header.key);
        Blueprint latest = snapshot instanceof Blueprint bp ? bp : null;
        if (latest != source
            || !ItemStack.isSameItemSameComponents(previousFind, fromStack)
            || !ItemStack.isSameItemSameComponents(previousReplacement, toStack)) {
            source = latest;
            previousFind = fromStack.copy();
            previousReplacement = toStack.copy();
            find = fromStack.isEmpty() ? null : ItemSchematicSingle.getSchematicSafe(fromStack);
            replacement = toStack.isEmpty() ? new SchematicBlockAir() : ItemSchematicSingle.getSchematicSafe(toStack);
            matches = 0;
            edited = latest;
            if (latest != null && find != null && !find.isAir() && replacement != null) {
                matches = latest.countMatchingSchematic(find);
                if (matches > 0) {
                    try {
                        Blueprint candidate = latest.copy();
                        candidate.replace(find, replacement);
                        edited = candidate;
                    } catch (RuntimeException ex) {
                        // Unavailable/invalid addon schematics must not crash the GUI.
                        edited = latest;
                    }
                }
            }
        }
        return edited;
    }

    public int matches() {
        return matches;
    }

    public boolean hasSummary() {
        return source != null && find != null && !find.isAir() && replacement != null;
    }

    public Component summary(boolean remove) {
        if (!hasSummary()) return Component.empty();
        String fromName = displayName(find);
        return remove
            ? Component.translatable("gui.buildcraftbuilders.replacer.summary_remove", matches, fromName)
            : Component.translatable("gui.buildcraftbuilders.replacer.summary", matches,
                fromName, displayName(replacement));
    }

    public static boolean canApply(ItemStack blueprintItem, ItemStack findStack, ItemStack replacementStack) {
        if (ItemSnapshot.getHeader(blueprintItem) == null || findStack.isEmpty()) return false;
        ISchematicBlock from = ItemSchematicSingle.getSchematicSafe(findStack);
        if (from == null || from.isAir()) return false;
        return replacementStack.isEmpty() || ItemSchematicSingle.getSchematicSafe(replacementStack) != null;
    }

    private static String displayName(ISchematicBlock schematic) {
        BlockState state = schematic.getBlockStateForRender();
        return state == null ? "?" : state.getBlock().getName().getString();
    }
}
