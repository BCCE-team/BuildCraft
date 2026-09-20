//? source if >=1.21.11
//this rendering code comes from ITank mod (1.19.4) by EwyBoy,since there are poor doc for me to look up;
//from https://github.com/EwyBoy/ITank/blob/1.19.4/src/main/java/com/ewyboy/itank/client/TankRenderer.java
package buildcraft.factory.client.render;

import javax.annotation.Nullable;

import buildcraft.factory.tile.TileTank;
import buildcraft.lib.client.render.fluid.FluidRenderer;
import buildcraft.lib.client.render.fluid.FluidSpriteType;
import buildcraft.lib.compat.RenderCompat;
import buildcraft.lib.fluid.FluidCompatRegistry;
import buildcraft.lib.fluid.FluidSmoother.FluidStackInterp;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;

/** Native 1.21.11 tank-fluid renderer.
 *
 * <p>Minecraft 1.21.11 submits block-entity geometry through extracted render state. This renderer preserves the
 * tank fill/interpolation/stacking rules while submitting the fluid cuboid through the native render queue.</p> */
public class RenderTank implements BlockEntityRenderer<TileTank, RenderTank.TankRenderState> {
    private static final Vec3 MIN = new Vec3(0.13, 0.01, 0.13);
    private static final Vec3 MAX = new Vec3(0.86, 0.99, 0.86);
    private static final Vec3 MIN_CONNECTED = new Vec3(0.13, 0, 0.13);
    private static final Vec3 MAX_CONNECTED = new Vec3(0.86, 1 - 1e-5, 0.86);

    public RenderTank(BlockEntityRendererProvider.Context ctx) {
    }

    public TankRenderState createRenderState() {
        return new TankRenderState();
    }

    public void extractRenderState(TileTank tile, TankRenderState state, float partialTick, Vec3 cameraPosition,
        @Nullable ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderState.extractBase(tile, state, crumblingOverlay);
        state.tile = tile;
        state.partialTick = partialTick;
    }

    public void submit(TankRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
        CameraRenderState cameraState) {
        TileTank tile = state.tile;
        if (tile == null || tile.getLevel() == null) {
            return;
        }

        FluidStackInterp forRender = tile.getFluidForRender(state.partialTick);
        if (forRender == null || forRender.fluid == null || forRender.fluid.isEmpty() || forRender.amount <= 0) {
            return;
        }

        boolean[] sideRender = { true, true, true, true, true, true };
        boolean connectedUp = isFullyConnected(tile, Direction.UP, state.partialTick);
        boolean connectedDown = isFullyConnected(tile, Direction.DOWN, state.partialTick);
        sideRender[Direction.DOWN.ordinal()] = !connectedDown;
        sideRender[Direction.UP.ordinal()] = !connectedUp;

        Vec3 min = connectedDown ? MIN_CONNECTED : MIN;
        Vec3 max = connectedUp ? MAX_CONNECTED : MAX;
        FluidStack fluid = forRender.fluid;

        int blockLight = state.lightCoords & 0x0000F0;
        int skyLight = state.lightCoords & 0xF00000;
        int fluidLight = fluid.getFluid().getFluidType().getLightLevel(fluid) << 4;
        int combinedLight = skyLight | Math.max(blockLight, fluidLight);

        // Fluid textures may contain real alpha, so custom geometry uses the transparent render phase.
        collector.submitCustomGeometry(poseStack, RenderCompat.translucent(), (pose, consumer) -> {
            FluidRenderer.vertex.overlay(OverlayTexture.NO_OVERLAY);
            FluidRenderer.renderFluid(
                FluidSpriteType.STILL,
                fluid,
                forRender.amount,
                tile.tank.getCapacity(),
                min,
                max,
                consumer,
                pose,
                sideRender,
                combinedLight
            );
        });
    }

    private static boolean isFullyConnected(TileTank thisTank, Direction face, float partialTicks) {
        BlockPos pos = thisTank.getBlockPos().offset(face.getUnitVec3i());
        BlockEntity otherTile = thisTank.getLevel().getBlockEntity(pos);
        if (!(otherTile instanceof TileTank otherTank)) {
            return false;
        }
        if (!TileTank.canTanksConnect(thisTank, otherTank, face)) {
            return false;
        }

        FluidStackInterp otherRender = otherTank.getFluidForRender(partialTicks);
        FluidStackInterp thisRender = thisTank.getFluidForRender(partialTicks);
        if (otherRender == null || thisRender == null || otherRender.fluid == null || thisRender.fluid == null
            || otherRender.fluid.isEmpty() || thisRender.fluid.isEmpty() || otherRender.amount <= 0) {
            return false;
        }
        if (!FluidCompatRegistry.areEquivalent(otherRender.fluid, thisRender.fluid)) {
            return false;
        }
        if (otherRender.fluid.getFluid().getFluidType().isLighterThanAir()) {
            face = face.getOpposite();
        }
        return otherRender.amount >= otherTank.tank.getCapacity() || face == Direction.UP;
    }

    public static final class TankRenderState extends BlockEntityRenderState {
        @Nullable
        TileTank tile;
        float partialTick;
    }
}
