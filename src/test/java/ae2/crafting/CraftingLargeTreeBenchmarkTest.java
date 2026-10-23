package ae2.crafting;

import ae2.api.networking.crafting.ICraftingPlan;
import ae2.core.AEConfig;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static ae2.crafting.CraftingTestNetwork.crafts;
import static ae2.crafting.CraftingTestNetwork.key;
import static ae2.crafting.CraftingTestNetwork.of;
import static ae2.crafting.CraftingTestNetwork.replay;
import static ae2.crafting.CraftingTestNetwork.total;
import static ae2.crafting.CraftingTestNetwork.used;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A repeatable, shared-dependency workload of 3,004 patterns and about 100,000 expanded item nodes.
 * Cold means a fresh CraftingService, warm means the same service and unchanged inventory/request.
 * Both are measured after JVM warmup; neither includes constructing the fixture, display generation or test replay.
 * Memoization can skip subtrees, so report actual item/process counts instead of calling craft quantities "nodes".
 */
@Tag("crafting-benchmark")
class CraftingLargeTreeBenchmarkTest {
    private static final int LAYERS = 7;
    private static final int WIDTH = 390;
    private static final int FAN_IN = 32;
    private static final int PATTERNS = 3_004;
    private static final int WARMUPS = 2;
    private static final int SAMPLES = 3;
    private static final long REQUEST = 2;
    private static final String OUTPUT = "large_tree_result";

    private static long[] runPair(int memoCacheSize, String label) {
        var fixture = new Fixture();
        var net = fixture.net;
        assertEquals(PATTERNS, net.patterns.size());
        assertNull(net.cachedGraph(OUTPUT));

        long allocatedBefore = allocatedBytes();
        long start = System.nanoTime();
        var cold = (CraftingPlan) net.plan(OUTPUT, REQUEST);
        long coldNanos = System.nanoTime() - start;
        long coldAllocated = allocatedBytes() - allocatedBefore;
        var cachedGraph = net.cachedGraph(OUTPUT);
        assertNotNull(cachedGraph, "the warm request must have a cached graph to reuse");

        allocatedBefore = allocatedBytes();
        start = System.nanoTime();
        var warm = (CraftingPlan) net.plan(OUTPUT, REQUEST);
        long warmNanos = System.nanoTime() - start;
        long warmAllocated = allocatedBytes() - allocatedBefore;
        assertSame(cachedGraph, net.cachedGraph(OUTPUT), "the warm request should reuse the graph structure");

        // Keep correctness checks outside both timers. A simulated plan with missing ingredients is not a success.
        fixture.verify(cold);
        fixture.verify(warm);
        assertEquals(cold.patternTimes(), warm.patternTimes(), "cache reuse changed the planned crafts");
        assertEquals(CraftingTestNetwork.names(cold.usedItems()), CraftingTestNetwork.names(warm.usedItems()),
            "cache reuse changed the required stock");
        assertNotNull(cold.tree(), "the productive cycles must exercise the legacy tree");
        assertNotNull(warm.tree());
        long coldNodes = cold.tree().getNodeCount();
        long warmNodes = warm.tree().getNodeCount();
        if (memoCacheSize == 0) {
            assertTrue(coldNodes >= 99_000 && coldNodes <= 102_000, "expanded item nodes: " + coldNodes);
            assertEquals(coldNodes, warmNodes, "graph reuse must not change the expanded workload");
        }
        System.out.printf(Locale.ROOT,
            "large-tree memo=%d %s: patterns=%d, cold=%.1f ms itemNodes=%d processNodes=%d depth=%d; "
                + "warm=%.1f ms itemNodes=%d processNodes=%d depth=%d%n",
            memoCacheSize, label, net.patterns.size(), coldNanos / 1e6, coldNodes,
            cold.tree().getPatternNodeCount(), cold.tree().getDepth(), warmNanos / 1e6, warmNodes,
            warm.tree().getPatternNodeCount(), warm.tree().getDepth());
        System.out.printf(Locale.ROOT, "large-tree memo=%d %s allocated: cold=%.1f MiB warm=%.1f MiB%n",
            memoCacheSize, label, coldAllocated / 1048576.0, warmAllocated / 1048576.0);
        return new long[]{coldNanos, warmNanos};
    }

    // Include both the calculator pool and the tick driver (recipe callbacks run on the driver). The fixed
    // benchmark does not run other calculations concurrently. Read counters outside the wall-clock timers.
    private static long allocatedBytes() {
        var management = ManagementFactory.getThreadMXBean();
        if (!(management instanceof ThreadMXBean threads)
            || !threads.isThreadAllocatedMemorySupported()) return 0;
        if (!threads.isThreadAllocatedMemoryEnabled()) threads.setThreadAllocatedMemoryEnabled(true);
        long total = 0;
        long driver = Thread.currentThread().threadId();
        for (var thread : threads.getThreadInfo(threads.getAllThreadIds())) {
            if (thread != null && (thread.getThreadId() == driver || thread.getThreadName().equals("AE Crafting Calculator"))) {
                total += Math.max(0, threads.getThreadAllocatedBytes(thread.getThreadId()));
            }
        }
        return total;
    }

    private static long median(long[] values) {
        var sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static long min(long[] values) {
        return Arrays.stream(values).min().orElseThrow();
    }

    private static long max(long[] values) {
        return Arrays.stream(values).max().orElseThrow();
    }

    private static String item(int layer, int column) {
        return "large_L" + layer + "_" + column;
    }

    @ParameterizedTest(name = "3004 patterns, memo cache size {0}")
    @ValueSource(ints = {0, 10_000})
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void coldAndWarmCalculation(int memoCacheSize) {
        CraftingTestNetwork.bootstrap();
        int previous = AEConfig.CRAFTING.memoizationCacheSize;
        AEConfig.CRAFTING.memoizationCacheSize = memoCacheSize;
        try {
            for (int i = 0; i < WARMUPS; i++) {
                runPair(memoCacheSize, "warmup-" + (i + 1));
            }
            var cold = new long[SAMPLES];
            var warm = new long[SAMPLES];
            for (int i = 0; i < SAMPLES; i++) {
                var sample = runPair(memoCacheSize, "sample-" + (i + 1));
                cold[i] = sample[0];
                warm[i] = sample[1];
            }
            System.out.printf(Locale.ROOT,
                "large-tree memo=%d: cold median=%.1f ms range=[%.1f, %.1f], "
                    + "warm median=%.1f ms range=[%.1f, %.1f], cold/warm=%.2fx%n",
                memoCacheSize, median(cold) / 1e6, min(cold) / 1e6, max(cold) / 1e6,
                median(warm) / 1e6, min(warm) / 1e6, max(warm) / 1e6,
                (double) median(cold) / median(warm));
        } finally {
            AEConfig.CRAFTING.memoizationCacheSize = previous;
        }
    }

    private static final class Fixture {
        final CraftingTestNetwork net = new CraftingTestNetwork();
        final List<CraftingTestNetwork.TestPattern> ordinary = new ArrayList<>();
        final List<ExpectedCraft> specialCrafts = new ArrayList<>();
        final List<ExpectedStock> specialStock = new ArrayList<>();
        final List<String> top = new ArrayList<>();
        final Map<String, Long> inputAmounts = new HashMap<>();

        Fixture() {
            for (int i = 0; i < WIDTH; i++) net.store(item(0, i), 1_000_000);
            for (int layer = 1; layer <= LAYERS; layer++) {
                for (int i = 0; i < WIDTH; i++) {
                    // Two distinct dependencies and one output: no surplus can shrink the expanded binary tree.
                    ordinary.add(net.pattern(item(layer, i),
                        of(item(layer - 1, i), 1, item(layer - 1, (i + layer) % WIDTH), 1),
                        of(item(layer, i), 1)));
                }
            }
            for (int i = 0; i < WIDTH; i++) top.add(item(LAYERS, i));

            for (int i = 0; i < 32; i++) {
                String p = "large_cycle2_" + i + "_";
                // A root craft takes one full two-y batch, even when a later branch reduces the root batch size.
                expect(net.pattern(p + "expand", of(p + "x", 1), of(p + "y", 2)), REQUEST);
                expect(net.pattern(p + "return", of(p + "y", 1), of(p + "z", 1, p + "x", 1)), 2 * REQUEST);
                stock(p + "x", 1, 1);
                top.add(p + "z");
                inputAmounts.put(p + "z", 2L);
            }
            for (int i = 0; i < 16; i++) {
                String p = "large_cycle3_" + i + "_";
                expect(net.pattern(p + "first", of(p + "a", 1, p + "fuel", 1), of(p + "b", 1)), REQUEST);
                expect(net.pattern(p + "second", of(p + "b", 1), of(p + "c", 1)), REQUEST);
                expect(net.pattern(p + "return", of(p + "c", 1), of(p + "dust", 1, p + "a", 2)), REQUEST);
                stock(p + "a", 1, 1);
                stock(p + "fuel", 1_000, REQUEST);
                top.add(p + "dust");
            }
            for (int i = 0; i < 40; i++) {
                String p = "large_recipes_" + i + "_";
                expect(net.pattern(p + "preferred", 10, of(p + "iron", 1), of(p + "rod", 1)), 1);
                expect(net.pattern(p + "fallback", 5, of(p + "steel", 1), of(p + "rod", 1)), REQUEST - 1);
                stock(p + "iron", 1, 1);
                stock(p + "steel", 1_000, REQUEST - 1);
                top.add(p + "rod");
            }
            for (int i = 0; i < 32; i++) {
                String p = "large_variants_" + i + "_";
                // Separate unit slots can mix variants within a craft; one encoded stack of three cannot.
                String variants = p + "oak|" + p + "birch";
                expect(net.pattern(p + "mix", of(variants, 1, variants, 1, variants, 1), of(p + "pulp", 1)), REQUEST);
                stock(p + "oak", 2, 2);
                stock(p + "birch", 3 * REQUEST - 2, 3 * REQUEST - 2);
                top.add(p + "pulp");
            }
            for (int i = 0; i < 16; i++) {
                String p = "large_catalyst_" + i + "_";
                expect(net.pattern(p + "make", of(p + "raw", 1, p + "tool", 1),
                    of(p + "part", 1, p + "tool", 1)), REQUEST);
                stock(p + "raw", 1_000, REQUEST);
                stock(p + "tool", 1, 1);
                top.add(p + "part");
            }
            for (int i = 0; i < 8; i++) {
                String p = "large_container_" + i + "_";
                net.container(p + "full", p + "empty");
                expect(net.pattern(p + "plate", of(p + "ingot", 1, p + "full", 1), of(p + "plate", 1)), REQUEST);
                expect(net.pattern(p + "fill", of(p + "empty", 1, p + "water", 1_000), of(p + "full", 1)), REQUEST);
                stock(p + "ingot", 1_000, REQUEST);
                stock(p + "water", 1_000_000, 1_000 * REQUEST);
                stock(p + "empty", 1, 1);
                top.add(p + "plate");
            }

            // Bound input slots per pattern; the root requests groups instead of using an enormous single recipe.
            var groups = new ArrayList<String>();
            for (int from = 0; from < top.size(); from += FAN_IN) {
                String group = "large_group_" + groups.size();
                expect(net.pattern(group, inputs(top.subList(from, Math.min(from + FAN_IN, top.size()))), of(group, 1)),
                    REQUEST);
                groups.add(group);
            }
            expect(net.pattern(OUTPUT, inputs(groups), of(OUTPUT, 1)), REQUEST);
        }

        private Object[] inputs(List<String> names) {
            var inputs = new Object[names.size() * 2];
            for (int i = 0; i < names.size(); i++) {
                inputs[2 * i] = names.get(i);
                inputs[2 * i + 1] = inputAmounts.getOrDefault(names.get(i), 1L);
            }
            return inputs;
        }

        private void expect(CraftingTestNetwork.TestPattern pattern, long times) {
            specialCrafts.add(new ExpectedCraft(pattern, times));
        }

        private void stock(String what, long available, long expectedUse) {
            net.store(what, available);
            specialStock.add(new ExpectedStock(what, expectedUse));
        }

        void verify(ICraftingPlan plan) {
            assertEquals(key(OUTPUT), plan.finalOutput().what());
            assertEquals(REQUEST, plan.finalOutput().amount());
            assertEquals(0, total(plan.missingItems()), () -> "missing: " + CraftingTestNetwork.names(plan.missingItems()));
            assertEquals(0, total(plan.emittedItems()));
            assertEquals(PATTERNS, plan.patternTimes().size(), "every registered pattern must actually run");
            for (int layer = 1; layer <= LAYERS; layer++) {
                long times = REQUEST * (1L << (LAYERS - layer));
                for (int i = 0; i < WIDTH; i++) {
                    var pattern = ordinary.get((layer - 1) * WIDTH + i);
                    assertEquals(times, crafts(plan, pattern), pattern.toString());
                }
            }
            for (int i = 0; i < WIDTH; i++) assertEquals(REQUEST * (1L << LAYERS), used(plan, item(0, i)));
            for (var expected : specialCrafts) {
                assertEquals(expected.times, crafts(plan, expected.pattern), expected.pattern.toString());
            }
            for (var expected : specialStock) {
                assertEquals(expected.amount, used(plan, expected.what), expected.what);
            }
            for (var entry : plan.usedItems()) {
                assertTrue(entry.getLongValue() <= net.stock.get(entry.getKey()), "used more than stored: " + entry.getKey());
            }
            assertNull(replay(plan), "the plan must execute in CPU task order and deliver the final output");
        }
    }

    private record ExpectedCraft(CraftingTestNetwork.TestPattern pattern, long times) {
    }

    private record ExpectedStock(String what, long amount) {
    }
}
