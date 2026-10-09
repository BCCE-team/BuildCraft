package buildcraft.core.client.render;

import buildcraft.lib.engine.TileEngineBase_BC8;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
import net.neoforged.neoforge.client.model.data.ModelData;

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

		REDSTONE_BACK = findSprite(redstoneModel, ResourceLocation.parse("buildcraftcore:blocks/engine/wood/back"));
		REDSTONE_SIDE = findSprite(redstoneModel, ResourceLocation.parse("buildcraftcore:blocks/engine/wood/side"));
		CREATIVE_BACK = findSprite(creativeModel, ResourceLocation.parse("buildcraftcore:blocks/engine/creative/back"));
		CREATIVE_SIDE = findSprite(creativeModel, ResourceLocation.parse("buildcraftcore:blocks/engine/creative/side"));
		STONE_BACK = findSprite(stoneModel, ResourceLocation.parse("buildcraftenergy:blocks/engine/stone/back"));
		STONE_SIDE = findSprite(stoneModel, ResourceLocation.parse("buildcraftenergy:blocks/engine/stone/side"));
		IRON_BACK = findSprite(ironModel, ResourceLocation.parse("buildcraftenergy:blocks/engine/iron/back"));
		IRON_SIDE = findSprite(ironModel, ResourceLocation.parse("buildcraftenergy:blocks/engine/iron/side"));
		FE_BACK = findSprite(feModel, ResourceLocation.parse("buildcraftenergy:blocks/engine/fe/back"));
		FE_SIDE = findSprite(feModel, ResourceLocation.parse("buildcraftenergy:blocks/engine/fe/side"));
	}

	public static void reloadDynamoSprites(BakedModel dynamoModel) {
		DYNAMO_BACK = findSprite(dynamoModel, ResourceLocation.parse("buildcraftenergy:blocks/mj_dynamo/back"));
		DYNAMO_FRONT = findSprite(dynamoModel, ResourceLocation.parse("buildcraftenergy:blocks/mj_dynamo/front"));
		DYNAMO_SIDE = findSprite(dynamoModel, ResourceLocation.parse("buildcraftenergy:blocks/mj_dynamo/side"));
	}

	private static TextureAtlasSprite getParticleSprite(BakedModel model, String description) {
		if (model == null) {
			throw new IllegalStateException("Missing baked model for " + description);
		}
		TextureAtlasSprite sprite = model.getParticleIcon(ModelData.EMPTY);
		if (sprite == null) {
			throw new IllegalStateException("Baked model has no particle sprite for " + description);
		}
		return sprite;
	}

	private static TextureAtlasSprite findSprite(BakedModel model, ResourceLocation location) {
		if (model == null) {
			throw new IllegalStateException("Missing baked texture model for " + location);
		}

		TextureAtlasSprite particle = model.getParticleIcon(ModelData.EMPTY);
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
        PoseStack.Pose pose = matrix.last();
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
//        renderStatic(BACK,SIDE, pose, builder, light, 0,overlay);
        renderMovingBack(FRONT, pose, builder, light, offset,overlay);
        for(int i=0;i<4;i++) {
        	renderLight(LIGHT, pose, builder, 15728880, offset,overlay,texoffset);
        	renderMovingSide(SIDE, pose, builder, light, offset,overlay);
        	renderChamber(CHAMBER, pose, builder, light, offset,overlay);
        	matrix.mulPose(Axis.YP.rotationDegrees(90));
        }



		matrix.popPose();

	}
	private static void renderMovingBack(TextureAtlasSprite back,PoseStack.Pose pose, VertexConsumer builder, int light,float offset, int overlay) {
		float width = 1f;
        float minU = back.getU((0) / 16.0F);
        float maxU = back.getU((16) / 16.0F);
        float minV = back.getV((0) / 16.0F);
        float maxV = back.getV((16) / 16.0F);

/*        RenderSystem.setShader(GameRenderer::getBlockShader);
//        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableDepthTest();
        back.atlas().bind();
        var tess  = Tesselator.getInstance();
        var b = tess.getBuilder();
        b.begin(Mode.QUADS, DefaultVertexFormat.BLOCK);*/


        builder.addVertex(pose.pose(), -8/16f, -4/16f + offset, (-width / 2)).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(minU, minV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), 8/16f, -4/16f + offset, (-width / 2)).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(maxU, minV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), 8/16f, -4/16f + offset, (width / 2)).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(maxU, maxV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), -8/16f, -4/16f +offset, (width / 2)).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(minU, maxV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), -8/16f, 0/16f +offset-0.001f, (width / 2)).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(minU, minV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), 8/16f, 0/16f +offset-0.001f, (width / 2)).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(maxU, minV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), 8/16f, 0/16f + offset-0.001f, (-width / 2)).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(maxU, maxV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), -8/16f, 0/16f +offset-0.001f, (-width / 2)).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(minU, maxV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
//        tess.end();
	}
    private static void renderLight(TextureAtlasSprite sprite,PoseStack.Pose pose, VertexConsumer builder, int light,float offset, int overlay,int texoffset) {

        float width = 8 / 16f;

        float minU = sprite.getU((0+texoffset) / 16.0F);
        float maxU = sprite.getU((2+texoffset) / 16.0F);
        float minV = sprite.getV((4) / 16.0F);
        float maxV = sprite.getV((10-offset*12) / 16.0F);
        offset = offset*6/8;
        builder.addVertex(pose.pose(), -4/16f, 6/16f+0.001f , (-width / 2)-0.001f).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(minU, minV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), -2/16f, 6/16f+0.001f , (-width / 2)-0.001f).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(maxU, minV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), -2/16f, 0/16f+offset, (-width / 2)-0.001f).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(maxU, maxV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), -4/16f, 0/16f+offset, (-width / 2)-0.001f).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(minU, maxV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), 2/16f, 6/16f+0.001f , (-width / 2)-0.001f).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(minU, minV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), 4/16f, 6/16f+0.001f , (-width / 2)-0.001f).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(maxU, minV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), 4/16f, 0/16f+offset, (-width / 2)-0.001f).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(maxU, maxV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), 2/16f, 0/16f+offset, (-width / 2)-0.001f).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(minU, maxV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
    }
    private static void renderMovingSide(TextureAtlasSprite side,PoseStack.Pose pose, VertexConsumer builder, int light,float offset, int overlay){
        float width = 16 / 16f;
        float minU = side.getU((0) / 16.0F);
        float maxU = side.getU((16) / 16.0F);
        float minV = side.getV((0) / 16.0F);
        float maxV = side.getV((4) / 16.0F);

        builder.addVertex(pose.pose(), -8/16f, 0/16f+offset , (-width / 2)).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(minU, minV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), 8/16f, 0/16f+offset , (-width / 2)).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(maxU, minV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), 8/16f, -4/16f+offset, (-width / 2)).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(maxU, maxV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), -8/16f, -4/16f+offset, (-width / 2)).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(minU, maxV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
    }
    private static void renderChamber(TextureAtlasSprite sprite,PoseStack.Pose pose, VertexConsumer builder, int light,float offset, int overlay) {
    	float width = 10/ 16f;
    //	progress = 1f;

        float minU = sprite.getU((3) / 16.0F);
        float maxU = sprite.getU((13) / 16.0F);
        float minV = sprite.getV((0) / 16.0F);
        float maxV = sprite.getV((8) / 16.0F);

        builder.addVertex(pose.pose(), -5/16f, (-4/16f+offset) , (-width / 2)).setColor(1f, 1f, 1f,1f).setUv(minU, minV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), 5/16f, (-4/16f+offset) , (-width / 2)).setColor(1f, 1f, 1f,1f).setUv(maxU, minV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), 5/16f, -4/16f, (-width / 2)).setColor(1f, 1f, 1f,1f).setUv(maxU, maxV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), -5/16f, -4/16f, (-width / 2)).setColor(1f, 1f, 1f,1f).setUv(minU, maxV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
    }
    private static void renderStatic(TextureAtlasSprite back,TextureAtlasSprite side,PoseStack.Pose pose, VertexConsumer builder, int light,float offset, int overlay) {

        float minU = back.getU((0) / 16.0F);
        float maxU = back.getU((16) / 16.0F);
        float minV = back.getV((0) / 16.0F);
        float maxV = back.getV((16) / 16.0F);
        float minU0 = side.getU((0) / 16.0F);
        float maxU0 = side.getU((16) / 16.0F);
        float minV0 = side.getV((0) / 16.0F);
        float maxV0 = side.getV((4) / 16.0F);

        builder.addVertex(pose.pose(), -8/16f, -8/16f, -8/16f).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(minU, minV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), 8/16f, -8/16f, -8/16f).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(maxU, minV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), 8/16f, -8/16f, 8/16f).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(maxU, maxV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), -8/16f, -8/16f, 8/16f).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(minU, maxV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), -8/16f, -4/16f, 8/16f).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(minU, minV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), 8/16f, -4/16f, 8/16f).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(maxU, minV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), 8/16f, -4/16f, -8/16f).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(maxU, maxV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), -8/16f, -4/16f, -8/16f).setColor(0.8f, 0.8f, 0.8f,0.8f).setUv(minU, maxV).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);

        builder.addVertex(pose.pose(), -8/16f, -4/16f , -8/16f).setColor(0.75f, 0.75f, 0.75f,0.75f).setUv(minU0, minV0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), 8/16f, -4/16f , -8/16f).setColor(0.75f, 0.75f, 0.75f,0.75f).setUv(maxU0, minV0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), 8/16f, -8/16f, -8/16f).setColor(0.75f, 0.75f, 0.75f,0.75f).setUv(maxU0, maxV0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), -8/16f, -8/16f, -8/16f).setColor(0.75f, 0.75f, 0.75f,0.75f).setUv(minU0, maxV0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);

        builder.addVertex(pose.pose(), -8/16f, -4/16f , 8/16f).setColor(0.75f, 0.75f, 0.75f,0.75f).setUv(minU0, minV0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), -8/16f, -4/16f , -8/16f).setColor(0.75f, 0.75f, 0.75f,0.75f).setUv(maxU0, minV0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), -8/16f, -8/16f, -8/16f).setColor(0.75f, 0.75f, 0.75f,0.75f).setUv(maxU0, maxV0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), -8/16f, -8/16f, 8/16f).setColor(0.75f, 0.75f, 0.75f,0.75f).setUv(minU0, maxV0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);

        builder.addVertex(pose.pose(), 8/16f, -4/16f , 8/16f).setColor(0.75f, 0.75f, 0.75f,0.75f).setUv(minU0, minV0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), -8/16f, -4/16f , 8/16f).setColor(0.75f, 0.75f, 0.75f,0.75f).setUv(maxU0, minV0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), -8/16f, -8/16f, 8/16f).setColor(0.75f, 0.75f, 0.75f,0.75f).setUv(maxU0, maxV0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), 8/16f, -8/16f, 8/16f).setColor(0.75f, 0.75f, 0.75f,0.75f).setUv(minU0, maxV0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);

        builder.addVertex(pose.pose(), 8/16f, -4/16f , -8/16f).setColor(0.75f, 0.75f, 0.75f,0.75f).setUv(minU0, minV0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), 8/16f, -4/16f , 8/16f).setColor(0.75f, 0.75f, 0.75f,0.75f).setUv(maxU0, minV0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), 8/16f, -8/16f, 8/16f).setColor(0.75f, 0.75f, 0.75f,0.75f).setUv(maxU0, maxV0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        builder.addVertex(pose.pose(), 8/16f, -8/16f, -8/16f).setColor(0.75f, 0.75f, 0.75f,0.75f).setUv(minU0, maxV0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);


    }
}

