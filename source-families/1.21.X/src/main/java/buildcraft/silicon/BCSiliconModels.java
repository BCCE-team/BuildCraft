//? source if >=1.21.1
package buildcraft.silicon;

import buildcraft.lib.platform.client.ClientModelProperties;
import buildcraft.lib.platform.client.ClientModelBaking;
import buildcraft.lib.platform.client.ClientRegistration;
import buildcraft.silicon.BCSiliconClientRenderers;
import buildcraft.transport.internal.pipe.PipeApiClient;
import buildcraft.transport.internal.pipe.PipeApiClient.IClientRegistry;
import buildcraft.transport.internal.pluggable.IPluggableStaticBaker;
import buildcraft.lib.client.model.ModelHolderStatic;
import buildcraft.lib.client.model.ModelHolderVariable;
import buildcraft.lib.client.model.ModelPluggableItem;
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.lib.client.model.plug.PlugBakerSimple;
import buildcraft.lib.expression.DefaultContexts;
import buildcraft.lib.expression.FunctionContext;
import buildcraft.lib.expression.node.value.NodeVariableBoolean;
import buildcraft.lib.expression.node.value.NodeVariableObject;
import buildcraft.lib.misc.ExpressionCompat;
import buildcraft.lib.misc.data.ModelVariableData;
import buildcraft.silicon.client.FacadeItemColours;
import buildcraft.silicon.client.model.ModelGateItem;
import buildcraft.silicon.client.model.key.KeyPlugFacade;
import buildcraft.silicon.client.model.key.KeyPlugGate;
import buildcraft.silicon.client.model.key.KeyPlugLens;
import buildcraft.silicon.client.model.key.KeyPlugLightSensor;
import buildcraft.silicon.client.model.key.KeyPlugPulsar;
import buildcraft.silicon.client.model.key.KeyPlugTimer;
import buildcraft.silicon.client.model.plug.ModelFacadeItem;
import buildcraft.silicon.client.model.plug.ModelLensItem;
import buildcraft.silicon.client.model.plug.PlugBakerFacade;
import buildcraft.silicon.client.model.plug.PlugBakerLens;
import buildcraft.silicon.client.model.plug.PlugGateBaker;
import buildcraft.silicon.client.render.PlugGateRenderer;
import buildcraft.silicon.client.render.PlugFacadeRenderer;
import buildcraft.silicon.client.render.PlugPulsarRenderer;
import buildcraft.silicon.client.render.RenderLaser;
import buildcraft.silicon.client.render.RenderProgrammingTable;
import buildcraft.silicon.gate.GateVariant;
import buildcraft.silicon.plug.PluggableFacade;
import buildcraft.silicon.plug.PluggableGate;
import buildcraft.silicon.plug.PluggablePulsar;
import buildcraft.lib.compat.mc121111.client.resources.model.BakedModel;
import buildcraft.lib.compat.mc121111.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import buildcraft.silicon.client.model.NativePluggableItemModels121111;

public final class BCSiliconModels {
    public static final ModelHolderStatic LIGHT_SENSOR;
    public static final ModelHolderStatic TIMER;

    public static final ModelHolderVariable GATE_STATIC;
    public static final ModelHolderVariable GATE_DYNAMIC;
    private static final ModelVariableData GATE_VAR_DATA_STATIC = new ModelVariableData();

    private static final ModelHolderVariable LENS;
    private static final ModelHolderVariable FILTER;
    private static final NodeVariableBoolean LENS_HAS_COLOUR;
    private static final NodeVariableObject<DyeColor> LENS_COLOUR;
    private static final NodeVariableObject<Direction> LENS_SIDE;

    public static final ModelHolderStatic PULSAR_STATIC;
    public static final ModelHolderVariable PULSAR_DYNAMIC;

    public static final IPluggableStaticBaker<KeyPlugPulsar> BAKER_PLUG_PULSAR;
    public static final IPluggableStaticBaker<KeyPlugLightSensor> BAKER_PLUG_LIGHT_SENSOR;
    public static final IPluggableStaticBaker<KeyPlugTimer> BAKER_PLUG_TIMER;

    private static boolean initialized;

    static {
        ExpressionCompat.setup();
        LIGHT_SENSOR = getStaticModel("plugs/light_sensor");
        TIMER = getStaticModel("plugs/timer");
        GATE_STATIC = getModel("plugs/gate", PluggableGate.MODEL_FUNC_CTX_STATIC);
        GATE_DYNAMIC = getModel("plugs/gate_dynamic", PluggableGate.MODEL_FUNC_CTX_DYNAMIC);
        PULSAR_STATIC = getStaticModel("plugs/pulsar_static");
        PULSAR_DYNAMIC = getModel("plugs/pulsar_dynamic", PluggablePulsar.MODEL_FUNC_CTX);

        BAKER_PLUG_PULSAR = new PlugBakerSimple<>(PULSAR_STATIC::getCutoutQuads);
        BAKER_PLUG_LIGHT_SENSOR = new PlugBakerSimple<>(LIGHT_SENSOR::getCutoutQuads);
        BAKER_PLUG_TIMER = new PlugBakerSimple<>(TIMER::getCutoutQuads);

        FunctionContext context = DefaultContexts.createWithAll();
        LENS_COLOUR = context.putVariableObject("colour", DyeColor.class);
        LENS_SIDE = context.putVariableObject("side", Direction.class);
        LENS_HAS_COLOUR = context.putVariableBoolean("has_colour");
        LENS = getModel("plugs/lens", context);
        FILTER = getModel("plugs/filter", context);
    }

    private BCSiliconModels() {
    }

    private static ModelHolderStatic getStaticModel(String path) {
        return new ModelHolderStatic(BCSilicon.MODID + ":" + path);
    }

    private static ModelHolderVariable getModel(String path, FunctionContext context) {
        return new ModelHolderVariable(BCSilicon.MODID + ":bcmodels/" + path, context);
    }

    /** Forces model-holder initialization before the first model-bake event. */
    public static void fmlPreInit() {
        // Static initialization is the registration step for BuildCraft jsonbc model holders.
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        IClientRegistry registry = PipeApiClient.registry;
        if (registry == null) {
            return;
        }
        initialized = true;

        registry.registerBaker(KeyPlugGate.class, PlugGateBaker.INSTANCE);
        registry.registerBaker(KeyPlugPulsar.class, BAKER_PLUG_PULSAR);
        registry.registerBaker(KeyPlugLightSensor.class, BAKER_PLUG_LIGHT_SENSOR);
        registry.registerBaker(KeyPlugTimer.class, BAKER_PLUG_TIMER);
        registry.registerBaker(KeyPlugLens.class, PlugBakerLens.INSTANCE);
        registry.registerBaker(KeyPlugFacade.class, PlugBakerFacade.INSTANCE);
        registry.registerRenderer(PluggableGate.class, PlugGateRenderer.INSTANCE);
        // Glass facades cannot stay in the static chunk model on 1.21.11: the native terrain path discards
        // their vertex alpha. This renderer also reads the live selected facade phase after a paint operation.
        registry.registerRenderer(PluggableFacade.class, PlugFacadeRenderer.INSTANCE);
        registry.registerRenderer(PluggablePulsar.class, PlugPulsarRenderer.INSTANCE);
    }

    public static void onBlockEntityRender(ClientRegistration.Renderers event) {
        BCSiliconClientRenderers.register(event);
    }

    public static void registerItemColor(ClientModelProperties.Tints event) {
    }
    public static void onModelBake(ClientModelBaking.Models event) {
        init();
        NativePluggableItemModels121111.install(event);
        clearAtlasDependentCaches();
    }

    /** Clears baked quads whose UV coordinates belong to the previous block-atlas generation. */
    public static void clearAtlasDependentCaches() {
        PlugGateBaker.onModelBake();
        PlugBakerLens.onModelBake();
        ModelGateItem.onModelBake();
        ModelLensItem.onModelBake();
        ModelFacadeItem.onModelBake();
        PlugFacadeRenderer.onModelBake();
        PlugPulsarRenderer.onModelBake();
        PlugGateRenderer.onModelBake();
    }

    private static void putModel(ClientModelBaking.Models event, String value, BakedModel model) {
    }

    public static MutableQuad[] getGateStaticQuads(Direction side, GateVariant variant) {
        PluggableGate.setClientModelVariables(side, variant);
        if (GATE_VAR_DATA_STATIC.hasNoNodes()) {
            GATE_VAR_DATA_STATIC.setNodes(GATE_STATIC.createTickableNodes());
        }
        GATE_VAR_DATA_STATIC.refresh();
        return GATE_STATIC.getCutoutQuads();
    }

    private static void setupLensVariables(ModelHolderVariable model, Direction side, DyeColor colour) {
        LENS_COLOUR.value = colour == null ? DyeColor.WHITE : colour;
        LENS_SIDE.value = side;
        LENS_HAS_COLOUR.value = colour != null;
        ModelVariableData data = new ModelVariableData();
        data.setNodes(model.createTickableNodes());
        data.tick();
        data.refresh();
    }

    public static MutableQuad[] getLensCutoutQuads(Direction side, DyeColor colour) {
        setupLensVariables(LENS, side, colour);
        return LENS.getCutoutQuads();
    }

    public static MutableQuad[] getLensTranslucentQuads(Direction side, DyeColor colour) {
        setupLensVariables(LENS, side, colour);
        return LENS.getTranslucentQuads();
    }

    public static MutableQuad[] getFilterCutoutQuads(Direction side, DyeColor colour) {
        setupLensVariables(FILTER, side, colour);
        return FILTER.getCutoutQuads();
    }

    public static MutableQuad[] getFilterTranslucentQuads(Direction side, DyeColor colour) {
        setupLensVariables(FILTER, side, colour);
        return FILTER.getTranslucentQuads();
    }
}
