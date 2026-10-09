//? source if >=1.21.11
package buildcraft.lib.client.render.compat;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

/** 1.21.11 compatibility bridge for BuildCraft direct entity renderers. */
public abstract class LegacyEntityRenderer<T extends Entity> extends EntityRenderer<T, EntityRenderState> {
    protected LegacyEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    public abstract Identifier getTextureLocation(T entity);

    /** Direct-render compatibility hook used by BuildCraft renderers across modern targets. */
    public void render(T entity, float yaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
    }

    @Override
    public EntityRenderState createRenderState() {
        return new EntityRenderState();
    }

    @Override
    public void extractRenderState(T entity, EntityRenderState state, float partialTick) {
        // The base extraction is mandatory in 1.21.11: EntityRenderDispatcher resolves the renderer
        // for the submit phase through state.entityType. Leaving this empty turns any legacy-bridged
        // entity into a client crash as soon as it enters render distance.
        super.extractRenderState(entity, state, partialTick);
    }

    @Override
    public void submit(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
            CameraRenderState cameraState) {
        // Preserve vanilla name-tag/leash submission for renderers using the compatibility geometry path.
        super.submit(state, poseStack, collector, cameraState);
    }
}
