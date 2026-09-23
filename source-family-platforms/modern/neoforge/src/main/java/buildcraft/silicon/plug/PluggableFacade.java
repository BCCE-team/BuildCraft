//? source if >=1.21.1
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.plug;

import java.util.ArrayList;

import javax.annotation.Nullable;

import buildcraft.lib.internal.module.BCModules;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;
import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.transport.internal.pluggable.PluggableDefinition;
import buildcraft.transport.internal.pluggable.PluggableModelKey;
import buildcraft.lib.misc.MathUtil;
import buildcraft.lib.world.SingleBlockAccess;
import buildcraft.silicon.BCSiliconItems;
import buildcraft.silicon.client.model.key.KeyPlugFacade;
import buildcraft.transport.client.model.key.KeyPlugBlocker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import buildcraft.lib.compat.neoforge121111.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.model.data.ModelData;
import net.minecraft.core.registries.BuiltInRegistries;
import buildcraft.lib.compat.RenderCompat;
import buildcraft.lib.compat.NbtCompat;

public class PluggableFacade extends PipePluggable {

    private static final VoxelShape[] BOXES = new VoxelShape[6];

    static {
        double ll = 0 / 16.0;
        double lu = 2 / 16.0;
        double ul = 14 / 16.0;
        double uu = 16 / 16.0;

        double min = 0 / 16.0;
        double max = 16 / 16.0;

        BOXES[Direction.DOWN.ordinal()] = Shapes.box(min, ll, min, max, lu, max);
        BOXES[Direction.UP.ordinal()] = Shapes.box(min, ul, min, max, uu, max);
        BOXES[Direction.NORTH.ordinal()] = Shapes.box(min, min, ll, max, max, lu);
        BOXES[Direction.SOUTH.ordinal()] = Shapes.box(min, min, ul, max, max, uu);
        BOXES[Direction.WEST.ordinal()] = Shapes.box(ll, min, min, lu, max, max);
        BOXES[Direction.EAST.ordinal()] = Shapes.box(ul, min, min, uu, max, max);
    }

    public static final int SIZE = 2;
    public FacadeInstance states;
    public final boolean isSideSolid;
    public int activeState;

    public PluggableFacade(PluggableDefinition definition, IPipeHolder holder, Direction side, FacadeInstance states) {
        super(definition, holder, side);
        this.states = states;
        isSideSolid = states.areAllStatesSolid(side);
    }

    public PluggableFacade(PluggableDefinition def, IPipeHolder holder, Direction side, CompoundTag nbt) {
        super(def, holder, side);
        if (nbt.contains("states") && !nbt.contains("facade")) {
            ListTag tagStates = NbtCompat.getList(nbt, "states");
            if (tagStates.size() > 0) {
                boolean isHollow = NbtCompat.getBoolean(NbtCompat.getCompound(tagStates, 0), "isHollow");
                CompoundTag tagFacade = new CompoundTag();
                tagFacade.put("states", tagStates);
                tagFacade.putBoolean("isHollow", isHollow);
                nbt.put("facade", tagFacade);
            }
        }
        this.states = FacadeInstance.readFromNbt(NbtCompat.getCompound(nbt, "facade"));
        activeState = MathUtil.clamp(NbtCompat.getInt(nbt, "activeState"), 0, states.phasedStates.length - 1);
        isSideSolid = states.areAllStatesSolid(side);
    }


    @Nullable
    public DyeColor getColour() {
        if (activeState < 0 || activeState >= states.phasedStates.length) {
            return null;
        }
        return states.phasedStates[activeState].activeColour;
    }

    public boolean setColour(@Nullable DyeColor colour) {
        if (activeState >= 0 && activeState < states.phasedStates.length
            && states.phasedStates[activeState].activeColour == colour) {
            return false;
        }
        int fallback = -1;
        for (int i = 0; i < states.phasedStates.length; i++) {
            FacadePhasedState phasedState = states.phasedStates[i];
            if (phasedState.activeColour == colour) {
                activeState = i;
                return true;
            }
            if (fallback < 0 && phasedState.activeColour == null) {
                fallback = i;
            }
        }
        if (colour == null && fallback >= 0) {
            activeState = fallback;
            return true;
        }
        if (states.type == FacadeType.Basic && enableStainedGlassPhases() && setColour(colour)) {
            return true;
        }
        return false;
    }

    /** Turns a basic stained-glass facade into switchable glass-colour phases on its first brush use. */
    private boolean enableStainedGlassPhases() {
        FacadeBlockStateInfo current = states.phasedStates[activeState].stateInfo;
        if (!(current.state.getBlock() instanceof StainedGlassBlock)) {
            return false;
        }
        ArrayList<FacadePhasedState> phases = new ArrayList<>();
        phases.add(current.createPhased(null));
        for (BlockState state : FacadeStateManager.validFacadeStates.keySet()) {
            if (state.getBlock() instanceof StainedGlassBlock glass && glass.getColor() != null) {
                phases.add(FacadeStateManager.validFacadeStates.get(state).createPhased(glass.getColor()));
            }
        }
        if (phases.size() <= 1) {
            return false;
        }
        states = new FacadeInstance(phases.toArray(FacadePhasedState[]::new), states.isHollow);
        activeState = 0;
        return true;
    }

    public CompoundTag writeToNbt() {
        CompoundTag nbt = super.writeToNbt();
        nbt.put("facade", states.writeToNbt());
        nbt.putInt("activeState", activeState);
        return nbt;
    }

    @Override
    public CompoundTag writeSyncState(BCNetworkSide side) {
        return writeToNbt();
    }

    @Override
    public void readSyncState(CompoundTag nbt, BCNetworkSide side, BCPacketContext ctx) {
        states = FacadeInstance.readFromNbt(NbtCompat.getCompound(nbt, "facade"));
        activeState = MathUtil.clamp(NbtCompat.getInt(nbt, "activeState"), 0, states.phasedStates.length - 1);
    }

    // Networking

    public PluggableFacade(PluggableDefinition def, IPipeHolder holder, Direction side, FriendlyByteBuf buffer) {
        super(def, holder, side);
        states = FacadeInstance.readFromBuffer(buffer);
        isSideSolid = buffer.readBoolean();
        activeState = MathUtil.clamp(buffer.readVarInt(), 0, states.phasedStates.length - 1);
    }

    public void writeCreationPayload(FriendlyByteBuf buffer) {
        states.writeToBuffer(buffer);
        buffer.writeBoolean(isSideSolid);
        buffer.writeVarInt(activeState);
    }

    @Override
    public void writePayload(FriendlyByteBuf buffer, BCNetworkSide side) {
        // Incremental pluggable packets do not include creation data, so synchronise the painted phase explicitly.
        buffer.writeVarInt(activeState);
    }

    @Override
    public void readPayload(FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) {
        int receivedState = buffer.readVarInt();
        activeState = MathUtil.clamp(receivedState, 0, states.phasedStates.length - 1);
    }

    // Pluggable methods

    public VoxelShape getBoundingBox() {
        return BOXES[side.ordinal()];
    }

    public boolean isBlocking() {
        return !isHollow();
    }

    public boolean canBeConnected() {
        return !isHollow();
    }

    public boolean isSideSolid() {
        return isSideSolid;
    }

    public float getExplosionResistance(@Nullable Entity exploder, Explosion explosion) {
        BlockState state = states.phasedStates[activeState].stateInfo.state;
        return state.getBlock().getExplosionResistance(state, new SingleBlockAccess(state), BlockPos.ZERO, explosion);
    }

    public ItemStack getPickStack() {
        return BCSiliconItems.PLUG_FACADE_ITEM.get().createItemStack(states);
    }

    public PluggableModelKey getModelRenderKey(RenderType layer) {
        if (states.type == FacadeType.Basic) {
            FacadePhasedState facadeState = states.phasedStates[activeState];
            BlockState blockState = facadeState.stateInfo.state;
            if (isGlass(blockState)) {
                if (layer != RenderCompat.translucent()) {
                    return null;
                }
            } else if (layer == RenderCompat.translucent()) {
                return null;
            }
            return new KeyPlugFacade(layer, side, blockState, isHollow());
        } else if (layer == RenderCompat.cutout() && BCModules.TRANSPORT.isLoaded()) {
            return KeyPlugBlocker.create(side);
        }
        return null;
    }
    public static boolean isGlass(BlockState state) {
        var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (key == null) {
            return false;
        }
        String path = key.getPath();
        return path.equals("glass") || path.equals("glass_pane")
            || path.endsWith("_stained_glass") || path.endsWith("_stained_glass_pane");
    }

    public int getBlockColor(int tintIndex) {
        FacadePhasedState state = states.phasedStates[activeState];
        BlockColors colours = Minecraft.getInstance().getBlockColors();
        return colours.getColor(state.stateInfo.state, holder.getPipeWorld(), holder.getPipePos(), tintIndex);
    }

    public FacadeType getType() {
        return states.getType();
    }

    public boolean isHollow() {
        return states.isHollow();
    }

    public FacadePhasedState[] getPhasedStates() {
        return states.getPhasedStates();
    }
}
