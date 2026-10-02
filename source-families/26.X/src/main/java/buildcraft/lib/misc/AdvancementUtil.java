package buildcraft.lib.misc;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import buildcraft.lib.internal.debug.BCLog;
import net.minecraft.advancements.AdvancementHolder;
//import net.minecraft.entity.player.PlayerMP;
import net.minecraft.resources.Identifier;
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
    private static final Set<Identifier> UNKNOWN_ADVANCEMENTS = new HashSet<>();

    public static void unlockAdvancement(Player player, Identifier advancementName) {
        if (!(player instanceof ServerPlayer requestedPlayer)) {
            return;
        }

        ServerPlayer playerMP = resolveConnectedPlayer(requestedPlayer);
        if (playerMP == null) {
            return;
        }

        ServerAdvancementManager advancementManager = playerMP.level().getServer().getAdvancements();
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
        if (requestedPlayer.level().getServer() == null) {
            return null;
        }
        ServerPlayer onlinePlayer = requestedPlayer.level().getServer().getPlayerList().getPlayer(requestedPlayer.getUUID());
        return onlinePlayer != null && onlinePlayer.connection != null ? onlinePlayer : null;
    }

    public static boolean unlockAdvancement(UUID player, Identifier advancementName) {
        ServerPlayer playermp = ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(player);
        if (playermp != null && playermp.connection != null) {
            unlockAdvancement((Player) playermp, advancementName);
            return true;
        }
        return false;
    }
}
