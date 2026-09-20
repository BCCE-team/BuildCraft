package buildcraft.builders;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.internal.enums.EnumSnapshotType;
import buildcraft.builders.item.ItemConstructionMarker;
import buildcraft.builders.item.ItemFillerPlanner;
import buildcraft.builders.item.ItemSchematicSingle;
import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.item.ItemSnapshot.EnumItemSnapshotType;
import buildcraft.core.BCCore;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

public class BCBuildersItems {
    public static final BCDeferredRegister<Item> ITEMS = BCDeferredRegister.create("minecraft:item", BCBuilders.MODID);

    public static final BCRegistryEntry<ItemSnapshot> BLUEPRINT = ITEMS.register("blueprint", () -> new ItemSnapshot(new Item.Properties().tab(BCCore.BUILDCRAFT_TAB).stacksTo(16), EnumSnapshotType.BLUEPRINT));
    public static final BCRegistryEntry<ItemSnapshot> TEMPLATE = ITEMS.register("template", () -> new ItemSnapshot(new Item.Properties().tab(BCCore.BUILDCRAFT_TAB).stacksTo(16), EnumSnapshotType.TEMPLATE));
    public static final BCRegistryEntry<ItemSchematicSingle> SCHEMATIC_SINGLE = ITEMS.register("schematic_single", () -> new ItemSchematicSingle(new Item.Properties().tab(BCCore.BUILDCRAFT_TAB).stacksTo(16)));
    public static final BCRegistryEntry<ItemFillerPlanner> FILLER_PLANNER = ITEMS.register("filler_planner", () -> new ItemFillerPlanner(new Item.Properties()));


    public static final BCRegistryEntry<BlockItem> FILLER_BLOCK_ITEM = ITEMS.register("filler", () -> new BlockItem(BCBuildersBlocks.FILLER.get(),new Item.Properties().tab(BCCore.BUILDCRAFT_TAB)));
    public static final BCRegistryEntry<BlockItem> BUILDER_BLOCK_ITEM = ITEMS.register("builder", () -> new BlockItem(BCBuildersBlocks.BUILDER.get(),new Item.Properties().tab(BCCore.BUILDCRAFT_TAB)));
    public static final BCRegistryEntry<BlockItem> ARCHITECT_BLOCK_ITEM = ITEMS.register("architect", () -> new BlockItem(BCBuildersBlocks.ARCHITECT.get(),new Item.Properties().tab(BCCore.BUILDCRAFT_TAB)));
    public static final BCRegistryEntry<BlockItem> LIBRARY_BLOCK_ITEM = ITEMS.register("library", () -> new BlockItem(BCBuildersBlocks.LIBRARY.get(),new Item.Properties().tab(BCCore.BUILDCRAFT_TAB)));
    public static final BCRegistryEntry<BlockItem> REPLACER_BLOCK_ITEM = ITEMS.register("replacer", () -> new BlockItem(BCBuildersBlocks.REPLACER.get(),new Item.Properties().tab(BCCore.BUILDCRAFT_TAB)));
    public static final BCRegistryEntry<ItemConstructionMarker> CONSTRUCTION_MARKER = ITEMS.register("marker_construction", () -> new ItemConstructionMarker(new Item.Properties().tab(BCCore.BUILDCRAFT_TAB)));
    public static final BCRegistryEntry<BlockItem> FRAME_BLOCK_ITEM = ITEMS.register("frame", () -> new BlockItem(BCBuildersBlocks.FRAME.get(),new Item.Properties().tab(BCCore.BUILDCRAFT_TAB)));
    public static final BCRegistryEntry<BlockItem> QUARRY_BLOCK_ITEM = ITEMS.register("quarry", () -> new BlockItem(BCBuildersBlocks.QUARRY.get(),new Item.Properties().tab(BCCore.BUILDCRAFT_TAB)));




    public static void registry(BCRegistryBinder b) {
        ITEMS.register(b);
    }

    public static void registerItemProperties() {
        ResourceLocation snapshotUsed = new ResourceLocation(BCBuilders.MODID, "used");
        ItemProperties.register(BLUEPRINT.get(), snapshotUsed, (itemStack, ClientWorld, entity, p_174638_) -> {
            return EnumItemSnapshotType.getFromStack(itemStack).used ? 1.0F : 0.0F;
        });
        ItemProperties.register(TEMPLATE.get(), snapshotUsed, (itemStack, ClientWorld, entity, p_174638_) -> {
            return EnumItemSnapshotType.getFromStack(itemStack).used ? 1.0F : 0.0F;
        });
        ItemProperties.register(SCHEMATIC_SINGLE.get(), snapshotUsed, (itemStack, ClientWorld, entity, p_174638_) -> {
            return ItemSchematicSingle.isUsed(itemStack) ? 1.0F : 0.0F;
        });
        ResourceLocation recording = new ResourceLocation(BCBuilders.MODID, "recording");
        ItemProperties.register(CONSTRUCTION_MARKER.get(), recording, (itemStack, ClientWorld, entity, p_174638_) -> {
            return ItemConstructionMarker.isRecording(itemStack) ? 1.0F : 0.0F;
        });
    }
}
