package buildcraft.lib.client.model;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import buildcraft.lib.misc.DirectionCompat;

class MutableQuadRotateTester {
    /** Rotated culling face must match the actual geometric face for every direction triple. */
    @Test
    void rotatedQuadFaceLabelMatchesGeometry() {
        for (Direction face : Direction.values()) {
            for (Direction from : Direction.values()) {
                for (Direction to : Direction.values()) {
                    MutableQuad quad = quadOnFace(face);
                    quad.rotate(from, to, 0.5f, 0.5f, 0.5f);
                    Assertions.assertEquals(sideOf(quad), quad.getFace(),
                        "face " + face + " rotated " + from + " -> " + to);
                }
            }
        }
    }

    /** Unlabeled geometry is not assigned an arbitrary face on rotation. */
    @Test
    void rotatedQuadWithoutFaceStaysUnlabelled() {
        MutableQuad quad = quadOnFace(Direction.UP);
        quad.setFace(null);
        quad.rotate(Direction.UP, Direction.NORTH, 0.5f, 0.5f, 0.5f);
        Assertions.assertNull(quad.getFace());
    }

    private static MutableQuad quadOnFace(Direction face) {
        MutableQuad quad = new MutableQuad(-1, face);
        int axis = face.getAxis().ordinal();
        int a = (axis + 1) % 3;
        int b = (axis + 2) % 3;
        float plane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
        float[][] corners = { { 0, 0 }, { 0, 1 }, { 1, 1 }, { 1, 0 } };
        for (int i = 0; i < 4; i++) {
            float[] pos = new float[3];
            pos[axis] = plane;
            pos[a] = corners[i][0];
            pos[b] = corners[i][1];
            quad.vertexs[i].positionf(pos[0], pos[1], pos[2]);
        }
        return quad;
    }

    private static Direction sideOf(MutableQuad quad) {
        float x = 0, y = 0, z = 0;
        for (MutableVertex vertex : quad.vertexs) {
            x += vertex.position_x / 4 - 0.125f;
            y += vertex.position_y / 4 - 0.125f;
            z += vertex.position_z / 4 - 0.125f;
        }
        return DirectionCompat.nearest(x, y, z);
    }
}
