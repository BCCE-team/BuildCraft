/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.pipe;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import buildcraft.lib.internal.core.InvalidInputDataException;
import buildcraft.api.v2.pipe.PipeType;
import buildcraft.transport.internal.pipe.IItemPipe;
import buildcraft.transport.internal.pipe.IPipeRegistry;
import buildcraft.transport.internal.pipe.PipeDefinition;
import buildcraft.transport.item.ItemPipeHolder;
import buildcraft.transport.api2.PipeTypeBridge;
import com.google.common.collect.ImmutableList;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

public enum PipeRegistry implements IPipeRegistry {
    INSTANCE;

    private final Map<ResourceLocation, PipeDefinition> definitions = new HashMap<>();
    private final Map<ResourceLocation, PipeDefinition> aliases = new HashMap<>();
    private final Map<PipeDefinition, IItemPipe> pipeItems = new IdentityHashMap<>();

    @Override
    public void registerPipe(PipeDefinition definition) {
        PipeDefinition previous = definitions.putIfAbsent(definition.identifier, definition);
        if (previous != null && previous != definition) {
            throw new IllegalStateException("Duplicate pipe definition id: " + definition.identifier);
        }
        PipeTypeBridge.ensureRegistered(definition);
    }

    /** Materializes an API2 pipe variant into the legacy-compatible runtime implementation. */
    public synchronized PipeDefinition ensureRuntimeDefinition(PipeType type) {
        if (type == null) throw new NullPointerException("type");
        PipeDefinition existing = definitions.get(type.id());
        if (existing != null) {
            existing.setApiType(type);
            return existing;
        }
        ResourceLocation baseId = type.archetypeId().orElseThrow(() -> new IllegalArgumentException(
            "Pipe type " + type.id() + " has no runtime archetype; create it with PipeType.variant(...)"
        ));
        PipeDefinition base = getDefinition(baseId);
        if (base == null) {
            throw new IllegalStateException("Unknown runtime pipe archetype " + baseId + " for " + type.id());
        }
        PipeDefinition definition = PipeDefinition.apiVariant(type, base);
        registerPipe(definition);
        return definition;
    }

    @Override
    public IItemPipe registryItemForPipe(Supplier<? extends Block> block, PipeDefinition definition) {
        ItemPipeHolder item = new ItemPipeHolder(definition);
        if (!definitions.values().contains(definition)) {
            registerPipe(definition);
        }
        pipeItems.put(definition, item);
        return item;
    }
    
    @Override
    public ItemPipeHolder createItemForPipe(PipeDefinition definition) {
        ItemPipeHolder item = new ItemPipeHolder(definition);
//        helper.addForcedItem(item);
        if (definitions.values().contains(definition)) {
            setItemForPipe(definition, item);
        }
        return item;
    }
    
    @Override
    public void setItemForPipe(PipeDefinition definition, @Nullable IItemPipe item) {
        if (definition == null) {
            throw new NullPointerException("definition");
        }
        if (item == null) {
        	pipeItems.remove(definition);
        } else {
        	pipeItems.put(definition, item);
        }
    }

    @Override
    public IItemPipe getItemForPipe(PipeDefinition definition) {
        return pipeItems.get(definition);
    }

    /** Registers a read-only compatibility alias for pipe definition IDs stored in world NBT. */
    public void registerAlias(String identifier, PipeDefinition target) {
        if (identifier == null) throw new NullPointerException("identifier");
        if (target == null) throw new NullPointerException("target");
        ResourceLocation id = ResourceLocation.parse(identifier);
        PipeDefinition canonical = definitions.get(id);
        if (canonical != null && canonical != target) {
            throw new IllegalStateException("Pipe alias shadows a registered definition: " + id);
        }
        PipeDefinition previous = aliases.putIfAbsent(id, target);
        if (previous != null && previous != target) {
            throw new IllegalStateException("Conflicting pipe alias " + id);
        }
        PipeTypeBridge.registerAlias(id, target);
    }

    @Override
    @Nullable
    public PipeDefinition getDefinition(ResourceLocation identifier) {
        PipeDefinition definition = definitions.get(identifier);
        return definition != null ? definition : aliases.get(identifier);
    }

    @Nonnull
    public PipeDefinition loadDefinition(String identifier) throws InvalidInputDataException {
        PipeDefinition def = getDefinition(ResourceLocation.parse(identifier));
        if (def == null) {
            throw new InvalidInputDataException("Unknown pipe definition " + identifier);
        }
        return def;
    }

    @Override
    public Iterable<PipeDefinition> getAllRegisteredPipes() {
        return ImmutableList.copyOf(definitions.values());
    }

	public Map<PipeDefinition, IItemPipe> getPipeItemsMap() {
		return pipeItems;
	}

}
