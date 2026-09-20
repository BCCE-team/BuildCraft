package buildcraft.silicon;

import buildcraft.lib.platform.client.ClientItemColours;
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
import buildcraft.silicon.client.render.PlugPulsarRenderer;
import buildcraft.silicon.client.render.RenderLaser;
import buildcraft.silicon.client.render.RenderProgrammingTable;
import buildcraft.silicon.gate.GateVariant;
import buildcraft.silicon.plug.PluggableGate;
import buildcraft.silicon.plug.PluggablePulsar;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;

public class BCSiliconModels {
    public static final ModelHolderStatic LIGHT_SENSOR;
    public static final ModelHolderStatic TIMER;

    public static final ModelHolderVariable GATE_STATIC;
    public static final ModelHolderVariable GATE_DYNAMIC;
    private static final ModelVariableData GATE_VAR_DATA_STATIC = new ModelVariableData();

    private static final ModelHolderVariable LENS, FILTER;
    private static final NodeVariableBoolean LENS_HAS_COLOUR;
    private static final NodeVariableObject<DyeColor> LENS_COLOUR;
    private static final NodeVariableObject<Direction> LENS_SIDE;

    public static final ModelHolderStatic PULSAR_STATIC;
    public static final ModelHolderVariable PULSAR_DYNAMIC;

    public static final IPluggableStaticBaker<KeyPlugPulsar> BAKER_PLUG_PULSAR;
    public static final IPluggableStaticBaker<KeyPlugLightSensor> BAKER_PLUG_LIGHT_SENSOR;
    public static final IPluggableStaticBaker<KeyPlugTimer> BAKER_PLUG_TIMER;

    static {
        LIGHT_SENSOR = getStaticModel("plugs/light_sensor");
        TIMER = getStaticModel("plugs/timer");
        GATE_STATIC = getModel("plugs/gate", PluggableGate.MODEL_FUNC_CTX_STATIC);
        GATE_DYNAMIC = getModel("plugs/gate_dynamic", PluggableGate.MODEL_FUNC_CTX_DYNAMIC);
        PULSAR_STATIC = getStaticModel("plugs/pulsar_static");
        PULSAR_DYNAMIC = getModel("plugs/pulsar_dynamic", PluggablePulsar.MODEL_FUNC_CTX);

        BAKER_PLUG_PULSAR = new PlugBakerSimple<>(PULSAR_STATIC::getCutoutQuads);
        BAKER_PLUG_LIGHT_SENSOR = new PlugBakerSimple<>(LIGHT_SENSOR::getCutoutQuads);
        BAKER_PLUG_TIMER = new PlugBakerSimple<>(TIMER::getCutoutQuads);

        {
            FunctionContext fnCtx = DefaultContexts.createWithAll();
            LENS_COLOUR = fnCtx.putVariableObject("colour", DyeColor.class);
            LENS_SIDE = fnCtx.putVariableObject("side", Direction.class);
            LENS_HAS_COLOUR = fnCtx.putVariableBoolean("has_colour");
            LENS = getModel("plugs/lens", fnCtx);
            FILTER = getModel("plugs/filter", fnCtx);
        }
    }

    private static ModelHolderStatic getStaticModel(String str) {
        return new ModelHolderStatic("buildcraftsilicon:" + str);
    }

    private static ModelHolderVariable getModel(String str, FunctionContext fnCtx) {
        return new ModelHolderVariable("buildcraftsilicon:bcmodels/" + str, fnCtx);
    }


    public static void init() {
        IClientRegistry pipeRegistryClient = PipeApiClient.registry;
        if (pipeRegistryClient != null) {
            pipeRegistryClient.registerBaker(KeyPlugGate.class, PlugGateBaker.INSTANCE);
            pipeRegistryClient.registerBaker(KeyPlugPulsar.class, BAKER_PLUG_PULSAR);
            pipeRegistryClient.registerBaker(KeyPlugLightSensor.class, BAKER_PLUG_LIGHT_SENSOR);
            pipeRegistryClient.registerBaker(KeyPlugTimer.class, BAKER_PLUG_TIMER);
            pipeRegistryClient.registerBaker(KeyPlugLens.class, PlugBakerLens.INSTANCE);
            pipeRegistryClient.registerBaker(KeyPlugFacade.class, PlugBakerFacade.INSTANCE);

            pipeRegistryClient.registerRenderer(PluggableGate.class, PlugGateRenderer.INSTANCE);
            pipeRegistryClient.registerRenderer(PluggablePulsar.class, PlugPulsarRenderer.INSTANCE);
        }
    }
    
    public static void onBlockEntityRender(ClientRegistration.Renderers event) {
        BCSiliconClientRenderers.register(event);
    }

    public static void RegisterItemColor(ClientItemColours event) {
    	event.register(FacadeItemColours.INSTANCE, BCSiliconItems.PLUG_FACADE_ITEM.get());
    }
    
    public static void onModelBake(ClientModelBaking.Completed event) {
        putModel(event, "plug/gate#inventory", ModelGateItem.INSTANCE);
        putModel(event, "plug/lens#inventory", ModelLensItem.INSTANCE);
        // The pulsar inventory model needs both the static base and the dynamic overlay. The plain JSON parent only
        // renders pulsar_static, which leaves the item looking like a dark/empty square.
        PluggablePulsar.setModelVariablesForItem();
        putModel(event, "plug/pulsar#inventory",
            new ModelPluggableItem(PULSAR_STATIC.getCutoutQuads(), PULSAR_DYNAMIC.getCutoutQuads()));
        putModel(event, "plug/facade#inventory", ModelFacadeItem.INSTANCE);

        clearAtlasDependentCaches();
    }

    /** Clears baked quads whose UV coordinates belong to the previous block-atlas generation. */
    public static void clearAtlasDependentCaches() {
        PlugGateBaker.onModelBake();
        PlugBakerLens.onModelBake();
        ModelGateItem.onModelBake();
        ModelLensItem.onModelBake();
        ModelFacadeItem.onModelBake();
        PlugPulsarRenderer.onModelBake();
        PlugGateRenderer.onModelBake();
    }

    private static void putModel(ClientModelBaking.Completed event, String str, BakedModel model) {
        event.getModels().put(new ModelResourceLocation(BCSilicon.MODID + ":" + str), model);
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
        ModelVariableData varData = new ModelVariableData();
        varData.setNodes(model.createTickableNodes());
        varData.tick();
        varData.refresh();
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
