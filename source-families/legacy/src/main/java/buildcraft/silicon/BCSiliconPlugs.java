package buildcraft.silicon;

import buildcraft.lib.internal.module.BCModules;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pluggable.PluggableDefinition;
import buildcraft.transport.internal.pluggable.PluggableDefinition.IPluggableCreator;
import buildcraft.transport.internal.pluggable.PluggableDefinition.IPluggableNbtReader;
import buildcraft.transport.internal.pluggable.PluggableDefinition.IPluggableNetLoader;
import buildcraft.lib.BCLibRegistries;
import buildcraft.silicon.plug.PluggableFacade;
import buildcraft.silicon.plug.PluggableGate;
import buildcraft.silicon.plug.PluggableLens;
import buildcraft.silicon.plug.PluggableLightSensor;
import buildcraft.silicon.plug.PluggablePulsar;
import buildcraft.silicon.plug.PluggableTimer;
import net.minecraft.resources.ResourceLocation;

public class BCSiliconPlugs {
	
    public static PluggableDefinition gate;
    public static PluggableDefinition lens;
    public static PluggableDefinition pulsar;
    public static PluggableDefinition lightSensor;
    public static PluggableDefinition timer;
    public static PluggableDefinition facade;

    public static void preInit() {
        gate = register("gate", PluggableGate::new, PluggableGate::new);
        lens = register("lens", PluggableLens::new, PluggableLens::new);
        pulsar = register("pulsar", PluggablePulsar::new, PluggablePulsar::new);
        lightSensor = register("daylight_sensor", PluggableLightSensor::new);
        timer = register("timer", PluggableTimer::new);
        facade = register("facade", PluggableFacade::new, PluggableFacade::new);
    }

    private static PluggableDefinition register(String name, IPluggableCreator creator) {
        return register(new PluggableDefinition(idFor(name), creator));
    }

    private static PluggableDefinition register(String name, IPluggableNbtReader reader, IPluggableNetLoader loader) {
        return register(new PluggableDefinition(idFor(name), reader, loader));
    }

    private static PluggableDefinition register(PluggableDefinition def) {
        // Built-in pluggables are always registered by this module.
        BCLibRegistries.initApiRegistries();
        PipeApi.pluggableRegistry.register(def);

        // Register the transport-namespace alias required by legacy saves and network payloads.
        String modId = BCModules.TRANSPORT.getModId();
        PipeApi.pluggableRegistry.register(new ResourceLocation(modId, def.identifier.getPath()), def);
        return def;
    }

    private static ResourceLocation idFor(String name) {
        return new ResourceLocation("buildcraftsilicon", name);
    }

}
