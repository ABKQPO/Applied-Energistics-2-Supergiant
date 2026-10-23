package ae2.crafting;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Lightweight counters retained with a crafting plan independently of performance logging.
 */
public final class CraftingAttemptMetrics {
    private static final VarHandle LOCAL_PATTERN_PLANS;
    private static final VarHandle LOCAL_COMPONENT_PLANS;
    private static final VarHandle LOCAL_REPLANS;
    private static final VarHandle NATIVE_SCCS;
    private static final VarHandle LOCAL_SCCS;
    private static final VarHandle DISPLAY_MATERIALIZATION_NANOS;

    static {
        try {
            var lookup = MethodHandles.lookup();
            LOCAL_PATTERN_PLANS = lookup.findVarHandle(CraftingAttemptMetrics.class, "localPatternPlans", long.class);
            LOCAL_COMPONENT_PLANS = lookup.findVarHandle(CraftingAttemptMetrics.class, "localComponentPlans", long.class);
            LOCAL_REPLANS = lookup.findVarHandle(CraftingAttemptMetrics.class, "localReplans", long.class);
            NATIVE_SCCS = lookup.findVarHandle(CraftingAttemptMetrics.class, "nativeSccs", long.class);
            LOCAL_SCCS = lookup.findVarHandle(CraftingAttemptMetrics.class, "localSccs", long.class);
            DISPLAY_MATERIALIZATION_NANOS = lookup.findVarHandle(CraftingAttemptMetrics.class,
                "displayMaterializationNanos", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private volatile long localPatternPlans;
    private volatile long localComponentPlans;
    private volatile long localReplans;
    private volatile long nativeSccs;
    private volatile long localSccs;
    private volatile long graphCalculationNanos;
    private volatile long displayMaterializationNanos;

    void recordLocalPatternPlan() {
        LOCAL_PATTERN_PLANS.getAndAdd(this, 1L);
    }

    void recordLocalComponentPlan() {
        LOCAL_COMPONENT_PLANS.getAndAdd(this, 1L);
    }

    void recordLocalReplan() {
        LOCAL_REPLANS.getAndAdd(this, 1L);
    }

    void recordNativeScc() {
        NATIVE_SCCS.getAndAdd(this, 1L);
    }

    void recordLocalScc() {
        LOCAL_SCCS.getAndAdd(this, 1L);
    }

    void recordGraphCalculation(long nanos) {
        this.graphCalculationNanos = nanos;
    }

    void recordDisplayMaterialization(long nanos) {
        DISPLAY_MATERIALIZATION_NANOS.compareAndSet(this, 0L, nanos);
    }

    public Snapshot snapshot() {
        return new Snapshot(
            0L,
            this.localPatternPlans,
            this.localComponentPlans,
            this.localReplans,
            this.nativeSccs,
            this.localSccs,
            this.graphCalculationNanos,
            this.displayMaterializationNanos);
    }

    public record Snapshot(long wholeFallbacks,
                           long localPatternPlans,
                           long localComponentPlans,
                           long localReplans,
                           long nativeSccs,
                           long localSccs,
                           long graphCalculationNanos,
                           long displayMaterializationNanos) {
    }
}
