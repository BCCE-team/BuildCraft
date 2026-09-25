package buildcraft.lib.net;

import buildcraft.builders.snapshot.MessageSnapshotRequest;
import buildcraft.builders.snapshot.MessageSnapshotResponse;
import buildcraft.core.marker.volume.MessageVolumeBoxes;
import buildcraft.lib.internal.module.BCModules;
import buildcraft.lib.net.cache.MessageObjectCacheRequest;
import buildcraft.lib.net.cache.MessageObjectCacheResponse;
import buildcraft.robotics.zone.MessageZoneMapRequest;
import buildcraft.robotics.zone.MessageZoneMapResponse;
import buildcraft.transport.net.MessageMultiPipeItem;
import buildcraft.transport.wire.MessageWireSystems;
import buildcraft.transport.wire.MessageWireSystemsPowered;

/**
 * Canonical packet catalogue for the legacy family (1.19.2/1.20.1).
 *
 * <p>The catalogue owns message identity, codec, direction and handler selection. Forge/Fabric adapters only map
 * these declarations to their native transport registration API.</p>
 */
public final class LegacyNetworkCatalog {
    private LegacyNetworkCatalog() {
    }

    public static void registerLibrary(BCMessageRegistrar registrar) {
        registrar.register(
            BCModules.LIB, MessageUpdateTile.class, MessageUpdateTile.HANDLER,
            MessageUpdateTile::toBytes, MessageUpdateTile::new, BCMessageDirection.BIDIRECTIONAL
        );
        registrar.register(
            BCModules.LIB, MessageContainer.class, MessageContainer.HANDLER,
            MessageContainer::toBytes, MessageContainer::new, BCMessageDirection.BIDIRECTIONAL
        );
        registrar.register(
            BCModules.LIB, MessageMarker.class, MessageMarker.HANDLER,
            MessageMarker::toBytes, MessageMarker::new, BCMessageDirection.CLIENTBOUND
        );
        registrar.register(
            BCModules.LIB, MessageObjectCacheRequest.class, MessageObjectCacheRequest.HANDLER,
            MessageObjectCacheRequest::toBytes, MessageObjectCacheRequest::new, BCMessageDirection.SERVERBOUND
        );
        registrar.register(
            BCModules.LIB, MessageObjectCacheResponse.class, MessageObjectCacheResponse.HANDLER,
            MessageObjectCacheResponse::toBytes, MessageObjectCacheResponse::new, BCMessageDirection.CLIENTBOUND
        );
        registrar.register(
            BCModules.LIB, MessageDebugRequest.class, MessageDebugRequest.HANDLER,
            MessageDebugRequest::toBytes, MessageDebugRequest::new, BCMessageDirection.SERVERBOUND
        );
        registrar.register(
            BCModules.LIB, MessageDebugResponse.class, MessageDebugResponse.HANDLER,
            MessageDebugResponse::toBytes, MessageDebugResponse::new, BCMessageDirection.CLIENTBOUND
        );
        registrar.register(
            BCModules.LIB, MessageGuideState.class, MessageGuideState.HANDLER,
            MessageGuideState::toBytes, MessageGuideState::new, BCMessageDirection.SERVERBOUND
        );
    }

    public static void registerCore(BCMessageRegistrar registrar) {
        registrar.register(
            BCModules.CORE, MessageVolumeBoxes.class, MessageVolumeBoxes.HANDLER,
            MessageVolumeBoxes::toBytes, MessageVolumeBoxes::new, BCMessageDirection.CLIENTBOUND
        );
    }

    public static void registerBuilders(BCMessageRegistrar registrar) {
        registrar.register(
            BCModules.BUILDERS, MessageSnapshotRequest.class, MessageSnapshotRequest.HANDLER,
            MessageSnapshotRequest::toBytes, MessageSnapshotRequest::new, BCMessageDirection.SERVERBOUND
        );
        registrar.register(
            BCModules.BUILDERS, MessageSnapshotResponse.class, MessageSnapshotResponse.HANDLER,
            MessageSnapshotResponse::toBytes, MessageSnapshotResponse::new, BCMessageDirection.CLIENTBOUND
        );
    }

    public static void registerRobotics(BCMessageRegistrar registrar) {
        registrar.register(
            BCModules.ROBOTICS, MessageZoneMapRequest.class, MessageZoneMapRequest.HANDLER,
            MessageZoneMapRequest::toBytes, MessageZoneMapRequest::new, BCMessageDirection.SERVERBOUND
        );
        registrar.register(
            BCModules.ROBOTICS, MessageZoneMapResponse.class, MessageZoneMapResponse.HANDLER,
            MessageZoneMapResponse::toBytes, MessageZoneMapResponse::new, BCMessageDirection.CLIENTBOUND
        );
    }

    public static void registerTransport(BCMessageRegistrar registrar) {
        registrar.register(
            BCModules.TRANSPORT, MessageWireSystems.class, MessageWireSystems.HANDLER,
            MessageWireSystems::toBytes, MessageWireSystems::new, BCMessageDirection.CLIENTBOUND
        );
        registrar.register(
            BCModules.TRANSPORT, MessageWireSystemsPowered.class, MessageWireSystemsPowered.HANDLER,
            MessageWireSystemsPowered::toBytes, MessageWireSystemsPowered::new, BCMessageDirection.CLIENTBOUND
        );
        registrar.register(
            BCModules.TRANSPORT, MessageMultiPipeItem.class, MessageMultiPipeItem.HANDLER,
            MessageMultiPipeItem::toBytes, MessageMultiPipeItem::new, BCMessageDirection.CLIENTBOUND
        );
    }
}
