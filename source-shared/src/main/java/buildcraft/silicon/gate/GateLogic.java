/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.gate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

import buildcraft.lib.internal.module.BCModules;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.signal.BuildCraftSignalChannels;
import buildcraft.api.v2.gate.GateControl;
import buildcraft.api.v2.gate.GateProgram;
import buildcraft.api.v2.gate.GateRule;
import buildcraft.api.v2.gate.GateView;
import buildcraft.api.v2.statement.StatementKind;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.core.InvalidInputDataException;
import buildcraft.transport.internal.gate.IGate;
import buildcraft.lib.internal.statement.IActionExternal;
import buildcraft.lib.internal.statement.IActionInternal;
import buildcraft.lib.internal.statement.IActionInternalSided;
import buildcraft.lib.internal.statement.IStatement;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.internal.statement.ITriggerExternal;
import buildcraft.lib.internal.statement.ITriggerInternal;
import buildcraft.lib.internal.statement.ITriggerInternalSided;
import buildcraft.lib.internal.statement.StatementManager;
import buildcraft.lib.internal.statement.StatementSlot;
import buildcraft.lib.internal.statement.containers.IRedstoneStatementContainer;
import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.internal.pipe.PipeEvent;
import buildcraft.transport.internal.pipe.PipeEventActionActivate;
import buildcraft.lib.misc.MessageUtil;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.internal.statement.api2.StatementApi2Bridge;
import buildcraft.lib.statement.ActionWrapper;
import buildcraft.lib.statement.ActionWrapper.ActionWrapperExternal;
import buildcraft.lib.statement.ActionWrapper.ActionWrapperInternal;
import buildcraft.lib.statement.ActionWrapper.ActionWrapperInternalSided;
import buildcraft.lib.statement.FullStatement;
import buildcraft.lib.statement.FullStatement.IStatementChangeListener;
import buildcraft.lib.statement.TriggerWrapper;
import buildcraft.lib.statement.TriggerWrapper.TriggerWrapperExternal;
import buildcraft.lib.statement.TriggerWrapper.TriggerWrapperInternal;
import buildcraft.lib.statement.TriggerWrapper.TriggerWrapperInternalSided;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.silicon.plug.PluggableGate;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BlockEntity;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class GateLogic implements IGate, IRedstoneStatementContainer, GateView, GateControl {

    protected static final IdAllocator ID_ALLOC = new IdAllocator("GateLogic");

    /** Sent when any of {@link #triggerOn}, {@link #actionOn}, or {@link #connections} change. */
    public static final int NET_ID_RESOLVE = ID_ALLOC.allocId("RESOLVE");

    /** Sent when a single statement changed. */
    public static final int NET_ID_CHANGE = ID_ALLOC.allocId("STATEMENT_CHANGE");

    /** Sent when {@link #isOn} is true. */
    public static final int NET_ID_GLOWING = ID_ALLOC.allocId("GLOWING");

    /** Sent when {@link #isOn} is false. */
    public static final int NET_ID_DARK = ID_ALLOC.allocId("DARK");

    /* Ideally we wouldn't use a pluggable, but we would use a more generic way of looking at a gate -- perhaps one
     * that's embedded in a robot, or in a minecart. */
    @Deprecated
    public final PluggableGate pluggable;
    public final GateVariant variant;
    public final StatementPair[] statements;

    public final List<StatementSlot> activeActions = new ArrayList<>();

    /** Used to determine if gate logic should go across several trigger/action pairs. */
    public final boolean[] connections;

    /** Used at the client to display if an action is activated (or would be activated if its not null), or a trigger is
     * currently triggering. */
    public final boolean[] triggerOn, actionOn;

    public int redstoneOutput, redstoneOutputSide;

    private final EnumSet<DyeColor> wireBroadcasts;
    private boolean signalOutputsSynced;

    /** Used on the client to determine if this gate should glow or not. */
    public boolean isOn;

    public GateLogic(PluggableGate pluggable, GateVariant variant) {
        this.pluggable = pluggable;
        this.variant = variant;
        statements = new StatementPair[variant.numSlots];
        for (int s = 0; s < variant.numSlots; s++) {
            statements[s] = new StatementPair(s);
        }

        connections = new boolean[variant.numSlots - 1];
        triggerOn = new boolean[variant.numSlots];
        actionOn = new boolean[variant.numSlots];

        wireBroadcasts = EnumSet.noneOf(DyeColor.class);
        signalOutputsSynced = false;
    }

    private void markGateDirty() {
        BlockEntity tile = getPipeHolder().getPipeTile();
        if (tile == null || tile.getLevel() == null || tile.getLevel().isClientSide) {
            return;
        }

        tile.setChanged();
        if (tile instanceof TileBC_Neptune bcTile) {
            bcTile.markChunkDirty();
        }
    }

    // Saving + Loading

    public GateLogic(PluggableGate pluggable, CompoundTag nbt) {
        this(pluggable, new GateVariant(nbt.getCompound("variant")));

        readConfigData(nbt);

        wireBroadcasts.addAll(NBTUtilBC.readEnumSet(nbt.get("wireBroadcasts"), DyeColor.class));
    }

    public void readConfigData(CompoundTag nbt) {
        short c = nbt.getShort("connections");
        for (int i = 0; i < connections.length; i++) {
            connections[i] = ((c >>> i) & 1) == 1;
        }

        for (int i = 0; i < statements.length; i++) {
            String tName = "trigger[" + i + "]";
            String aName = "action[" + i + "]";
            // Legacy
            if (nbt.contains(tName, Tag.TAG_STRING)) {
                CompoundTag nbt2 = new CompoundTag();
                nbt2.putString("kind", nbt.getString(tName));
                nbt2.putByte("side", nbt.getByte(tName + ".side"));
                nbt.put(tName, nbt2);
            }
            // Legacy
            if (nbt.contains(aName, Tag.TAG_STRING)) {
                CompoundTag nbt2 = new CompoundTag();
                nbt2.putString("kind", nbt.getString(aName));
                nbt2.putByte("side", nbt.getByte(aName + ".side"));
                nbt.put(aName, nbt2);
            }

            statements[i].trigger.readFromNbt(nbt.getCompound(tName));
            statements[i].action.readFromNbt(nbt.getCompound(aName));
        }
    }

    public CompoundTag writeToNbt() {
        CompoundTag nbt = new CompoundTag();
        nbt.put("variant", variant.writeToNBT());

        short c = 0;
        for (int i = 0; i < connections.length; i++) {
            if (connections[i]) {
                c |= 1 << i;
            }
        }
        nbt.putShort("connections", c);

        for (int s = 0; s < statements.length; s++) {
            if (statements[s].trigger.get() != null) {
                nbt.put("trigger[" + s + "]", statements[s].trigger.writeToNbt());
            }
            if (statements[s].action.get() != null) {
                nbt.put("action[" + s + "]", statements[s].action.writeToNbt());
            }
        }
        nbt.put("wireBroadcasts", NBTUtilBC.writeEnumSet(wireBroadcasts, DyeColor.class));
        return nbt;
    }

    // Networking

    public GateLogic(PluggableGate pluggable, FriendlyByteBuf buffer) {
        this(pluggable, new GateVariant(buffer));

        MessageUtil.readBooleanArray(buffer, triggerOn);
        MessageUtil.readBooleanArray(buffer, actionOn);
        MessageUtil.readBooleanArray(buffer, connections);
        try {
            for (StatementPair pair : statements) {
                pair.trigger.readFromBuffer(buffer);
                pair.action.readFromBuffer(buffer);
            }
        } catch (IOException io) {
            throw new Error(io);
        }
        boolean on = false;
        for (int i = 0; i < statements.length; i++) {
            boolean b = actionOn[i];
            on |= b && (statements[i].action.get() != null);
        }
        isOn = on;

    }

    public void writeCreationToBuf(FriendlyByteBuf buffer) {
        variant.writeToBuffer(buffer);

        MessageUtil.writeBooleanArray(buffer, triggerOn);
        MessageUtil.writeBooleanArray(buffer, actionOn);
        MessageUtil.writeBooleanArray(buffer, connections);

        for (StatementPair pair : statements) {
            pair.trigger.writeToBuffer(buffer);
            pair.action.writeToBuffer(buffer);
        }
    }

    public void readPayload(FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        int id = buffer.readUnsignedByte();
        if (id == NET_ID_CHANGE) {
            boolean isAction = buffer.readBoolean();
            int slot = buffer.readUnsignedByte();
            if (slot < 0 || slot >= statements.length) {
                throw new InvalidInputDataException(
                    "Slot index out of range! (" + slot + ", must be within " + statements.length + ")");
            }
            StatementPair s = statements[slot];
            (isAction ? s.action : s.trigger).readFromBuffer(buffer);
            if (side == BCNetworkSide.SERVER) {
                markGateDirty();
                sendStatementUpdate(isAction, slot);
            }
            BCLog.d("rec "+(isAction ? s.action : s.trigger).writeToNbt());
            return;
        }
        if (side == BCNetworkSide.CLIENT) {
            if (id == NET_ID_RESOLVE) {
                MessageUtil.readBooleanArray(buffer, triggerOn);
                MessageUtil.readBooleanArray(buffer, actionOn);
                MessageUtil.readBooleanArray(buffer, connections);
            } else if (id == NET_ID_GLOWING) {
                isOn = true;
            } else if (id == NET_ID_DARK) {
                isOn = false;
            } else {
                BCLog.logger.warn("Unknown ID " + ID_ALLOC.getNameFor(id));
            }
        } else {
            BCLog.logger.warn("Unknown side " + side + " + ID " + ID_ALLOC.getNameFor(id));
        }
    }

    public void sendStatementUpdate(boolean isAction, int slot) {
        pluggable.sendGuiMessage((buffer) -> {
            buffer.writeByte(NET_ID_CHANGE);
            buffer.writeBoolean(isAction);
            buffer.writeByte(slot);
            StatementPair s = statements[slot];
            (isAction ? s.action : s.trigger).writeToBuffer(buffer);
            BCLog.d("send "+(isAction ? s.action : s.trigger).writeToNbt());
        });
    }

    public void sendResolveData() {
        pluggable.sendGuiMessage((buffer) -> {
            buffer.writeByte(NET_ID_RESOLVE);
            MessageUtil.writeBooleanArray(buffer, triggerOn);
            MessageUtil.writeBooleanArray(buffer, actionOn);
            MessageUtil.writeBooleanArray(buffer, connections);
        });
    }

    public void sendIsOn() {
        pluggable.sendMessage(buffer -> {
            buffer.writeByte(isOn ? NET_ID_GLOWING : NET_ID_DARK);
        });
    }

    // IGate

    @Override
    public Direction getSide() {
        return pluggable.side;
    }

    @Override
    public BlockEntity getTile() {
        return getPipeHolder().getPipeTile();
    }

    @Override
    public BlockEntity getNeighbourTile(Direction side) {
        return getPipeHolder().getNeighbourTile(side);
    }

    @Override
    public IPipeHolder getPipeHolder() {
        return pluggable.holder;
    }

    // API2 gate view/control

    @Override
    public net.minecraft.core.BlockPos position() {
        return getPipeHolder().getPipePos();
    }

    @Override
    public Direction side() {
        return getSide();
    }

    @Override
    public GateProgram program() {
        List<GateRule> rules = new ArrayList<>(statements.length);
        for (StatementPair pair : statements) {
            TriggerWrapper trigger = pair.trigger.get();
            ActionWrapper action = pair.action.get();
            buildcraft.api.v2.statement.StatementSlot apiTrigger = trigger == null ? null
                : new buildcraft.api.v2.statement.StatementSlot(
                    StatementKind.TRIGGER, java.util.Objects.requireNonNull(net.minecraft.resources.ResourceLocation.tryParse(trigger.getUniqueTag())),
                    trigger.getSourcePart().face, StatementApi2Bridge.toApiParameters(trigger.getDelegate(), pair.trigger.getParameters())
                );
            buildcraft.api.v2.statement.StatementSlot apiAction = action == null ? null
                : new buildcraft.api.v2.statement.StatementSlot(
                    StatementKind.ACTION, java.util.Objects.requireNonNull(net.minecraft.resources.ResourceLocation.tryParse(action.getUniqueTag())),
                    action.getSourcePart().face, StatementApi2Bridge.toApiParameters(action.getDelegate(), pair.action.getParameters())
                );
            rules.add(new GateRule(apiTrigger, apiAction));
        }
        List<Boolean> links = new ArrayList<>(connections.length);
        for (boolean connection : connections) links.add(connection);
        return new GateProgram(rules, links);
    }

    @Override
    public Optional<GateControl> control() {
        return Optional.of(this);
    }

    @Override
    public boolean setProgram(GateProgram program, OperationMode mode) {
        if (program == null || mode == null || program.rules().size() > statements.length
            || program.connections().size() > connections.length) {
            return false;
        }

        TriggerWrapper[] triggers = new TriggerWrapper[statements.length];
        ActionWrapper[] actions = new ActionWrapper[statements.length];
        buildcraft.lib.internal.statement.IStatementParameter[][] triggerParams =
            new buildcraft.lib.internal.statement.IStatementParameter[statements.length][];
        buildcraft.lib.internal.statement.IStatementParameter[][] actionParams =
            new buildcraft.lib.internal.statement.IStatementParameter[statements.length][];

        for (int i = 0; i < program.rules().size(); i++) {
            GateRule rule = program.rules().get(i);
            if (rule.trigger() != null) {
                if (rule.trigger().kind() != StatementKind.TRIGGER) return false;
                triggers[i] = StatementApi2Bridge.toLegacyTrigger(rule.trigger());
                if (triggers[i] == null || !statements[i].trigger.canSet(triggers[i])) return false;
                triggerParams[i] = StatementApi2Bridge.toLegacyParameters(triggers[i].getDelegate(), rule.trigger().parameters());
            }
            if (rule.action() != null) {
                if (rule.action().kind() != StatementKind.ACTION) return false;
                actions[i] = StatementApi2Bridge.toLegacyAction(rule.action());
                if (actions[i] == null || !statements[i].action.canSet(actions[i])) return false;
                actionParams[i] = StatementApi2Bridge.toLegacyParameters(actions[i].getDelegate(), rule.action().parameters());
            }
        }

        if (mode == OperationMode.SIMULATE) return true;

        for (int i = 0; i < statements.length; i++) {
            setApiTrigger(i, triggers[i], triggerParams[i]);
            setApiAction(i, actions[i], actionParams[i]);
        }
        Arrays.fill(connections, false);
        for (int i = 0; i < program.connections().size(); i++) connections[i] = program.connections().get(i);
        markGateDirty();
        for (int i = 0; i < statements.length; i++) {
            sendStatementUpdate(false, i);
            sendStatementUpdate(true, i);
        }
        sendResolveData();
        return true;
    }

    private void setApiTrigger(int index, TriggerWrapper trigger, buildcraft.lib.internal.statement.IStatementParameter[] params) {
        FullStatement<TriggerWrapper> full = statements[index].trigger;
        full.set(trigger);
        for (int p = 0; p < full.getParamCount(); p++) full.set(p, params != null && p < params.length ? params[p] : null);
    }

    private void setApiAction(int index, ActionWrapper action, buildcraft.lib.internal.statement.IStatementParameter[] params) {
        FullStatement<ActionWrapper> full = statements[index].action;
        full.set(action);
        for (int p = 0; p < full.getParamCount(); p++) full.set(p, params != null && p < params.length ? params[p] : null);
    }

    @Override
    public List<IStatement> getTriggers() {
        List<IStatement> list = new ArrayList<>(statements.length);
        for (StatementPair pair : statements) {
            TriggerWrapper e = pair.trigger.get();
            list.add(e == null ? e : e.getDelegate());
        }
        return list;
    }

    @Override
    public List<IStatement> getActions() {
        List<IStatement> list = new ArrayList<>(statements.length);
        for (StatementPair pair : statements) {
            ActionWrapper e = pair.action.get();
            list.add(e == null ? e : e.getDelegate());
        }
        return list;
    }

    @Override
    public List<StatementSlot> getActiveActions() {
        return activeActions;
    }

    @Override
    public List<IStatementParameter> getTriggerParameters(int slot) {
        return Arrays.asList(statements[slot].trigger.getParameters());
    }

    @Override
    public List<IStatementParameter> getActionParameters(int slot) {
        return Arrays.asList(statements[slot].action.getParameters());
    }

    @Override
    public int getRedstoneInput(Direction side) {
        return getPipeHolder().getRedstoneInput(side);
    }

    @Override
    public boolean setRedstoneOutput(Direction side, int value) {
        return getPipeHolder().setRedstoneOutput(side, value);
    }

    // Wire related

    @Override
    public void emitSignal(DyeColor colour) {
        wireBroadcasts.add(colour);
    }

    // Internal Logic


    private static final class PendingGateAction {
        final int actionIndex;
        final ActionWrapper action;
        final IStatementParameter[] parameters;

        PendingGateAction(int actionIndex, ActionWrapper action, IStatementParameter[] parameters) {
            this.actionIndex = actionIndex;
            this.action = action;
            this.parameters = parameters;
        }
    }

    /** @return True if the gate GUI should be split into 2 separate columns. Needed on the server for the values of
     *         {@link #connections} */
    public boolean isSplitInTwo() {
        return variant.numSlots > 4;
    }

    public void resolveActions() {
        int groupCount = 0;
        int groupActive = 0;

        boolean prevIsOn = isOn;
        isOn = false;
        boolean[] prevTriggers = Arrays.copyOf(triggerOn, triggerOn.length);
        boolean[] prevActions = Arrays.copyOf(actionOn, actionOn.length);

        Arrays.fill(triggerOn, false);
        Arrays.fill(actionOn, false);

        activeActions.clear();
        List<PendingGateAction> pendingActions = new ArrayList<>();

        EnumSet<DyeColor> previousBroadcasts = EnumSet.copyOf(wireBroadcasts);
        wireBroadcasts.clear();

        for (int triggerIndex = 0; triggerIndex < statements.length; triggerIndex++) {
            StatementPair pair = statements[triggerIndex];
            TriggerWrapper trigger = pair.trigger.get();
            groupCount++;
            if (trigger != null) {
                IStatementParameter[] params = new IStatementParameter[pair.trigger.getParamCount()];
                for (int p = 0; p < pair.trigger.getParamCount(); p++) {
                    params[p] = pair.trigger.getParamRef(p).get();
                }
                if (trigger.isTriggerActive(this, params)) {
                    groupActive++;
                    triggerOn[triggerIndex] = true;
                }
            }
            if (connections.length == triggerIndex || !connections[triggerIndex]) {
                boolean allActionsActive;
                if (variant.logic == EnumGateLogic.AND) {
                    allActionsActive = groupActive == groupCount;
                } else {
                    allActionsActive = groupActive > 0;
                }
                for (int i = groupCount - 1; i >= 0; i--) {
                    int actionIndex = triggerIndex - i;
                    StatementPair fullAction = statements[actionIndex];
                    ActionWrapper action = fullAction.action.get();
                    if (action == null) {
                        actionOn[actionIndex] = allActionsActive;
                    } else if (allActionsActive) {
                        pendingActions.add(new PendingGateAction(
                            actionIndex, action, fullAction.action.getParameters().clone()
                        ));
                    } else {
                        action.actionDeactivated(this, fullAction.action.getParameters());
                    }
                }
                groupActive = 0;
                groupCount = 0;
            }
        }

        // A gate is ordered: when multiple active rows write the same setting, the last row wins. Apply only the
        // final value to avoid intermediate mutations/events (for example EAST immediately followed by WEST on a
        // directional pipe action). Different source parts remain independent targets.
        for (int i = 0; i < pendingActions.size(); i++) {
            PendingGateAction pending = pendingActions.get(i);
            boolean overridden = false;
            for (int j = i + 1; j < pendingActions.size(); j++) {
                if (pending.action.targetsSameSetting(pendingActions.get(j).action)) {
                    overridden = true;
                    break;
                }
            }
            if (overridden) {
                pending.action.actionDeactivated(this, pending.parameters);
                continue;
            }

            actionOn[pending.actionIndex] = true;
            isOn = true;
            StatementSlot slot = new StatementSlot();
            slot.statement = pending.action.getDelegate();
            slot.parameters = pending.parameters;
            slot.part = pending.action.getSourcePart();
            activeActions.add(slot);
            pending.action.actionActivate(this, slot.parameters);
            PipeEvent evt = new PipeEventActionActivate(
                getPipeHolder(), pending.action.getDelegate(), slot.parameters, pending.action.getSourcePart()
            );
            getPipeHolder().fireEvent(evt);
        }

        if (BCModules.TRANSPORT.isLoaded() && !getPipeHolder().getPipeWorld().isClientSide
            && (!signalOutputsSynced || !previousBroadcasts.equals(wireBroadcasts))) {
            var signalService = BuildCraftApi.service(BuildCraftServices.SIGNALS);
            for (DyeColor colour : DyeColor.values()) {
                boolean before = previousBroadcasts.contains(colour);
                boolean now = wireBroadcasts.contains(colour);
                if (signalOutputsSynced && before == now) continue;
                signalService.port(
                    getPipeHolder().getPipeWorld(),
                    getPipeHolder().getPipePos(),
                    getSide(),
                    BuildCraftSignalChannels.id(colour)
                ).ifPresent(port -> {
                    @SuppressWarnings("unchecked")
                    buildcraft.api.v2.signal.SignalPort<Boolean> booleanPort =
                        (buildcraft.api.v2.signal.SignalPort<Boolean>) port;
                    booleanPort.publish(now, OperationMode.EXECUTE);
                });
            }
            signalOutputsSynced = true;
        }

        if (isOn != prevIsOn) {
            sendIsOn();
        }

        if (!Arrays.equals(prevTriggers, triggerOn) || !Arrays.equals(prevActions, actionOn)) {
            sendResolveData();
        }
    }

    public void onTick() {
        if (getPipeHolder().getPipeWorld().isClientSide()) {
            return;
        }
        resolveActions();
    }

    public SortedSet<TriggerWrapper> getAllValidTriggers() {
        SortedSet<TriggerWrapper> set = new TreeSet<>();
        for (ITriggerInternal trigger : StatementManager.getInternalTriggers(this)) {
            if (isValidTrigger(trigger)) {
                set.add(new TriggerWrapperInternal(trigger));
            }
        }
        for (Direction face : Direction.values()) {
            for (ITriggerInternalSided trigger : StatementManager.getInternalSidedTriggers(this, face)) {
                if (isValidTrigger(trigger)) {
                    set.add(new TriggerWrapperInternalSided(trigger, face));
                }
            }
            BlockEntity neighbour = getNeighbourTile(face);
            if (neighbour != null) {
                for (ITriggerExternal trigger : StatementManager.getExternalTriggers(face, neighbour)) {
                    if (isValidTrigger(trigger)) {
                        set.add(new TriggerWrapperExternal(trigger, face));
                    }
                }
            }
        }
        return set;
    }

    public SortedSet<ActionWrapper> getAllValidActions() {
        SortedSet<ActionWrapper> set = new TreeSet<>();
        for (IActionInternal trigger : StatementManager.getInternalActions(this)) {
            if (isValidAction(trigger)) {
                set.add(new ActionWrapperInternal(trigger));
            }
        }
        for (Direction face : Direction.values()) {
            for (IActionInternalSided trigger : StatementManager.getInternalSidedActions(this, face)) {
                if (isValidAction(trigger)) {
                    set.add(new ActionWrapperInternalSided(trigger, face));
                }
            }
            BlockEntity neighbour = getNeighbourTile(face);
            if (neighbour != null) {
                for (IActionExternal trigger : StatementManager.getExternalActions(face, neighbour)) {
                    if (isValidAction(trigger)) {
                        set.add(new ActionWrapperExternal(trigger, face));
                    }
                }
            }
        }
        return set;
    }

    public boolean isValidTrigger(IStatement statement) {
        return statement != null && statement.minParameters() <= variant.numTriggerArgs;
    }

    public boolean isValidAction(IStatement statement) {
        return statement != null && statement.minParameters() <= variant.numActionArgs;
    }

    public class StatementPair {
        public final FullStatement<TriggerWrapper> trigger;
        public final FullStatement<ActionWrapper> action;

        public StatementPair(int index) {
            IStatementChangeListener tChange = (s, i) -> {
                sendStatementUpdate(false, index);
            };
            IStatementChangeListener aChange = (s, i) -> {
                sendStatementUpdate(true, index);
            };
            trigger = new FullStatement<>(TriggerType.INSTANCE, variant.numTriggerArgs, tChange);
            action = new FullStatement<>(ActionType.INSTANCE, variant.numActionArgs, aChange);
        }
    }
}
