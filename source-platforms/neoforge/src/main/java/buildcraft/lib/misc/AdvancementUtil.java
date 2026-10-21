package buildcraft.lib.misc;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import buildcraft.lib.internal.debug.BCLog;
import net.minecraft.advancements.AdvancementHolder;
//import net.minecraft.entity.player.PlayerMP;
//? if mc_26_x {
import net.minecraft.resources.Identifier;
//? } else {
import net.minecraft.resources.ResourceLocation;
//?}
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
//import net.minecraft.advancements.AdvancementManager;
//import net.minecraft.advancements.PlayerAdvancements;
//import net.minecraft.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.server.ServerLifecycleHooks;


//import buildcraft.lib.internal.debug.BCLog;

public class AdvancementUtil {
//? if mc_26_x {
    private static final Set<Identifier> UNKNOWN_ADVANCEMENTS = new HashSet<>();
//? } else {
    private static final Set<ResourceLocation> UNKNOWN_ADVANCEMENTS = new HashSet<>();
//?}

//? if mc_26_x {
    public static void unlockAdvancement(Player player, Identifier advancementName) {
//? } else {
    public static void unlockAdvancement(Player player, ResourceLocation advancementName) {
//?}
        if (!(player instanceof ServerPlayer requestedPlayer)) {
            return;
        }

        ServerPlayer playerMP = resolveConnectedPlayer(requestedPlayer);
        if (playerMP == null) {
            return;
        }

//? if mc_26_x {
        ServerAdvancementManager advancementManager = playerMP.level().getServer().getAdvancements();
//? } else {
        ServerAdvancementManager advancementManager = playerMP.getServer().getAdvancements();
//?}
        if (advancementManager == null) {
            // Because this *can* happen
            return;
        }
        AdvancementHolder advancement = advancementManager.get(advancementName);
        if (advancement != null) {
            // Never assume the advancement exists, we create them but they are removable by datapacks.
            // Always restore the connected player before awarding: a fake player with the same GameProfile
            // can otherwise leave this shared tracker pointing at a null network connection.
            PlayerAdvancements tracker = playerMP.getAdvancements();
            tracker.setPlayer(playerMP);
            tracker.award(advancement, "code_trigger");
        } else if (UNKNOWN_ADVANCEMENTS.add(advancementName)) {
            BCLog.logger.warn("[lib.advancement] Attempted to trigger undefined advancement: " + advancementName);
        }
    }

    private static ServerPlayer resolveConnectedPlayer(ServerPlayer requestedPlayer) {
        if (requestedPlayer.connection != null) {
            return requestedPlayer;
        }
//? if mc_26_x {
        if (requestedPlayer.level().getServer() == null) {
//? } else {
        if (requestedPlayer.getServer() == null) {
//?}
            return null;
        }
//? if mc_26_x {
        ServerPlayer onlinePlayer = requestedPlayer.level().getServer().getPlayerList().getPlayer(requestedPlayer.getUUID());
//? } else {
        ServerPlayer onlinePlayer = requestedPlayer.getServer().getPlayerList().getPlayer(requestedPlayer.getUUID());
//?}
        return onlinePlayer != null && onlinePlayer.connection != null ? onlinePlayer : null;
    }

//? if mc_26_x {
    public static boolean unlockAdvancement(UUID player, Identifier advancementName) {
//? } else {
    public static boolean unlockAdvancement(UUID player, ResourceLocation advancementName) {
//?}
        ServerPlayer playermp = ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(player);
        if (playermp != null && playermp.connection != null) {
            unlockAdvancement((Player) playermp, advancementName);
            return true;
        }
        return false;
    }
}
