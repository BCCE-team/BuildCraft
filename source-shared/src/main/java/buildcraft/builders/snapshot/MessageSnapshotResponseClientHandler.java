/*
 * Client-only handler for snapshot response packets.
 */
package buildcraft.builders.snapshot;
public final class MessageSnapshotResponseClientHandler {
    private MessageSnapshotResponseClientHandler() {
    }

    public static void handle(MessageSnapshotResponse message) {
        if (message.getSnapshot() != null) {
            ClientSnapshots.INSTANCE.onSnapshotReceived(message.getSnapshot());
        }
    }
}
