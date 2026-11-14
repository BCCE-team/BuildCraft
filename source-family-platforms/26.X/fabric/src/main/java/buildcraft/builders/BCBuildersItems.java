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
import buildcraft.builders.item.ItemQuarry;
import buildcraft.builders.item.ItemSnapshot.EnumItemSnapshotType;
import buildcraft.lib.CreativeTabManager;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import buildcraft.lib.platform.client.ClientModelProperties;
import net.minecraft.world.entity.ItemOwner;
import javax.annotation.Nullable;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import buildcraft.lib.compat.RegistryCompat;

public class BCBuildersItems {
    public static final BCDeferredRegister<Item> ITEMS = BCDeferredRegister.create("minecraft:item", BCBuilders.MODID);

    public static final BCRegistryEntry<ItemSnapshot> BLUEPRINT = RegistryCompat.registerItem(ITEMS, "blueprint", () -> new ItemSnapshot(RegistryCompat.itemProperties(new Item.Properties()).stacksTo(16), EnumSnapshotType.BLUEPRINT));
    public static final BCRegistryEntry<ItemSnapshot> TEMPLATE = RegistryCompat.registerItem(ITEMS, "template", () -> new ItemSnapshot(RegistryCompat.itemProperties(new Item.Properties()).stacksTo(16), EnumSnapshotType.TEMPLATE));
    public static final BCRegistryEntry<ItemSchematicSingle> SCHEMATIC_SINGLE = RegistryCompat.registerItem(ITEMS, "schematic_single", () -> new ItemSchematicSingle(RegistryCompat.itemProperties(new Item.Properties()).stacksTo(16)));
    public static final BCRegistryEntry<ItemFillerPlanner> FILLER_PLANNER = RegistryCompat.registerItem(ITEMS, "filler_planner", () -> new ItemFillerPlanner(RegistryCompat.itemProperties(new Item.Properties())));


    public static final BCRegistryEntry<BlockItem> FILLER_BLOCK_ITEM = RegistryCompat.registerItem(ITEMS, "filler", () -> new BlockItem(BCBuildersBlocks.FILLER.get(),RegistryCompat.itemProperties(new Item.Properties())));
    public static final BCRegistryEntry<BlockItem> BUILDER_BLOCK_ITEM = RegistryCompat.registerItem(ITEMS, "builder", () -> new BlockItem(BCBuildersBlocks.BUILDER.get(),RegistryCompat.itemProperties(new Item.Properties())));
    public static final BCRegistryEntry<BlockItem> ARCHITECT_BLOCK_ITEM = RegistryCompat.registerItem(ITEMS, "architect", () -> new BlockItem(BCBuildersBlocks.ARCHITECT.get(),RegistryCompat.itemProperties(new Item.Properties())));
    public static final BCRegistryEntry<BlockItem> LIBRARY_BLOCK_ITEM = RegistryCompat.registerItem(ITEMS, "library", () -> new BlockItem(BCBuildersBlocks.LIBRARY.get(),RegistryCompat.itemProperties(new Item.Properties())));
    public static final BCRegistryEntry<BlockItem> REPLACER_BLOCK_ITEM = RegistryCompat.registerItem(ITEMS, "replacer", () -> new BlockItem(BCBuildersBlocks.REPLACER.get(),RegistryCompat.itemProperties(new Item.Properties())));
    public static final BCRegistryEntry<ItemConstructionMarker> CONSTRUCTION_MARKER = RegistryCompat.registerItem(ITEMS, "marker_construction", () -> new ItemConstructionMarker(RegistryCompat.itemProperties(new Item.Properties())));
    public static final BCRegistryEntry<BlockItem> FRAME_BLOCK_ITEM = RegistryCompat.registerItem(ITEMS, "frame", () -> new BlockItem(BCBuildersBlocks.FRAME.get(),RegistryCompat.itemProperties(new Item.Properties())));
    public static final BCRegistryEntry<ItemQuarry> QUARRY_BLOCK_ITEM = RegistryCompat.registerItem(ITEMS, "quarry", () ->
        new ItemQuarry(BCBuildersBlocks.QUARRY.get(),
            RegistryCompat.itemProperties(new Item.Properties()).stacksTo(1).enchantable(10)));




    public static void registry(BCRegistryBinder b) {
        ITEMS.register(b);
    }

    public static List<ItemStack> getCreativeTabItems() {
        List<ItemStack> items = new ArrayList<>();
        add(items, CONSTRUCTION_MARKER);
        add(items, BLUEPRINT);
        add(items, TEMPLATE);
        add(items, SCHEMATIC_SINGLE);
        add(items, FILLER_PLANNER);
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

    public static void registerItemModelProperties(ClientModelProperties.Ranges event) {
        event.register(Identifier.fromNamespaceAndPath(BCBuilders.MODID, "used"), SnapshotUsedModelProperty.MAP_CODEC);
        event.register(Identifier.fromNamespaceAndPath(BCBuilders.MODID, "recording"), ConstructionRecordingModelProperty.MAP_CODEC);
    }

    public record SnapshotUsedModelProperty() implements RangeSelectItemModelProperty {
        public static final MapCodec<SnapshotUsedModelProperty> MAP_CODEC = MapCodec.unit(new SnapshotUsedModelProperty());

        public float get(ItemStack stack, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
            if (stack.getItem() == SCHEMATIC_SINGLE.get()) {
                return ItemSchematicSingle.isUsed(stack) ? 1.0F : 0.0F;
            }
            return EnumItemSnapshotType.getFromStack(stack).used ? 1.0F : 0.0F;
        }

        public MapCodec<SnapshotUsedModelProperty> type() {
            return MAP_CODEC;
        }
    }

    public record ConstructionRecordingModelProperty() implements RangeSelectItemModelProperty {
        public static final MapCodec<ConstructionRecordingModelProperty> MAP_CODEC = MapCodec.unit(new ConstructionRecordingModelProperty());

        public float get(ItemStack stack, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
            return ItemConstructionMarker.isRecording(stack) ? 1.0F : 0.0F;
        }

        public MapCodec<ConstructionRecordingModelProperty> type() {
            return MAP_CODEC;
        }
    }
}
