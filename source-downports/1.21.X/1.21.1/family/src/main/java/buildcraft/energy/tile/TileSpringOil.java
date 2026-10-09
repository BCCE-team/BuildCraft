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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

// We don't extend TileBC here because we have no need of any of its functions.
public class TileSpringOil extends BCBlockEntity implements IDebuggable, ITileOilSpring {

	private static final ResourceLocation ADVANCEMENT_PUMP_LARGE_OIL_WELL = ResourceLocation.parse("buildcraftfactory:black_gold");

    @Override
    protected boolean storesMachineDataAtRoot() { return true; }
    @Override
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
    
    @Override
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
                AdvancementUtil.unlockAdvancement(profile.getId(), ADVANCEMENT_PUMP_LARGE_OIL_WELL);
            }
        }
    }

    @Override
    protected void readData(BCValueInput bcData) {
        totalSources = bcData.readInt("totalSources");
        for (Tag entry : bcData.readList("pumpProgress", Tag.TAG_COMPOUND)) {
            if (entry instanceof CompoundTag tag) {
                PlayerPumpInfo info = new PlayerPumpInfo(tag);
                if (info.profile != null) pumpProgress.put(info.profile, info);
            }
        }
    }
    
    @Override
    protected void writeData(BCValueOutput bcData) {
        bcData.writeInt("totalSources", totalSources);
        ListTag list = new ListTag();
        for (PlayerPumpInfo info : pumpProgress.values()) list.add(info.writeToNbt());
        bcData.put("pumpProgress", list);
    }

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        left.add("totalSources = " + totalSources);
        boolean added = false;
        for (PlayerPumpInfo info : pumpProgress.values()) {
            if (!added) {
                left.add("Player Progress:");
                added = true;
            }
            left.add("  " + info.profile.getName() + " = " + info.sourcesPumped + " ( "
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
            profile = readGameProfile(nbt.getCompound("profile"));
            lastPumpTick = nbt.getLong("lastPumpTick");
            sourcesPumped = nbt.getInt("sourcesPumped");
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
            UUID id = nbt.hasUUID("Id") ? nbt.getUUID("Id") : null;
            String name = nbt.contains("Name", Tag.TAG_STRING) ? nbt.getString("Name") : null;
            if (id == null && (name == null || name.isBlank())) {
                return null;
            }
            return new GameProfile(id, name);
        }

        private static CompoundTag writeGameProfile(GameProfile profile) {
            CompoundTag nbt = new CompoundTag();
            if (profile.getId() != null) {
                nbt.putUUID("Id", profile.getId());
            }
            if (profile.getName() != null) {
                nbt.putString("Name", profile.getName());
            }
            return nbt;
        }
    }
}
