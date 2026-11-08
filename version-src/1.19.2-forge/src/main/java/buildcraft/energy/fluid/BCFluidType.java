package buildcraft.energy.fluid;

import java.util.function.Consumer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.mojang.math.Vector3f;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.fluids.FluidType;

public class BCFluidType extends FluidType{

	//store fluid type, but no temperature
	private final ResourceLocation stillTexture;
	private final ResourceLocation flowTexture;
	private final int tintColor;
    private final int fogColor;
    private final float fogDistance;
	
	public BCFluidType(Properties properties, ResourceLocation stillTexture, ResourceLocation flowTexture,
            int tintColor, int fogColor, float fogDistance) {
		super(properties);
		this.stillTexture = stillTexture;
		this.flowTexture = flowTexture;
		this.tintColor = tintColor;
        this.fogColor = fogColor;
        this.fogDistance = fogDistance;
	}

	@Override
    public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer)
    {
		consumer.accept(new IClientFluidTypeExtensions() {
           ResourceLocation UNDERWATER_LOCATION =
               new ResourceLocation("textures/misc/underwater.png");
            
			@Override
			public ResourceLocation getStillTexture() {
				return stillTexture;
			}

			@Override
			public ResourceLocation getFlowingTexture() {
				return flowTexture;
			}
			
			@Override
			public @Nullable ResourceLocation getOverlayTexture() {
				// This texture replaces side faces when a fluid touches a block. Using the vanilla water
				// overlay here turns every BuildCraft fluid side into a white/grey water-overlay quad.
				return null;
			}
			
            @Override
            public ResourceLocation getRenderOverlayTexture(Minecraft mc)
            {
                return UNDERWATER_LOCATION;
            }

			@Override
			public int getTintColor() {
				return tintColor;
			}

			@Override
			public @NotNull Vector3f modifyFogColor(Camera camera, float partialTick, ClientLevel level,
					int renderDistance, float darkenWorldAmount, Vector3f fluidFogColor) {
                return new Vector3f(
                    BCFluidFog.channel(fogColor, 16),
                    BCFluidFog.channel(fogColor, 8),
                    BCFluidFog.channel(fogColor, 0));
            }

            @Override
            public void modifyFogRender(Camera camera, net.minecraft.client.renderer.FogRenderer.FogMode mode,
                    float renderDistance, float partialTick, float nearDistance, float farDistance,
                    com.mojang.blaze3d.shaders.FogShape shape) {
                com.mojang.blaze3d.systems.RenderSystem.setShaderFogStart(0.0F);
                com.mojang.blaze3d.systems.RenderSystem.setShaderFogEnd(fogDistance);
            }

		});
    }
	
	
}
