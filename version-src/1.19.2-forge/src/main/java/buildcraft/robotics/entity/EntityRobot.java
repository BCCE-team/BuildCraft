package buildcraft.robotics.entity;

import buildcraft.api.v2.energy.MjAmount;
import buildcraft.lib.internal.mj.MjFormatting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;

import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.lib.internal.area.IZone;
import buildcraft.lib.internal.mj.MjBattery;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.api.v2.robot.RobotEventKind;
import buildcraft.robotics.internal.api2.RobotEventSupport;
import buildcraft.robotics.internal.legacy.robots.IRobotRegistry;
import buildcraft.robotics.internal.legacy.robots.RobotManager;
import buildcraft.lib.internal.statement.StatementSlot;
import buildcraft.lib.misc.WrenchUtil;
import buildcraft.lib.BCLibConfig;
import buildcraft.lib.fluid.FluidCompatRegistry;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.BCRoboticsBoards.BoardEntry;
import buildcraft.robotics.BCRoboticsEntities;
import buildcraft.robotics.DockingStationPipe;
import buildcraft.robotics.ai.AIRobotMain;
import buildcraft.robotics.ai.AIRobotReturnToLostStation;
import buildcraft.robotics.ai.AIRobotShutdown;
import buildcraft.robotics.ai.AIRobotSleep;
import buildcraft.robotics.statements.ActionRobotWorkInArea;
import buildcraft.robotics.item.ItemRobot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.network.NetworkHooks;

public class EntityRobot extends EntityRobotBase implements IEntityAdditionalSpawnData {
    private static final Set<Item> BLACKLISTED_ITEMS_FOR_UPDATE = new HashSet<>();
    private static final double ROBOT_HALF_SIZE = 0.25D;
    /** Number of internal robot charge units that represent one BuildCraft Minecraft Joule. */
    public static final long ENERGY_UNITS_PER_MJ = 100L;
    private static final EntityDataAccessor<Boolean> ROBOT_ASLEEP = SynchedEntityData.defineId(EntityRobot.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> ROBOT_AIM_YAW = SynchedEntityData.defineId(EntityRobot.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> ROBOT_AIM_PITCH = SynchedEntityData.defineId(EntityRobot.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> ROBOT_DOCKED = SynchedEntityData.defineId(EntityRobot.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> ROBOT_DOCK_X = SynchedEntityData.defineId(EntityRobot.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ROBOT_DOCK_Y = SynchedEntityData.defineId(EntityRobot.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ROBOT_DOCK_Z = SynchedEntityData.defineId(EntityRobot.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ROBOT_DOCK_SIDE = SynchedEntityData.defineId(EntityRobot.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ROBOT_ENERGY = SynchedEntityData.defineId(EntityRobot.class, EntityDataSerializers.INT);
    /**
     * BuildCraft robot coordinates use the centre of the 0.5x0.5x0.5 cube, while modern LivingEntity positions are
     * normally feet-based. A centred bounding box is therefore restored after every snap or direct movement to prevent
     * one-tick sinking and keep docked robots aligned to the station.
     */

    public static final int MAX_WEARABLES = 8;
    public static final int TRANSFER_INV_SLOTS = 4;
    public static final int MAX_FLUID = 4_000;

    private final net.minecraft.core.NonNullList<ItemStack> inventory = net.minecraft.core.NonNullList.withSize(TRANSFER_INV_SLOTS, ItemStack.EMPTY);
    private final List<ItemStack> wearables = new ArrayList<>();
    private final WeakHashMap<Entity, Long> unreachableEntities = new WeakHashMap<>();
    private final MjBattery battery = new MjBattery(MAX_ENERGY);

    private BoardEntry boardEntry = BCRoboticsBoards.EMPTY;
    private RedstoneBoardRobot board;
    private AIRobotMain mainAI;
    private long robotId = NULL_ROBOT_ID;
    /** Player whose identity this robot uses for Forge protection events. */
    @Nullable
    private GameProfile owner;

    private DockingStation linkedStation;
    private BlockIndex linkedStationIndex;
    private Direction linkedStationSide;

    // Kept independently from linkedStationIndex. Station objects can temporarily disappear while chunks or pipe
    // pluggables are being reloaded, and an actually removed station is still the safest place to return a lost robot.
    private BlockIndex lastMainStationIndex;
    private Direction lastMainStationSide;
    /** True when a player explicitly freed the home station; prevents automatic re-taking of that same station. */
    private boolean mainStationReleasedManually;

    private DockingStation dockingStation;
    private BlockIndex dockingStationIndex;
    private Direction dockingStationSide;

    private ItemStack itemInUse = ItemStack.EMPTY;
    private boolean itemActive;
    private float aimYaw;
    private float aimPitch;
    private FluidStack tank = FluidStack.EMPTY;
    private boolean firstUpdateDone;
    private boolean convertingToItems;
    private int ticksCharging;
    /** Fractional charge accumulator, stored as (microJoules * ENERGY_UNITS_PER_MJ) % MjAmount.MICRO_MJ_PER_MJ. */
    private long chargeRemainder;

    public EntityRobot(EntityType<? extends EntityRobot> type, Level level) {
        super(type, level);
        setNoGravity(true);
        noPhysics = true;
    }

    public EntityRobot(Level level, BoardEntry boardEntry) {
        this(BCRoboticsEntities.ROBOT.get(), level);
        setBoard(boardEntry);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return createLivingAttributes()
                .add(Attributes.MAX_HEALTH, 1.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.0D)
                .add(Attributes.FOLLOW_RANGE, 4.0D);
    }

    /**
     * Converts the robotics internal charge unit into BuildCraft power units.
     *
     * <p>The robot battery intentionally stores legacy robotics charge units for AI costs and balancing. Display code and
     * {@link buildcraft.lib.internal.mj.IMjReadable} implementations must expose real BuildCraft micro-MJ instead.</p>
     */
    public static long robotEnergyToMicroMj(long robotEnergy) {
        if (robotEnergy <= 0L) {
            return 0L;
        }
        long maxBeforeMultiply = Long.MAX_VALUE / MjAmount.MICRO_MJ_PER_MJ;
        if (robotEnergy > maxBeforeMultiply) {
            return Long.MAX_VALUE;
        }
        return robotEnergy * MjAmount.MICRO_MJ_PER_MJ / ENERGY_UNITS_PER_MJ;
    }

    /** Formats internal robot charge as player-facing BuildCraft Minecraft Joules, without appending the MJ suffix. */
    public static String formatRobotEnergy(long robotEnergy) {
        if (BCLibConfig.hidePowerValues) {
            return LocaleUtil.localize("buildcraft.value.hidden");
        }
        long clamped = Math.max(0L, Math.min((long) MAX_ENERGY, robotEnergy));
        return MjFormatting.formatMicroMj(robotEnergyToMicroMj(clamped));
    }

    public void setBoard(BoardEntry entry) {
        this.boardEntry = entry == null ? BCRoboticsBoards.EMPTY : entry;
        this.board = this.boardEntry.nbt().create(this);
        if (!level.isClientSide && mainAI == null) {
            mainAI = new AIRobotMain(this);
            mainAI.start();
        }
    }

    public BoardEntry getBoardEntry() {
        return boardEntry;
    }

    public net.minecraft.resources.ResourceLocation getTexture() {
        return boardEntry == null ? BCRoboticsBoards.EMPTY.robotTextureLocation() : boardEntry.robotTextureLocation();
    }

    /**
     * Sleeping/shutdown robots render only their base texture with the dark centre, while working robots render
     * the red/cyan active overlay. Inventory rendering is always active.
     */
    public boolean isAsleepForRendering() {
        if (level.isClientSide) {
            return entityData.get(ROBOT_ASLEEP);
        }
        return isAsleepOrShutdownOnServer();
    }

    /** Allow gate statements to override the robot's main AI (e.g., goto station, wake up). */
    public void setMainAIOverride(buildcraft.robotics.internal.legacy.robots.AIRobot ai) {
        if (level.isClientSide) {
            return;
        }
        if (mainAI == null) {
            mainAI = new AIRobotMain(this);
            mainAI.start();
        }
        mainAI.setOverridingAI(ai);
    }

    @Override
    public AIRobot getActiveAI() {
        return mainAI == null ? null : mainAI.getActiveAI();
    }

    private boolean isAsleepOrShutdownOnServer() {
        AIRobot activeAI = mainAI == null ? null : mainAI.getActiveAI();
        return activeAI instanceof AIRobotSleep || activeAI instanceof AIRobotShutdown;
    }

    private boolean isShutdownActive() {
        return mainAI != null && mainAI.getActiveAI() instanceof AIRobotShutdown;
    }

    public long getMjPowerRequestedForCharging() {
        long requestedEnergy = Math.max(0L, battery.getCapacity() - battery.getStored());
        if (requestedEnergy <= 0) {
            return 0;
        }
        long numeratorNeeded = requestedEnergy * MjAmount.MICRO_MJ_PER_MJ - Math.min(chargeRemainder, MjAmount.MICRO_MJ_PER_MJ - 1);
        return ceilDiv(numeratorNeeded, ENERGY_UNITS_PER_MJ);
    }

    public long receivePower(long maxReceive, FluidAction action) {
        if (maxReceive <= 0) {
            return maxReceive;
        }
        long requestedEnergy = Math.max(0L, battery.getCapacity() - battery.getStored());
        if (requestedEnergy <= 0) {
            if (action.execute()) {
                chargeRemainder = 0;
            }
            return maxReceive;
        }

        long accepted = Math.min(maxReceive, getMjPowerRequestedForCharging());
        if (accepted > 0 && action.execute()) {
            long numerator = accepted * ENERGY_UNITS_PER_MJ + chargeRemainder;
            long energyReceived = numerator / MjAmount.MICRO_MJ_PER_MJ;
            chargeRemainder = numerator % MjAmount.MICRO_MJ_PER_MJ;

            if (energyReceived > 0) {
                long added = Math.min(energyReceived, requestedEnergy);
                battery.addPower(added, FluidAction.EXECUTE);
                if (battery.getStored() >= battery.getCapacity()) {
                    chargeRemainder = 0;
                }
                if (added > 0 && ticksCharging <= 25) {
                    ticksCharging += 5;
                }
            }
        }
        return maxReceive - accepted;
    }

    private static long ceilDiv(long value, long divisor) {
        if (value <= 0) {
            return 0;
        }
        return (value + divisor - 1) / divisor;
    }

    public void setEnergy(int energy) {
        int clampedEnergy = Math.max(0, Math.min(MAX_ENERGY, energy));
        battery.extractAll();
        battery.addPower(clampedEnergy, IFluidHandler.FluidAction.EXECUTE);
        entityData.set(ROBOT_ENERGY, clampedEnergy);
    }

    public int getEnergyForRendering() {
        if (level.isClientSide) {
            return Mth.clamp(entityData.get(ROBOT_ENERGY), 0, MAX_ENERGY);
        }
        return getEnergy();
    }

    public void setUniqueRobotId(long id) {
        this.robotId = id;
        IRobotRegistry registry = getRegistry();
        if (registry != null) {
            registry.registerRobot(this);
        }
    }

    public void setOwner(@Nullable GameProfile profile) {
        owner = isRealOwner(profile) ? profile : null;
    }

    /**
     * Returns the identity used by this robot for block/tool/entity interaction events. Robots without a saved
     * owner inherit the owner of their main robot-station pipe when possible.
     */
    public GameProfile getOwnerProfile() {
        if (!isRealOwner(owner)) {
            DockingStation station = getLinkedStation();
            if (station instanceof DockingStationPipe pipeStation && pipeStation.getPipe() != null) {
                GameProfile stationOwner = pipeStation.getPipe().getKnownOwner();
                if (isRealOwner(stationOwner)) {
                    owner = stationOwner;
                }
            }
        }
        return isRealOwner(owner) ? owner : FakePlayerProvider.NULL_PROFILE;
    }

    private static boolean isRealOwner(@Nullable GameProfile profile) {
        return profile != null
                && !FakePlayerProvider.NULL_PROFILE.getId().equals(profile.getId())
                && (profile.getId() != null || profile.getName() != null && !profile.getName().isBlank());
    }

    public ItemStack asItemStack() {
        return ItemRobot.createRobotStack(boardEntry, getEnergy());
    }

    /** Read-only view used by gate robot filters, matching the BC7/BC8 wearable-filter behaviour. */
    public List<ItemStack> getWearables() {
        return Collections.unmodifiableList(wearables);
    }

    @Override
    public void tick() {
        if (!firstUpdateDone) {
            firstUpdate();
            firstUpdateDone = true;
        }

        if (ticksCharging > 0) {
            ticksCharging--;
        }

        setNoGravity(true);
        noPhysics = !isShutdownActive();

        if (!level.isClientSide) {
            if (robotId != NULL_ROBOT_ID && getRegistry() != null) {
                getRegistry().registerRobot(this);
            }
            resolveStations();
            validateStationReferences();
            if (mainAI == null) {
                mainAI = new AIRobotMain(this);
                mainAI.start();
            }
            validateLinkedStation();
            // Station availability must not gate the AI cycle. Lost-station recovery owns station loss explicitly,
            // while the normal cycle remains responsible for updating movement every tick.
            mainAI.cycle();
            entityData.set(ROBOT_ASLEEP, isAsleepOrShutdownOnServer());
            entityData.set(ROBOT_AIM_YAW, aimYaw);
            entityData.set(ROBOT_AIM_PITCH, aimPitch);
            if (dockingStation != null) {
                syncDockingStationToClient(dockingStation);
            } else {
                clearDockingStationSync();
            }
        } else {
            aimYaw = entityData.get(ROBOT_AIM_YAW);
            aimPitch = entityData.get(ROBOT_AIM_PITCH);
            updateRotationYaw(60.0F);
        }

        if (dockingStation != null) {
            snapToStation(dockingStation);
        } else if (!snapToSyncedDockingStation()) {
            refreshRobotBoundingBox();
        }

        updateItem(itemInUse, 0, true);
        for (int i = 0; i < inventory.size(); i++) {
            updateItem(inventory.get(i), i, false);
        }
        battery.tick(level, position());
        if (!level.isClientSide) {
            int energy = getEnergy();
            if (entityData.get(ROBOT_ENERGY) != energy) {
                entityData.set(ROBOT_ENERGY, energy);
            }
        }

        super.tick();

        if (dockingStation != null) {
            snapToStation(dockingStation);
        } else if (!snapToSyncedDockingStation()) {
            refreshRobotBoundingBox();
        }

        if (!level.isClientSide && getY() < level.getMinBuildHeight() - 128) {
            convertToItems();
        }
    }

    private void firstUpdate() {
        if (!level.isClientSide) {
            if (mainAI == null) {
                mainAI = new AIRobotMain(this);
                mainAI.start();
            }
            if (getRegistry() != null) {
                getRegistry().registerRobot(this);
            }
        }
    }

    private void resolveStations() {
        IRobotRegistry registry = getRegistry();
        if (registry == null) return;
        if (linkedStation == null && linkedStationIndex != null) {
            linkedStation = registry.getStation(linkedStationIndex.toBlockPos(), linkedStationSide);
        }
        if (dockingStation == null && dockingStationIndex != null) {
            dockingStation = registry.getStation(dockingStationIndex.toBlockPos(), dockingStationSide);
        }
    }

    private void validateStationReferences() {
        IRobotRegistry registry = getRegistry();

        DockingStation currentDockingStation = dockingStation;
        if (currentDockingStation != null && isStationGone(currentDockingStation)) {
            BlockIndex oldIndex = copyIndex(dockingStationIndex != null ? dockingStationIndex : currentDockingStation.index());
            Direction oldSide = dockingStationSide != null ? dockingStationSide : currentDockingStation.side();
            currentDockingStation.unsafeRelease(this);

            DockingStation replacement = getStation(registry, oldIndex, oldSide);
            if (replacement != null
                    && (replacement.robotIdTaking() == NULL_ROBOT_ID || replacement.robotIdTaking() == robotId)
                    && replacement.take(this)) {
                dockingStation = replacement;
                dockingStationIndex = copyIndex(replacement.index());
                dockingStationSide = replacement.side();
                syncDockingStationToClient(replacement);
            } else if (dockingStation == currentDockingStation) {
                dockingStation = null;
                dockingStationIndex = null;
                dockingStationSide = null;
                clearDockingStationSync();
            }
        }

        DockingStation currentLinkedStation = linkedStation;
        if (currentLinkedStation != null && isStationGone(currentLinkedStation)) {
            BlockIndex oldIndex = copyIndex(linkedStationIndex != null ? linkedStationIndex : currentLinkedStation.index());
            Direction oldSide = linkedStationSide != null ? linkedStationSide : currentLinkedStation.side();
            rememberMainStation(oldIndex, oldSide);
            currentLinkedStation.unsafeRelease(this);

            linkedStation = null;
            linkedStationIndex = null;
            linkedStationSide = null;

            DockingStation replacement = getStation(registry, oldIndex, oldSide);
            if (replacement != null
                    && (replacement.robotIdTaking() == NULL_ROBOT_ID || replacement.robotIdTaking() == robotId)) {
                replacement.takeAsMain(this);
            }
        }
    }

    private boolean isStationGone(DockingStation station) {
        if (station == null) {
            return true;
        }

        IRobotRegistry registry = getRegistry();
        if (registry != null && registry.getStation(station.index().toBlockPos(), station.side()) != station) {
            return true;
        }

        Level stationLevel = station.level();
        if (stationLevel != null && stationLevel.isLoaded(station.index().toBlockPos())) {
            if (!station.isInitialized()) {
                return true;
            }
            return registry != null && registry.getStation(station.index().toBlockPos(), station.side()) != station;
        }

        return false;
    }

    /**
     * Keeps the classic station ownership safety check, but no longer abandons a robot at its current work target.
     * A missing/recreated station is first rebound by coordinates; if that fails, the emergency return AI sends the
     * robot back to the last known home position and waits there for the station to become available again.
     */
    private void validateLinkedStation() {
        if (mainStationReleasedManually) {
            ensureManualStationReturnAI();
            return;
        }

        if (linkedStation == null && linkedStationIndex != null) {
            linkedStation = getStation(getRegistry(), linkedStationIndex, linkedStationSide);
            if (linkedStation != null) {
                rememberMainStation(linkedStationIndex, linkedStationSide);
            }
        }

        if (linkedStation != null && linkedStation.wasManuallyReleased(robotId)) {
            rememberMainStation(linkedStation.index(), linkedStation.side());
            mainStationReleasedManually = true;
            linkedStation = null;
            linkedStationIndex = null;
            linkedStationSide = null;
            ensureManualStationReturnAI();
            return;
        }

        if (linkedStation == null && !tryRecoverMainStation()) {
            returnToLastKnownStation("no docking station");
            return;
        }

        if (!linkedStation.isInitialized()) {
            rememberMainStation(linkedStation.index(), linkedStation.side());
            linkedStation = null;
            linkedStationIndex = null;
            linkedStationSide = null;
            returnToLastKnownStation("docking station chunk is unavailable");
            return;
        }

        if (linkedStation.robotTaking() != this) {
            if (linkedStation.robotIdTaking() == robotId) {
                BCLog.logger.warn("Robot {} was detached from its station entity reference; rebinding it", robotId);
                linkedStation.invalidateRobotTakingEntity();
            }

            if (linkedStation.robotTaking() != this
                    && (linkedStation.robotIdTaking() == NULL_ROBOT_ID || linkedStation.robotIdTaking() == robotId)) {
                linkedStation.takeAsMain(this);
            }

            if (linkedStation.robotTaking() != this) {
                BlockIndex lostIndex = copyIndex(linkedStation.index());
                Direction lostSide = linkedStation.side();
                rememberMainStation(lostIndex, lostSide);
                linkedStation = null;
                linkedStationIndex = null;
                linkedStationSide = null;
                returnToLastKnownStation("wrong docking station owner");
                return;
            }
        }

        // A detached robot can persist in shutdown while its station is unavailable. Once the station is visible,
        // replace that stale shutdown with the emergency return path. A robot that is already docked may remain shut
        // down normally.
        if (mainAI != null
                && mainAI.getDelegateAI() instanceof AIRobotShutdown
                && dockingStation != linkedStation
                && lastMainStationIndex != null) {
            BCLog.logger.info("Robot {} recovered its main station; returning to dock", robotId);
            mainAI.startDelegateAI(new AIRobotReturnToLostStation(this, lastMainStationIndex, lastMainStationSide));
        }
    }

    private void ensureManualStationReturnAI() {
        setDeltaMovement(Vec3.ZERO);
        if (lastMainStationIndex == null || mainAI == null) {
            return;
        }
        if (!(mainAI.getDelegateAI() instanceof AIRobotReturnToLostStation)) {
            mainAI.setOverridingAI(null);
            mainAI.startDelegateAI(new AIRobotReturnToLostStation(
                    this, lastMainStationIndex, lastMainStationSide, false));
        }
    }

    private boolean tryRecoverMainStation() {
        IRobotRegistry registry = getRegistry();
        if (registry == null) {
            return false;
        }

        // If entity NBT lacks station coordinates, recover ownership from the persistent robot registry, which
        // retains the main dock associated with this robot id.
        if (lastMainStationIndex == null) {
            DockingStation nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (DockingStation candidate : registry.getStations()) {
                if (candidate == null || !candidate.isMainStation() || candidate.robotIdTaking() != robotId) {
                    continue;
                }
                double distance = distanceToSqr(candidate.x() + 0.5D, candidate.y() + 0.5D, candidate.z() + 0.5D);
                if (distance < nearestDistance) {
                    nearest = candidate;
                    nearestDistance = distance;
                }
            }
            if (nearest != null) {
                rememberMainStation(nearest.index(), nearest.side());
            }
        }

        if (lastMainStationIndex == null) {
            return false;
        }

        DockingStation station = getStation(registry, lastMainStationIndex, lastMainStationSide);
        if (station == null) {
            return false;
        }

        long takingId = station.robotIdTaking();
        if (takingId != NULL_ROBOT_ID && takingId != robotId) {
            return false;
        }
        return station.takeAsMain(this);
    }

    private void returnToLastKnownStation(String reason) {
        // Clear movement from the previous work AI before station recovery so the robot cannot coast outside its
        // work area while the station is unavailable.
        setDeltaMovement(Vec3.ZERO);

        if (lastMainStationIndex == null) {
            shutdownRobot(reason + ", no last station coordinates");
            return;
        }
        if (mainAI == null
                || mainAI.getDelegateAI() instanceof AIRobotReturnToLostStation
                || mainAI.getOverridingAI() != null) {
            return;
        }

        BCLog.logger.warn(
                "Robot {} lost its main station at {} side {} while at [{}, {}, {}] ({}). Returning within 5 blocks.",
                robotId, lastMainStationIndex, lastMainStationSide,
                Mth.floor(getX()), Mth.floor(getY()), Mth.floor(getZ()), reason
        );
        mainAI.startDelegateAI(new AIRobotReturnToLostStation(this, lastMainStationIndex, lastMainStationSide));
    }

    private void shutdownRobot(String reason) {
        if (mainAI != null && !(mainAI.getDelegateAI() instanceof AIRobotShutdown)) {
            BCLog.logger.info("Shutting down robot " + this + " - " + reason);
            mainAI.startDelegateAI(new AIRobotShutdown(this));
        }
    }

    @Nullable
    private static DockingStation getStation(@Nullable IRobotRegistry registry, @Nullable BlockIndex index,
                                             @Nullable Direction side) {
        if (registry == null || index == null) {
            return null;
        }
        return registry.getStation(index.toBlockPos(), side);
    }

    private void rememberMainStation(@Nullable BlockIndex index, @Nullable Direction side) {
        if (index == null) {
            return;
        }
        lastMainStationIndex = copyIndex(index);
        lastMainStationSide = side;
    }

    @Nullable
    private static BlockIndex copyIndex(@Nullable BlockIndex index) {
        return index == null ? null : new BlockIndex(index.x, index.y, index.z);
    }

    public static Vec3 stationPosition(DockingStation station) {
        return stationPosition(station.x(), station.y(), station.z(), station.side());
    }

    private static Vec3 stationPosition(int x, int y, int z, @Nullable Direction side) {
        return new Vec3(
                x + 0.5D + (side == null ? 0.0D : side.getStepX() * 0.5D),
                y + 0.5D + (side == null ? 0.0D : side.getStepY() * 0.5D),
                z + 0.5D + (side == null ? 0.0D : side.getStepZ() * 0.5D)
        );
    }

    private static double stationX(DockingStation station) {
        return stationPosition(station).x;
    }

    private static double stationY(DockingStation station) {
        return stationPosition(station).y;
    }

    private static double stationZ(DockingStation station) {
        return stationPosition(station).z;
    }

    private void snapToStation(DockingStation station) {
        Vec3 stationPos = stationPosition(station);
        setNoGravity(true);
        noPhysics = true;
        setDeltaMovement(Vec3.ZERO);
        setPos(stationPos.x, stationPos.y, stationPos.z);
        refreshRobotBoundingBox();
    }

    private boolean snapToSyncedDockingStation() {
        if (!level.isClientSide || !entityData.get(ROBOT_DOCKED)) {
            return false;
        }
        int sideId = entityData.get(ROBOT_DOCK_SIDE);
        Direction side = sideId >= 0 && sideId < Direction.values().length ? Direction.values()[sideId] : null;
        Vec3 stationPos = stationPosition(
                entityData.get(ROBOT_DOCK_X),
                entityData.get(ROBOT_DOCK_Y),
                entityData.get(ROBOT_DOCK_Z),
                side
        );
        setNoGravity(true);
        noPhysics = true;
        setDeltaMovement(Vec3.ZERO);
        setPos(stationPos.x, stationPos.y, stationPos.z);
        refreshRobotBoundingBox();
        return true;
    }

    private void syncDockingStationToClient(DockingStation station) {
        entityData.set(ROBOT_DOCKED, true);
        entityData.set(ROBOT_DOCK_X, station.x());
        entityData.set(ROBOT_DOCK_Y, station.y());
        entityData.set(ROBOT_DOCK_Z, station.z());
        Direction side = station.side();
        entityData.set(ROBOT_DOCK_SIDE, side == null ? -1 : side.ordinal());
    }

    private void clearDockingStationSync() {
        entityData.set(ROBOT_DOCKED, false);
    }

    private void alignToStation(DockingStation station) {
        Direction side = station.side();
        if (side != null && side.getStepY() == 0) {
            aimItemAt(station.x() + side.getStepX() * 2, station.y(), station.z() + side.getStepZ() * 2);
        } else {
            aimItemAt(Mth.floor(aimYaw / 90.0F) * 90.0F + 180.0F, aimPitch);
        }
        forceYawToAim();
    }

    private void updateRotationYaw(float maxStep) {
        float step = Mth.wrapDegrees(aimYaw - getYRot());
        if (step > maxStep) {
            step = maxStep;
        } else if (step < -maxStep) {
            step = -maxStep;
        }
        setRobotYaw(getYRot() + step);
    }

    private void forceYawToAim() {
        setRobotYaw(aimYaw);
    }

    private void setRobotYaw(float yaw) {
        setYRot(yaw);
        yRotO = yaw;
        setYHeadRot(yaw);
        yHeadRotO = yaw;
        yBodyRot = yaw;
        yBodyRotO = yaw;
    }

    private void refreshRobotBoundingBox() {
        setBoundingBox(new AABB(
                getX() - ROBOT_HALF_SIZE, getY() - ROBOT_HALF_SIZE, getZ() - ROBOT_HALF_SIZE,
                getX() + ROBOT_HALF_SIZE, getY() + ROBOT_HALF_SIZE, getZ() + ROBOT_HALF_SIZE
        ));
    }

    protected AABB makeBoundingBox() {
        return new AABB(
                getX() - ROBOT_HALF_SIZE, getY() - ROBOT_HALF_SIZE, getZ() - ROBOT_HALF_SIZE,
                getX() + ROBOT_HALF_SIZE, getY() + ROBOT_HALF_SIZE, getZ() + ROBOT_HALF_SIZE
        );
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(ROBOT_ASLEEP, true);
        entityData.define(ROBOT_AIM_YAW, 0.0F);
        entityData.define(ROBOT_AIM_PITCH, 0.0F);
        entityData.define(ROBOT_DOCKED, false);
        entityData.define(ROBOT_DOCK_X, 0);
        entityData.define(ROBOT_DOCK_Y, 0);
        entityData.define(ROBOT_DOCK_Z, 0);
        entityData.define(ROBOT_DOCK_SIDE, -1);
        entityData.define(ROBOT_ENERGY, 0);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("boardId", boardEntry.id());
        tag.putLong("robotId", robotId);
        if (isRealOwner(owner)) {
            tag.put("owner", NbtUtils.writeGameProfile(new CompoundTag(), owner));
        }
        tag.put("battery", battery.serializeNBT());
        tag.putBoolean("itemActive", itemActive);
        tag.putBoolean("asleep", isAsleepForRendering());
        tag.putInt("ticksCharging", ticksCharging);
        tag.putLong("chargeRemainder", chargeRemainder);
        tag.putBoolean("mainStationReleasedManually", mainStationReleasedManually);
        tag.putFloat("aimYaw", aimYaw);
        tag.putFloat("aimPitch", aimPitch);
        if (!itemInUse.isEmpty()) {
            tag.put("itemInUse", itemInUse.save(new CompoundTag()));
        }
        ContainerHelper.saveAllItems(tag, inventory);
        if (linkedStationIndex != null) {
            tag.put("linkedStation", writeStation(linkedStationIndex, linkedStationSide));
        }
        if (lastMainStationIndex != null) {
            tag.put("lastMainStation", writeStation(lastMainStationIndex, lastMainStationSide));
        }
        if (dockingStationIndex != null) {
            tag.put("currentStation", writeStation(dockingStationIndex, dockingStationSide));
        }
        if (!wearables.isEmpty()) {
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            for (ItemStack stack : wearables) {
                list.add(stack.save(new CompoundTag()));
            }
            tag.put("wearables", list);
        }
        if (mainAI != null) {
            CompoundTag aiTag = new CompoundTag();
            mainAI.writeToNBT(aiTag);
            tag.put("mainAI", aiTag);
        }
        if (!tank.isEmpty()) {
            tag.put("tank", tank.writeToNBT(new CompoundTag()));
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        setBoard(BCRoboticsBoards.getById(tag.getString("boardId")));
        robotId = tag.contains("robotId") ? tag.getLong("robotId") : NULL_ROBOT_ID;
        owner = tag.contains("owner") ? NbtUtils.readGameProfile(tag.getCompound("owner")) : null;
        battery.deserializeNBT(tag.getCompound("battery"));
        itemActive = tag.getBoolean("itemActive");
        ticksCharging = tag.getInt("ticksCharging");
        chargeRemainder = tag.getLong("chargeRemainder");
        mainStationReleasedManually = tag.getBoolean("mainStationReleasedManually");
        if (tag.contains("asleep")) {
            entityData.set(ROBOT_ASLEEP, tag.getBoolean("asleep"));
        }
        aimYaw = tag.getFloat("aimYaw");
        aimPitch = tag.getFloat("aimPitch");
        itemInUse = tag.contains("itemInUse") ? ItemStack.of(tag.getCompound("itemInUse")) : ItemStack.EMPTY;
        ContainerHelper.loadAllItems(tag, inventory);
        if (tag.contains("linkedStation")) {
            readLinkedStation(tag.getCompound("linkedStation"));
        }
        if (tag.contains("lastMainStation")) {
            readLastMainStation(tag.getCompound("lastMainStation"));
        } else if (linkedStationIndex != null) {
            rememberMainStation(linkedStationIndex, linkedStationSide);
        }
        if (tag.contains("currentStation")) {
            readCurrentStation(tag.getCompound("currentStation"));
        }
        wearables.clear();
        if (tag.contains("wearables")) {
            net.minecraft.nbt.ListTag list = tag.getList("wearables", net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                ItemStack stack = ItemStack.of(list.getCompound(i));
                if (!stack.isEmpty()) wearables.add(stack);
            }
        }
        tank = tag.contains("tank") ? FluidStack.loadFluidStackFromNBT(tag.getCompound("tank")) : FluidStack.EMPTY;
        if (!level.isClientSide) {
            mainAI = null;
            if (tag.contains("mainAI")) {
                AIRobot loaded = AIRobot.loadAI(tag.getCompound("mainAI"), this);
                if (loaded instanceof AIRobotMain loadedMain) {
                    mainAI = loadedMain;
                }
            }
            if (mainAI == null) {
                mainAI = new AIRobotMain(this);
                mainAI.start();
            }
        }
    }

    private static CompoundTag writeStation(BlockIndex index, @Nullable Direction side) {
        CompoundTag tag = new CompoundTag();
        CompoundTag indexTag = new CompoundTag();
        index.writeTo(indexTag);
        tag.put("index", indexTag);
        tag.putByte("side", (byte) (side == null ? -1 : side.ordinal()));
        return tag;
    }

    private void readLinkedStation(CompoundTag tag) {
        linkedStationIndex = new BlockIndex(tag.getCompound("index"));
        linkedStationSide = readSide(tag);
    }

    private void readLastMainStation(CompoundTag tag) {
        lastMainStationIndex = new BlockIndex(tag.getCompound("index"));
        lastMainStationSide = readSide(tag);
    }

    private void readCurrentStation(CompoundTag tag) {
        dockingStationIndex = new BlockIndex(tag.getCompound("index"));
        dockingStationSide = readSide(tag);
    }

    @Nullable
    private static Direction readSide(CompoundTag tag) {
        int sideId = tag.getByte("side");
        return sideId >= 0 && sideId < Direction.values().length ? Direction.values()[sideId] : null;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        return handleRobotInteract(player, hand);
    }

    public InteractionResult interactAt(Player player, Vec3 hitVec, InteractionHand hand) {
        return handleRobotInteract(player, hand);
    }

    private InteractionResult handleRobotInteract(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty()) {
            return InteractionResult.PASS;
        }

        if (RobotEventSupport.denied(RobotEventKind.INTERACT, this, player, stack)) {
            return InteractionResult.PASS;
        }

        if (player.isShiftKeyDown() && WrenchUtil.isWrench(stack)) {
            if (RobotEventSupport.denied(RobotEventKind.DISMANTLE, this, player, stack)) {
                return InteractionResult.PASS;
            }
            if (!level.isClientSide) {
                onRobotHit(false);
            } else {
                WrenchUtil.wrenchUsed(player, hand, stack, new EntityHitResult(this));
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (wearables.size() < MAX_WEARABLES && isWearable(stack)) {
            if (!level.isClientSide) {
                wearables.add(stack.split(1));
            } else {
                player.swing(hand);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        return InteractionResult.PASS;
    }

    private boolean isWearable(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getItem() instanceof ArmorItem) return true;
        return stack.getItem() instanceof buildcraft.robotics.internal.legacy.robots.IRobotOverlayItem
                && ((buildcraft.robotics.internal.legacy.robots.IRobotOverlayItem) stack.getItem()).isValidRobotOverlay(stack);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        Entity src = source.getEntity();
        if (src == null || src instanceof FallingBlockEntity || src instanceof Enemy || dockingStation != null) {
            return false;
        }
        if (MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.entity.living.LivingAttackEvent(this, source, amount))) {
            return false;
        }
        if (!level.isClientSide) {
            hurtTime = hurtDuration = 10;
            int mul = 2600;
            for (ItemStack wearable : wearables) {
                if (wearable.getItem() instanceof ArmorItem armor) {
                    mul = mul * 2 / Math.max(2, 2 + armor.getDefense());
                } else {
                    mul = Math.round(mul * 0.7F);
                }
            }
            long energy = Math.round(amount * mul);
            if (battery.getStored() - energy > 0) {
                battery.extractPower(energy);
                return true;
            }
            onRobotHit(true);
        }
        return true;
    }

    private void onRobotHit(boolean attacked) {
        if (level.isClientSide) return;
        if (attacked) {
            convertToItems();
        } else if (!wearables.isEmpty()) {
            spawnAtLocation(wearables.remove(wearables.size() - 1), 0.0F);
        } else if (!itemInUse.isEmpty()) {
            spawnAtLocation(itemInUse, 0.0F);
            itemInUse = ItemStack.EMPTY;
        } else {
            convertToItems();
        }
    }

    private List<ItemStack> getDrops() {
        List<ItemStack> drops = new ArrayList<>();
        drops.add(asItemStack());
        if (!itemInUse.isEmpty()) drops.add(itemInUse.copy());
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty()) drops.add(stack.copy());
        }
        for (ItemStack stack : wearables) {
            if (!stack.isEmpty()) drops.add(stack.copy());
        }
        return drops;
    }

    private void convertToItems() {
        if (!level.isClientSide && !convertingToItems) {
            convertingToItems = true;
            undock();
            releaseResources();
            for (ItemStack stack : getDrops()) {
                if (!stack.isEmpty()) spawnAtLocation(stack, 0.0F);
            }
            IRobotRegistry registry = getRegistry();
            if (registry != null) registry.killRobot(this);
            discard();
        }
    }

    public void attackTargetEntityWithCurrentItem(Entity target) {
        if (target == null || level.isClientSide || !target.isAttackable() || target.skipAttackInteraction(this)) {
            return;
        }
        if (level instanceof ServerLevel serverLevel) {
            Player fakePlayer = FakePlayerProvider.INSTANCE.getFakePlayer(
                    serverLevel, getOwnerProfile(), blockPosition());
            if (MinecraftForge.EVENT_BUS.post(new AttackEntityEvent(fakePlayer, target))) {
                return;
            }
        }

        float attackDamage = 2.0F;
        if (!itemInUse.isEmpty()) {
            Multimap<Attribute, AttributeModifier> attributes = itemInUse.getAttributeModifiers(EquipmentSlot.MAINHAND);
            for (AttributeModifier modifier : attributes.get(Attributes.ATTACK_DAMAGE)) {
                attackDamage = applyAttributeModifier(attackDamage, modifier);
            }
        }
        if (target instanceof net.minecraft.world.entity.LivingEntity living) {
            attackDamage += EnchantmentHelper.getDamageBonus(itemInUse, living.getMobType());
        }

        if (attackDamage > 0.0F && target.hurt(DamageSource.mobAttack(this), attackDamage)) {
            setLastHurtMob(target);
            if (target instanceof net.minecraft.world.entity.LivingEntity living) {
                EnchantmentHelper.doPostHurtEffects(living, this);
                EnchantmentHelper.doPostDamageEffects(this, target);
                if (!itemInUse.isEmpty()) {
                    itemInUse.getItem().hurtEnemy(itemInUse, living, this);
                    if (itemInUse.isEmpty()) {
                        setItemInUse(ItemStack.EMPTY);
                    }
                }
            }
        }
    }

    private static float applyAttributeModifier(float value, AttributeModifier modifier) {
        return switch (modifier.getOperation()) {
            case ADDITION -> (float) (value + modifier.getAmount());
            case MULTIPLY_BASE -> (float) (value + value * modifier.getAmount());
            case MULTIPLY_TOTAL -> (float) (value * (1.0D + modifier.getAmount()));
        };
    }

    @Override
    public boolean isPushedByFluid() {
        // Classic BuildCraft robots fly under their own AI control. Water currents must not drag them away from
        // docking stations or work targets; otherwise a stream can permanently desync the robot AI from its planned
        // path. Keep fluid detection intact, but opt out of vanilla fluid-current motion.
        return false;
    }

    @Override
    public void travel(Vec3 travelVector) {
        if (dockingStation != null) {
            setDeltaMovement(Vec3.ZERO);
            return;
        }
        Vec3 motion = getDeltaMovement();
        if (motion.lengthSqr() > 0.0D) {
            noPhysics = !isShutdownActive();
            move(MoverType.SELF, motion);
            refreshRobotBoundingBox();
        }
    }

    @Override
    public boolean causeFallDamage(float distance, float damageMultiplier, DamageSource source) {
        return false;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
    }

    public boolean isOnLadder() {
        return false;
    }

    public boolean onClimbable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public void push(Entity entity) {
    }

    @Override
    protected void pushEntities() {
        // Keep classic robot movement no-clip-like: the robot itself must not shove drops out of the picker pickup
        // range while flying. Entity collision for players is handled by canBeCollidedWith/canCollideWith below.
    }

    @Override
    public boolean canCollideWith(Entity entity) {
        return !(entity instanceof ItemEntity);
    }

    public boolean canBeCollidedWith() {
        // Players should be able to bump into and stand on robots like in BuildCraft 7.1.x. Dropped items are kept
        // from being shoved by the empty pushEntities() override and the ItemEntity check in canCollideWith().
        return isAlive();
    }

    @Override
    public Iterable<ItemStack> getArmorSlots() {
        return Collections.unmodifiableList(wearables);
    }

    @Override
    public ItemStack getItemBySlot(EquipmentSlot slot) {
        if (slot == EquipmentSlot.MAINHAND) {
            return itemInUse;
        }
        if (slot.getType() == EquipmentSlot.Type.ARMOR) {
            int index = slot.getIndex();
            return index >= 0 && index < wearables.size() ? wearables.get(index) : ItemStack.EMPTY;
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        if (slot == EquipmentSlot.MAINHAND) {
            setItemInUse(stack);
        }
    }

    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }

    @Override
    public int getContainerSize() {
        return inventory.size();
    }

    @Override
    public boolean isEmpty() {
        return inventory.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < inventory.size() ? inventory.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(inventory, slot, amount);
        if (!result.isEmpty()) setChanged();
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(inventory, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot >= 0 && slot < inventory.size()) {
            inventory.set(slot, stack);
            setChanged();
        }
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= inventory.size()) return false;
        ItemStack existing = inventory.get(slot);
        return existing.isEmpty()
                || (ItemStack.isSameItemSameTags(existing, stack) && existing.isStackable()
                && existing.getCount() + stack.getCount() <= Math.min(existing.getMaxStackSize(), getMaxStackSize()));
    }

    @Override
    public int getMaxStackSize() {
        return 64;
    }

    @Override
    public void setChanged() {
    }

    @Override
    public boolean stillValid(Player player) {
        return isAlive() && player.distanceToSqr(this) <= 64.0D;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < inventory.size(); i++) {
            inventory.set(i, ItemStack.EMPTY);
        }
    }

    @Override
    public void setItemInUse(ItemStack stack) {
        itemInUse = stack == null ? ItemStack.EMPTY : stack.copy();
    }

    @Override
    public void setItemActive(boolean active) {
        itemActive = active;
    }

    public boolean isItemActive() {
        return itemActive;
    }

    @Override
    public boolean isMoving() {
        return getDeltaMovement().lengthSqr() > 1.0E-7D;
    }

    @Override
    public DockingStation getLinkedStation() {
        if (linkedStation == null && linkedStationIndex != null && getRegistry() != null) {
            linkedStation = getRegistry().getStation(linkedStationIndex.toBlockPos(), linkedStationSide);
        }
        return linkedStation;
    }

    @Override
    public RedstoneBoardRobot getBoard() {
        return board;
    }

    @Override
    public void aimItemAt(float yaw, float pitch) {
        aimYaw = yaw;
        aimPitch = pitch;
        if (!level.isClientSide) {
            entityData.set(ROBOT_AIM_YAW, aimYaw);
            entityData.set(ROBOT_AIM_PITCH, aimPitch);
        }
    }

    @Override
    public void aimItemAt(int x, int y, int z) {
        double dx = x - Math.floor(getX());
        double dy = y - Math.floor(getY());
        double dz = z - Math.floor(getZ());
        if (dx != 0 || dz != 0) {
            aimYaw = (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) + 180.0F;
        }
        aimPitch = (float) (-(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0D / Math.PI));
        if (!level.isClientSide) {
            entityData.set(ROBOT_AIM_YAW, aimYaw);
            entityData.set(ROBOT_AIM_PITCH, aimPitch);
        }
    }

    @Override
    public float getAimYaw() {
        return aimYaw;
    }

    @Override
    public float getAimPitch() {
        return aimPitch;
    }

    @Override
    public MjBattery getBattery() {
        return battery;
    }

    @Override
    public DockingStation getDockingStation() {
        return dockingStation;
    }

    @Override
    public void dock(DockingStation station) {
        dockingStation = station;
        if (station != null) {
            station.setLevel(level);
            dockingStationIndex = station.index();
            dockingStationSide = station.side();
            alignToStation(station);
            syncDockingStationToClient(station);
            snapToStation(station);
        }
    }

    @Override
    public void undock() {
        if (dockingStation != null) {
            dockingStation.release(this);
            dockingStation = null;
            dockingStationIndex = null;
            dockingStationSide = null;
            if (!level.isClientSide) {
                clearDockingStationSync();
            }
        }
    }

    @Override
    public IZone getZoneToWork() {
        return getZone(ActionRobotWorkInArea.AreaType.WORK);
    }

    @Override
    public IZone getZoneToLoadUnload() {
        IZone zone = getZone(ActionRobotWorkInArea.AreaType.LOAD_UNLOAD);
        return zone == null ? getZoneToWork() : zone;
    }

    private IZone getZone(ActionRobotWorkInArea.AreaType areaType) {
        DockingStation station = getLinkedStation();
        if (station == null) {
            return null;
        }

        for (StatementSlot slot : station.getActiveActions()) {
            if (slot.statement instanceof ActionRobotWorkInArea action && action.getAreaType() == areaType) {
                IZone zone = ActionRobotWorkInArea.getArea(slot);
                if (zone != null) {
                    return zone;
                }
            }
        }
        return null;
    }

    @Override
    public boolean containsItems() {
        return !isEmpty() || !itemInUse.isEmpty();
    }

    @Override
    public boolean hasFreeSlot() {
        return inventory.stream().anyMatch(ItemStack::isEmpty);
    }

    @Override
    public void unreachableEntityDetected(Entity entity) {
        unreachableEntities.put(entity, level.getGameTime() + 1200L);
    }

    @Override
    public boolean isKnownUnreachable(Entity entity) {
        Long expires = unreachableEntities.get(entity);
        if (expires == null) return false;
        if (expires >= level.getGameTime()) return true;
        unreachableEntities.remove(entity);
        return false;
    }

    @Override
    public long getRobotId() {
        return robotId;
    }

    @Override
    public IRobotRegistry getRegistry() {
        return RobotManager.registryProvider == null ? null : RobotManager.registryProvider.getRegistry(level);
    }

    @Override
    public void releaseResources() {
        IRobotRegistry registry = getRegistry();
        if (registry != null) registry.releaseResources(this);
    }

    @Override
    public void onChunkUnload() {
        IRobotRegistry registry = getRegistry();
        if (registry != null) registry.unloadRobot(this);
    }

    @Override
    public void remove(RemovalReason reason) {
        IRobotRegistry registry = getRegistry();
        if (!level.isClientSide && !convertingToItems && registry != null) {
            if (reason.shouldDestroy()) {
                registry.killRobot(this);
            } else {
                registry.unloadRobot(this);
            }
        }
        super.remove(reason);
    }

    @Override
    public ItemStack receiveItem(BlockEntity blockEntity, ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        if (dockingStation == null || blockEntity == null || !dockingStation.index().nextTo(new BlockIndex(blockEntity))) {
            return stack;
        }
        return insertIntoInventory(stack);
    }

    public ItemStack insertIntoInventory(ItemStack stack) {
        return insertIntoInventory(stack, true);
    }

    public ItemStack insertIntoInventory(ItemStack stack, boolean doInsert) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < inventory.size() && !remaining.isEmpty(); i++) {
            ItemStack existing = inventory.get(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameTags(existing, remaining) && existing.isStackable()) {
                int limit = Math.min(existing.getMaxStackSize(), getMaxStackSize());
                int move = Math.min(limit - existing.getCount(), remaining.getCount());
                if (move > 0) {
                    if (doInsert) existing.grow(move);
                    remaining.shrink(move);
                }
            }
        }
        for (int i = 0; i < inventory.size() && !remaining.isEmpty(); i++) {
            if (inventory.get(i).isEmpty()) {
                int move = Math.min(Math.min(remaining.getMaxStackSize(), getMaxStackSize()), remaining.getCount());
                if (doInsert) {
                    ItemStack inserted = remaining.copy();
                    inserted.setCount(move);
                    inventory.set(i, inserted);
                }
                remaining.shrink(move);
            }
        }
        if (doInsert && remaining.getCount() != stack.getCount()) setChanged();
        return remaining;
    }

    /** Called after a player shift-wrenches the robot's home station. */
    public void releaseMainStationForPlayer(DockingStation station) {
        if (level.isClientSide || station == null) {
            return;
        }

        rememberMainStation(station.index(), station.side());
        mainStationReleasedManually = true;
        linkedStation = null;
        linkedStationIndex = null;
        linkedStationSide = null;
        releaseResources();
        setDeltaMovement(Vec3.ZERO);

        if (mainAI == null) {
            mainAI = new AIRobotMain(this);
            mainAI.start();
        }
        mainAI.setOverridingAI(null);
        mainAI.startDelegateAI(new AIRobotReturnToLostStation(
                this, lastMainStationIndex, lastMainStationSide, false));
    }

    @Override
    public void setMainStation(DockingStation station) {
        if (linkedStation != null && linkedStation != station) {
            linkedStation.unsafeRelease(this);
        }
        linkedStation = station;
        if (station != null) {
            mainStationReleasedManually = false;
            station.setLevel(level);
            linkedStationIndex = copyIndex(station.index());
            linkedStationSide = station.side();
            rememberMainStation(linkedStationIndex, linkedStationSide);
        } else {
            // Deliberately keep lastMainStationIndex: a removed or temporarily missing station is exactly where a
            // stranded robot should return so it can be found and automatically reconnect when the dock reappears.
            linkedStationIndex = null;
            linkedStationSide = null;
        }
    }

    public DockingStation getMainStation() {
        return linkedStation;
    }

    @Override
    public void writeSpawnData(FriendlyByteBuf buffer) {
        buffer.writeUtf(boardEntry == null ? BCRoboticsBoards.EMPTY.id() : boardEntry.id());
        buffer.writeVarInt(getEnergy());
        buffer.writeBoolean(isAsleepForRendering());
        buffer.writeFloat(aimYaw);
        buffer.writeFloat(aimPitch);
        buffer.writeVarInt(wearables.size());
        for (ItemStack stack : wearables) {
            buffer.writeItem(stack);
        }
    }

    @Override
    public void readSpawnData(FriendlyByteBuf buffer) {
        setBoard(BCRoboticsBoards.getById(buffer.readUtf(32767)));
        setEnergy(buffer.readVarInt());
        entityData.set(ROBOT_ASLEEP, buffer.readBoolean());
        aimYaw = buffer.readFloat();
        aimPitch = buffer.readFloat();
        entityData.set(ROBOT_AIM_YAW, aimYaw);
        entityData.set(ROBOT_AIM_PITCH, aimPitch);
        forceYawToAim();
        wearables.clear();
        int wearableCount = buffer.readVarInt();
        for (int i = 0; i < wearableCount; i++) {
            ItemStack stack = buffer.readItem();
            if (!stack.isEmpty()) wearables.add(stack);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return (Packet<ClientGamePacketListener>) (Packet<?>) NetworkHooks.getEntitySpawningPacket(this);
    }

    @Override
    public int getTanks() {
        return 1;
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        return tank == 0 ? this.tank.copy() : FluidStack.EMPTY;
    }

    @Override
    public int getTankCapacity(int tank) {
        return tank == 0 ? MAX_FLUID : 0;
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return tank == 0 && (this.tank.isEmpty() || FluidCompatRegistry.areEquivalent(this.tank, stack));
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource == null || resource.isEmpty() || !isFluidValid(0, resource)) return 0;
        int accepted = Math.min(MAX_FLUID - tank.getAmount(), resource.getAmount());
        if (accepted <= 0) return 0;
        if (action.execute()) {
            if (tank.isEmpty()) {
                tank = resource.copy();
                tank.setAmount(accepted);
            } else {
                tank.grow(accepted);
            }
        }
        return accepted;
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        if (resource == null || resource.isEmpty() || tank.isEmpty() || !FluidCompatRegistry.areEquivalent(tank, resource)) {
            return FluidStack.EMPTY;
        }
        return drain(resource.getAmount(), action);
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        if (maxDrain <= 0 || tank.isEmpty()) return FluidStack.EMPTY;
        int drained = Math.min(maxDrain, tank.getAmount());
        FluidStack result = tank.copy();
        result.setAmount(drained);
        if (action.execute()) {
            tank.shrink(drained);
            if (tank.getAmount() <= 0) tank = FluidStack.EMPTY;
        }
        return result;
    }

    private void updateItem(ItemStack stack, int slot, boolean held) {
        if (stack.isEmpty() || BLACKLISTED_ITEMS_FOR_UPDATE.contains(stack.getItem())) return;
        try {
            stack.inventoryTick(level, this, slot, held);
        } catch (Exception e) {
            buildcraft.lib.internal.debug.BCLog.logger.warn("Disabling item inventoryTick in robot after it threw an exception: " + stack, e);
            BLACKLISTED_ITEMS_FOR_UPDATE.add(stack.getItem());
        }
    }
}
