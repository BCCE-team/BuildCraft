/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport;

import net.minecraft.resources.ResourceLocation;

import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pluggable.PluggableDefinition;
import buildcraft.transport.internal.pluggable.PluggableDefinition.IPluggableCreator;
import buildcraft.transport.internal.pluggable.PluggableDefinition.IPluggableNbtReader;
import buildcraft.transport.internal.pluggable.PluggableDefinition.IPluggableNetLoader;
import buildcraft.lib.BCLibRegistries;

import buildcraft.transport.plug.PluggableBlocker;
import buildcraft.transport.plug.PluggablePowerAdaptor;

public class BCTransportPlugs {

    public static PluggableDefinition blocker;
    public static PluggableDefinition powerAdaptor;

    public static void preInit() {
        blocker = register("blocker", PluggableBlocker::new);
        powerAdaptor = register("power_adaptor", PluggablePowerAdaptor::new);
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
        return def;
    }

    private static ResourceLocation idFor(String name) {
        return ResourceLocation.fromNamespaceAndPath("buildcrafttransport", name);
    }
}
