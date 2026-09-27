package buildcraft.transport.internal.pipe;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;

import javax.annotation.Nonnull;

import buildcraft.api.v2.fluid.FluidVolume;
import net.minecraft.core.Direction;

/** Loader-neutral legacy fluid-pipe events. */
public abstract class PipeEventFluid extends PipeEvent {

    public final IFlowFluid flow;

    protected PipeEventFluid(IPipeHolder holder, IFlowFluid flow) {
        super(holder);
        this.flow = flow;
    }

    /** @deprecated Cancellation is not part of the stable pipe-event contract. */
    @Deprecated
    protected PipeEventFluid(boolean canBeCancelled, IPipeHolder holder, IFlowFluid flow) {
        super(canBeCancelled, holder);
        this.flow = flow;
    }

    public static class TryInsert extends PipeEventFluid {
        public final Direction from;
        /** Incoming fluid. Changing this field does not replace the offered volume. */
        @Nonnull
        public final FluidVolume fluid;

        public TryInsert(IPipeHolder holder, IFlowFluid flow, Direction from, @Nonnull FluidVolume fluid) {
            super(true, holder, flow);
            this.from = from;
            this.fluid = fluid;
        }
    }

    /** Fired after collecting the amounts of fluid that can be moved from each pipe part into the centre. */
    public static class PreMoveToCentre extends PipeEventFluid {
        public final FluidVolume fluid;
        public final int totalAcceptable;
        public final int[] totalOffered;
        private final int[] totalOfferedCheck;
        public final int[] actuallyOffered;

        public PreMoveToCentre(IPipeHolder holder, IFlowFluid flow, FluidVolume fluid, int totalAcceptable,
            int[] totalOffered, int[] actuallyOffered) {
            super(holder, flow);
            this.fluid = fluid;
            this.totalAcceptable = totalAcceptable;
            this.totalOffered = totalOffered;
            totalOfferedCheck = Arrays.copyOf(totalOffered, totalOffered.length);
            this.actuallyOffered = actuallyOffered;
        }

        @Override
        public String checkStateForErrors() {
            for (int i = 0; i < totalOffered.length; i++) {
                if (totalOffered[i] != totalOfferedCheck[i]) {
                    return "Changed totalOffered";
                }
                if (actuallyOffered[i] > totalOffered[i]) {
                    return "actuallyOffered[" + i + "](=" + actuallyOffered[i]
                        + ") shouldn't be greater than totalOffered[" + i + "](=" + totalOffered[i] + ")";
                }
            }
            return super.checkStateForErrors();
        }
    }

    /** Fired after {@link PreMoveToCentre} when all of the amounts have been totalled up. */
    public static class OnMoveToCentre extends PipeEventFluid {
        public final FluidVolume fluid;
        public final int[] fluidLeavingSide;
        public final int[] fluidEnteringCentre;
        private final int[] fluidLeaveCheck, fluidEnterCheck;

        public OnMoveToCentre(IPipeHolder holder, IFlowFluid flow, FluidVolume fluid, int[] fluidLeavingSide,
            int[] fluidEnteringCentre) {
            super(holder, flow);
            this.fluid = fluid;
            this.fluidLeavingSide = fluidLeavingSide;
            this.fluidEnteringCentre = fluidEnteringCentre;
            fluidLeaveCheck = Arrays.copyOf(fluidLeavingSide, fluidLeavingSide.length);
            fluidEnterCheck = Arrays.copyOf(fluidEnteringCentre, fluidEnteringCentre.length);
        }

        @Override
        public String checkStateForErrors() {
            for (int i = 0; i < fluidLeavingSide.length; i++) {
                if (fluidLeavingSide[i] > fluidLeaveCheck[i]) {
                    return "fluidLeavingSide[" + i + "](=" + fluidLeavingSide[i]
                        + ") shouldn't be bigger than its original value!(=" + fluidLeaveCheck[i] + ")";
                }
                if (fluidEnteringCentre[i] > fluidEnterCheck[i]) {
                    return "fluidEnteringCentre[" + i + "](=" + fluidEnteringCentre[i]
                        + ") shouldn't be bigger than its original value!(=" + fluidEnterCheck[i] + ")";
                }
                if (fluidEnteringCentre[i] > fluidLeavingSide[i]) {
                    return "fluidEnteringCentre[" + i + "](=" + fluidEnteringCentre[i]
                        + ") shouldn't be bigger than fluidLeavingSide[" + i + "](=" + fluidLeavingSide[i] + ")";
                }
            }
            return super.checkStateForErrors();
        }
    }

    public static class SideCheck extends PipeEventFluid {
        public final FluidVolume fluid;
        private final int[] priority = new int[6];
        private final EnumSet<Direction> allowed = EnumSet.allOf(Direction.class);

        public SideCheck(IPipeHolder holder, IFlowFluid flow, FluidVolume fluid) {
            super(holder, flow);
            this.fluid = fluid;
        }

        public boolean isAllowed(Direction side) {
            return allowed.contains(side);
        }

        public void disallow(Direction... sides) {
            for (Direction side : sides) allowed.remove(side);
        }

        public void disallowAll(Collection<Direction> sides) {
            allowed.removeAll(sides);
        }

        public void disallowAllExcept(Direction side) {
            if (allowed.contains(side)) {
                allowed.clear();
                allowed.add(side);
            } else {
                allowed.clear();
            }
        }

        public void disallowAllExcept(Direction... sides) {
            switch (sides.length) {
                case 0 -> allowed.clear();
                case 1 -> disallowAllExcept(sides[0]);
                case 2 -> allowed.retainAll(EnumSet.of(sides[0], sides[1]));
                case 3 -> allowed.retainAll(EnumSet.of(sides[0], sides[1], sides[2]));
                case 4 -> allowed.retainAll(EnumSet.of(sides[0], sides[1], sides[2], sides[3]));
                default -> {
                    EnumSet<Direction> except = EnumSet.noneOf(Direction.class);
                    for (Direction face : sides) except.add(face);
                    allowed.retainAll(except);
                }
            }
        }

        public void disallowAllExcept(Collection<Direction> sides) {
            allowed.retainAll(sides);
        }

        public void disallowAll() {
            allowed.clear();
        }

        public void increasePriority(Direction side) {
            increasePriority(side, 1);
        }

        public void increasePriority(Direction side, int by) {
            priority[side.ordinal()] -= by;
        }

        public void decreasePriority(Direction side) {
            decreasePriority(side, 1);
        }

        public void decreasePriority(Direction side, int by) {
            increasePriority(side, -by);
        }

        public EnumSet<Direction> getOrder() {
            if (allowed.isEmpty()) return EnumSet.noneOf(Direction.class);
            if (allowed.size() == 1) return EnumSet.copyOf(allowed);

            boolean allEqual = true;
            int val = priority[0];
            for (int i = 1; i < priority.length; i++) {
                if (priority[i] != val) {
                    allEqual = false;
                    break;
                }
            }
            if (allEqual) return EnumSet.copyOf(allowed);

            int[] ordered = Arrays.copyOf(priority, 6);
            Arrays.sort(ordered);
            EnumSet<Direction> result = EnumSet.noneOf(Direction.class);
            for (int current : ordered) {
                for (Direction face : Direction.values()) {
                    if (allowed.contains(face) && priority[face.ordinal()] == current) result.add(face);
                }
                if (!result.isEmpty()) return result;
            }
            return result;
        }
    }
}
