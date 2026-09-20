package buildcraft.robotics.client.model.plug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import buildcraft.transport.internal.pluggable.IPluggableStaticBaker;
import buildcraft.lib.client.model.ModelHolderStatic;
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.robotics.client.model.key.KeyRobotStation;
import buildcraft.robotics.plug.RobotStationPluggable.RobotStationState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;

/**
 * Robot stations use shared geometry while their texture encodes state: free stations are green, reserved stations
 * are yellow, and linked/main stations are red.
 */
public final class PlugBakerRobotStation implements IPluggableStaticBaker<KeyRobotStation> {
    private final Map<RobotStationState, ModelHolderStatic> models = new EnumMap<>(RobotStationState.class);
    private final Map<RobotStationState, Map<Direction, List<BakedQuad>>> cached = new EnumMap<>(RobotStationState.class);
    private final Map<RobotStationState, MutableQuad[]> lastSeen = new EnumMap<>(RobotStationState.class);

    public PlugBakerRobotStation(ModelHolderStatic available, ModelHolderStatic reserved, ModelHolderStatic linked) {
        models.put(RobotStationState.None, available);
        models.put(RobotStationState.Available, available);
        models.put(RobotStationState.Reserved, reserved);
        models.put(RobotStationState.Linked, linked);
    }

    @Override
    public List<BakedQuad> bake(KeyRobotStation key) {
        RobotStationState state = normalize(key.state);
        ModelHolderStatic model = models.get(state);
        MutableQuad[] quads = model.getCutoutQuads();

        if (lastSeen.get(state) != quads || !cached.containsKey(state)) {
            rebuildCache(state, quads);
        }

        return cached.getOrDefault(state, Collections.emptyMap()).getOrDefault(key.side, Collections.emptyList());
    }

    private void rebuildCache(RobotStationState state, MutableQuad[] quads) {
        Map<Direction, List<BakedQuad>> bySide = new EnumMap<>(Direction.class);
        MutableQuad copy = new MutableQuad();

        for (Direction to : Direction.values()) {
            List<BakedQuad> baked = new ArrayList<>();
            for (MutableQuad quad : quads) {
                copy.copyFrom(quad);
                copy.rotate(Direction.WEST, to, 0.5F, 0.5F, 0.5F);
                baked.add(copy.toBakedBlock());
            }
            bySide.put(to, baked);
        }

        cached.put(state, bySide);
        lastSeen.put(state, quads);
    }

    private static RobotStationState normalize(RobotStationState state) {
        return state == null ? RobotStationState.None : state;
    }
}
