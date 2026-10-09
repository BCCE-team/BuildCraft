/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.registry;

import java.util.HashMap;
import java.util.Map;

import buildcraft.transport.internal.pluggable.IPluggableRegistry;
import buildcraft.transport.internal.pluggable.PluggableDefinition;
import buildcraft.transport.api2.PipeAttachmentBridge;

import net.minecraft.resources.Identifier;

public enum PluggableRegistry implements IPluggableRegistry {
    INSTANCE;

    private final Map<Identifier, PluggableDefinition> registered = new HashMap<>();

    public void register(Identifier id, PluggableDefinition definition) {
        registered.put(id, definition);
        PipeAttachmentBridge.ensureRegistered(id, definition);
    }

    public PluggableDefinition getDefinition(Identifier identifier) {
        return registered.get(identifier);
    }
}
