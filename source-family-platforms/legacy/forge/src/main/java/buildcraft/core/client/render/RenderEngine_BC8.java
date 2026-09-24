package buildcraft.core.client.render;

import buildcraft.lib.engine.TileEngineBase_BC8;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraftforge.client.model.data.ModelData;

public class RenderEngine_BC8 implements BlockEntityRenderer<TileEngineBase_BC8>{
	

	
	public RenderEngine_BC8(BlockEntityRendererProvider.Context bpc) {
	}
	
	private static TextureAtlasSprite LIGHT;
	private static TextureAtlasSprite TRUNK;
	private static TextureAtlasSprite CHAMBER;

	public static TextureAtlasSprite getTrunkLightSprite() {
		if (LIGHT == null) throw new IllegalStateException("Engine trunk-light sprite has not been baked yet");
		return LIGHT;
	}

	public static TextureAtlasSprite getChamberSprite() {
		if (CHAMBER == null) throw new IllegalStateException("Engine chamber sprite has not been baked yet");
		return CHAMBER;
	}
	public static TextureAtlasSprite REDSTONE_BACK;
	public static TextureAtlasSprite REDSTONE_SIDE;
	public static TextureAtlasSprite CREATIVE_BACK;
	public static TextureAtlasSprite CREATIVE_SIDE;
	public static TextureAtlasSprite IRON_BACK;
	public static TextureAtlasSprite IRON_SIDE;
	public static TextureAtlasSprite STONE_BACK;
	public static TextureAtlasSprite STONE_SIDE;
	public static TextureAtlasSprite FE_BACK;
	public static TextureAtlasSprite FE_SIDE;
	public static TextureAtlasSprite DYNAMO_BACK;
	public static TextureAtlasSprite DYNAMO_FRONT;
	public static TextureAtlasSprite DYNAMO_SIDE;

	public static void reloadSprites(
		BakedModel lightModel,
		BakedModel chamberModel,
		BakedModel redstoneModel,
		BakedModel creativeModel,
		BakedModel stoneModel,
		BakedModel ironModel,
		BakedModel feModel
	) {
		LIGHT = getParticleSprite(lightModel, "engine trunk light");
		CHAMBER = getParticleSprite(chamberModel, "engine chamber");

		REDSTONE_BACK = findSprite(redstoneModel, new ResourceLocation("buildcraftcore:blocks/engine/wood/back"));
		REDSTONE_SIDE = findSprite(redstoneModel, new ResourceLocation("buildcraftcore:blocks/engine/wood/side"));
		CREATIVE_BACK = findSprite(creativeModel, new ResourceLocation("buildcraftcore:blocks/engine/creative/back"));
		CREATIVE_SIDE = findSprite(creativeModel, new ResourceLocation("buildcraftcore:blocks/engine/creative/side"));
		STONE_BACK = findSprite(stoneModel, new ResourceLocation("buildcraftenergy:blocks/engine/stone/back"));
		STONE_SIDE = findSprite(stoneModel, new ResourceLocation("buildcraftenergy:blocks/engine/stone/side"));
		IRON_BACK = findSprite(ironModel, new ResourceLocation("buildcraftenergy:blocks/engine/iron/back"));
		IRON_SIDE = findSprite(ironModel, new ResourceLocation("buildcraftenergy:blocks/engine/iron/side"));
		FE_BACK = findSprite(feModel, new ResourceLocation("buildcraftenergy:blocks/engine/fe/back"));
		FE_SIDE = findSprite(feModel, new ResourceLocation("buildcraftenergy:blocks/engine/fe/side"));
	}

	public static void reloadDynamoSprites(BakedModel dynamoModel) {
		DYNAMO_BACK = findSprite(dynamoModel, new ResourceLocation("buildcraftenergy:blocks/mj_dynamo/back"));
		DYNAMO_FRONT = findSprite(dynamoModel, new ResourceLocation("buildcraftenergy:blocks/mj_dynamo/front"));
		DYNAMO_SIDE = findSprite(dynamoModel, new ResourceLocation("buildcraftenergy:blocks/mj_dynamo/side"));
	}

	private static TextureAtlasSprite getParticleSprite(BakedModel model, String description) {
		if (model == null) {
			throw new IllegalStateException("Missing baked model for " + description);
		}
		TextureAtlasSprite sprite = model.getParticleIcon();
		if (sprite == null) {
			throw new IllegalStateException("Baked model has no particle sprite for " + description);
		}
		return sprite;
	}

	private static TextureAtlasSprite findSprite(BakedModel model, ResourceLocation location) {
		if (model == null) {
			throw new IllegalStateException("Missing baked texture model for " + location);
		}

		TextureAtlasSprite particle = model.getParticleIcon();
		if (matches(particle, location)) {
			return particle;
		}

		RandomSource random = RandomSource.create(0L);
		TextureAtlasSprite sprite = findSprite(model.getQuads(null, null, random, ModelData.EMPTY, null), location);
		if (sprite != null) {
			return sprite;
		}

		for (Direction direction : Direction.values()) {
			sprite = findSprite(model.getQuads(null, direction, random, ModelData.EMPTY, null), location);
			if (sprite != null) {
				return sprite;
			}
		}

		// A malformed model should not crash the whole client. Its particle texture is
		// still a valid stitched sprite and is a safe visual fallback.
		if (particle != null) {
			return particle;
		}
		throw new IllegalStateException("Unable to find stitched sprite " + location);
	}

	private static TextureAtlasSprite findSprite(Iterable<BakedQuad> quads, ResourceLocation location) {
		for (BakedQuad quad : quads) {
			TextureAtlasSprite sprite = quad.getSprite();
			if (matches(sprite, location)) {
				return sprite;
			}
		}
		return null;
	}

	private static boolean matches(TextureAtlasSprite sprite, ResourceLocation location) {
		return sprite != null && sprite.contents().name().equals(location);
	}

	

	
    private static TextureAtlasSprite getBackSprite(TileEngineBase_BC8.EngineVisualType type) {
        return switch (type) {
            case REDSTONE -> REDSTONE_BACK;
            case STONE -> STONE_BACK;
            case IRON -> IRON_BACK;
            case CREATIVE -> CREATIVE_BACK;
            case FE -> FE_BACK;
            case MJ_DYNAMO -> DYNAMO_BACK;
        };
    }

    private static TextureAtlasSprite getFrontSprite(TileEngineBase_BC8.EngineVisualType type) {
        return type == TileEngineBase_BC8.EngineVisualType.MJ_DYNAMO ? DYNAMO_FRONT : getBackSprite(type);
    }

    private static TextureAtlasSprite getSideSprite(TileEngineBase_BC8.EngineVisualType type) {
        return switch (type) {
            case REDSTONE -> REDSTONE_SIDE;
            case STONE -> STONE_SIDE;
            case IRON -> IRON_SIDE;
            case CREATIVE -> CREATIVE_SIDE;
            case FE -> FE_SIDE;
            case MJ_DYNAMO -> DYNAMO_SIDE;
        };
    }

	@Override
	public void render(TileEngineBase_BC8 tile, float partialTicks, PoseStack matrix, MultiBufferSource buffer, int light, int overlay) {
		matrix.pushPose();
		matrix.translate(0.5f, 0.5f, 0.5f);
        Matrix4f matrix4f = matrix.last().pose();
        Matrix3f matrix3f = matrix.last().normal();
//        VertexConsumer builder = buffer.getBuffer(RenderType.solid());

        float offset  = tile.getRenderProgress(partialTicks) * 8/16f;
//        float f1 = neighborcombineresult.<Float2FloatFunction>apply(ChestBlock.opennessCombiner(tile)).get(light)
//        int i = neighborcombineresult.<Int2IntFunction>apply(new BrightnessCombiner<>()).applyAsInt(light);
        TextureAtlasSprite BACK = getBackSprite(tile.getVisualType());
        TextureAtlasSprite FRONT = getFrontSprite(tile.getVisualType());
        TextureAtlasSprite SIDE = getSideSprite(tile.getVisualType());
        VertexConsumer builder = buffer.getBuffer(RenderType.solid());
        int texoffset = 0;
        switch(tile.getCurrentFacing()) {
		case DOWN:
			matrix.mulPose(Axis.XP.rotationDegrees(180));
			break;
		case EAST:
			matrix.mulPose(Axis.XP.rotationDegrees(90));
			matrix.mulPose(Axis.ZN.rotationDegrees(90));
			break;
		case NORTH:
			matrix.mulPose(Axis.XN.rotationDegrees(90));
			break;
		case SOUTH:
			matrix.mulPose(Axis.XP.rotationDegrees(90));
			break;
		case UP:
			break;
		case WEST:
			matrix.mulPose(Axis.XP.rotationDegrees(90));
			matrix.mulPose(Axis.ZP.rotationDegrees(90));
			break;
        }
        switch (tile.getPowerStage()) {
        case BLUE:{
            break;
        }
        case GREEN:{
        	texoffset = 2;
        	break;
        }
        case YELLOW:{
        	texoffset = 4;
        	break;
        }
        case RED:{
        	texoffset = 6;
        	break;
        }
        case OVERHEAT:{
        	texoffset = 8;
        }
        default:
            break;
        }
//        renderStatic(BACK,SIDE, matrix4f, matrix3f, builder, light, 0,overlay);
        renderMovingBack(FRONT, matrix4f, matrix3f, builder, light, offset,overlay);
        for(int i=0;i<4;i++) {
        	renderLight(LIGHT, matrix4f, matrix3f, builder, 15728880, offset,overlay,texoffset);
        	renderMovingSide(SIDE, matrix4f, matrix3f, builder, light, offset,overlay);
        	renderChamber(CHAMBER, matrix4f, matrix3f, builder, light, offset,overlay);
        	matrix.mulPose(Axis.YP.rotationDegrees(90));
        }
        

        
		matrix.popPose();
		
	}
	private static void renderMovingBack(TextureAtlasSprite back,Matrix4f matrix4f, Matrix3f normalMatrix, VertexConsumer builder, int light,float offset, int overlay) {
		float width = 1f;
        float minU = back.getU(0);
        float maxU = back.getU(16);
        float minV = back.getV(0);
        float maxV = back.getV(16);
        
/*        RenderSystem.setShader(GameRenderer::getBlockShader);
//        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableDepthTest();
        back.atlas().bind();
        var tess  = Tesselator.getInstance();
        var b = tess.getBuilder();
        b.begin(Mode.QUADS, DefaultVertexFormat.BLOCK);*/
        

        builder.vertex(matrix4f, -8/16f, -4/16f + offset, (-width / 2)).color(0.8f, 0.8f, 0.8f,0.8f).uv(minU, minV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, 8/16f, -4/16f + offset, (-width / 2)).color(0.8f, 0.8f, 0.8f,0.8f).uv(maxU, minV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, 8/16f, -4/16f + offset, (width / 2)).color(0.8f, 0.8f, 0.8f,0.8f).uv(maxU, maxV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, -8/16f, -4/16f +offset, (width / 2)).color(0.8f, 0.8f, 0.8f,0.8f).uv(minU, maxV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();        
        builder.vertex(matrix4f, -8/16f, 0/16f +offset-0.001f, (width / 2)).color(0.8f, 0.8f, 0.8f,0.8f).uv(minU, minV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, 8/16f, 0/16f +offset-0.001f, (width / 2)).color(0.8f, 0.8f, 0.8f,0.8f).uv(maxU, minV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, 8/16f, 0/16f + offset-0.001f, (-width / 2)).color(0.8f, 0.8f, 0.8f,0.8f).uv(maxU, maxV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, -8/16f, 0/16f +offset-0.001f, (-width / 2)).color(0.8f, 0.8f, 0.8f,0.8f).uv(minU, maxV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
//        tess.end();
	}
    private static void renderLight(TextureAtlasSprite sprite,Matrix4f matrix4f, Matrix3f normalMatrix, VertexConsumer builder, int light,float offset, int overlay,int texoffset) {

        float width = 8 / 16f;

        float minU = sprite.getU(0+texoffset);
        float maxU = sprite.getU(2+texoffset);
        float minV = sprite.getV(4);
        float maxV = sprite.getV(10-offset*12);
        offset = offset*6/8;
        builder.vertex(matrix4f, -4/16f, 6/16f+0.001f , (-width / 2)-0.001f).color(0.8f, 0.8f, 0.8f,0.8f).uv(minU, minV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, -2/16f, 6/16f+0.001f , (-width / 2)-0.001f).color(0.8f, 0.8f, 0.8f,0.8f).uv(maxU, minV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, -2/16f, 0/16f+offset, (-width / 2)-0.001f).color(0.8f, 0.8f, 0.8f,0.8f).uv(maxU, maxV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, -4/16f, 0/16f+offset, (-width / 2)-0.001f).color(0.8f, 0.8f, 0.8f,0.8f).uv(minU, maxV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, 2/16f, 6/16f+0.001f , (-width / 2)-0.001f).color(0.8f, 0.8f, 0.8f,0.8f).uv(minU, minV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, 4/16f, 6/16f+0.001f , (-width / 2)-0.001f).color(0.8f, 0.8f, 0.8f,0.8f).uv(maxU, minV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, 4/16f, 0/16f+offset, (-width / 2)-0.001f).color(0.8f, 0.8f, 0.8f,0.8f).uv(maxU, maxV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, 2/16f, 0/16f+offset, (-width / 2)-0.001f).color(0.8f, 0.8f, 0.8f,0.8f).uv(minU, maxV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
    }
    private static void renderMovingSide(TextureAtlasSprite side,Matrix4f matrix4f, Matrix3f normalMatrix, VertexConsumer builder, int light,float offset, int overlay){
        float width = 16 / 16f;
        float minU = side.getU(0);
        float maxU = side.getU(16);
        float minV = side.getV(0);
        float maxV = side.getV(4);

        builder.vertex(matrix4f, -8/16f, 0/16f+offset , (-width / 2)).color(0.8f, 0.8f, 0.8f,0.8f).uv(minU, minV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, 8/16f, 0/16f+offset , (-width / 2)).color(0.8f, 0.8f, 0.8f,0.8f).uv(maxU, minV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, 8/16f, -4/16f+offset, (-width / 2)).color(0.8f, 0.8f, 0.8f,0.8f).uv(maxU, maxV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, -8/16f, -4/16f+offset, (-width / 2)).color(0.8f, 0.8f, 0.8f,0.8f).uv(minU, maxV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex(); 
    }
    private static void renderChamber(TextureAtlasSprite sprite,Matrix4f matrix4f, Matrix3f normalMatrix, VertexConsumer builder, int light,float offset, int overlay) {
    	float width = 10/ 16f;
    //	progress = 1f;

        float minU = sprite.getU(3);
        float maxU = sprite.getU(13);
        float minV = sprite.getV(0);
        float maxV = sprite.getV(8);

        builder.vertex(matrix4f, -5/16f, (-4/16f+offset) , (-width / 2)).color(1f, 1f, 1f,1f).uv(minU, minV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, 5/16f, (-4/16f+offset) , (-width / 2)).color(1f, 1f, 1f,1f).uv(maxU, minV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, 5/16f, -4/16f, (-width / 2)).color(1f, 1f, 1f,1f).uv(maxU, maxV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, -5/16f, -4/16f, (-width / 2)).color(1f, 1f, 1f,1f).uv(minU, maxV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
    }
    private static void renderStatic(TextureAtlasSprite back,TextureAtlasSprite side,Matrix4f matrix4f, Matrix3f normalMatrix, VertexConsumer builder, int light,float offset, int overlay) {
		
        float minU = back.getU(0);
        float maxU = back.getU(16);
        float minV = back.getV(0);
        float maxV = back.getV(16);
        float minU0 = side.getU(0);
        float maxU0 = side.getU(16);
        float minV0 = side.getV(0);
        float maxV0 = side.getV(4);
        
        builder.vertex(matrix4f, -8/16f, -8/16f, -8/16f).color(0.8f, 0.8f, 0.8f,0.8f).uv(minU, minV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, 8/16f, -8/16f, -8/16f).color(0.8f, 0.8f, 0.8f,0.8f).uv(maxU, minV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, 8/16f, -8/16f, 8/16f).color(0.8f, 0.8f, 0.8f,0.8f).uv(maxU, maxV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, -8/16f, -8/16f, 8/16f).color(0.8f, 0.8f, 0.8f,0.8f).uv(minU, maxV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, -8/16f, -4/16f, 8/16f).color(0.8f, 0.8f, 0.8f,0.8f).uv(minU, minV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, 8/16f, -4/16f, 8/16f).color(0.8f, 0.8f, 0.8f,0.8f).uv(maxU, minV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, 8/16f, -4/16f, -8/16f).color(0.8f, 0.8f, 0.8f,0.8f).uv(maxU, maxV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, -8/16f, -4/16f, -8/16f).color(0.8f, 0.8f, 0.8f,0.8f).uv(minU, maxV).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
 
        builder.vertex(matrix4f, -8/16f, -4/16f , -8/16f).color(0.75f, 0.75f, 0.75f,0.75f).uv(minU0, minV0).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, 8/16f, -4/16f , -8/16f).color(0.75f, 0.75f, 0.75f,0.75f).uv(maxU0, minV0).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, 8/16f, -8/16f, -8/16f).color(0.75f, 0.75f, 0.75f,0.75f).uv(maxU0, maxV0).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, -8/16f, -8/16f, -8/16f).color(0.75f, 0.75f, 0.75f,0.75f).uv(minU0, maxV0).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        
        builder.vertex(matrix4f, -8/16f, -4/16f , 8/16f).color(0.75f, 0.75f, 0.75f,0.75f).uv(minU0, minV0).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, -8/16f, -4/16f , -8/16f).color(0.75f, 0.75f, 0.75f,0.75f).uv(maxU0, minV0).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, -8/16f, -8/16f, -8/16f).color(0.75f, 0.75f, 0.75f,0.75f).uv(maxU0, maxV0).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, -8/16f, -8/16f, 8/16f).color(0.75f, 0.75f, 0.75f,0.75f).uv(minU0, maxV0).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
   
        builder.vertex(matrix4f, 8/16f, -4/16f , 8/16f).color(0.75f, 0.75f, 0.75f,0.75f).uv(minU0, minV0).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, -8/16f, -4/16f , 8/16f).color(0.75f, 0.75f, 0.75f,0.75f).uv(maxU0, minV0).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, -8/16f, -8/16f, 8/16f).color(0.75f, 0.75f, 0.75f,0.75f).uv(maxU0, maxV0).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, 8/16f, -8/16f, 8/16f).color(0.75f, 0.75f, 0.75f,0.75f).uv(minU0, maxV0).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        
        builder.vertex(matrix4f, 8/16f, -4/16f , -8/16f).color(0.75f, 0.75f, 0.75f,0.75f).uv(minU0, minV0).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, 8/16f, -4/16f , 8/16f).color(0.75f, 0.75f, 0.75f,0.75f).uv(maxU0, minV0).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, 8/16f, -8/16f, 8/16f).color(0.75f, 0.75f, 0.75f,0.75f).uv(maxU0, maxV0).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
        builder.vertex(matrix4f, 8/16f, -8/16f, -8/16f).color(0.75f, 0.75f, 0.75f,0.75f).uv(minU0, maxV0).overlayCoords(overlay).uv2(light).normal(normalMatrix, 0, 0, 1).endVertex();
   
        
    }
}

