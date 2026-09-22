//this rendering code comes from ITank mod (1.19.4) by EwyBoy,since there are poor doc for me to look up;
//from https://github.com/EwyBoy/ITank/blob/1.19.4/src/main/java/com/ewyboy/itank/client/TankRenderer.java
package buildcraft.factory.client.render;


import buildcraft.factory.tile.TileTank;
import buildcraft.lib.client.render.fluid.FluidRenderer;
import buildcraft.lib.client.render.fluid.FluidSpriteType;
import buildcraft.lib.fluid.FluidCompatRegistry;
import buildcraft.lib.fluid.FluidSmoother.FluidStackInterp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fluids.FluidStack;

public class RenderTank implements BlockEntityRenderer<TileTank> {

    private static final Vec3 MIN = new Vec3(0.13, 0.01, 0.13);
    private static final Vec3 MAX = new Vec3(0.86, 0.99, 0.86);
    private static final Vec3 MIN_CONNECTED = new Vec3(0.13, 0, 0.13);
    private static final Vec3 MAX_CONNECTED = new Vec3(0.86, 1 - 1e-5, 0.86);

    public RenderTank(BlockEntityRendererProvider.Context ctx) {
    }

    @Override
    public void render(TileTank tile, float partialTicks, PoseStack matrix, MultiBufferSource buffer, int light, int overlay) {
        FluidStackInterp forRender = tile.getFluidForRender(partialTicks);
        if (forRender == null) {
            return;
        }
        matrix.pushPose();
        VertexConsumer bb = buffer.getBuffer(RenderType.translucent());
        
        boolean[] sideRender = { true, true, true, true, true, true };
        boolean connectedUp = isFullyConnected(tile, Direction.UP, partialTicks);
        boolean connectedDown = isFullyConnected(tile, Direction.DOWN, partialTicks);
        sideRender[Direction.DOWN.ordinal()] = !connectedDown;
        sideRender[Direction.UP.ordinal()] = !connectedUp;

        Vec3 min = connectedDown ? MIN_CONNECTED : MIN;
        Vec3 max = connectedUp ? MAX_CONNECTED : MAX;
        FluidStack fluid = forRender.fluid;
        int blocklight0 = light&0x0000F0;
        int skylight0 = light&0xF00000;
        int blocklight = fluid.getFluid().getFluidType().getLightLevel(fluid)<<4;
        blocklight = blocklight > blocklight0 ? blocklight : blocklight0;
        int combinedLight = (skylight0)+(blocklight);
        
        
        FluidRenderer.vertex.lighti(combinedLight);
        FluidRenderer.vertex.overlay(overlay);

        FluidRenderer.renderFluid(FluidSpriteType.STILL, fluid, forRender.amount, tile.tank.getCapacity(), min, max, bb, matrix.last(), sideRender);
        matrix.popPose();
    }
    
    private static boolean isFullyConnected(TileTank thisTank, Direction face, float partialTicks) {
        BlockPos pos = thisTank.getBlockPos().offset(face.getNormal());
        BlockEntity oTile = thisTank.getLevel().getBlockEntity(pos);
        if (oTile instanceof TileTank oTank) {
            if (!TileTank.canTanksConnect(thisTank, oTank, face)) {
                return false;
            }
            FluidStackInterp forRender = oTank.getFluidForRender(partialTicks);
            if (forRender == null) {
                return false;
            }
            FluidStack fluid = forRender.fluid;
            if (fluid == null || forRender.amount <= 0) {
                return false;
            } else if (thisTank.getFluidForRender(partialTicks) == null
                || !FluidCompatRegistry.areEquivalent(fluid, thisTank.getFluidForRender(partialTicks).fluid)) {
                return false;
            }
            if (fluid.getFluid().getFluidType().isLighterThanAir()) {
                face = face.getOpposite();
            }
            return forRender.amount >= oTank.tank.getCapacity() || face == Direction.UP;
        } else {
            return false;
        }
    }


}