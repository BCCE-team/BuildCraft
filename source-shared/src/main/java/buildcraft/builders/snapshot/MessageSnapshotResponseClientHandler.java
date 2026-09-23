/*
 * Client-only handler for snapshot response packets.
 */
package buildcraft.builders.snapshot;
public final class MessageSnapshotResponseClientHandler {
    private MessageSnapshotResponseClientHandler() {
    }

    public static void handle(MessageSnapshotResponse message) {
        if (message.getSnapshot() != null) {
            // A normal server response is a temporary construction/tooltip copy. Only NET_DOWN from the
            // Electronic Library explicitly calls saveClientSnapshot; otherwise simply viewing/creating a
            // blueprint would incorrectly turn it into a cross-world client-library entry.
            ClientSnapshots.INSTANCE.onSnapshotReceived(message.getSnapshot());
        }
    }
}
