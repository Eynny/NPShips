package com.eynny.npships.naturalspawn.search;

import com.eynny.npships.config.EnvironmentRule;
import com.eynny.npships.config.SupportBlockDetector;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Incremental environment profile for one X/Z column and one rule.
 *
 * <p>The requested origin-Y range belongs to the CURRENT search region. The actual scanned Y range
 * is derived from that origin range, structure height and environment depth.
 *
 * <p>All block states are obtained from SpawnSpatialSnapshot. No world block-state lookup is
 * performed during profile evaluation.
 */
public final class ColumnEnvironmentProfile {

    public enum Status {
        RUNNING,
        WAITING_FOR_DATA,
        COMPLETE
    }

    public record StepResult(Status status, int operationsUsed) {}

    private final ServerLevel level;

    private final SpawnSpatialSnapshot snapshot;

    private final int x;
    private final int z;

    private final EnvironmentRule rule;

    private final boolean above;

    private final int structureHeight;

    private final int originMinY;
    private final int originMaxY;

    /*
     * Actual vertical range required to determine this profile.
     */
    private final int requiredMinY;
    private final int requiredMaxY;

    private final List<OriginYInterval> intervals = new ArrayList<>();

    private boolean initialized;
    private boolean complete;

    private int currentY;

    private int lastTerminalY;

    private int activeRunStart = Integer.MIN_VALUE;

    public ColumnEnvironmentProfile(
            final ServerLevel level,
            final SpawnSpatialSnapshot snapshot,
            final int x,
            final int z,
            final EnvironmentRule rule,
            final boolean above,
            final int structureHeight,
            final int originMinY,
            final int originMaxY) {
        if (level == null) {
            throw new IllegalArgumentException("level cannot be null.");
        }

        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot cannot be null.");
        }

        if (structureHeight <= 0) {
            throw new IllegalArgumentException("structureHeight must be positive.");
        }

        if (originMinY > originMaxY) {
            throw new IllegalArgumentException("originMinY cannot be greater than originMaxY.");
        }

        this.level = level;
        this.snapshot = snapshot;

        this.x = x;
        this.z = z;

        this.rule = rule;
        this.above = above;

        this.structureHeight = structureHeight;

        this.originMinY = originMinY;

        this.originMaxY = originMaxY;

        /*
         * A null rule is treated the same way as NONE.
         *
         * This keeps the profile defensive while the config layer
         * remains responsible for normal validation.
         */
        if (rule == null || rule.type() == EnvironmentRule.Type.NONE) {

            requiredMinY = originMinY;
            requiredMaxY = originMaxY;

        } else if (above) {

            /*
             * Above environment starts immediately above the
             * structure top.
             *
             * For an origin Y:
             *
             * structure occupies:
             *     originY .. originY + structureHeight - 1
             *
             * above rule starts at:
             *     originY + structureHeight
             */
            requiredMinY = coordinate((long) originMinY + structureHeight);

            requiredMaxY = coordinate((long) originMaxY + structureHeight + rule.depth() - 1);

        } else {

            /*
             * Under environment starts immediately below the
             * structure origin.
             */
            requiredMinY = coordinate((long) originMinY - rule.depth());

            requiredMaxY = coordinate((long) originMaxY - 1);
        }
    }

    private static int coordinate(long value) {
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "Environment profile coordinate exceeds integer range: " + value, overflow);
        }
    }

    public List<OriginYInterval> intervals() {
        return List.copyOf(intervals);
    }

    public boolean isComplete() {
        return complete;
    }

    public StepResult step(final int maxOperations) {
        if (complete) {
            return new StepResult(Status.COMPLETE, 0);
        }

        if (maxOperations <= 0) {
            return new StepResult(Status.RUNNING, 0);
        }

        if (rule == null || rule.type() == EnvironmentRule.Type.NONE) {

            intervals.clear();

            if (originMinY <= originMaxY) {
                intervals.add(new OriginYInterval(originMinY, originMaxY));
            }

            complete = true;

            /*
             * Resolving a NONE profile is still one logical
             * profile evaluation.
             */
            return new StepResult(Status.COMPLETE, 1);
        }

        /*
         * The complete required world range must already exist
         * inside the snapshot.
         *
         * No world scanning is performed here.
         */
        if (!snapshot.isColumnRangeComplete(x, z, requiredMinY, requiredMaxY)) {

            snapshot.requestColumn(x, z, requiredMinY, requiredMaxY);

            return new StepResult(Status.WAITING_FOR_DATA, 0);
        }

        if (!initialized) {
            initialize();
        }

        int operations = 0;

        while (operations < maxOperations && !complete) {

            boolean finished;

            switch (rule.type()) {
                case AIR, FLUID, ALL -> finished = stepRunBased();

                case SURFACE -> finished = stepSurface();

                case NONE -> finished = true;

                default -> finished = true;
            }

            operations++;

            if (finished) {
                // Under-SURFACE traverses downward; intersections require ascending intervals.
                intervals.sort(java.util.Comparator.comparingInt(OriginYInterval::minY));
                complete = true;
            }
        }

        return new StepResult(complete ? Status.COMPLETE : Status.RUNNING, operations);
    }

    private void initialize() {
        initialized = true;

        intervals.clear();

        activeRunStart = Integer.MIN_VALUE;

        if (rule.type() == EnvironmentRule.Type.SURFACE && !above) {

            /*
             * Under-surface search proceeds from high Y to low Y.
             */
            currentY = requiredMaxY;

            lastTerminalY = Integer.MAX_VALUE;

        } else {

            /*
             * Normal upward scan for AIR/FLUID/ALL and above-SURFACE.
             */
            currentY = requiredMinY;

            lastTerminalY = Integer.MIN_VALUE;
        }
    }

    private boolean stepRunBased() {

        if (currentY > requiredMaxY) {

            closeRunIfPresent(requiredMaxY);

            return true;
        }

        final BlockPos pos = new BlockPos(x, currentY, z);

        final BlockState state = snapshot.getState(x, currentY, z);

        final boolean valid = matchesRunBlock(pos, state);

        if (valid) {

            extendRun(currentY);

        } else {

            closeRunIfPresent(currentY - 1);
        }

        currentY++;

        if (currentY > requiredMaxY) {

            closeRunIfPresent(requiredMaxY);

            return true;
        }

        return false;
    }

    private boolean stepSurface() {

        if (!above) {
            return stepSurfaceUnder();
        }

        return stepSurfaceAbove();
    }

    private boolean stepSurfaceUnder() {

        if (currentY < requiredMinY) {
            return true;
        }

        final BlockPos pos = new BlockPos(x, currentY, z);

        final BlockState state = snapshot.getState(x, currentY, z);

        /*
         * IMPORTANT:
         *
         * The state comes from SpawnSpatialSnapshot.
         * No level.getBlockState(...) is performed.
         */
        final boolean support = isSupportBlock(pos, state);

        if (support) {

            if (EnvironmentBlockMatcher.matchesSurfaceSupport(state, rule)) {

                /*
                 * If the support is at Y, valid structure origins
                 * are directly above it.
                 *
                 * With depth D, origins may range from:
                 *
                 *     supportY + 1
                 * through
                 *     supportY + D
                 */
                int minOriginY = currentY + 1;

                long maxOriginY = (long) currentY + rule.depth();

                if (lastTerminalY != Integer.MAX_VALUE) {

                    /*
                     * A closer/higher terminal support prevents
                     * crossing that already-known surface.
                     */
                    maxOriginY = Math.min(maxOriginY, lastTerminalY);
                }

                addClampedInterval(minOriginY, maxOriginY);
            }

            /*
             * Every support is terminal, including a support
             * that failed the positive selector.
             */
            lastTerminalY = currentY;

        } else if (isInvalidSurfacePath(state)) {

            /*
             * An exclusion or fluid interrupts surface traversal.
             */
            lastTerminalY = currentY;
        }

        currentY--;

        return currentY < requiredMinY;
    }

    private boolean stepSurfaceAbove() {

        if (currentY > requiredMaxY) {
            return true;
        }

        final BlockPos pos = new BlockPos(x, currentY, z);

        final BlockState state = snapshot.getState(x, currentY, z);

        /*
         * IMPORTANT:
         *
         * The state comes from SpawnSpatialSnapshot.
         * No world block lookup is performed here.
         */
        final boolean support = isSupportBlock(pos, state);

        if (support) {

            if (EnvironmentBlockMatcher.matchesSurfaceSupport(state, rule)) {

                /*
                 * A support at currentY can serve as the terminal
                 * support for structure origins whose top is within
                 * rule.depth() blocks below it.
                 */
                long minStartY = (long) currentY - rule.depth() + 1;

                int maxStartY = currentY;

                if (lastTerminalY != Integer.MIN_VALUE) {

                    minStartY = Math.max(minStartY, lastTerminalY + 1);
                }

                long minOriginY = minStartY - structureHeight;

                int maxOriginY = maxStartY - structureHeight;

                addClampedInterval(minOriginY, maxOriginY);
            }

            /*
             * Every support is terminal, even when the support
             * itself does not satisfy the positive selector.
             */
            lastTerminalY = currentY;

        } else if (isInvalidSurfacePath(state)) {

            /*
             * An exclusion or fluid interrupts upward traversal.
             */
            lastTerminalY = currentY;
        }

        currentY++;

        return currentY > requiredMaxY;
    }

    private boolean matchesRunBlock(final BlockPos pos, final BlockState state) {
        return switch (rule.type()) {
            case AIR -> state.isAir();

            case FLUID -> matchesFluid(state);

            case ALL -> EnvironmentBlockMatcher.matchesAll(level, pos, state, rule);

            default -> false;
        };
    }

    private boolean matchesFluid(final BlockState state) {
        if (!state.getFluidState().isSource()) {
            return false;
        }

        if (rule.fluid() == null) {
            return true;
        }

        return state.getFluidState()
                .getType()
                .builtInRegistryHolder()
                .key()
                .location()
                .equals(rule.fluid());
    }

    /**
     * Checks whether the supplied already-scanned BlockState provides the required upper support
     * face.
     *
     * <p>This method deliberately does NOT fetch the BlockState from the world. The supplied state
     * came from SpawnSpatialSnapshot.
     */
    private boolean isSupportBlock(final BlockPos pos, final BlockState state) {
        if (!state.getFluidState().isEmpty()) {
            return false;
        }

        return SupportBlockDetector.isSupportBlock(level, pos, state);
    }

    private boolean isInvalidSurfacePath(final BlockState state) {
        /*
         * Explicit exclusions always interrupt traversal.
         */
        if (EnvironmentBlockMatcher.isExcluded(state, rule)) {
            return true;
        }

        /*
         * Fluids also interrupt surface traversal.
         */
        return !state.getFluidState().isEmpty();
    }

    private void extendRun(final int y) {
        if (activeRunStart == Integer.MIN_VALUE) {

            activeRunStart = y;
        }
    }

    private void closeRunIfPresent(final int runEnd) {
        if (activeRunStart == Integer.MIN_VALUE) {
            return;
        }

        addRunPlacementIntervals(activeRunStart, runEnd);

        activeRunStart = Integer.MIN_VALUE;
    }

    private void addRunPlacementIntervals(final int runStart, final int runEnd) {
        if (runStart > runEnd) {
            return;
        }

        final int depth = rule.depth();

        if (!above) {

            addClampedInterval((long) runStart + depth, (long) runEnd + 1);

        } else {

            addClampedInterval(
                    (long) runStart - structureHeight, (long) runEnd - depth + 1 - structureHeight);
        }
    }

    private void addClampedInterval(long minY, long maxY) {
        minY = Math.max(minY, originMinY);

        maxY = Math.min(maxY, originMaxY);

        if (minY <= maxY) {

            intervals.add(new OriginYInterval(Math.toIntExact(minY), Math.toIntExact(maxY)));
        }
    }
}
