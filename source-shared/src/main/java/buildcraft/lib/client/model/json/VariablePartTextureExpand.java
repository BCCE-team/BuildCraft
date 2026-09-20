package buildcraft.lib.client.model.json;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonObject;

import buildcraft.lib.client.model.MutableQuad;
import buildcraft.lib.client.model.json.JsonVariableModel.ITextureGetter;
import buildcraft.lib.client.model.json.VariablePartCuboidBase.VariableFaceData;
import buildcraft.lib.expression.FunctionContext;
import buildcraft.lib.expression.api.IExpressionNode.INodeBoolean;
import buildcraft.lib.expression.api.IExpressionNode.INodeDouble;
import buildcraft.lib.expression.api.IExpressionNode.INodeLong;
import buildcraft.lib.expression.api.IExpressionNode.INodeObject;
import buildcraft.lib.expression.node.value.NodeConstantBoolean;
import buildcraft.lib.expression.node.value.NodeConstantLong;
import buildcraft.lib.internal.debug.BCLog;
import net.minecraft.core.Direction;

/**
 * Renders a textured face expanded to the requested 3D bounds.
 *
 * <p>The quad is built directly so the requested UV sub-region is honored consistently on all maintained loaders,
 * with {@link JsonVariableFaceUV} as the single source of texture, UV and rotation semantics.</p>
 */
public class VariablePartTextureExpand extends JsonVariableModelPart {
    public final INodeDouble[] from;
    public final INodeDouble[] to;
    public final INodeBoolean visible;
    public final INodeBoolean shade;
    public final INodeLong light;
    public final INodeLong colour;
    public final INodeObject<String> face;
    public final JsonVariableFaceUV faceUv;
    private final Set<String> invalidFaceStrings = new HashSet<>();

    public VariablePartTextureExpand(JsonObject obj, FunctionContext fnCtx) {
        from = readVariablePosition(obj, "from", fnCtx);
        to = readVariablePosition(obj, "to", fnCtx);
        shade = obj.has("shade") ? readVariableBoolean(obj, "shade", fnCtx) : NodeConstantBoolean.TRUE;
        visible = obj.has("visible") ? readVariableBoolean(obj, "visible", fnCtx) : NodeConstantBoolean.TRUE;
        light = obj.has("light") ? readVariableLong(obj, "light", fnCtx) : new NodeConstantLong(0);
        colour = obj.has("colour") ? readVariableLong(obj, "colour", fnCtx) : new NodeConstantLong(-1);
        face = readVariableString(obj, "face", fnCtx);
        faceUv = new JsonVariableFaceUV(obj, fnCtx);
    }

    @Override
    public void addQuads(List<MutableQuad> addTo, ITextureGetter spriteLookup) {
        if (!visible.evaluate() || !faceUv.visible.evaluate()) {
            return;
        }

        float[] f = bakePosition(from);
        float[] t = bakePosition(to);
        float sx = t[0] - f[0];
        float sy = t[1] - f[1];
        float sz = t[2] - f[2];

        VariableFaceData data = faceUv.evaluate(spriteLookup);
        Direction targetFace = evaluateFace(face);

        // Start with the +Z face of a unit cube. Rotating before applying the non-uniform bounds maps this face onto
        // the requested side while preserving the from/to box on every axis.
        MutableQuad quad = new MutableQuad(-1, Direction.SOUTH);
        quad.vertex_0.positionf(0, 1, 1).texf(data.uvs.minU, data.uvs.minV);
        quad.vertex_1.positionf(0, 0, 1).texf(data.uvs.minU, data.uvs.maxV);
        quad.vertex_2.positionf(1, 0, 1).texf(data.uvs.maxU, data.uvs.maxV);
        quad.vertex_3.positionf(1, 1, 1).texf(data.uvs.maxU, data.uvs.minV);

        quad.rotateTextureUp(data.rotations);
        quad.rotate(Direction.SOUTH, targetFace, 0.5f, 0.5f, 0.5f);
        quad.setFace(targetFace);
        quad.scaled(sx, sy, sz);
        quad.translated(f[0], f[1], f[2]);
        quad.setCalculatedNormal();
        quad.lighti((int) (light.evaluate() & 15), 0);
        quad.colouri((int) colour.evaluate());
        quad.texFromSprite(data.sprite);
        quad.setSprite(data.sprite);
        quad.setShade(shade.evaluate());

        if (data.bothSides) {
            addTo.add(quad.copyAndInvertNormal());
        } else if (data.invertNormal) {
            quad = quad.copyAndInvertNormal();
        }
        addTo.add(quad);
    }

    private Direction evaluateFace(INodeObject<String> node) {
        String s = node.evaluate();
        Direction side = Direction.byName(s);
        if (side == null) {
            if (invalidFaceStrings.add(s)) {
                BCLog.logger.warn("Invalid facing '" + s + "' from expression '" + node + "'");
            }
            return Direction.UP;
        }
        return side;
    }
}
