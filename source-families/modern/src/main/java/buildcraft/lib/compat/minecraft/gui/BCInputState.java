//? source if >=1.21.1
package buildcraft.lib.compat.minecraft.gui;

/** Internal modifier scope for callbacks whose legacy signature has no modifiers. */
public final class BCInputState {
    private static final ThreadLocal<Boolean> SHIFT = ThreadLocal.withInitial(() -> false);
    private BCInputState() {}

    public static boolean shiftDown() { return SHIFT.get(); }
    public static void setShiftDown(boolean value) { SHIFT.set(value); }
    public static Scope pushShift(boolean value) { return new Scope(value); }

    /** Restores the previous callback's modifiers, including nested and exceptional dispatch. */
    public static final class Scope implements AutoCloseable {
        private final Thread thread = Thread.currentThread();
        private final boolean previous;
        private boolean closed;
        private Scope(boolean value) {
            previous = SHIFT.get();
            SHIFT.set(value);
        }
        @Override
        public void close() {
            if (Thread.currentThread() != thread) throw new IllegalStateException("Input scope crossed threads");
            if (!closed) {
                SHIFT.set(previous);
                closed = true;
            }
        }
    }
}
