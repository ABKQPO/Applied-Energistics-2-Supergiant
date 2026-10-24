package ae2.crafting;

import ae2.api.crafting.IPatternDetails;
import ae2.api.networking.crafting.CalculationStrategy;
import ae2.api.stacks.GenericStack;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static ae2.crafting.CraftingTestNetwork.crafts;
import static ae2.crafting.CraftingTestNetwork.key;
import static ae2.crafting.CraftingTestNetwork.of;
import static ae2.crafting.CraftingTestNetwork.total;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingPlanningCombinationsTest extends CraftingPlanningTestSupport {
    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    void differentSizedAlternativesKeepTheirUnitsAndContainerReturns(int cache) {
        memo(cache);
        var net = new CraftingTestNetwork();
        net.container("bucket", "empty");
        var wet = net.pattern("wet", new IPatternDetails.IInput[]{net.input(1,
            new GenericStack(key("water"), 1_000), new GenericStack(key("bucket"), 1))}, of("wet", 1));
        var root = net.pattern("root", of("wet", 2, "empty", 1), of("result", 1));
        net.store("water", 1_500);
        net.store("bucket", 1);
        var plan = net.plan("result", 1);
        complete(net, plan, "result", 1);
        assertEquals(2, crafts(plan, wet));
        assertEquals(1, crafts(plan, root));
        materials(plan.usedItems(), "water", 1_000, "bucket", 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    void aMissingCycleSeedIsReportedOnceAndReplenishmentClearsIt(int cache) {
        memo(cache);
        var net = new CraftingTestNetwork();
        net.pattern("first", of("a", 1, "fuel", 1), of("b", 1));
        net.pattern("second", of("b", 1), of("c", 1));
        net.pattern("last", of("c", 1), of("dust", 1, "a", 2));
        net.store("fuel", 5);
        var missingSeed = net.plan("dust", 5);
        assertEquals(1, total(missingSeed.missingItems()), "one seed suffices to start the cycle");
        for (var missingKey : amounts(missingSeed.missingItems()).keySet()) {
            assertTrue(missingKey.equals(key("a")) || missingKey.equals(key("b")) || missingKey.equals(key("c")));
        }
        net.store("a", 1);
        var replenished = net.plan("dust", 5);
        complete(net, replenished, "dust", 5);
        materials(replenished.usedItems(), "a", 1, "fuel", 5);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    void multipliedInputMixesUnitsRatherThanRequiringWholeVariantStacks(int cache) {
        memo(cache);
        var net = new CraftingTestNetwork();
        var pulp = net.pattern("pulp", new IPatternDetails.IInput[]{net.input(3, "oak", "birch")}, of("pulp", 1));
        net.store("oak", 5);
        net.store("birch", 4);
        for (int attempt = 0; attempt < 2; attempt++) {
            var plan = net.plan("pulp", 3);
            complete(net, plan, "pulp", 3);
            assertEquals(3, crafts(plan, pulp));
            materials(plan.usedItems(), "oak", 5, "birch", 4);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    void partialMultipliedInputDoesNotConsumeTheUnusedRemainder(int cache) {
        memo(cache);
        var net = new CraftingTestNetwork();
        var preferred = net.pattern("preferred", new IPatternDetails.IInput[]{net.input(3, "raw")},
            of("result", 1, "slag", 1));
        net.priority(preferred, 10);
        var fallback = net.pattern("fallback", 5, of("other", 1), of("result", 1));
        net.store("raw", 5);
        net.store("other", 1);
        var plan = net.plan("result", 2);
        complete(net, plan, "result", 2);
        assertEquals(1, crafts(plan, preferred));
        assertEquals(1, crafts(plan, fallback));
        materials(plan.usedItems(), "raw", 3, "other", 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    void alternativeInputsReturnOnlyTheirOwnContainer(int cache) {
        memo(cache);
        var net = new CraftingTestNetwork();
        net.container("water_cell", "empty_cell");
        net.container("water_bucket", "empty_bucket");
        var part = net.pattern("part", of("water_cell|water_bucket", 1), of("part", 1));
        var root = net.pattern("root", of("part", 2, "empty_cell", 1, "empty_bucket", 1), of("result", 1));
        net.store("water_cell", 1);
        net.store("water_bucket", 1);
        for (int attempt = 0; attempt < 2; attempt++) {
            var plan = net.plan("result", 1);
            complete(net, plan, "result", 1);
            assertEquals(2, crafts(plan, part));
            assertEquals(1, crafts(plan, root));
            materials(plan.usedItems(), "water_cell", 1, "water_bucket", 1);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    void separateProductiveCyclesShareOneSeedWithoutDuplicatingIt(int cache) {
        memo(cache);
        var net = new CraftingTestNetwork();
        var ab = net.pattern("ab", of("seed", 1, "fuel_b", 1), of("b", 1));
        var ba = net.pattern("ba", of("b", 1), of("left", 1, "seed", 1));
        var ac = net.pattern("ac", of("seed", 1, "fuel_c", 1), of("c", 1));
        var ca = net.pattern("ca", of("c", 1), of("right", 1, "seed", 1));
        var root = net.pattern("root", of("left", 1, "right", 1), of("result", 1));
        net.store("seed", 1);
        net.store("fuel_b", 3);
        net.store("fuel_c", 3);
        for (int attempt = 0; attempt < 2; attempt++) {
            var plan = net.plan("result", 3);
            complete(net, plan, "result", 3);
            for (var pattern : new IPatternDetails[]{ab, ba, ac, ca, root}) assertEquals(3, crafts(plan, pattern));
            materials(plan.usedItems(), "seed", 1, "fuel_b", 3, "fuel_c", 3);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    void oddCycleRequestsRoundTheExpansionAndReuseItsRemainder(int cache) {
        memo(cache);
        var net = new CraftingTestNetwork();
        var expand = net.pattern("expand", of("x", 1), of("y", 2));
        var result = net.pattern("return", of("y", 1), of("z", 1, "x", 1));
        net.store("x", 1);
        for (int amount : new int[]{1, 3, 7, 2, 3}) {
            var plan = net.plan("z", amount);
            complete(net, plan, "z", amount);
            assertEquals(amount, crafts(plan, result));
            assertEquals((amount + 1) / 2, crafts(plan, expand));
            materials(plan.usedItems(), "x", 1);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    void twoConsumersShareByproductsInsteadOfCraftingThemAgain(int cache) {
        memo(cache);
        var net = new CraftingTestNetwork();
        var split = net.pattern("split", of("ore", 1), of("a", 2, "b", 3));
        var left = net.pattern("left", of("a", 1, "b", 1), of("left", 1));
        var right = net.pattern("right", of("b", 2), of("right", 1));
        var root = net.pattern("root", of("left", 1, "right", 1), of("result", 1));
        net.store("ore", 5);
        var plan = net.plan("result", 5);
        complete(net, plan, "result", 5);
        for (var pattern : new IPatternDetails[]{split, left, right, root}) assertEquals(5, crafts(plan, pattern));
        materials(plan.usedItems(), "ore", 5);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    void craftLessMatchesExhaustiveAlternativeRecipeCounts(int cache) {
        memo(cache);
        // Enumerate all affordable counts independently of the planner. Two intermediate producers and a
        // downstream bottleneck make this more than a single recipe's stock/input division.
        for (int iron = 0; iron <= 5; iron++) {
            for (int copper = 0; copper <= 5; copper++) {
                var net = new CraftingTestNetwork();
                net.pattern("iron", 10, of("iron", 2), of("wire", 3));
                net.pattern("copper", 5, of("copper", 1), of("wire", 2));
                net.pattern("root", of("wire", 2, "rubber", 1), of("result", 1));
                net.store("iron", iron);
                net.store("copper", copper);
                net.store("rubber", 4);
                int maximum = 0;
                for (int a = 0; 2 * a <= iron; a++) {
                    for (int b = 0; b <= copper; b++) maximum = Math.max(maximum, Math.min(4, (3 * a + 2 * b) / 2));
                }
                var plan = net.plan("result", 9, CalculationStrategy.CRAFT_LESS);
                if (maximum == 0) {
                    assertEquals(9, plan.finalOutput().amount(), "zero feasible output must use missing-item mode");
                    assertFalse(amounts(plan.missingItems()).isEmpty());
                    withinStock(net, plan);
                } else {
                    complete(net, plan, "result", maximum);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    void largeAmountsKeepExactIntegerRounding(int cache) {
        memo(cache);
        var net = new CraftingTestNetwork();
        var wire = net.pattern("wire", of("ore", 2), of("wire", 3));
        long amount = (1L << 32) + 1;
        long times = (amount + 2) / 3;
        net.store("ore", 2 * times);
        var plan = net.plan("wire", amount);
        complete(net, plan, "wire", amount);
        assertEquals(times, crafts(plan, wire));
        materials(plan.usedItems(), "ore", 2 * times);
    }
}
