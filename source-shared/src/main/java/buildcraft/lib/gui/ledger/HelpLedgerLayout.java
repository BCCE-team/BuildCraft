package buildcraft.lib.gui.ledger;

/** Screen-space calculations shared by the help ledger on every Minecraft target.
 *  Deliberately independent of Minecraft types so boundary cases can be regression-tested. */
public final class HelpLedgerLayout {
    private HelpLedgerLayout() {}

    /** Width available to a ledger growing from its anchor, leaving a small screen-edge gap. */
    public static double availableWidth(double anchorX, double screenWidth, boolean expandPositive, double closedWidth) {
        double available = expandPositive ? screenWidth - anchorX : anchorX;
        return Math.max(closedWidth, available - 2);
    }

    /** Move a tall ledger upwards as a unit, never allowing its top to cross the screen edge. */
    public static double upwardShift(double anchorY, double drawnHeight, double screenHeight) {
        double overflow = anchorY + drawnHeight - screenHeight + 2;
        return Math.max(0, Math.min(overflow, Math.max(0, anchorY - 2)));
    }

    /** Fit text between the icon/gaps and the edge of the ledger; narrower windows need more wrapping. */
    public static int textWidth(double panelWidth, int preferredWidth) {
        return Math.max(10, Math.min(preferredWidth, (int) Math.floor(panelWidth) - 32));
    }
}
