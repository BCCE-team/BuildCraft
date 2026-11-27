package buildcraft.robotics.item;

import buildcraft.api.v2.robot.RobotEventKind;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;
import buildcraft.robotics.internal.api2.RobotEventSupport;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.IRobotRegistry;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
//? if >=1.21.1 {
import buildcraft.lib.misc.ItemStackUtil;
//?}

/** Identity stored only on a real dismantled robot, never on crafted/model/creative templates. */
public final class RobotItemIdentity {
    private static final String KEY = "bc_packed_robot";
    private RobotItemIdentity() { }

    private static UUID ownerId(GameProfile profile) {
        if (profile == null) return null;
        //? if >=1.21.11 {
        return buildcraft.lib.compat.GameProfileCompat.id(profile);
        //?} else {
        return profile.getId();
        //?}
    }

    private static String ownerName(GameProfile profile) {
        if (profile == null) return "";
        //? if >=1.21.11 {
        String name = buildcraft.lib.compat.GameProfileCompat.name(profile);
        //?} else {
        String name = profile.getName();
        //?}
        return name == null ? "" : name;
    }

    private static boolean unowned(UUID owner) {
        return owner == null || owner.equals(ownerId(FakePlayerProvider.NULL_PROFILE));
    }

    public static boolean operator(Player player) {
        return player instanceof ServerPlayer && player.hasPermissions(2);
    }

    private static boolean allows(UUID owner, Player player) {
        return player != null && (unowned(owner) || owner.equals(player.getUUID()) || operator(player));
    }

    public static boolean hasOwnershipAccess(EntityRobotBase robot, Player player) {
        return robot != null && allows(ownerId(RobotAutomationSupport.owner(robot)), player);
    }

    public static boolean canManage(EntityRobotBase robot, Player player, ItemStack held) {
        return hasOwnershipAccess(robot, player)
                && !RobotEventSupport.denied(RobotEventKind.INTERACT, robot, player, held);
    }

    private static CompoundTag root(ItemStack stack) {
        //? if >=1.21.1 {
        return ItemStackUtil.getCustomData(stack);
        //?} else {
        return stack.getTag() == null ? new CompoundTag() : stack.getTag().copy();
        //?}
    }
    private static CompoundTag data(CompoundTag root) {
        //? if >=1.21.11 {
        return buildcraft.lib.compat.NbtCompat.getCompound(root, KEY);
        //?} else {
        return root.getCompound(KEY);
        //?}
    }
    private static String string(CompoundTag data, String key) {
        //? if >=1.21.11 {
        return buildcraft.lib.compat.NbtCompat.getString(data, key);
        //?} else {
        return data.getString(key);
        //?}
    }
    private static long id(CompoundTag data) {
        //? if >=1.21.11 {
        return buildcraft.lib.compat.NbtCompat.getLong(data, "id");
        //?} else {
        return data.getLong("id");
        //?}
    }
    private static void write(ItemStack stack, CompoundTag root) {
        //? if >=1.21.1 {
        ItemStackUtil.setCustomData(stack, root);
        //?} else {
        stack.setTag(root);
        //?}
    }

    public static ItemStack pack(EntityRobotBase robot, ItemStack stack) {
        IRobotRegistry registry = robot.getRegistry();
        long id = robot.getRobotId();
        if (registry == null || id <= 0 || id == Long.MAX_VALUE) return stack;
        GameProfile profile = RobotAutomationSupport.owner(robot);
        UUID owner = ownerId(profile);
        String ownerKey = unowned(owner) ? "" : owner.toString();
        String name = ownerKey.isEmpty() ? "" : ownerName(profile);
        String token = registry.robotIdentities().reserve(id, ownerKey, name);
        CompoundTag packed = new CompoundTag();
        packed.putLong("id", id);
        packed.putString("token", token);
        packed.putString("owner", ownerKey);
        packed.putString("name", name);
        CompoundTag root = root(stack);
        root.put(KEY, packed);
        write(stack, root);
        registry.registryMarkDirty();
        return stack;
    }

    public static boolean isPacked(ItemStack stack) { return root(stack).contains(KEY); }

    /** 0 is a new robot; -1 rejects invalid, copied, foreign-dimension or unauthorized items. */
    public static long preparePlacement(ItemStack stack, EntityRobot robot, Player player) {
        if (!isPacked(stack)) {
            robot.setOwner(player.getGameProfile());
            return 0;
        }
        if (stack.getCount() != 1) return -1;
        CompoundTag packed = data(root(stack));
        long id = id(packed);
        String ownerKey = string(packed, "owner"), name = string(packed, "name");
        String token = string(packed, "token");
        IRobotRegistry registry = robot.getRegistry();
        if (registry == null || registry.getLoadedRobot(id) != null
                || !registry.robotIdentities().matches(id, token, ownerKey, name)) return -1;
        UUID owner;
        try { owner = ownerKey.isEmpty() ? null : UUID.fromString(ownerKey); }
        catch (IllegalArgumentException invalid) { return -1; }
        if (!allows(owner, player)) return -1;
        robot.setOwner(owner == null ? player.getGameProfile() : new GameProfile(owner, name.isEmpty() ? owner.toString() : name));
        return id;
    }

    /** Called after API PLACE listeners and immediately before live registration. */
    public static boolean claimPlacement(ItemStack stack, IRobotRegistry registry, long savedId) {
        if (savedId == 0) return !isPacked(stack);
        CompoundTag packed = data(root(stack));
        if (id(packed) != savedId || registry.getLoadedRobot(savedId) != null) return false;
        boolean claimed = registry.robotIdentities().consume(savedId, string(packed, "token"),
                string(packed, "owner"), string(packed, "name"));
        if (claimed) registry.registryMarkDirty();
        return claimed;
    }

    public static void cancelPlacement(ItemStack stack, IRobotRegistry registry, long savedId) {
        if (savedId <= 0) return;
        CompoundTag packed = data(root(stack));
        if (id(packed) != savedId) return;
        registry.robotIdentities().restoreClaim(savedId, string(packed, "token"),
                string(packed, "owner"), string(packed, "name"));
        registry.registryMarkDirty();
    }
}
