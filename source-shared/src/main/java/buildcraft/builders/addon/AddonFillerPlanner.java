/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.addon;

import java.io.IOException;
import java.util.stream.IntStream;

import javax.annotation.Nullable;

import buildcraft.lib.internal.area.IBox;
import buildcraft.builders.internal.filler.legacy.IFillerPattern;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.internal.statement.containers.IFillerStatementContainer;
import buildcraft.builders.BCBuildersSprites;
import buildcraft.builders.BCBuildersItems;
import buildcraft.builders.platform.PlannerMenuOpening;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import buildcraft.builders.filler.FillerType;
import buildcraft.builders.filler.FillerUtil;
import buildcraft.builders.snapshot.Template;
import buildcraft.core.marker.volume.Addon;
import buildcraft.core.marker.volume.AddonDefaultRenderer;
import buildcraft.core.marker.volume.IFastAddonRenderer;
import buildcraft.core.marker.volume.ISingleAddon;
import buildcraft.lib.statement.FullStatement;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public class AddonFillerPlanner extends Addon implements ISingleAddon, IFillerStatementContainer {
    public final FullStatement<IFillerPattern> patternStatement = new FullStatement<>(
        FillerType.INSTANCE,
        4,
        null
    );
    public boolean inverted;
    @Nullable
    public Template.BuildingInfo buildingInfo;

    public void updateBuildingInfo() {
        buildingInfo = null;
        if (volumeBox == null || volumeBox.box == null ||
            volumeBox.box.min() == null || volumeBox.box.max() == null) return;
        // The pattern factory allocates and fills a complete template, independently of the
        // renderer's near-camera cap. Bound it before allocating, using long to avoid overflow.
        var size = volumeBox.box.size();
        long voxels = (long) size.getX() * size.getY() * size.getZ();
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0 || voxels > 2_000_000L) return;
        buildingInfo = FillerUtil.createBuildingInfo(
            this,
            patternStatement,
            IntStream.range(0, patternStatement.maxParams)
                .mapToObj(patternStatement::get)
                .toArray(IStatementParameter[]::new),
            inverted
        );
    }

    @Override
    public void onVolumeBoxSizeChange() {
        updateBuildingInfo();
    }

    @Override
    public IFastAddonRenderer<AddonFillerPlanner> getRenderer() {
        return new AddonDefaultRenderer<AddonFillerPlanner>(BCBuildersSprites.FILLER_PLANNER.getSprite())
            .then(new AddonRendererFillerPlanner());
    }

    @Override
    public void onAdded() {
        super.onAdded();
        updateBuildingInfo();
    }

    @Override
    public void postReadFromNbt() {
        super.postReadFromNbt();
        updateBuildingInfo();
    }

    @Override
    public void onPlayerRightClick(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        if (volumeBox == null || !volumeBox.addons.containsValue(this)) return;
        PlannerMenuOpening.open(serverPlayer, volumeBox.id, getSlot());
    }

    /** Return the attached item on dismantling or deleting an unlocked Volume Box. */
    @Override
    public void onRemoved() {
        if (volumeBox == null || volumeBox.world.isClientSide) return;
        Vec3 center = getBoundingBox().getCenter();
        ItemEntity dropped = new ItemEntity(volumeBox.world, center.x, center.y, center.z,
            new ItemStack(BCBuildersItems.FILLER_PLANNER.get()));
        volumeBox.world.addFreshEntity(dropped);
    }

    @Override
    public CompoundTag writeToNBT(CompoundTag nbt) {
        nbt.put("patternStatement", patternStatement.writeToNbt());
        nbt.putBoolean("inverted", inverted);
        return nbt;
    }

    @Override
    public void readFromNBT(CompoundTag nbt) {
        patternStatement.readFromNbt(nbt.getCompound("patternStatement"));
        inverted = nbt.getBoolean("inverted");
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        patternStatement.writeToBuffer(buf);
        buf.writeBoolean(inverted);
    }

    @Override
    public void fromBytes(FriendlyByteBuf buf) throws IOException {
        patternStatement.readFromBuffer(buf);
        inverted = buf.readBoolean();
        updateBuildingInfo();
    }

    // IFillerStatementContainer

    @Override
    public BlockEntity getNeighbourTile(Direction side) {
        return null;
    }

    @Override
    public BlockEntity getTile() {
        return null;
    }

    @Override
    public Level getFillerWorld() {
        return volumeBox.world;
    }

    @Override
    public boolean hasBox() {
        return true;
    }

    @Override
    public IBox getBox() {
        return volumeBox.box;
    }

    @Override
    public void setPattern(IFillerPattern pattern, IStatementParameter[] params) {
        patternStatement.set(pattern, params);
        updateBuildingInfo();
    }
}
