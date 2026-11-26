package buildcraft.lib.client.config;

/** Row-based scrolling shared by the category tree and the parameter list. */
final class ConfigurationScroll {
    private ConfigurationScroll() {}

    static int maxOffset(int size, int visible) {
        return Math.max(0, size - Math.max(1, visible));
    }

    static int clamp(int offset, int size, int visible) {
        return Math.max(0, Math.min(offset, maxOffset(size, visible)));
    }

    static int wheel(int offset, double delta, int size, int visible) {
        if (!Double.isFinite(delta) || delta == 0) return clamp(offset, size, visible);
        int steps = (int) Math.min(Integer.MAX_VALUE, Math.ceil(Math.abs(delta)));
        long next = (long) offset + (delta > 0 ? -steps : steps);
        return (int) Math.max(0, Math.min(next, maxOffset(size, visible)));
    }

    record Thumb(int top, int height) {
        boolean contains(double y) { return y >= top && y < top + height; }
    }

    static Thumb thumb(int trackTop, int trackHeight, int offset, int size, int visible) {
        if (trackHeight <= 0) return new Thumb(trackTop, 0);
        int max = maxOffset(size, visible);
        if (max == 0) return new Thumb(trackTop, trackHeight);
        int height = Math.min(trackHeight, Math.max(12, (int) Math.round((double) trackHeight * visible / size)));
        int top = trackTop + (int) Math.round((double) (trackHeight - height) * clamp(offset, size, visible) / max);
        return new Thumb(top, height);
    }

    static int drag(double thumbTop, int trackTop, int trackHeight, int size, int visible) {
        int travel = trackHeight - thumb(trackTop, trackHeight, 0, size, visible).height();
        if (travel <= 0) return 0;
        double fraction = Math.max(0, Math.min(1, (thumbTop - trackTop) / travel));
        return (int) Math.round(fraction * maxOffset(size, visible));
    }
}
