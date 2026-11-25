package buildcraft.lib.misc;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import buildcraft.lib.internal.debug.BCLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Optional coordinate bridge for live block entities on Sable plots and VS2 ships.
 * Storage positions remain unchanged for inventory, packet routing and neighbour access.
 * Only spatial tests and explicitly world-space operations use projected coordinates.
 * No static linkage to either optional mod, and no ship reference survives a call.
 */
public final class ShipWorldAccess {
    private ShipWorldAccess() {}
    private static final AtomicBoolean WARNED = new AtomicBoolean();
    private static void warn(Throwable failure) {
        if (WARNED.compareAndSet(false, true)) {
            BCLog.logger.warn("[physics] Optional ship coordinate bridge failed; spatial interactions and unsafe world operations are rejected: {}", failure.toString());
        }
    }

    private static final class Backend {
        final Object receiver;
        final Method containing, project, distance;
        final boolean sable;
        Backend(Object receiver, Method containing, Method project, Method distance, boolean sable) {
            this.receiver = receiver; this.containing = containing; this.project = project;
            this.distance = distance; this.sable = sable;
        }
        Object ship(Level level, BlockPos pos) throws ReflectiveOperationException {
            return sable ? containing.invoke(receiver, level, pos.getX() >> 4, pos.getZ() >> 4)
                         : containing.invoke(null, level, pos);
        }
        Vec3 world(Level level, Vec3 point) throws ReflectiveOperationException {
            return (Vec3) project.invoke(receiver, level, point);
        }
        double distance(Player player, Level level, Vec3 point) throws ReflectiveOperationException {
            if (!sable) return ((Number) distance.invoke(null, player, point.x, point.y, point.z)).doubleValue();
            Vec3 p = player.position();
            return ((Number) distance.invoke(receiver, level, p.x, p.y, p.z, point.x, point.y, point.z)).doubleValue();
        }
    }
    private record Resolution(Backend backend, boolean failed) {}
    private static final class OptionalBackend {
        static final Resolution INSTANCE = resolve();
        private static Resolution resolve() {
            ClassLoader loader = ShipWorldAccess.class.getClassLoader();
            // Sable exposes the public companion helper; VS exposes Kotlin extension functions as static methods.
            try {
                Class<?> sable = Class.forName("dev.ryanhcode.sable.Sable", false, loader);
                Object helper = sable.getField("HELPER").get(null);
                Class<?> api = helper.getClass();
                return new Resolution(new Backend(helper,
                    api.getMethod("getContaining", Level.class, int.class, int.class),
                    api.getMethod("projectOutOfSubLevel", Level.class, Vec3.class),
                    api.getMethod("distanceSquaredWithSubLevels", Level.class, double.class, double.class,
                        double.class, double.class, double.class, double.class), true), false);
            } catch (ClassNotFoundException absent) {
                // VS2 is the legacy physics backend.
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                warn(failure); return new Resolution(null, true);
            }
            try {
                Class<?> vs = Class.forName("org.valkyrienskies.mod.common.VSGameUtilsKt", false, loader);
                return new Resolution(new Backend(null,
                    vs.getMethod("getShipManagingPos", Level.class, BlockPos.class),
                    vs.getMethod("toWorldCoordinates", Level.class, Vec3.class),
                    vs.getMethod("squaredDistanceToInclShips", Entity.class, double.class, double.class, double.class), false), false);
            } catch (ClassNotFoundException absent) {
                return new Resolution(null, false);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                warn(failure); return new Resolution(null, true);
            }
        }
    }

    public static Level playerLevel(Player player) {
        //? if <1.20 {
        return player.level;
        //?} else {
        /*?
        return player.level();
        ?*/
        //?}
    }
    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }
    private static boolean finite(Vec3 v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
    private static double squared(Vec3 a, Vec3 b) {
        if (!finite(a) || !finite(b)) return Double.POSITIVE_INFINITY;
        double dx=a.x-b.x, dy=a.y-b.y, dz=a.z-b.z;
        return dx*dx+dy*dy+dz*dz;
    }

    /** Fail closed on an installed but incompatible backend for world-changing machines. */
    public static boolean isMounted(Level level, BlockPos pos) {
        if (level == null || pos == null) return false;
        Resolution r = OptionalBackend.INSTANCE;
        if (r.failed()) return true;
        if (r.backend() == null) return false;
        try { return r.backend().ship(level, pos) != null; }
        catch (ReflectiveOperationException | LinkageError | RuntimeException failure) { warn(failure); return true; }
    }

    /** Null means a physical transform was unavailable; do not substitute plot coordinates. */
    public static Vec3 worldPoint(Level level, Vec3 point) {
        if (level == null || !finite(point)) return null;
        Resolution r = OptionalBackend.INSTANCE;
        if (r.failed()) return null;
        if (r.backend() == null) return point;
        try {
            Vec3 result = r.backend().world(level, point);
            return finite(result) ? result : null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) { warn(failure); return null; }
    }
    public static BlockPos worldBlockPos(Level level, BlockPos pos) {
        if (pos == null) return null;
        Vec3 p = worldPoint(level, center(pos));
        if (p == null || Math.abs(p.x) > Integer.MAX_VALUE - 1 || Math.abs(p.y) > Integer.MAX_VALUE - 1
            || Math.abs(p.z) > Integer.MAX_VALUE - 1) return null;
        return new BlockPos((int)Math.floor(p.x), (int)Math.floor(p.y), (int)Math.floor(p.z));
    }
    public static double distanceSquared(Level level, Vec3 a, Vec3 b) {
        return squared(worldPoint(level, a), worldPoint(level, b));
    }
    public static boolean withinReach(Player player, BlockPos pos, double maxSquared) {
        return player != null && withinReach(player, playerLevel(player), pos, maxSquared);
    }
    public static boolean withinReach(Player player, Level level, BlockPos pos, double maxSquared) {
        if (player == null || level == null || pos == null || playerLevel(player) != level
            || !Double.isFinite(maxSquared) || maxSquared < 0) return false;
        Resolution r = OptionalBackend.INSTANCE;
        if (r.failed()) return false;
        Vec3 target = center(pos);
        double distance;
        try {
            distance = r.backend() == null ? squared(player.position(), target) : r.backend().distance(player, level, target);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) { warn(failure); return false; }
        return Double.isFinite(distance) && distance >= 0 && distance <= maxSquared;
    }
}
