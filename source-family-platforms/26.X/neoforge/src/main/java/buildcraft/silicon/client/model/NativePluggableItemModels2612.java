package buildcraft.silicon.client.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import buildcraft.lib.client.model.ModelItemSimple;
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.lib.compat.RenderCompat;
import buildcraft.lib.compat.minecraft.model.NativeItemModelBuilder;
import buildcraft.lib.misc.StackUtil;
import buildcraft.lib.platform.client.ClientModelBaking;
import buildcraft.silicon.BCSiliconItems;
import buildcraft.silicon.BCSiliconModels;
import buildcraft.silicon.client.FacadeItemColours;
import buildcraft.silicon.client.model.key.KeyPlugFacade;
import buildcraft.silicon.client.model.plug.PlugBakerFacade;
import buildcraft.silicon.gate.GateVariant;
import buildcraft.silicon.item.ItemPluggableFacade;
import buildcraft.silicon.item.ItemPluggableGate;
import buildcraft.silicon.item.ItemPluggableLens;
import buildcraft.silicon.item.ItemPluggableLens.LensData;
import buildcraft.silicon.plug.FacadeInstance;
import buildcraft.silicon.plug.FacadePhasedState;
import buildcraft.silicon.plug.PluggableFacade;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.item.CompositeModel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Native 26.1.2 item models for the dynamic BuildCraft plugs.
 *
 * <p>Legacy BakedModel overrides are not routed through the
 * target model map because it stores {@link ItemModel}s. These models retain
 * the mutable BuildCraft geometry, cached by gameplay variant, and turn it into
 * the normal vanilla item render layers.</p>
 */
public final class NativePluggableItemModels2612 {
    private static final Map<GateVariant, ItemModel> GATES = new HashMap<>();
    private static final Map<Integer, ItemModel> LENSES = new HashMap<>();
    private static final Map<KeyPlugFacade, ItemModel> FACADES = new HashMap<>();

    private NativePluggableItemModels2612() {
    }

    public static void install(ClientModelBaking.Models event) {
        GATES.clear();
        LENSES.clear();
        FACADES.clear();
        put(event, BCSiliconItems.PLUG_GATE_ITEM.get(), new GateItemModel());
        put(event, BCSiliconItems.PLUG_LENS_ITEM.get(), new LensItemModel());
        put(event, BCSiliconItems.PLUG_FACADE_ITEM.get(), new FacadeItemModel());
        put(event, BCSiliconItems.PLUG_PULSAR_ITEM.get(), new StaticPlugItemModel(
            BCSiliconModels.PULSAR_STATIC.getCutoutQuads(), BCSiliconModels.PULSAR_DYNAMIC.getCutoutQuads(),
            ModelItemSimple.TRANSFORM_PLUG_AS_ITEM_BIGGER));
        put(event, BCSiliconItems.PLUG_LIGHT_SENSOR_ITEM.get(), new StaticPlugItemModel(
            BCSiliconModels.LIGHT_SENSOR.getCutoutQuads(), new MutableQuad[0], ModelItemSimple.TRANSFORM_PLUG_AS_ITEM));
        put(event, BCSiliconItems.PLUG_TIMER_ITEM.get(), new StaticPlugItemModel(
            BCSiliconModels.TIMER.getCutoutQuads(), new MutableQuad[0], ModelItemSimple.TRANSFORM_PLUG_AS_ITEM));
    }

    private static void put(ClientModelBaking.Models event, net.minecraft.world.item.Item item, ItemModel model) {
        event.itemStackModels().put(BuiltInRegistries.ITEM.getKey(item), model);
    }

    private static ItemModel itemLayer(List<MutableQuad> quads, ItemTransforms transforms, boolean translucent) {
        return NativeItemModelBuilder.layer(quads, transforms,
            translucent ? Sheets.translucentBlockItemSheet() : Sheets.cutoutBlockItemSheet(), true);
    }

    private static ItemModel composite(List<MutableQuad> cutout, List<MutableQuad> translucent, ItemTransforms transforms) {
        ItemModel opaque = itemLayer(cutout, transforms, false);
        if (translucent.isEmpty()) {
            return opaque;
        }
        return new CompositeModel(List.of(opaque, itemLayer(translucent, transforms, true)));
    }

    private abstract static class DynamicItemModel implements ItemModel {
        @Override
        public final void update(ItemStackRenderState state, ItemStack stack, ItemModelResolver resolver,
            ItemDisplayContext displayContext, ClientLevel level, ItemOwner owner, int seed) {
            model(stack).update(state, stack, resolver, displayContext, level, owner, seed);
        }

        abstract ItemModel model(ItemStack stack);
    }

    private static final class GateItemModel extends DynamicItemModel {
        @Override
        ItemModel model(ItemStack stack) {
            GateVariant variant = ItemPluggableGate.getVariant(StackUtil.asNonNull(stack));
            return GATES.computeIfAbsent(variant, NativePluggableItemModels2612::bakeGate);
        }
    }

    private static ItemModel bakeGate(GateVariant variant) {
        List<MutableQuad> cutout = new ArrayList<>();
        for (MutableQuad quad : BCSiliconModels.getGateStaticQuads(Direction.WEST, variant)) {
            cutout.add(new MutableQuad(quad));
        }
        for (MutableQuad quad : BCSiliconModels.GATE_DYNAMIC.getCutoutQuads()) {
            cutout.add(new MutableQuad(quad));
        }
        return itemLayer(cutout, ModelItemSimple.TRANSFORM_PLUG_AS_ITEM_BIGGER, false);
    }

    private static final class LensItemModel extends DynamicItemModel {
        @Override
        ItemModel model(ItemStack stack) {
            LensData data = ItemPluggableLens.getData(stack);
            return LENSES.computeIfAbsent(data.getItemDamage(), NativePluggableItemModels2612::bakeLens);
        }
    }

    private static ItemModel bakeLens(int damage) {
        LensData data = new LensData(damage);
        Direction side = Direction.WEST;
        MutableQuad[] cutout = data.isFilter
            ? BCSiliconModels.getFilterCutoutQuads(side, data.colour)
            : BCSiliconModels.getLensCutoutQuads(side, data.colour);
        MutableQuad[] translucent = data.isFilter
            ? BCSiliconModels.getFilterTranslucentQuads(side, data.colour)
            : BCSiliconModels.getLensTranslucentQuads(side, data.colour);
        return composite(copy(cutout), copy(translucent), ModelItemSimple.TRANSFORM_PLUG_AS_ITEM);
    }

    private static final class FacadeItemModel extends DynamicItemModel {
        @Override
        ItemModel model(ItemStack stack) {
            FacadeInstance instance = ItemPluggableFacade.getStates(stack);
            FacadePhasedState current = instance.getCurrentStateForStack();
            boolean glass = PluggableFacade.isGlass(current.stateInfo.state);
            KeyPlugFacade key = new KeyPlugFacade(glass ? RenderCompat.translucent() : RenderCompat.cutout(),
                Direction.WEST, current.stateInfo.state, instance.isHollow);
            return FACADES.computeIfAbsent(key, ignored -> bakeFacade(key, stack, glass));
        }
    }

    private static ItemModel bakeFacade(KeyPlugFacade key, ItemStack stack, boolean glass) {
        List<MutableQuad> quads = PlugBakerFacade.INSTANCE.bakeForKey(key, false);
        for (MutableQuad quad : quads) {
            int tint = quad.getTint();
            if (tint >= 0) {
                quad.colouri(FacadeItemColours.INSTANCE.getColor(stack, tint));
                quad.setTint(-1);
            }
        }
        return itemLayer(quads, ModelItemSimple.TRANSFORM_PLUG_AS_BLOCK, glass);
    }

    private static final class StaticPlugItemModel extends DynamicItemModel {
        private final ItemModel model;

        StaticPlugItemModel(MutableQuad[] cutout, List<MutableQuad> translucent, ItemTransforms transforms) {
            this(copy(cutout), translucent, transforms);
        }

        StaticPlugItemModel(MutableQuad[] cutout, MutableQuad[] translucent, ItemTransforms transforms) {
            this(copy(cutout), copy(translucent), transforms);
        }

        StaticPlugItemModel(List<MutableQuad> cutout, List<MutableQuad> translucent, ItemTransforms transforms) {
            model = composite(cutout, translucent, transforms);
        }

        @Override
        ItemModel model(ItemStack stack) {
            return model;
        }
    }

    private static List<MutableQuad> copy(MutableQuad[] source) {
        List<MutableQuad> copied = new ArrayList<>(source.length);
        for (MutableQuad quad : source) {
            copied.add(new MutableQuad(quad));
        }
        return copied;
    }
}
