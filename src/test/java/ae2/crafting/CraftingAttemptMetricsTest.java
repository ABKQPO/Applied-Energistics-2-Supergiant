package ae2.crafting;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingAttemptMetricsTest {
    @Test
    void retainsAllConcurrentIncrementsAndFirstDisplayTiming() throws Exception {
        var metrics = new CraftingAttemptMetrics();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Future<?>>();
            for (int thread = 1; thread <= 8; thread++) {
                int timing = thread;
                tasks.add(executor.submit(() -> {
                    start.await();
                    metrics.recordDisplayMaterialization(timing);
                    for (int i = 0; i < 10_000; i++) {
                        metrics.recordLocalPatternPlan();
                        metrics.recordLocalComponentPlan();
                        metrics.recordLocalReplan();
                        metrics.recordNativeScc();
                        metrics.recordLocalScc();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        }

        var snapshot = metrics.snapshot();
        assertEquals(0, snapshot.wholeFallbacks());
        assertEquals(80_000, snapshot.localPatternPlans());
        assertEquals(80_000, snapshot.localComponentPlans());
        assertEquals(80_000, snapshot.localReplans());
        assertEquals(80_000, snapshot.nativeSccs());
        assertEquals(80_000, snapshot.localSccs());
        assertTrue(snapshot.displayMaterializationNanos() >= 1 && snapshot.displayMaterializationNanos() <= 8);
        metrics.recordDisplayMaterialization(100);
        assertEquals(snapshot.displayMaterializationNanos(), metrics.snapshot().displayMaterializationNanos());
    }

    @Test
    void publishesTimingAndKeepsMetricsInstancesIndependent() throws Exception {
        var metrics = new CraftingAttemptMetrics();
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> metrics.recordGraphCalculation(1234567890123L)).get(5, TimeUnit.SECONDS);
        }
        assertEquals(1234567890123L, metrics.snapshot().graphCalculationNanos());
        metrics.recordGraphCalculation(99);
        assertEquals(99, metrics.snapshot().graphCalculationNanos());
        assertEquals(new CraftingAttemptMetrics.Snapshot(0, 0, 0, 0, 0, 0, 0, 0),
            new CraftingAttemptMetrics().snapshot());
    }
}
