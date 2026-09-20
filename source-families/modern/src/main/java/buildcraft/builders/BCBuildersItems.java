package buildcraft.builders;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import java.util.ArrayList;
import java.util.List;

import buildcraft.lib.internal.enums.EnumSnapshotType;
import buildcraft.builders.item.ItemConstructionMarker;
import buildcraft.builders.item.ItemFillerPlanner;
import buildcraft.builders.item.ItemSchematicSingle;
import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.item.ItemSnapshot.EnumItemSnapshotType;
import buildcraft.lib.CreativeTabManager;
//? if >=1.21.4 {
import com.mojang.serialization.MapCodec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import buildcraft.lib.platform.client.ClientModelProperties;
import net.minecraft.world.entity.ItemOwner;
import javax.annotation.Nullable;
//?} else {
import net.minecraft.client.renderer.item.ItemProperties;
//?}
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class BCBuildersItems {
    public static final BCDeferredRegister<Item> ITEMS = BCDeferredRegister.create("minecraft:item", BCBuilders.MODID);

    public static final BCRegistryEntry<ItemSnapshot> BLUEPRINT = ITEMS.register("blueprint", () -> new ItemSnapshot(new Item.Properties().stacksTo(16), EnumSnapshotType.BLUEPRINT));
    public static final BCRegistryEntry<ItemSnapshot> TEMPLATE = ITEMS.register("template", () -> new ItemSnapshot(new Item.Properties().stacksTo(16), EnumSnapshotType.TEMPLATE));
    public static final BCRegistryEntry<ItemSchematicSingle> SCHEMATIC_SINGLE = ITEMS.register("schematic_single", () -> new ItemSchematicSingle(new Item.Properties().stacksTo(16)));
    public static final BCRegistryEntry<ItemFillerPlanner> FILLER_PLANNER = ITEMS.register("filler_planner", () -> new ItemFillerPlanner(new Item.Properties()));


    public static final BCRegistryEntry<BlockItem> FILLER_BLOCK_ITEM = ITEMS.register("filler", () -> new BlockItem(BCBuildersBlocks.FILLER.get(),new Item.Properties()));
    public static final BCRegistryEntry<BlockItem> BUILDER_BLOCK_ITEM = ITEMS.register("builder", () -> new BlockItem(BCBuildersBlocks.BUILDER.get(),new Item.Properties()));
    public static final BCRegistryEntry<BlockItem> ARCHITECT_BLOCK_ITEM = ITEMS.register("architect", () -> new BlockItem(BCBuildersBlocks.ARCHITECT.get(),new Item.Properties()));
    public static final BCRegistryEntry<BlockItem> LIBRARY_BLOCK_ITEM = ITEMS.register("library", () -> new BlockItem(BCBuildersBlocks.LIBRARY.get(),new Item.Properties()));
    public static final BCRegistryEntry<BlockItem> REPLACER_BLOCK_ITEM = ITEMS.register("replacer", () -> new BlockItem(BCBuildersBlocks.REPLACER.get(),new Item.Properties()));
    public static final BCRegistryEntry<ItemConstructionMarker> CONSTRUCTION_MARKER = ITEMS.register("marker_construction", () -> new ItemConstructionMarker(new Item.Properties()));
    public static final BCRegistryEntry<BlockItem> FRAME_BLOCK_ITEM = ITEMS.register("frame", () -> new BlockItem(BCBuildersBlocks.FRAME.get(),new Item.Properties()));
    public static final BCRegistryEntry<BlockItem> QUARRY_BLOCK_ITEM = ITEMS.register("quarry", () -> new BlockItem(BCBuildersBlocks.QUARRY.get(),new Item.Properties()));




    public static void registry(BCRegistryBinder b) {
        ITEMS.register(b);
    }

    public static List<ItemStack> getCreativeTabItems() {
        List<ItemStack> items = new ArrayList<>();
        add(items, CONSTRUCTION_MARKER);
        add(items, BLUEPRINT);
        add(items, TEMPLATE);
        add(items, SCHEMATIC_SINGLE);
        add(items, FILLER_BLOCK_ITEM);
        add(items, BUILDER_BLOCK_ITEM);
        add(items, ARCHITECT_BLOCK_ITEM);
        add(items, LIBRARY_BLOCK_ITEM);
        add(items, REPLACER_BLOCK_ITEM);
        add(items, FRAME_BLOCK_ITEM);
        add(items, QUARRY_BLOCK_ITEM);
        return items;
    }

    private static void add(List<ItemStack> items, BCRegistryEntry<? extends Item> item) {
        CreativeTabManager.addItemVariants(item.get(), items::add);
    }

    //? if >=1.21.4 {
    public static void registerItemModelProperties(ClientModelProperties.Ranges event) {
        event.register(ResourceLocation.fromNamespaceAndPath(BCBuilders.MODID, "used"), SnapshotUsedModelProperty.MAP_CODEC);
        event.register(ResourceLocation.fromNamespaceAndPath(BCBuilders.MODID, "recording"), ConstructionRecordingModelProperty.MAP_CODEC);
    }

    public record SnapshotUsedModelProperty() implements RangeSelectItemModelProperty {
        public static final MapCodec<SnapshotUsedModelProperty> MAP_CODEC = MapCodec.unit(new SnapshotUsedModelProperty());

        @Override
        public float get(ItemStack stack, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
            if (stack.getItem() == SCHEMATIC_SINGLE.get()) {
                return ItemSchematicSingle.isUsed(stack) ? 1.0F : 0.0F;
            }
            return EnumItemSnapshotType.getFromStack(stack).used ? 1.0F : 0.0F;
        }

        @Override
        public MapCodec<SnapshotUsedModelProperty> type() {
            return MAP_CODEC;
        }
    }

    public record ConstructionRecordingModelProperty() implements RangeSelectItemModelProperty {
        public static final MapCodec<ConstructionRecordingModelProperty> MAP_CODEC = MapCodec.unit(new ConstructionRecordingModelProperty());

        @Override
        public float get(ItemStack stack, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
            return ItemConstructionMarker.isRecording(stack) ? 1.0F : 0.0F;
        }

        @Override
        public MapCodec<ConstructionRecordingModelProperty> type() {
            return MAP_CODEC;
        }
    }
    //?} else {
    public static void registerItemProperties() {
        ResourceLocation snapshotUsed = ResourceLocation.fromNamespaceAndPath(BCBuilders.MODID, "used");
        ItemProperties.register(BLUEPRINT.get(), snapshotUsed, (itemStack, ClientWorld, entity, p_174638_) -> {
            return EnumItemSnapshotType.getFromStack(itemStack).used ? 1.0F : 0.0F;
        });
        ItemProperties.register(TEMPLATE.get(), snapshotUsed, (itemStack, ClientWorld, entity, p_174638_) -> {
            return EnumItemSnapshotType.getFromStack(itemStack).used ? 1.0F : 0.0F;
        });
        ItemProperties.register(SCHEMATIC_SINGLE.get(), snapshotUsed, (itemStack, ClientWorld, entity, p_174638_) -> {
            return ItemSchematicSingle.isUsed(itemStack) ? 1.0F : 0.0F;
        });
        ResourceLocation recording = ResourceLocation.fromNamespaceAndPath(BCBuilders.MODID, "recording");
        ItemProperties.register(CONSTRUCTION_MARKER.get(), recording, (itemStack, ClientWorld, entity, p_174638_) -> {
            return ItemConstructionMarker.isRecording(itemStack) ? 1.0F : 0.0F;
        });
    }
    //?}
}
