package buildcraft.silicon;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.misc.ItemStackUtil;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

import buildcraft.lib.internal.enums.EnumRedstoneChipset;
import buildcraft.lib.CreativeTabManager;
import buildcraft.lib.item.ItemByEnum;
import buildcraft.lib.item.ItemPluggableSimple;
import buildcraft.silicon.item.ItemGateCopier;
import buildcraft.silicon.item.ItemPluggableFacade;
import buildcraft.silicon.item.ItemPluggableGate;
import buildcraft.silicon.item.ItemPluggableLens;
import buildcraft.silicon.item.ItemRedstoneChipset;
import buildcraft.silicon.plug.PluggablePulsar;
import buildcraft.silicon.plug.FacadeStateManager;
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

public class BCSiliconItems {
    public static final BCDeferredRegister<Item> ITEMS = BCDeferredRegister.create("minecraft:item", BCSilicon.MODID);

    public static final BCRegistryEntry<Item> REDSTONE_CRYSTAL = RegistryCompat.registerItem(ITEMS, "redstone_crystal", () -> new Item(RegistryCompat.itemProperties(new Item.Properties())));
    public static final EnumMap<EnumRedstoneChipset, ItemRedstoneChipset> REDSTONE_CHIPSET_ITEMS =
        ItemByEnum.creatItems(ItemRedstoneChipset::new, RegistryCompat.itemProperties(new Item.Properties()).stacksTo(16),
            EnumRedstoneChipset.values(), EnumRedstoneChipset.class, "redstone_chipset", ITEMS);

    public static final BCRegistryEntry<ItemPluggableGate> PLUG_GATE_ITEM =
        RegistryCompat.registerItem(ITEMS, "plug/gate", ItemPluggableGate::new);
    public static final BCRegistryEntry<ItemPluggableFacade> PLUG_FACADE_ITEM =
        RegistryCompat.registerItem(ITEMS, "plug/facade", ItemPluggableFacade::new);
    public static final BCRegistryEntry<ItemPluggableLens> PLUG_LENS_ITEM =
        RegistryCompat.registerItem(ITEMS, "plug/lens", ItemPluggableLens::new);
    public static final BCRegistryEntry<ItemPluggableSimple> PLUG_LIGHT_SENSOR_ITEM = RegistryCompat.registerItem(ITEMS, "plug/light_sensor", () -> new ItemPluggableSimple(BCSiliconPlugs.lightSensor, RegistryCompat.itemProperties(new Item.Properties())));
    public static final BCRegistryEntry<ItemPluggableSimple> PLUG_TIMER_ITEM = RegistryCompat.registerItem(ITEMS, "plug/timer", () -> new ItemPluggableSimple(BCSiliconPlugs.timer, RegistryCompat.itemProperties(new Item.Properties())));
    public static final BCRegistryEntry<ItemPluggableSimple> PLUG_PULSAR_ITEM = RegistryCompat.registerItem(ITEMS, "plug/pulsar", () -> new ItemPluggableSimple(BCSiliconPlugs.pulsar, PluggablePulsar::new,
            ItemPluggableSimple.PIPE_BEHAVIOUR_ACCEPTS_RS_POWER, RegistryCompat.itemProperties(new Item.Properties())));
    public static final BCRegistryEntry<ItemGateCopier> GATE_COPIER_ITEM =
        RegistryCompat.registerItem(ITEMS, "gate_copier", ItemGateCopier::new);

    public static final BCRegistryEntry<BlockItem> LASER_BLOCK_ITEM = RegistryCompat.registerItem(ITEMS, "laser", () -> new BlockItem(BCSiliconBlocks.LASER_BLOCK.get(), RegistryCompat.itemProperties(new Item.Properties())));
    public static final BCRegistryEntry<BlockItem> ASSEMBLY_TABLE_ITEM = RegistryCompat.registerItem(ITEMS, "assembly_table", () -> new BlockItem(BCSiliconBlocks.ASSEMBLY_TABLE_BLOCK.get(), RegistryCompat.itemProperties(new Item.Properties())));
    public static final BCRegistryEntry<BlockItem> CHARGING_TABLE_ITEM = RegistryCompat.registerItem(ITEMS, "charging_table", () -> new BlockItem(BCSiliconBlocks.CHARGING_TABLE_BLOCK.get(), RegistryCompat.itemProperties(new Item.Properties())));
    public static final BCRegistryEntry<BlockItem> INTERGRATION_TABLE_ITEM = RegistryCompat.registerItem(ITEMS, "integration_table", () -> new BlockItem(BCSiliconBlocks.INTERGRATION_TABLE_BLOCK.get(), RegistryCompat.itemProperties(new Item.Properties())));
    public static final BCRegistryEntry<BlockItem> ADVANCED_CRAFTING_TABLE_ITEM = RegistryCompat.registerItem(ITEMS, "advanced_crafting_table", () -> new BlockItem(BCSiliconBlocks.ADVANCED_CRAFTING_TABLE_BLOCK.get(), RegistryCompat.itemProperties(new Item.Properties())));
    public static final BCRegistryEntry<BlockItem> PROGRAMMING_TABLE_ITEM = RegistryCompat.registerItem(ITEMS, "programming_table", () -> new BlockItem(BCSiliconBlocks.PROGRAMMING_TABLE_BLOCK.get(), RegistryCompat.itemProperties(new Item.Properties())));

    private BCSiliconItems() {
    }

    public static void registry(BCRegistryBinder bus) {
        ITEMS.register(bus);
    }

    public static List<ItemStack> getMainTabItems() {
        List<ItemStack> items = new ArrayList<>();
        items.add(LASER_BLOCK_ITEM.get().getDefaultInstance());
        items.add(ASSEMBLY_TABLE_ITEM.get().getDefaultInstance());
        items.add(ADVANCED_CRAFTING_TABLE_ITEM.get().getDefaultInstance());
        items.add(INTERGRATION_TABLE_ITEM.get().getDefaultInstance());
        items.add(CHARGING_TABLE_ITEM.get().getDefaultInstance());
        items.add(PROGRAMMING_TABLE_ITEM.get().getDefaultInstance());
        for (EnumRedstoneChipset type : EnumRedstoneChipset.values()) {
            ItemRedstoneChipset item = REDSTONE_CHIPSET_ITEMS.get(type);
            if (item != null) {
                items.add(item.getDefaultInstance());
            }
        }
        items.add(REDSTONE_CRYSTAL.get().getDefaultInstance());
        items.add(GATE_COPIER_ITEM.get().getDefaultInstance());
        return items;
    }

    public static List<ItemStack> getPlugTabItems() {
        List<ItemStack> items = new ArrayList<>();
        CreativeTabManager.addItemVariants(PLUG_GATE_ITEM.get(), items::add);
        CreativeTabManager.addItemVariants(PLUG_LENS_ITEM.get(), items::add);
        items.add(PLUG_PULSAR_ITEM.get().getDefaultInstance());
        items.add(PLUG_LIGHT_SENSOR_ITEM.get().getDefaultInstance());
        items.add(PLUG_TIMER_ITEM.get().getDefaultInstance());
        return items;
    }

    public static List<ItemStack> getFacadeTabItems() {
        if (!BCSiliconConfig.enableFacades) {
            return List.of();
        }
        if (!FacadeStateManager.isInitialized()) {
            FacadeStateManager.init();
        }
        List<ItemStack> items = new ArrayList<>();
        CreativeTabManager.addItemVariants(PLUG_FACADE_ITEM.get(), items::add);
        return items;
    }

    public static void registerItemModelProperties(ClientModelProperties.Ranges event) {
        event.register(Identifier.fromNamespaceAndPath(BCSilicon.MODID, "isempty"), GateCopierEmptyModelProperty.MAP_CODEC);
    }

    public record GateCopierEmptyModelProperty() implements RangeSelectItemModelProperty {
        public static final MapCodec<GateCopierEmptyModelProperty> MAP_CODEC = MapCodec.unit(new GateCopierEmptyModelProperty());

        public float get(ItemStack stack, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
            return ItemStackUtil.getCustomData(stack).contains(ItemGateCopier.NBT_DATA) ? 0.0F : 1.0F;
        }

        public MapCodec<GateCopierEmptyModelProperty> type() {
            return MAP_CODEC;
        }
    }
}
