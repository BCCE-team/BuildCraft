package buildcraft.builders.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
//? if <1.20 {
import com.mojang.math.Matrix3f;
import com.mojang.math.Matrix4f;
//?} else {
/*?
import org.joml.Matrix3f;
import org.joml.Matrix4f;
?*/
//?}

import buildcraft.builders.BCBuildersSprites;
import buildcraft.builders.tile.TileQuarry;
import buildcraft.core.client.BuildCraftLaserManager;
import buildcraft.lib.client.render.laser.LaserBoxRenderer;
import buildcraft.lib.client.render.laser.LaserData_BC8;
import buildcraft.lib.client.render.laser.LaserRenderer_BC8;
import buildcraft.lib.misc.VecUtil;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public class RenderQuarry implements BlockEntityRenderer<TileQuarry>{
    public static final LaserData_BC8.LaserType FRAME;
    public static final LaserData_BC8.LaserType FRAME_BOTTOM;
    public static final LaserData_BC8.LaserType DRILL;
    public static final LaserData_BC8.LaserType LASER;
    
    private static final int ID = LaserData_BC8.AllocateId(6);

    static {
        {
            var sprite = BCBuildersSprites.QUARRY_FRAME;
            LaserData_BC8.LaserRow capStart = new LaserData_BC8.LaserRow(sprite, 0, 0, 0, 0);
            LaserData_BC8.LaserRow start = null;
            LaserData_BC8.LaserRow[] middle = { new LaserData_BC8.LaserRow(sprite, 0, 4, 16, 12) };
            LaserData_BC8.LaserRow end = new LaserData_BC8.LaserRow(sprite, 0, 4, 16, 12);
            LaserData_BC8.LaserRow capEnd = new LaserData_BC8.LaserRow(sprite, 0, 0, 0, 0);
            FRAME = new LaserData_BC8.LaserType(capStart, start, middle, end, capEnd);
        }
        {
            var sprite = BCBuildersSprites.QUARRY_FRAME;
            LaserData_BC8.LaserRow capStart = new LaserData_BC8.LaserRow(sprite, 0, 0, 0, 0);
            LaserData_BC8.LaserRow start = null;
            LaserData_BC8.LaserRow[] middle = { new LaserData_BC8.LaserRow(sprite, 0, 4, 16, 12) };
            LaserData_BC8.LaserRow end = new LaserData_BC8.LaserRow(sprite, 0, 4, 16, 12);
            LaserData_BC8.LaserRow capEnd = new LaserData_BC8.LaserRow(sprite, 4, 4, 12, 12);
            FRAME_BOTTOM = new LaserData_BC8.LaserType(capStart, start, middle, end, capEnd);
        }
        {
            var sprite = BCBuildersSprites.QUARRY_DRILL;
            LaserData_BC8.LaserRow capStart = new LaserData_BC8.LaserRow(sprite, 6, 0, 10, 4);
            LaserData_BC8.LaserRow start = null;
            LaserData_BC8.LaserRow[] middle = { new LaserData_BC8.LaserRow(sprite, 0, 0, 16, 4) };
            LaserData_BC8.LaserRow end = null;
            LaserData_BC8.LaserRow capEnd = new LaserData_BC8.LaserRow(sprite, 6, 0, 10, 4);
            DRILL = new LaserData_BC8.LaserType(capStart, start, middle, end, capEnd);
        }
        {
            LASER = BuildCraftLaserManager.POWER_LOW;
        }
    }

	public RenderQuarry(BlockEntityRendererProvider.Context bpc) {
	}

	@Override
	public void render(TileQuarry tile, float partialTicks, PoseStack matrix, MultiBufferSource buffer, int light, int overlay) {
		matrix.pushPose();
		// The quarry frame texture contains one-pixel lattice lines repeated over long beams.
		// RenderType.cutout() disables mipmapping, which makes those lines alias and shimmer when
		// viewed at a distance or oblique angle. The mipped cutout layer preserves hard alpha
		// edges while using the block atlas mip levels for stable minification.
		VertexConsumer bb = buffer.getBuffer(RenderType.cutoutMipped());
//		matrix.translate(0.5f, 0.5f, 0.5f);
		Matrix4f pose = matrix.last().pose();
		Matrix3f normal = matrix.last().normal();
		
        final BlockPos min = tile.frameBox.min();
        final BlockPos max = tile.frameBox.max();
        if (tile.frameBox.isInitialized()) {
            double yOffset = 1 + 4 / 16D;

 //           profiler.startSection("laser");
            if (tile.currentTask != null && tile.currentTask instanceof TileQuarry.TaskBreakBlock) {
                TileQuarry.TaskBreakBlock taskBreakBlock = (TileQuarry.TaskBreakBlock) tile.currentTask;
                BlockPos pos = taskBreakBlock.breakPos;

                if (tile.drillPos == null) {
                    if (taskBreakBlock.clientPower != 0) {
                        // Don't render a laser before we have any power
                        Vec3 from = VecUtil.convertCenter(tile.getBlockPos());
                        Vec3 to = VecUtil.convertCenter(pos);
                        LaserData_BC8 laser = new LaserData_BC8(LASER, from, to, 1 / 16.0);
                        LaserRenderer_BC8.renderLaserDynamic(pose, normal, laser, bb);
                    }
                } else {
                    long power = (long) (
                        taskBreakBlock.prevClientPower +
                            (taskBreakBlock.clientPower - taskBreakBlock.prevClientPower) * (double) partialTicks
                    );
                    VoxelShape aabb = tile.getLevel().getBlockState(pos).getShape(tile.getLevel(), pos);
                    // Fluid/replaceable blocks may expose an empty collision/outline shape. Calling min/max on an
                    // empty VoxelShape yields non-finite bounds, which turns the drill transform into NaN and makes
                    // the drill head/cap disappear. Keep the normal idle offset for such transient/stale tasks.
                    if (!aabb.isEmpty()) {
                        double value = (double) power / taskBreakBlock.getTarget();
                        if (value < 0.9) {
                            value = 1 - value / 0.9;
                        } else {
                            value = (value - 0.9) / 0.1;
                        }
                        double scaleMin = 1 - (1 - aabb.max(Axis.Y)) - (aabb.max(Axis.Y) - aabb.min(Axis.Y)) / 2;
                        double scaleMax = 1 + 4 / 16D;
                        yOffset = scaleMin + value * (scaleMax - scaleMin);
                    }
                }
            }

   //         profiler.endStartSection("frame");
            BlockPos pos = tile.getBlockPos();
            if (tile.clientDrillPos != null && tile.prevClientDrillPos != null) {
                Vec3 interpolatedPos = tile.prevClientDrillPos.add(tile.clientDrillPos.subtract(tile.prevClientDrillPos).scale(partialTicks));
                matrix.translate(interpolatedPos.x - pos.getX()+0.5f, max.getY()- pos.getY()+0.5f, interpolatedPos.z - pos.getZ()+0.5f);
                // These beams are closed prisms, so every exterior face already has the correct winding.
                // Emitting a second coplanar winding makes the differently lit copies z-fight, producing
                // the angle-dependent striped shimmer visible on the quarry drill assembly.
                LaserRenderer_BC8.renderLaserDynamic(pose, normal, LaserData_BC8.of(FRAME,//
                        new Vec3(interpolatedPos.x + 0.5, max.getY() + 0.5, interpolatedPos.z + 1),//
                        new Vec3(interpolatedPos.x + 0.5, max.getY() + 0.5, max.getZ() + 12 / 16D),//
                        1 / 16D, true, false, 0, ID), bb);
                LaserRenderer_BC8.renderLaserDynamic(pose, normal, LaserData_BC8.of(FRAME,//
                        new Vec3(interpolatedPos.x + 0.5, max.getY() + 0.5, interpolatedPos.z ),//
                        new Vec3(interpolatedPos.x + 0.5, max.getY() + 0.5, min.getZ() + 4 / 16D),//
                        1 / 16D, true, false, 0, ID+1), bb);
                LaserRenderer_BC8.renderLaserDynamic(pose, normal, LaserData_BC8.of(FRAME,//
                        new Vec3(interpolatedPos.x+1, max.getY() + 0.5, interpolatedPos.z + 0.5),//
                        new Vec3(max.getX() + 12 / 16D, max.getY() + 0.5, interpolatedPos.z + 0.5),//
                        1 / 16D, true, false, 0, ID+2), bb);
                LaserRenderer_BC8.renderLaserDynamic(pose, normal, LaserData_BC8.of(FRAME,//
                        new Vec3(interpolatedPos.x, max.getY() + 0.5, interpolatedPos.z + 0.5),//
                        new Vec3(min.getX() + 4 / 16D, max.getY() + 0.5, interpolatedPos.z + 0.5),//
                        1 / 16D, true, false, 0, ID+3), bb);
                matrix.translate(0, interpolatedPos.y + 1 - max.getY() - 4/16D, 0);
                LaserRenderer_BC8.renderLaserDynamic(pose, normal, LaserData_BC8.of(FRAME_BOTTOM,//
                        new Vec3(interpolatedPos.x + 0.5, interpolatedPos.y + 1 + 4 / 16D, interpolatedPos.z + 0.5),//
                        new Vec3(interpolatedPos.x + 0.5, max.getY() + 0.5, interpolatedPos.z + 0.5),//
                        1 / 16D, true, false, 0, ID+4), bb);
                matrix.translate(0, - 4/16D + yOffset, 0);
                LaserRenderer_BC8.renderLaserDynamic(pose, normal, LaserData_BC8.of(DRILL,//
                        new Vec3(interpolatedPos.x + 0.5, interpolatedPos.y + 1 + yOffset, interpolatedPos.z + 0.5),//
                        new Vec3(interpolatedPos.x + 0.5, interpolatedPos.y + yOffset, interpolatedPos.z + 0.5),//
                        1 / 16D, true, false, 0, ID+5), bb);
            } else {
            	matrix.translate(-pos.getX(), -pos.getY(), -pos.getZ());
                LaserBoxRenderer.renderLaserBoxDynamic(tile.frameBox, BuildCraftLaserManager.STRIPES_WRITE, pose, normal, bb, true);
            }
//            profiler.endSection();
        }
        
		matrix.popPose();
		
	}

	@Override
	public boolean shouldRenderOffScreen(TileQuarry p_112306_) {
		return true;
	}

	@Override
	public int getViewDistance() {
		return 128;
	}

	@Override
	public boolean shouldRender(TileQuarry p_173568_, Vec3 p_173569_) {
		return BlockEntityRenderer.super.shouldRender(p_173568_, p_173569_);
	}
	
	
}
