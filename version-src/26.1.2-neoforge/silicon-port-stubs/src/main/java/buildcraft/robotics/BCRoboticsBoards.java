package buildcraft.robotics;

import java.util.List;

/**
 * Compile-only Silicon boundary. The Robotics port replaces this class with the
 * real board registry before a production 26.X artifact is assembled.
 */
public final class BCRoboticsBoards {
    public record BoardEntry(String id, int energyCost) { }

    public static final BoardEntry EMPTY = new BoardEntry("buildcraft:empty", 0);

    private BCRoboticsBoards() {
    }

    public static void init() {
    }

    public static List<BoardEntry> robotEntries() {
        return List.of();
    }
}
