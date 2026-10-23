package ae2.crafting;

import ae2.core.AEConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static ae2.crafting.CraftingTestNetwork.names;
import static ae2.crafting.CraftingTestNetwork.replay;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Random small networks with cycles, byproducts, alternative patterns, input variants and catalysts: every plan the
 * calculation returns must run on a crafting CPU, and nothing but a reported failure may come out.
 */
class CraftingCalculationFuzzTest {
    private static final int RUNS = 1500;

    @Test
    void everyPlanRuns() {
        check("memo cache on");
    }

    @Test
    void everyPlanRunsWithoutMemoCache() {
        int previous = AEConfig.CRAFTING.memoizationCacheSize;
        AEConfig.CRAFTING.memoizationCacheSize = 0;
        try {
            check("memo cache off");
        } finally {
            AEConfig.CRAFTING.memoizationCacheSize = previous;
        }
    }

    private static void check(String label) {
        var problems = new ArrayList<String>();
        int plans = 0;
        int failures = 0;
        for (int seed = 0; seed < RUNS; seed++) {
            var fuzz = network(seed);
            var net = fuzz.net();
            String target = fuzz.target();
            long amount = fuzz.amount();
            try {
                var plan = net.plan(target, amount);
                plans++;
                var verdict = replay(plan);
                if (verdict != null) {
                    problems.add("seed " + seed + ": " + verdict + ", crafts " + plan.patternTimes() + ", used "
                        + names(plan.usedItems()) + ", missing " + names(plan.missingItems()));
                }
            } catch (CraftingCalculationFailure expected) {
                failures++;
            } catch (RuntimeException e) {
                problems.add("seed " + seed + " threw " + e);
            }
        }
        System.out.printf("fuzz (%s): %d plans, %d reported failures, %d problems%n", label, plans, failures,
            problems.size());
        for (var problem : problems) {
            System.out.println("fuzz problem (" + label + ") " + problem);
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    record Fuzz(CraftingTestNetwork net, String target, long amount) {
    }

    static Fuzz network(int seed) {
        var random = new Random(seed);
        var net = new CraftingTestNetwork();
        String prefix = "f" + seed + "_";
        int items = 4 + random.nextInt(8);
        for (int i = 2; i < items; i++) {
            int patterns = random.nextInt(10) < 7 ? 1 : 2;
            for (int p = 0; p < patterns; p++) {
                List<Object> in = new ArrayList<>();
                var names = new HashSet<String>();
                int inputs = 1 + random.nextInt(3);
                for (int c = 0; c < inputs; c++) {
                    // Mostly lower items, sometimes a higher one for cycles
                    int from = random.nextInt(10) < 8 ? random.nextInt(i) : random.nextInt(items);
                    String name = prefix + from;
                    if (!names.add(name)) continue;
                    if (random.nextInt(8) == 0) name = name + "|" + prefix + random.nextInt(items);
                    in.add(name);
                    in.add(1 + random.nextInt(3));
                }
                if (random.nextInt(10) == 0) {
                    in.add(prefix + "cat");
                    in.add(1);
                }
                List<Object> out = new ArrayList<>();
                out.add(prefix + i);
                out.add(1 + random.nextInt(3));
                if (random.nextInt(4) == 0) {
                    int byproduct = random.nextInt(items);
                    if (byproduct != i) {
                        out.add(prefix + byproduct);
                        out.add(1);
                    }
                }
                if (in.contains(prefix + "cat")) {
                    out.add(prefix + "cat");
                    out.add(1);
                }
                net.pattern(prefix + "p" + i + "_" + p, in.toArray(), out.toArray());
            }
        }
        for (int i = 0; i < items; i++) {
            if (random.nextInt(3) > 0) net.store(prefix + i, random.nextInt(40));
        }
        if (random.nextBoolean()) net.store(prefix + "cat", 1);
        return new Fuzz(net, prefix + (items - 1), 1 + random.nextInt(30));
    }
}
