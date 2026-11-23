package buildcraft.robotics.ai;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Iterator;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.lib.misc.ChunkUtil;
import buildcraft.robotics.boards.BoardRobotFisher;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.scheduler.RobotProcessorWorkScheduler;
import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
//? if <1.21.11 {
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
//?} else {
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
//?}
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
//? if <1.20 {
import net.minecraft.network.protocol.game.ClientboundPlayerInfoPacket;
//?} else {
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
    //? if >=1.21.1 {
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
    //?}
//?}
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Uses FishingRodItem.use for BOTH cast and reel. The hook ticks in the world,
 * so loot, enchanted rods, fishing weather, sounds and durability are vanilla.
 * Fisher never generates loot itself and never collects drops; that is Picker's job.
 */
public final class AIRobotFish extends AIRobot {
    // Scan the actual water surface, not a narrow slice around a flying robot's Y.
    // 96 blocks matches the regular robot search range; the expanding rings find
    // nearby pools first, without examining every column before choosing one.
    private static final int SEARCH_RADIUS = 96;
    private static final int SEARCH_BUDGET_PER_TICK = 96;
    private static final int SEARCH_WIDTH = SEARCH_RADIUS * 2 + 1;
    private static final int SEARCH_COLUMNS = SEARCH_WIDTH * SEARCH_WIDTH;
    private static final int SEARCH_RETRY_TICKS = 100;
    private static final int MAX_REJECTED_AREAS = 16;
    private static final int REJECTED_AREA_RADIUS_SQ = 6 * 6;
    private static final double MAX_CAST_DISTANCE_SQ = 7.0D * 7.0D;
    private static final int CAST_WINDUP_TICKS = 12;
    // A bobber occasionally rises above the surface during ordinary fishing.
    // Only a continuously dry hook is considered a missed cast.
    private static final int BOBBER_LAND_TIMEOUT_TICKS = 160;
    /** Moving away from a cast breaks the illusion of a rod held by the robot.
     * Cancel that cast rather than leaving a line tied to an invisible angler.
     */
    private static final double MAX_CAST_STATIONARY_DRIFT_SQ = 0.35D * 0.35D;
    // Do not impose an overall time limit once the bobber reaches water.
    // Vanilla alone determines when a fish actually bites.
    /** A client must know a Player entity before it receives FishingHook's spawn packet. */
    private static final double ANGLER_SYNC_RADIUS_SQ = 128.0D * 128.0D;
    /** Robot entities are 0.5 blocks tall. Align the invisible angler's EYE
     * with the robot's held-rod origin instead of a player's 1.62-block eye.
     * Both the spawned hook and the rendered fishing line use this angler.
     */
    private static final double ROBOT_ROD_ORIGIN_HEIGHT = 0.42D;
    /** Vanilla spawns its bobber *behind* a player's eye. With the phantom
     * player centred inside the robot, that puts the hook inside the robot's
     * collision shape. Start the real hook beyond that shape instead.
     */
    private static final double HOOK_FORWARD_CLEARANCE = 0.95D;
    /** Keep the invisible line owner attached to the moving robot on clients.
     * FakePlayers are not part of vanilla entity tracking, so no movement
     * packet is generated automatically for them.
     */
    private static final double ANGLER_POSITION_EPSILON_SQ = 0.02D * 0.02D;
    /** XPlus-AutoFish's state monitor detects a real bite, not a bobber's
     * movement. On the server, vanilla's nibble countdown is authoritative for
     * loot retrieval; the biting state is the compatibility fallback.
     * Resolve only known vanilla fields: guessing the first boolean accessor
     * can accidentally treat a different entity flag as a fish bite.
     */
    private static final Field VANILLA_NIBBLE = vanillaField("nibble", "f_37089_", "field_7173");
    private static final Field VANILLA_BITING_FLAG = vanillaField("biting");
    private static final Field VANILLA_BITING_DATA = vanillaField("DATA_BITING", "f_37102_", "field_23234");

    private BlockPos origin;
    private BlockPos water;
    private int searched;
    private int retrySearchTicks;
    /** Do not repeatedly navigate toward a known blocked shore position. */
    private final List<BlockPos> rejectedWater = new ArrayList<>();
    private boolean moving;
    private int windup;
    private boolean reachedWater;
    private int dryTicks;
    private Vec3 castRobotPosition;
    private ServerPlayer angler;
    private FishingHook hook;
    /** Viewers of this Fisher's private fake angler; never a server player-list entry. */
    private final Set<ServerPlayer> anglerViewers = new HashSet<>();
    private Vec3 lastSyncedAnglerPosition;
    private float lastSyncedAnglerYaw;
    private float lastSyncedAnglerPitch;

    public AIRobotFish(EntityRobotBase robot) {
        super(robot);
    }

    @Override
    public void update() {
        if (!(robot.getCommandSenderWorld() instanceof ServerLevel level)
                || !BoardRobotFisher.isUsableRod(robot.getItemBySlot(EquipmentSlot.MAINHAND))) {
            fail();
            return;
        }
        // A rod and its line must never remain displayed while Fisher flies.
        // Navigation is delegated to AIRobotGotoBlock, so clear the ghost angler
        // before starting that delegate (even when this update is suspended).
        if (moving || robot.isMoving()) {
            robot.setItemActive(false);
            windup = 0;
            if (hook != null) cancelCast();
            else hideAnglerViewers();
            return;
        }
        if (hook != null) {
            if (castRobotPosition != null && robot.position().distanceToSqr(castRobotPosition)
                    > MAX_CAST_STATIONARY_DRIFT_SQ) {
                cancelCast();
            } else {
                waitForVanillaBite(level);
            }
            return;
        }
        if (water == null) {
            findWater(level);
            return;
        }
        if (!fishable(level, water)) {
            rejectWater();
            return;
        }
        if (distanceToWaterSq() > MAX_CAST_DISTANCE_SQ) {
            if (!moving) {
                BlockPos approach = approachPosition();
                // The pathfinder cannot arrive inside terrain, and its normal
                // failed route should not end this fisher's entire work cycle.
                if (ChunkUtil.getChunk(level, approach, false) == null
                        || !level.getBlockState(approach).isAir()) {
                    rejectWater();
                    return;
                }
                moving = true;
                robot.setItemActive(false);
                hideAnglerViewers();
                startDelegateAI(new AIRobotGotoBlock(robot,
                        approach.getX(), approach.getY(), approach.getZ()));
            }
            return;
        }
        robot.aimItemAt(water);
        robot.setItemActive(true);
        if (++windup >= CAST_WINDUP_TICKS) cast(level);
    }

    /**
     * Search loaded surface columns in expanding square rings. The previous
     * 24x24x8 volume around the robot silently missed normal lakes whenever
     * a dock was more than five blocks above the water, and waited ~100 ticks
     * to pick a target even when water was right beside it.
     */
    private void findWater(ServerLevel level) {
        if (retrySearchTicks > 0) {
            retrySearchTicks--;
            return;
        }
        if (origin == null) origin = robot.blockPosition();
        int budget = SEARCH_BUDGET_PER_TICK;
        while (budget-- > 0 && searched < SEARCH_COLUMNS) {
            int index = searched++;
            int dx = 0, dz = 0;
            if (index > 0) {
                int ring = ((int) Math.sqrt(index) + 1) / 2;
                int first = (2 * ring - 1) * (2 * ring - 1);
                int offset = index - first;
                int side = offset / (2 * ring);
                int step = offset % (2 * ring);
                switch (side) {
                    case 0 -> { dx = -ring + step; dz = -ring; }
                    case 1 -> { dx = ring; dz = -ring + step; }
                    case 2 -> { dx = ring - step; dz = ring; }
                    default -> { dx = -ring; dz = ring - step; }
                }
            }
            int x = origin.getX() + dx, z = origin.getZ() + dz;
            // getHeight may request a chunk; guard it with a strict *loaded*
            // lookup, not hasChunkAt (which can include generated/unloaded chunks).
            if (ChunkUtil.getChunk(level, x >> 4, z >> 4, false) == null) continue;
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight() - 1) continue;
            BlockPos candidate = new BlockPos(x, y, z);
            if (rejected(candidate) || !fishable(level, candidate)) continue;
            water = candidate;
            windup = 0;
            return; // Nearest available surface water: start moving immediately.
        }
        if (searched >= SEARCH_COLUMNS) {
            // The shoreline (or chunk residency) can change. Retry after a
            // bounded cooldown instead of permanently putting the robot to sleep.
            searched = 0;
            origin = null;
            rejectedWater.clear();
            retrySearchTicks = SEARCH_RETRY_TICKS;
        }
    }

    private boolean rejected(BlockPos candidate) {
        for (BlockPos other : rejectedWater) {
            long dx = (long) candidate.getX() - other.getX();
            long dz = (long) candidate.getZ() - other.getZ();
            if (dx * dx + dz * dz <= REJECTED_AREA_RADIUS_SQ) return true;
        }
        return false;
    }

    private void rejectWater() {
        if (water != null && rejectedWater.size() < MAX_REJECTED_AREAS) {
            rejectedWater.add(water.immutable());
        }
        water = null;
        moving = false;
        windup = 0;
        robot.setItemActive(false);
    }

    private boolean fishable(ServerLevel level, BlockPos pos) {
        if (ChunkUtil.getChunk(level, pos, false) == null
                || ChunkUtil.getChunk(level, pos.above(), false) == null
                || !level.getFluidState(pos).is(FluidTags.WATER)
                || !level.getFluidState(pos).isSource()
                || !level.getBlockState(pos.above()).isAir()
                || !RobotProcessorWorkScheduler.allowsRobotTarget(robot, pos)) return false;
        if (robot.getZoneToWork() != null
                && !robot.getZoneToWork().contains(Vec3.atCenterOf(pos))) return false;
        // A two-wide pond is fishable with a vanilla rod; the old mandatory
        // 3x3 water square incorrectly rejected small lakes and river edges.
        int neighbors = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) + Math.abs(dz) != 1) continue;
                BlockPos neighbor = pos.offset(dx, 0, dz);
                if (ChunkUtil.getChunk(level, neighbor, false) != null
                        && level.getFluidState(neighbor).is(FluidTags.WATER)) neighbors++;
            }
        }
        return neighbors >= 1;
    }

    private double distanceToWaterSq() {
        double dx = robot.getX() - (water.getX() + 0.5D);
        double dz = robot.getZ() - (water.getZ() + 0.5D);
        double dy = robot.getY() - (water.getY() + 2.0D);
        return dx * dx + dy * dy + dz * dz;
    }

    /** Approach from the robot's side of the shore instead of flying into a lake. */
    private BlockPos approachPosition() {
        double dx = robot.getX() - (water.getX() + 0.5D);
        double dz = robot.getZ() - (water.getZ() + 0.5D);
        double distance = Math.max(0.001D, Math.sqrt(dx * dx + dz * dz));
        int x = (int) Math.floor(water.getX() + 0.5D + 4.0D * dx / distance);
        int z = (int) Math.floor(water.getZ() + 0.5D + 4.0D * dz / distance);
        return new BlockPos(x, water.getY() + 2, z);
    }

    private ServerPlayer player(ServerLevel level) {
        if (angler == null) {
            // A bobber is stored on Player.fishing. Never share it with another
            // fisher controlled by the same owner; give each robot its own actor.
            String key = "buildcraft:fisher:" + robot.getUUID() + ":" + RobotAutomationSupport.owner(robot);
            UUID identity = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
            angler = FakePlayerProvider.INSTANCE.getFakePlayer(level,
                    new GameProfile(identity, "[BC Fisher]"), robot.blockPosition());
            // Client-only representation must not render a second player beside the robot.
            angler.setInvisible(true);
        }
        // FishingHook and FishingHookRenderer both use the fake Player's eye/hand.
        // A normal player's eye is far ABOVE a half-block robot; shift the
        // invisible angler down while keeping the hook attached to this robot.
        double eyeHeight = angler.getEyeY() - angler.getY();
        angler.setPos(robot.getX(), robot.getY() + ROBOT_ROD_ORIGIN_HEIGHT - eyeHeight, robot.getZ());
        return angler;
    }

    /**
     * The real vanilla hook stores its owner as a Player entity ID. FakePlayers
     * created for automation are intentionally absent from server tracking, so
     * vanilla clients cannot resolve that ID and discard the fishing bobber.
     * Mirror only THIS fisher's invisible angler to nearby clients, using the
     * normal player spawn protocol before the vanilla rod casts its hook.
     */
    private void syncAnglerViewers(ServerLevel level) {
        if (angler == null) return;
        // The owner is intentionally a private FakePlayer, not a tracked world
        // entity. Its client copy therefore never receives vanilla movement
        // updates unless Fisher sends them. Without this, the line stays tied
        // to the casting position while the robot moves away.
        Vec3 at = angler.position();
        boolean poseChanged = lastSyncedAnglerPosition == null
                || at.distanceToSqr(lastSyncedAnglerPosition) > ANGLER_POSITION_EPSILON_SQ
                || angler.getYRot() != lastSyncedAnglerYaw
                || angler.getXRot() != lastSyncedAnglerPitch;
        Iterator<ServerPlayer> iter = anglerViewers.iterator();
        while (iter.hasNext()) {
            ServerPlayer viewer = iter.next();
            if (!level.players().contains(viewer)
                    || viewer.distanceToSqr(angler) > ANGLER_SYNC_RADIUS_SQ) {
                if (level.players().contains(viewer)) removeAnglerViewer(viewer);
                iter.remove();
            } else if (poseChanged) {
                //? if <1.21.11 {
                viewer.connection.send(new ClientboundTeleportEntityPacket(angler));
                //?} else {
                viewer.connection.send(ClientboundEntityPositionSyncPacket.of(angler));
                //?}
            }
        }
        lastSyncedAnglerPosition = at;
        lastSyncedAnglerYaw = angler.getYRot();
        lastSyncedAnglerPitch = angler.getXRot();
        for (ServerPlayer viewer : level.players()) {
            if (viewer == angler || viewer.distanceToSqr(angler) > ANGLER_SYNC_RADIUS_SQ
                    || !anglerViewers.add(viewer)) continue;
            // Packet order matters: player profile, player entity, invisible flag,
            // then (from FishingRodItem.use) the actual vanilla fishing hook.
            //? if <1.20 {
            viewer.connection.send(new ClientboundPlayerInfoPacket(
                    ClientboundPlayerInfoPacket.Action.ADD_PLAYER, angler));
            viewer.connection.send(angler.getAddEntityPacket());
            viewer.connection.send(new ClientboundSetEntityDataPacket(
                    angler.getId(), angler.getEntityData(), true));
            //?} else {
            viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(
                    List.of(angler)));
                //? if <1.21.1 {
            viewer.connection.send(angler.getAddEntityPacket());
                //?} else {
            // The Entity/int/BlockPos constructor snaps the fake angler to a
            // whole block on 1.21.1, 1.21.11 AND the 26.X targets. The vanilla
            // fishing line then renders far from the 0.5-block Fisher robot.
            // Spawn the invisible owner at its exact double-precision position.
            viewer.connection.send(new ClientboundAddEntityPacket(
                    angler.getId(), angler.getUUID(),
                    angler.getX(), angler.getY(), angler.getZ(),
                    angler.getXRot(), angler.getYRot(), angler.getType(),
                    0, angler.getDeltaMovement(), angler.getYHeadRot()));
                //?}
            var metadata = angler.getEntityData().getNonDefaultValues();
            if (metadata != null && !metadata.isEmpty()) {
                viewer.connection.send(new ClientboundSetEntityDataPacket(angler.getId(), metadata));
            }
            //?}
            // FishingHookRenderer picks the line's hand using the owner's
            // equipped fishing rod. A phantom client player needs its equipment
            // explicitly, since it is not tracked as a normal server player.
            viewer.connection.send(new ClientboundSetEquipmentPacket(angler.getId(),
                    List.of(Pair.of(EquipmentSlot.MAINHAND,
                            angler.getItemInHand(InteractionHand.MAIN_HAND).copy()))));
        }
    }

    private void removeAnglerViewer(ServerPlayer viewer) {
        if (angler == null) return;
        viewer.connection.send(new ClientboundRemoveEntitiesPacket(angler.getId()));
        //? if <1.20 {
        viewer.connection.send(new ClientboundPlayerInfoPacket(
                ClientboundPlayerInfoPacket.Action.REMOVE_PLAYER, angler));
        //?} else {
        viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(angler.getUUID())));
        //?}
    }

    private void cast(ServerLevel level) {
        if (!RobotAutomationSupport.permitsBlock(robot, level, water,
                WorldOperationKind.ITEM_USE, OperationMode.EXECUTE)) {
            fail();
            return;
        }
        ServerPlayer player = player(level);
        if (player.fishing != null) { // stale hook from an interrupted cast
            player.fishing.discard();
            player.fishing = null;
        }
        ItemStack rod = robot.getItemBySlot(EquipmentSlot.MAINHAND).copy();
        player.setItemInHand(InteractionHand.MAIN_HAND, rod);
        double dx = water.getX() + 0.5D - player.getX();
        double dz = water.getZ() + 0.5D - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = water.getY() + 1.0D - player.getEyeY();
        player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setXRot((float) Math.toDegrees(Math.atan2(-dy, Math.max(0.01D, horizontal))) - 6.0F);
        player.setYHeadRot(player.getYRot());
        player.setYBodyRot(player.getYRot());
        // Client must see the ghost angler at the ROBOT's hand, facing the
        // water and equipped with the rod, before it sees the hook spawn.
        syncAnglerViewers(level);
        // Actual vanilla casting: spawns the real FishingHook entity.
        rod.getItem().use(level, player, InteractionHand.MAIN_HAND);
        hook = player.fishing;
        if (hook == null || hook.isRemoved()) {
            fail();
            return;
        }
        // FishingRodItem.use spawns the actual vanilla hook a short distance
        // BEHIND the player's eyes. Our invisible angler occupies the robot,
        // so this would cause the projectile to collide with its own caster's
        // robot before it can land in the water. Move the vanilla hook just
        // outside the robot, keeping its native velocity, loot and bite logic.
        double reach = Math.sqrt(dx * dx + dz * dz);
        if (reach > 0.001D) {
            hook.setPos(robot.getX() + HOOK_FORWARD_CLEARANCE * dx / reach,
                    robot.getY() + ROBOT_ROD_ORIGIN_HEIGHT,
                    robot.getZ() + HOOK_FORWARD_CLEARANCE * dz / reach);
        }
        castRobotPosition = robot.position();
        reachedWater = false;
        dryTicks = 0;
    }

    private static Field vanillaField(String... names) {
        for (String name : names) {
            try {
                Field field = FishingHook.class.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException | RuntimeException ignored) {
                // The runtime may use a different obfuscation mapping or may
                // forbid reflective access. Fall through to the next mapping.
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static boolean vanillaBiteReady(FishingHook bobber) {
        // The SERVER'S nibble countdown is the condition that vanilla
        // FishingHook.retrieve() itself uses to award fishing loot. Prefer it
        // whenever available; a synced boolean may be applied a tick later.
        if (VANILLA_NIBBLE != null) {
            try {
                return VANILLA_NIBBLE.getInt(bobber) > 0;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // A different mapping may expose only the biting flag below.
            }
        }
        // Modern mappings expose the same bite via 'biting'; others expose a
        // synced EntityDataAccessor. Never guess the first boolean field and
        // never treat ordinary downward bobber motion as a bite.
        if (VANILLA_BITING_FLAG != null) {
            try {
                return VANILLA_BITING_FLAG.getBoolean(bobber);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the known synced accessor.
            }
        }
        if (VANILLA_BITING_DATA != null) {
            try {
                Object dataKey = VANILLA_BITING_DATA.get(null);
                if (dataKey instanceof EntityDataAccessor<?> accessor) {
                    return Boolean.TRUE.equals(bobber.getEntityData().get((EntityDataAccessor<Boolean>) accessor));
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Fail closed; never reel from a timer or guessed field.
            }
        }
        return false;
    }

    /** A missed/collided/expired hook is NOT a fish bite. Simply discard it:
     * invoking FishingRodItem.use() here damages the rod and visually reels
     * the line, despite there being nothing to catch.
     */
    private void cancelCast() {
        boolean navigating = moving;
        if (hook != null && !hook.isRemoved()) hook.discard();
        if (angler != null) {
            if (angler.fishing == hook) angler.fishing = null;
            angler.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        hook = null;
        castRobotPosition = null;
        reachedWater = false;
        dryTicks = 0;
        hideAnglerViewers();
        rejectWater();
        moving = navigating;
    }

    /** Remove the private player mirror during navigation and between casts.
     * This also hides its held rod and prevents orphaned fishing lines.
     */
    private void hideAnglerViewers() {
        for (ServerPlayer viewer : anglerViewers) removeAnglerViewer(viewer);
        anglerViewers.clear();
        lastSyncedAnglerPosition = null;
    }

    private void waitForVanillaBite(ServerLevel level) {
        ServerPlayer player = player(level);
        syncAnglerViewers(level);
        if (!RobotAutomationSupport.permitsBlock(robot, level, water,
                WorldOperationKind.ITEM_USE, OperationMode.SIMULATE)) {
            fail();
            return;
        }
        if (hook.isRemoved() || player.fishing != hook) {
            // A hook can disappear from an unloaded section or due to a
            // collision. Treat this as a missed cast, not a dead profession.
            cancelCast();
            return;
        }
        robot.aimItemAt(water);
        robot.setItemActive(true);
        if (hook.isInWater()) {
            reachedWater = true;
            dryTicks = 0;
        } else {
            dryTicks++;
        }
        if (hook.getHookedIn() != null) {
            // Hooking Fisher or a nearby entity is a failed CAST, not a bite.
            // Never invoke vanilla retrieve() on a non-fish collision.
            cancelCast();
            return;
        }
        if (reachedWater && vanillaBiteReady(hook)) {
            reelAfterBite(level);
            return;
        }
        if (dryTicks >= BOBBER_LAND_TIMEOUT_TICKS) {
            // The hook never reached water, or has stayed out for eight seconds.
            // A short splash/surface bounce is not a reason to discard a bite.
            cancelCast();
        }
    }

    private void reelAfterBite(ServerLevel level) {
        if (!RobotAutomationSupport.permitsBlock(robot, level, water,
                WorldOperationKind.ITEM_USE, OperationMode.EXECUTE)) {
            fail();
            return;
        }
        ServerPlayer player = player(level);
        ItemStack rod = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!BoardRobotFisher.isUsableRod(rod) || player.fishing != hook) {
            fail();
            return;
        }
        // Actual vanilla reeling: loot tables, enchantments, XP, sounds and rod
        // damage are handled by FishingHook.retrieve and FishingRodItem.use.
        rod.getItem().use(level, player, InteractionHand.MAIN_HAND);
        robot.setItemInUse(player.getItemInHand(InteractionHand.MAIN_HAND).copy());
        if (BoardRobotFisher.isUsableRod(robot.getItemBySlot(EquipmentSlot.MAINHAND))
                && player.fishing == null) {
            // Keep fishing this pool without scanning another 19k positions after
            // every catch. Allow the cast animation to finish before the next throw.
            hook = null;
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            reachedWater = false;
            dryTicks = 0;
            castRobotPosition = null;
            windup = -20;
            robot.setItemActive(false);
            hideAnglerViewers();
            return;
        }
        terminate();
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotGotoBlock) {
            moving = false;
            if (!ai.success() || water == null || distanceToWaterSq() > MAX_CAST_DISTANCE_SQ) {
                // The closest water may be behind a wall or unreachable shore.
                // Continue searching rather than sleeping forever on that spot.
                rejectWater();
            }
        }
    }

    private void fail() {
        setSuccess(false);
        terminate();
    }

    @Override
    public void end() {
        robot.setItemActive(false);
        clearBobber();
    }

    @Override
    public void onUnload() {
        super.onUnload();
        clearBobber();
    }

    private void clearBobber() {
        // Undo only Fisher's client-side phantom angler, never the shared real
        // player list, other robot entities or the owning human player's state.
        hideAnglerViewers();
        if (hook != null && !hook.isRemoved()) hook.discard();
        if (angler != null) {
            if (angler.fishing == hook) angler.fishing = null;
            angler.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        hook = null;
    }

    @Override
    public int getEnergyCost() {
        // Discovering water and waiting for chunks is not active fishing.
        return water == null ? 0 : 2;
    }
}
