package buildcraft.silicon.client.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import buildcraft.lib.client.model.ModelItemSimple;
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.lib.compat.RenderCompat;
import buildcraft.lib.compat.minecraft.model.NativeItemModelBuilder;
import buildcraft.lib.internal.module.BCModules;
import buildcraft.lib.misc.StackUtil;
import buildcraft.silicon.BCSiliconItems;
import buildcraft.silicon.BCSiliconModels;
import buildcraft.silicon.client.FacadeItemColours;
import buildcraft.silicon.client.LensItemTintSource;
import net.minecraft.client.color.item.ItemTintSource;
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
import buildcraft.silicon.plug.PluggablePulsar;
import buildcraft.transport.BCTransportModels;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.item.CompositeModel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
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
    private static volatile ItemModel PULSAR_ITEM;

    private NativePluggableItemModels2612() {
    }

    /** Native Fabric 26.3 model modifier. Called after the vanilla model is baked. */
    public static ItemModel replace(ItemModel original, Identifier itemId) {
        Item item = BuiltInRegistries.ITEM.getValue(itemId);
        if (item == BCSiliconItems.PLUG_GATE_ITEM.get()) return new GateItemModel();
        if (item == BCSiliconItems.PLUG_LENS_ITEM.get()) return new LensItemModel();
        if (item == BCSiliconItems.PLUG_FACADE_ITEM.get()) return new FacadeItemModel();
        // Pulsar geometry is jsonbc-backed and becomes available only after the model holders finish baking.
        // Bake lazily on first item render so the inventory model cannot freeze as an empty quad set during reload.
        if (item == BCSiliconItems.PLUG_PULSAR_ITEM.get()) return new PulsarItemModel();
        // Timer and Light Sensor remain ordinary static JSON item models.
        return original;
    }

    /** Clears cached atlas UVs before Fabric bakes a new resource generation. */
    public static void clearCaches() {
        GATES.clear();
        LENSES.clear();
        FACADES.clear();
        PULSAR_ITEM = null;
    }

    /** Compatibility hook for model-bake adapters which expose a mutable model map. */
    public static void install(buildcraft.lib.platform.client.ClientModelBaking.Models event) {
        clearCaches();
        event.itemStackModels().put(BuiltInRegistries.ITEM.getKey(BCSiliconItems.PLUG_GATE_ITEM.get()), new GateItemModel());
        event.itemStackModels().put(BuiltInRegistries.ITEM.getKey(BCSiliconItems.PLUG_LENS_ITEM.get()), new LensItemModel());
        event.itemStackModels().put(BuiltInRegistries.ITEM.getKey(BCSiliconItems.PLUG_FACADE_ITEM.get()), new FacadeItemModel());
        event.itemStackModels().put(BuiltInRegistries.ITEM.getKey(BCSiliconItems.PLUG_PULSAR_ITEM.get()), new PulsarItemModel());
    }

    private static ItemModel itemLayer(List<MutableQuad> quads, ItemTransforms transforms, boolean translucent) {
        return itemLayer(quads, transforms, translucent, List.of());
    }

    private static ItemModel itemLayer(List<MutableQuad> quads, ItemTransforms transforms,
        boolean translucent, List<ItemTintSource> tints) {
        // Plug items use gui_light = front in their vanilla json models; keep the native 26.1.2 path
        // consistent so they do not render noticeably darker than the legacy baked-model version.
        return NativeItemModelBuilder.layer(quads, transforms,
            translucent ? Sheets.translucentBlockItemSheet() : Sheets.cutoutBlockItemSheet(), false, tints);
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
        // Vanilla's BakedQuad has no per-vertex colour. Tint just the glass
        // insert, not the metal frame, using the same legacy dye palette.
        List<MutableQuad> glass = copy(translucent);
        for (MutableQuad quad : glass) quad.setTint(0);
        ItemModel frame = itemLayer(copy(cutout), ModelItemSimple.TRANSFORM_PLUG_AS_ITEM, false);
        if (glass.isEmpty()) return frame;
        return new CompositeModel(List.of(frame, itemLayer(glass,
            ModelItemSimple.TRANSFORM_PLUG_AS_ITEM, true, List.of(new LensItemTintSource()))));
    }

    private static final class PulsarItemModel extends DynamicItemModel {
        @Override
        ItemModel model(ItemStack stack) {
            ItemModel model = PULSAR_ITEM;
            if (model == null) {
                synchronized (NativePluggableItemModels2612.class) {
                    model = PULSAR_ITEM;
                    if (model == null) {
                        PluggablePulsar.setModelVariablesForItem();
                        model = composite(
                            copy(BCSiliconModels.PULSAR_STATIC.getCutoutQuads()),
                            copy(BCSiliconModels.PULSAR_DYNAMIC.getCutoutQuads()),
                            ModelItemSimple.TRANSFORM_PLUG_AS_ITEM
                        );
                        PULSAR_ITEM = model;
                    }
                }
            }
            return model;
        }
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
        List<MutableQuad> quads = new ArrayList<>();
        for (MutableQuad source : PlugBakerFacade.INSTANCE.bakeForKey(key, false)) {
            quads.add(new MutableQuad(source));
        }
        for (MutableQuad quad : quads) {
            int tint = quad.getTint();
            if (tint >= 0) {
                // PlugBakerFacade packs the source tint index together with the facade side for world rendering.
                // Item rendering has no pipe side lookup, so recover the original block tint before resolving colour.
                int sourceTint = tint / Direction.values().length;
                quad.colouri(FacadeItemColours.INSTANCE.getColor(stack, sourceTint));
                quad.setTint(-1);
            }
        }
        // Match the legacy facade item model: a solid non-hollow facade has a pipe blocker behind the thin facade
        // shell. Without it the inventory/hand model exposes the otherwise invisible inside/back face.
        if (BCModules.TRANSPORT.isLoaded() && key.state.isSolidRender() && !key.isHollow) {
            for (MutableQuad blocker : BCTransportModels.BLOCKER.getCutoutQuads()) {
                quads.add(new MutableQuad(blocker));
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
