//? source if >=1.21.1
package buildcraft.energy.tile;

import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;
import buildcraft.lib.compat.minecraft.persistence.BCBlockEntity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.energy.BCEnergyBlocks;
import buildcraft.lib.misc.AdvancementUtil;
import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import buildcraft.lib.compat.GameProfileCompat;
import buildcraft.lib.compat.NbtCompat;

// We don't extend TileBC here because we have no need of any of its functions.
public class TileSpringOil extends BCBlockEntity implements IDebuggable, ITileOilSpring {

	private static final Identifier ADVANCEMENT_PUMP_LARGE_OIL_WELL = Identifier.parse("buildcraftfactory:black_gold");

    protected boolean storesMachineDataAtRoot() { return true; }
    protected boolean requiresPersistenceRegistries() { return false; }

    private final Map<GameProfile, PlayerPumpInfo> pumpProgress = new ConcurrentHashMap<>();

    /** An approximation of the total number of oil source blocks in the oil spring. The actual number will be less than
     * this, so this is taken as an approximation.
     * <p>
     * Note that this SHOULD NEVER be set! (Except by the generator, and readFromNbt) */
    public int totalSources;

    public TileSpringOil(BlockPos pos, BlockState state) {
		super(BCEnergyBlocks.TILE_SPRING.get(), pos, state);
	}
    
    public void onPumpOil(GameProfile profile, BlockPos oilPos) {
        if (profile == null) {
            // BCLog.logger.warn("Unknown owner for pump at " + pump.getPos());
            return;
        }
        PlayerPumpInfo info = pumpProgress.computeIfAbsent(profile, PlayerPumpInfo::new);
        info.lastPumpTick = level.getGameTime();
        info.sourcesPumped++;

        // BCLog.logger.info("Pumped " + info.sourcesPumped + " / " + totalSources + " at " + oilPos + " (for " +
        // System.identityHashCode(this) + ", "+getPos()+")");
        if (info.sourcesPumped >= totalSources * 7 / 8) {
            // BCLog.logger.info("Pumped nearly all oil blocks!");
            if (oilPos.equals(getBlockPos().above())) {
                AdvancementUtil.unlockAdvancement(GameProfileCompat.id(profile), ADVANCEMENT_PUMP_LARGE_OIL_WELL);
            }
        }
    }

    protected void readData(BCValueInput bcData) {
        totalSources = bcData.readInt("totalSources");
        pumpProgress.clear();
        for (Tag entry : bcData.readList("pumpProgress", Tag.TAG_COMPOUND)) {
            if (entry instanceof CompoundTag tag) {
                PlayerPumpInfo info = new PlayerPumpInfo(tag);
                if (info.profile != null) pumpProgress.put(info.profile, info);
            }
        }
    }
    
    protected void writeData(BCValueOutput bcData) {
        bcData.writeInt("totalSources", totalSources);
        ListTag list = new ListTag();
        for (PlayerPumpInfo info : pumpProgress.values()) list.add(info.writeToNbt());
        bcData.put("pumpProgress", list);
    }

    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        left.add("totalSources = " + totalSources);
        boolean added = false;
        for (PlayerPumpInfo info : pumpProgress.values()) {
            if (!added) {
                left.add("Player Progress:");
                added = true;
            }
            left.add("  " + GameProfileCompat.name(info.profile) + " = " + info.sourcesPumped + " ( "
                + (level.getGameTime() - info.lastPumpTick) / 20 + "s )");
        }
    }

    static class PlayerPumpInfo {
        final GameProfile profile;
        long lastPumpTick = -1;
        int sourcesPumped = 0;

        public PlayerPumpInfo(GameProfile profile) {
            this.profile = profile;
        }

        public PlayerPumpInfo(CompoundTag nbt) {
            profile = readGameProfile(NbtCompat.getCompound(nbt, "profile"));
            lastPumpTick = NbtCompat.getLong(nbt, "lastPumpTick");
            sourcesPumped = NbtCompat.getInt(nbt, "sourcesPumped");
        }

        public CompoundTag writeToNbt() {
            CompoundTag nbt = new CompoundTag();
            nbt.put("profile", writeGameProfile(profile));
            nbt.putLong("lastPumpTick", lastPumpTick);
            nbt.putInt("sourcesPumped", sourcesPumped);
            return nbt;
        }

        @Nullable
        private static GameProfile readGameProfile(CompoundTag nbt) {
            UUID id = NbtCompat.hasUUID(nbt, "Id") ? NbtCompat.getUUID(nbt, "Id") : null;
            String name = NbtCompat.contains(nbt, "Name", Tag.TAG_STRING) ? NbtCompat.getString(nbt, "Name") : null;
            if (id == null && (name == null || name.isBlank())) {
                return null;
            }
            return new GameProfile(id, name);
        }

        private static CompoundTag writeGameProfile(GameProfile profile) {
            CompoundTag nbt = new CompoundTag();
            if (GameProfileCompat.id(profile) != null) {
                NbtCompat.putUUID(nbt, "Id", GameProfileCompat.id(profile));
            }
            if (GameProfileCompat.name(profile) != null) {
                nbt.putString("Name", GameProfileCompat.name(profile));
            }
            return nbt;
        }
    }
}
