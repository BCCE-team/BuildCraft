/**
 * Copyright (c) 2011-2017, SpaceToad and the BuildCraft Team
 * http://www.mod-buildcraft.com
 *
 * The BuildCraft API is distributed under the terms of the MIT License.
 */
package buildcraft.robotics.internal.legacy.robots;

import net.minecraft.nbt.CompoundTag;
import buildcraft.lib.balance.BalanceConfig;
import buildcraft.robotics.RobotTrace;
import net.minecraft.world.item.ItemStack;

public class AIRobot {
    public final EntityRobotBase robot;

    private AIRobot delegateAI;
    private AIRobot parentAI;
    private boolean success;

    public AIRobot(EntityRobotBase robot) {
        this.robot = robot;
        this.success = true;
    }

    public void start() {
    }

    public void preempt(AIRobot ai) {
    }

    public void update() {
        // Update should always handle terminate. Some AIs only use start/end; if update is called on them,
        // terminating is the safest default.
        terminate();
    }

    public void end() {
    }

    /** Release transient claims on unload without aborting a persisted AI job. */
    public void onUnload() {
        if (delegateAI != null) delegateAI.onUnload();
    }

    /** Called when a delegate AI ends work naturally. */
    public void delegateAIEnded(AIRobot ai) {
    }

    /** Called when a delegate AI is forcibly aborted. */
    public void delegateAIAborted(AIRobot ai) {
    }

    public void writeSelfToNBT(CompoundTag nbt) {
    }

    public void loadSelfFromNBT(CompoundTag nbt) {
    }

    public boolean success() {
        return success;
    }

    protected void setSuccess(boolean success) {
        this.success = success;
    }

    public int getEnergyCost() {
        return 1;
    }

    public boolean canLoadFromNBT() {
        return false;
    }

    /** Whether the current runtime state should be written as a reloadable AI delegate.
     * By default this matches {@link #canLoadFromNBT()}, but AIs containing transient runtime closures may opt out
     * until they reach a self-contained state that can actually be reconstructed after reload. */
    public boolean shouldSaveToNBT() {
        return canLoadFromNBT();
    }

    /** Tries to receive items and returns the items left after the operation. */
    public ItemStack receiveItem(ItemStack stack) {
        return stack;
    }

    public final void terminate() {
        if (RobotTrace.enabled()) RobotTrace.event(robot, "AI_TERMINATE", "ai=%s success=%s", RobotTrace.name(this), success);
        abortDelegateAI();
        end();

        if (parentAI != null) {
            parentAI.delegateAI = null;
            parentAI.delegateAIEnded(this);
        }
    }

    public final void abort() {
        if (RobotTrace.enabled()) RobotTrace.event(robot, "AI_ABORT", "ai=%s child=%s", RobotTrace.name(this), RobotTrace.name(delegateAI));
        abortDelegateAI();

        try {
            end();

            if (parentAI != null) {
                parentAI.delegateAI = null;
                parentAI.delegateAIAborted(this);
            }
        } catch (Throwable throwable) {
            buildcraft.lib.internal.debug.BCLog.logger.error("Robot AI abort failed for " + getClass().getName(), throwable);
            delegateAI = null;

            if (parentAI != null) {
                parentAI.delegateAI = null;
            }
        }
    }

    public final void cycle() {
        try {
            if (RobotTrace.enabled()) RobotTrace.cycle(this);
            preempt(delegateAI);

            if (delegateAI != null) {
                delegateAI.cycle();
            } else {
                int cost = getEnergyCost();
                if (cost > 0) {
                    String key = robot.getBoard() == null ? "empty" : robot.getBoard().getNBTHandler().getID();
                    double multiplier = BalanceConfig.robot(key, "energy_cost_multiplier", 1.0);
                    long energy = Math.max(1L, Math.min(Integer.MAX_VALUE, Math.round(cost * multiplier)));
                    robot.getBattery().extractPower(energy);
                }
                update();
            }
        } catch (Throwable throwable) {
            buildcraft.lib.internal.debug.BCLog.logger.error("Robot AI cycle failed for " + getClass().getName(), throwable);
            abort();
        }
    }

    public final void startDelegateAI(AIRobot ai) {
        if (RobotTrace.enabled()) RobotTrace.event(robot, "AI_SWITCH", "parent=%s old=%s next=%s",
                RobotTrace.name(this), RobotTrace.name(delegateAI), RobotTrace.name(ai));
        abortDelegateAI();
        delegateAI = ai;
        ai.parentAI = this;
        delegateAI.start();
    }

    public final void abortDelegateAI() {
        if (delegateAI != null) {
            delegateAI.abort();
        }
    }

    public final AIRobot getActiveAI() {
        if (delegateAI != null) {
            return delegateAI.getActiveAI();
        }
        return this;
    }

    public final AIRobot getDelegateAI() {
        return delegateAI;
    }

    public final void writeToNBT(CompoundTag nbt) {
        nbt.putString("aiName", RobotManager.getAIRobotName(getClass()));

        CompoundTag data = new CompoundTag();
        writeSelfToNBT(data);
        nbt.put("data", data);

        if (delegateAI != null && delegateAI.shouldSaveToNBT()) {
            CompoundTag sub = new CompoundTag();
            delegateAI.writeToNBT(sub);
            nbt.put("delegateAI", sub);
        }
    }

    public final void loadFromNBT(CompoundTag nbt) {
        loadSelfFromNBT(nbt.getCompound("data"));

        if (nbt.contains("delegateAI")) {
            CompoundTag sub = nbt.getCompound("delegateAI");

            try {
                Class<?> aiRobotClass;
                if (sub.contains("class")) {
                    aiRobotClass = RobotManager.getAIRobotByLegacyClassName(sub.getString("class"));
                } else {
                    aiRobotClass = RobotManager.getAIRobotByName(sub.getString("aiName"));
                }
                if (aiRobotClass != null) {
                    AIRobot loadedDelegate = (AIRobot) aiRobotClass.getConstructor(EntityRobotBase.class).newInstance(robot);
                    if (loadedDelegate.canLoadFromNBT()) {
                        loadedDelegate.parentAI = this;
                        loadedDelegate.loadFromNBT(sub);
                        delegateAI = loadedDelegate;
                    }
                }
            } catch (Throwable throwable) {
                buildcraft.lib.internal.debug.BCLog.logger.warn("Failed to load delegate robot AI from NBT", throwable);
            }
        }
    }

    public static AIRobot loadAI(CompoundTag nbt, EntityRobotBase robot) {
        AIRobot ai = null;

        try {
            Class<?> aiRobotClass;
            if (nbt.contains("class")) {
                aiRobotClass = RobotManager.getAIRobotByLegacyClassName(nbt.getString("class"));
            } else {
                aiRobotClass = RobotManager.getAIRobotByName(nbt.getString("aiName"));
            }
            if (aiRobotClass != null) {
                AIRobot loaded = (AIRobot) aiRobotClass.getConstructor(EntityRobotBase.class).newInstance(robot);
                if (loaded.canLoadFromNBT()) {
                    loaded.loadFromNBT(nbt);
                    ai = loaded;
                }
            }
        } catch (Throwable throwable) {
            buildcraft.lib.internal.debug.BCLog.logger.warn("Failed to load robot AI from NBT", throwable);
        }

        return ai;
    }
}
