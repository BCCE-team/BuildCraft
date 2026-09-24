package buildcraft.core;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

import buildcraft.lib.BCLibItems;
import buildcraft.lib.internal.enums.EnumEngineType;
import buildcraft.lib.internal.enums.EnumSpring;
import buildcraft.core.item.MapLocationType;
import buildcraft.core.item.ItemFragileFluidContainer;
import buildcraft.core.item.ItemList_BC8;
import buildcraft.core.item.ItemMapLocation;
import buildcraft.core.item.ItemMarkerConnector;
import buildcraft.core.item.ItemPaintbrush_BC8;
import buildcraft.core.item.ItemVolumeBox;
import buildcraft.core.item.ItemWrench;
import buildcraft.lib.item.ItemByEnum;
import buildcraft.lib.item.MultiBlockItem;
import buildcraft.lib.list.ListHandler;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class BCCoreItems {
    public static final BCDeferredRegister<Item> ITEMS = BCDeferredRegister.create("minecraft:item", BCCore.MODID);
    public static final BCRegistryEntry<Item> WRENCH = ITEMS.register("wrench", ItemWrench::new);
    public static final BCRegistryEntry<Item> GEAR_WOOD = ITEMS.register("gears/gear_wood", () -> new Item(new Item.Properties()));
    public static final BCRegistryEntry<Item> GEAR_STONE = ITEMS.register("gears/gear_stone", () -> new Item(new Item.Properties()));
    public static final BCRegistryEntry<Item> GEAR_IRON = ITEMS.register("gears/gear_iron", () -> new Item(new Item.Properties()));
    public static final BCRegistryEntry<Item> GEAR_GOLD = ITEMS.register("gears/gear_gold", () -> new Item(new Item.Properties()));
    public static final BCRegistryEntry<Item> GEAR_DIAMOND = ITEMS.register("gears/gear_diamond", () -> new Item(new Item.Properties()));
    public static final BCRegistryEntry<ItemPaintbrush_BC8> PAINT_BRUSH = ITEMS.register("paintbrush/clean", () -> new ItemPaintbrush_BC8(new Item.Properties(), null));
    public static final BCRegistryEntry<ItemMarkerConnector> MARKER_CONNECTOR = ITEMS.register("marker_connector", () -> new ItemMarkerConnector(new Item.Properties().stacksTo(1)));
    public static final BCRegistryEntry<ItemVolumeBox> VOLUME_BOX = ITEMS.register("volume_box", () -> new ItemVolumeBox(new Item.Properties()));
    public static final BCRegistryEntry<ItemMapLocation> MAP_LOCATION = ITEMS.register("map_location", () -> new ItemMapLocation(new Item.Properties().stacksTo(16)));
    public static final BCRegistryEntry<ItemList_BC8> LIST = ITEMS.register("list", () -> new ItemList_BC8(new Item.Properties().stacksTo(1)));



    public static final BCRegistryEntry<ItemFragileFluidContainer> FRAGILE_FLUID_SHARD = ITEMS.register("fragile_fluid_shard", ItemFragileFluidContainer::new);



    public static final EnumMap<DyeColor, ItemPaintbrush_BC8> PAINT_BRUSHS = ItemByEnum.creatItems(ItemPaintbrush_BC8::new, new Item.Properties().durability(64),
            DyeColor.values(), DyeColor.class, "paintbrush", ITEMS);;
    public static final EnumMap<EnumEngineType, MultiBlockItem<EnumEngineType>> ENGINE_ITEM_MAP = new EnumMap<EnumEngineType, MultiBlockItem<EnumEngineType>>(EnumEngineType.class);
    public static final EnumMap<EnumSpring, MultiBlockItem<EnumSpring>> SPRING_ITEM_MAP = new EnumMap<EnumSpring, MultiBlockItem<EnumSpring>>(EnumSpring.class);

    public static final BCRegistryEntry<MultiBlockItem<EnumEngineType>> ENGINE_RESTONE_ITEM_BC8 = ITEMS.register("engine_redstone", () -> new MultiBlockItem<EnumEngineType>(BCCoreBlocks.ENGINE_BC8.get(),new Item.Properties(),EnumEngineType.WOOD, ENGINE_ITEM_MAP));
    public static final BCRegistryEntry<MultiBlockItem<EnumEngineType>> ENGINE_CREATIVE_ITEM_BC8 = ITEMS.register("engine_creative", () -> new MultiBlockItem<EnumEngineType>(BCCoreBlocks.ENGINE_BC8.get(),new Item.Properties(),EnumEngineType.CREATIVE, ENGINE_ITEM_MAP));

    public static final BCRegistryEntry<MultiBlockItem<EnumSpring>> SPRING_WATER = ITEMS.register("spring_water", () -> new MultiBlockItem<EnumSpring>(BCCoreBlocks.SPRING.get(), new Item.Properties(), EnumSpring.WATER, SPRING_ITEM_MAP));
    public static final BCRegistryEntry<MultiBlockItem<EnumSpring>> SPRING_OIL = ITEMS.register("spring_oil", () -> new MultiBlockItem<EnumSpring>(BCCoreBlocks.SPRING.get(), new Item.Properties(), EnumSpring.OIL, SPRING_ITEM_MAP));

    public static final BCRegistryEntry<BlockItem> MARKER_PATH = ITEMS.register("marker_path", () -> new BlockItem(BCCoreBlocks.MARKER_PATH.get(), new Item.Properties()));
    public static final BCRegistryEntry<BlockItem> MARKER_VOLUME = ITEMS.register("marker_volume", () -> new BlockItem(BCCoreBlocks.MARKER_VOLUME.get(), new Item.Properties()));


    /** Core-owned contents of the BuildCraft main creative tab. */
    public static List<ItemStack> getCreativeTabItems() {
        List<ItemStack> items = new ArrayList<>();
        add(items, BCLibItems.GUIDE);
        add(items, MARKER_VOLUME);
        add(items, MARKER_PATH);
        add(items, ENGINE_RESTONE_ITEM_BC8);
        add(items, ENGINE_CREATIVE_ITEM_BC8);
        add(items, WRENCH);
        add(items, GEAR_WOOD);
        add(items, GEAR_STONE);
        add(items, GEAR_IRON);
        add(items, GEAR_GOLD);
        add(items, GEAR_DIAMOND);
        add(items, PAINT_BRUSH);
        PAINT_BRUSHS.values().forEach(item -> items.add(new ItemStack(item)));
        add(items, LIST);
        add(items, MAP_LOCATION);
        add(items, MARKER_CONNECTOR);
        return items;
    }

    private static void add(List<ItemStack> items, BCRegistryEntry<? extends Item> item) {
        if (item.isPresent()) {
            items.add(new ItemStack(item.get()));
        }
    }

    static void registry(BCRegistryBinder m) {
        ITEMS.register(m);
    }

    public static void registerItemProperties() {
        ResourceLocation label = new ResourceLocation("buildcraftcore","map_type");
        ItemProperties.register(MAP_LOCATION.get(), label, (itemStack, ClientWorld, entity, p_174638_) -> {
            return (8 - MapLocationType.getFromStack(itemStack).meta) / 8.0F;
        });

        ResourceLocation listState = new ResourceLocation("buildcraftcore", "isempty");
        ItemProperties.register(LIST.get(), listState, (itemStack, level, entity, seed) ->
            ListHandler.hasItems(itemStack) ? 1.0F : 0.0F
        );
/*    	ItemProperties.register(TEMPLATE.get(), label, (itemStack, ClientWorld, entity, p_174638_) -> {
            return itemStack.getDamageValue() == ItemSchematicSingle.DAMAGE_CLEAN ? 0.0F : 1.0F;
        });*/
    }
}
