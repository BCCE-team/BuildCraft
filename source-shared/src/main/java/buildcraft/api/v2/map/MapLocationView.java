package buildcraft.api.v2.map;

import buildcraft.api.v2.area.BlockBox;
import buildcraft.api.v2.area.Path;
import buildcraft.api.v2.area.Zone;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Immutable semantic view of an encoded map-location item. */
public record MapLocationView(
    MapLocationKind kind,
    String label,
    Optional<BlockPos> point,
    Optional<Direction> pointSide,
    Optional<BlockBox> box,
    Optional<Zone> zone,
    Optional<Path> path
) {
    public MapLocationView {
        Objects.requireNonNull(kind, "kind");
        label = Objects.requireNonNull(label, "label");
        point = Objects.requireNonNull(point, "point");
        pointSide = Objects.requireNonNull(pointSide, "pointSide");
        box = Objects.requireNonNull(box, "box");
        zone = Objects.requireNonNull(zone, "zone");
        path = Objects.requireNonNull(path, "path");
        if (kind == MapLocationKind.CLEAN && (point.isPresent() || box.isPresent() || zone.isPresent() || path.isPresent())) {
            throw new IllegalArgumentException("CLEAN map location must not carry geometry");
        }
    }

    public static MapLocationView point(String label, BlockPos pos, Direction side) {
        return new MapLocationView(MapLocationKind.SPOT, label, Optional.of(Objects.requireNonNull(pos, "pos")),
            Optional.ofNullable(side), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static MapLocationView box(String label, BlockBox box) {
        return new MapLocationView(MapLocationKind.AREA, label, Optional.empty(), Optional.empty(),
            Optional.of(Objects.requireNonNull(box, "box")), Optional.empty(), Optional.empty());
    }

    public static MapLocationView zone(String label, Zone zone) {
        return new MapLocationView(MapLocationKind.ZONE, label, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of(Objects.requireNonNull(zone, "zone")), Optional.empty());
    }

    public static MapLocationView path(String label, Path path) {
        return new MapLocationView(MapLocationKind.PATH, label, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.of(Objects.requireNonNull(path, "path")));
    }

    public static MapLocationView repeatingPath(String label, Path path) {
        return new MapLocationView(MapLocationKind.PATH_REPEATING, label, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.of(Objects.requireNonNull(path, "path")));
    }

    public static MapLocationView clean(String label) {
        return new MapLocationView(MapLocationKind.CLEAN, label, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
