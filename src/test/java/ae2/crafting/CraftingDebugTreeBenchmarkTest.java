package ae2.crafting;

import ae2.api.crafting.IPatternDetails;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.debug.TileCraftingTreeTest;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Uses the in-game END generator, whose 2,999 intermediates all share one NBT variant group.
 */
@Tag("crafting-benchmark")
class CraftingDebugTreeBenchmarkTest extends CraftingPlanningTestSupport {
    private static void verify(CraftingPlan plan, List<IPatternDetails> patterns, long[] expected,
                               AEItemKey cobble, long raw) {
        assertEquals(patterns.size(), plan.patternTimes().size());
        for (int i = 0; i < patterns.size(); i++)
            assertEquals(expected[i], plan.patternTimes().getLong(patterns.get(i)));
        materials(plan.usedItems(), cobble, raw);
        materials(plan.missingItems());
        assertFalse(plan.simulation());
        assertEquals(0, plan.attemptMetrics().displayMaterializationNanos());
        assertEquals(0, plan.attemptMetrics().localReplans());
    }

    @ParameterizedTest(name = "TileCraftingTreeTest END seed={0}")
    @ValueSource(longs = {0x6A09E667F3BCC909L, 1L})
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void endColdAndWarmCalculation(long seed) throws Exception {
        memo(10_000);
        var generator = TileCraftingTreeTest.class.getDeclaredMethod("generatePatterns", long.class, List.class);
        generator.setAccessible(true);
        var patterns = new ArrayList<IPatternDetails>(3000);
        generator.invoke(null, seed, patterns);
        assertEquals(3000, patterns.size());
        var byOutput = new IdentityHashMap<AEKey, Integer>();
        for (int i = 0; i < patterns.size(); i++) byOutput.put(patterns.get(i).getPrimaryOutput().what(), i);
        var demand = new long[patterns.size()];
        demand[demand.length - 1] = 1;
        long raw = 0;
        for (int i = patterns.size() - 1; i >= 0; i--) {
            for (var input : patterns.get(i).getInputs()) {
                var stack = input.possibleInputs()[0];
                long count = stack.amount() * input.getMultiplier() * demand[i];
                var producer = byOutput.get(stack.what());
                if (producer == null) raw += count;
                else demand[producer] += count;
            }
        }
        assertEquals(12_000, raw);
        var output = patterns.getLast().getPrimaryOutput().what();
        var cobble = AEItemKey.of(new ItemStack(Blocks.COBBLESTONE));
        int warmups = Integer.getInteger("ae2.debugTreeWarmups", 8);
        int samples = Integer.getInteger("ae2.debugTreeSamples", 20);
        var cold = new long[3];
        var warm = new long[samples * cold.length];
        var graph = new long[warm.length];
        for (int round = 0; round < cold.length; round++) {
            var net = new CraftingTestNetwork();
            for (var pattern : patterns) net.addPattern(pattern, 0);
            net.store(cobble, raw);
            long start = System.nanoTime();
            var first = (CraftingPlan) net.plan(output, 1);
            cold[round] = System.nanoTime() - start;
            verify(first, patterns, demand, cobble, raw);
            for (int i = 0; i < warmups + samples; i++) {
                start = System.nanoTime();
                var plan = (CraftingPlan) net.plan(output, 1);
                long elapsed = System.nanoTime() - start;
                if (i >= warmups) {
                    int index = round * samples + i - warmups;
                    warm[index] = elapsed;
                    graph[index] = plan.attemptMetrics().graphCalculationNanos();
                }
                verify(plan, patterns, demand, cobble, raw);
            }
        }
        Arrays.sort(cold);
        Arrays.sort(warm);
        Arrays.sort(graph);
        System.out.printf(Locale.ROOT,
            "debug-END seed=%d: cold median=%.3f ms; warm median=%.3f ms p90=%.3f ms; graph median=%.3f ms%n",
            seed, cold[1] / 1e6, warm[warm.length / 2] / 1e6, warm[warm.length * 9 / 10] / 1e6,
            graph[graph.length / 2] / 1e6);
    }
}
