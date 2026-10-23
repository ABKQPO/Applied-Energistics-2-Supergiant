package ae2.crafting;

import ae2.api.crafting.IPatternDetails;
import ae2.api.networking.crafting.ICraftingPlan;
import ae2.api.stacks.AEKey;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static ae2.crafting.CraftingTestNetwork.crafts;
import static ae2.crafting.CraftingTestNetwork.key;
import static ae2.crafting.CraftingTestNetwork.of;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent arithmetic expectations and constructive feasibility witnesses, with no accepted planning failures.
 */
class CraftingPlanningOracleTest extends CraftingPlanningTestSupport {
    static Stream<Arguments> cases() {
        return IntStream.of(0, 10_000).boxed().flatMap(cache ->
            IntStream.range(0, 100).mapToObj(seed -> Arguments.of(cache, seed)));
    }

    private static void supplyWitness(List<Recipe> recipes, int item, long amount, long[] supplied, List<Step> witness) {
        if (item < 3) {
            supplied[item] += amount;
            return;
        }
        var recipe = recipes.get(item - 3);
        long times = ceil(amount, recipe.output);
        for (int i = 0; i < recipe.dependencies.length; i++) {
            supplyWitness(recipes, recipe.dependencies[i], times * recipe.units[i], supplied, witness);
        }
        witness.add(new Step(recipe.pattern, times));
    }

    private static void verifyWitness(CraftingTestNetwork net, List<Step> witness, String output, long amount) {
        var inventory = new HashMap<>(amounts(net.stock));
        for (var step : witness) {
            for (long craft = 0; craft < step.times; craft++) {
                for (var input : step.pattern.getInputs()) {
                    var stack = input.possibleInputs()[0];
                    long needed = stack.amount() * input.getMultiplier();
                    long available = inventory.getOrDefault(stack.what(), 0L);
                    assertTrue(available >= needed, "invalid feasibility witness for " + stack.what());
                    inventory.put(stack.what(), available - needed);
                }
                for (var produced : step.pattern.getOutputs()) {
                    inventory.merge(produced.what(), produced.amount(), Long::sum);
                }
            }
        }
        assertTrue(inventory.getOrDefault(key(output), 0L) >= amount, "witness did not produce its claimed output");
    }

    private static void assertMaterialBalance(ICraftingPlan plan) {
        var balance = new HashMap<AEKey, Long>(amounts(plan.usedItems()));
        for (var entry : plan.patternTimes().object2LongEntrySet()) {
            var pattern = entry.getKey();
            long times = entry.getLongValue();
            for (var input : pattern.getInputs()) {
                assertEquals(1, input.possibleInputs().length, "this independent balance model uses exact inputs");
                var stack = input.possibleInputs()[0];
                balance.merge(stack.what(), -times * input.getMultiplier() * stack.amount(), Long::sum);
            }
            for (var output : pattern.getOutputs()) balance.merge(output.what(), times * output.amount(), Long::sum);
        }
        for (var entry : balance.entrySet()) assertTrue(entry.getValue() >= 0, "material deficit: " + entry);
        assertTrue(balance.getOrDefault(plan.finalOutput().what(), 0L) >= plan.finalOutput().amount());
    }

    private static String name(int item) {
        return "oracle_" + item;
    }

    private static long ceil(long amount, long batch) {
        return amount / batch + (amount % batch == 0 ? 0 : 1);
    }

    @ParameterizedTest(name = "requirements: memo={0}, seed={1}")
    @MethodSource("cases")
    void exactTwoStageMaterialRequirementsIncludeStockAndRounding(int cache, int seed) {
        memo(cache);
        var random = new Random(seed);
        int rawPerCraft = 1 + random.nextInt(4);
        int partsPerCraft = 1 + random.nextInt(4);
        int partsPerResult = 1 + random.nextInt(4);
        int resultsPerCraft = 1 + random.nextInt(3);
        int request = 1 + random.nextInt(11);
        int partStock = random.nextInt(9);
        int rawStock = random.nextInt(21);
        // With a single producer at each stage these closed-form quantities are unambiguous.
        long rootTimes = ceil(request, resultsPerCraft);
        long usedParts = Math.min(partStock, rootTimes * partsPerResult);
        long partTimes = ceil(rootTimes * partsPerResult - usedParts, partsPerCraft);
        long neededRaw = partTimes * rawPerCraft;
        var net = new CraftingTestNetwork();
        var part = net.pattern("part", new IPatternDetails.IInput[]{net.input(rawPerCraft, "raw")}, (seed & 1) == 0
            ? of("part", partsPerCraft) : of("part", partsPerCraft, "waste", 1));
        var root = net.pattern("root", new IPatternDetails.IInput[]{net.input(partsPerResult, "part")},
            of("result", resultsPerCraft));
        net.store("raw", rawStock);
        net.store("part", partStock);
        var before = amounts(net.stock);
        assertAll(IntStream.range(0, 2).mapToObj(attempt -> (Executable) () -> {
            try {
                var plan = net.plan("result", request);
                assertEquals(request, plan.finalOutput().amount());
                assertEquals(key("result"), plan.finalOutput().what());
                assertEquals(rootTimes, crafts(plan, root));
                assertEquals(partTimes, crafts(plan, part));
                // A rounded batch may leave enough surplus to return some of the initially reserved stored parts.
                // Both allocations are valid; require the minimal craft count and enough parts to cover the demand.
                long actualUsedParts = plan.usedItems().get(key("part"));
                long minimumUsedParts = Math.max(0, rootTimes * partsPerResult - partTimes * partsPerCraft);
                assertTrue(actualUsedParts >= minimumUsedParts && actualUsedParts <= usedParts,
                    "stored parts outside the valid allocation range: " + actualUsedParts);
                materials(plan.usedItems(), "part", actualUsedParts, "raw", Math.min(rawStock, neededRaw));
                materials(plan.missingItems(), "raw", Math.max(0, neededRaw - rawStock));
                materials(plan.emittedItems());
                assertEquals(before, amounts(net.stock));
                withinStock(net, plan);
            } catch (AssertionError | RuntimeException failure) {
                throw new AssertionError("Two-stage requirements: memo=" + cache + ", seed=" + seed
                    + ", attempt=" + attempt + ", request=" + request + ", expected crafts: root=" + rootTimes
                    + ", part=" + partTimes + ", raw=" + neededRaw + ": " + failure.getMessage()
                    + "\n" + net.describe(), failure);
            }
        }));
    }

    @ParameterizedTest(name = "feasible: memo={0}, seed={1}")
    @MethodSource("cases")
    void constructiveRandomNetworksMustPlanWithoutMissingItems(int cache, int seed) {
        memo(cache);
        var random = new Random(90210L + seed);
        var net = new CraftingTestNetwork();
        var recipes = new ArrayList<Recipe>();
        int size = 5 + random.nextInt(5);
        for (int item = 3; item < size + 3; item++) {
            int other = random.nextInt(item - 1);
            int[] dependencies = {item - 1, other};
            int[] units = {1 + random.nextInt(2), 1 + random.nextInt(2)};
            int output = 1 + random.nextInt(3);
            var inputs = new ArrayList<Object>(List.of(name(item - 1), units[0], name(other), units[1]));
            var outputs = new ArrayList<Object>(List.of(name(item), output));
            if ((seed & 1) != 0) {
                // Exercise byproduct/local-tree paths as well as ordinary graph planning.
                outputs.add("waste_" + item);
                outputs.add(1);
            }
            boolean catalyst = random.nextInt(4) == 0;
            if (catalyst) {
                inputs.add("tool");
                inputs.add(1);
                outputs.add("tool");
                outputs.add(1);
            }
            var pattern = net.pattern("recipe_" + item, inputs.toArray(), outputs.toArray());
            recipes.add(new Recipe(pattern, dependencies, units, output, catalyst));
        }
        net.store("tool", 1);
        int request = 1 + random.nextInt(5);
        assertAll(IntStream.of(request, request + 1).mapToObj(amount -> (Executable) () -> {
            long[] supplied = new long[3];
            var witness = new ArrayList<Step>();
            // Expand each branch separately, leaving every rounding surplus unused. This describes a concrete
            // feasible sequence (dependencies first) without depending on the planner's sharing or caches.
            supplyWitness(recipes, size + 2, amount, supplied, witness);
            for (int raw = 0; raw < supplied.length; raw++) net.setStock(name(raw), supplied[raw]);
            var before = amounts(net.stock);
            verifyWitness(net, witness, name(size + 2), amount);
            try {
                var plan = net.plan(name(size + 2), amount);
                complete(net, plan, name(size + 2), amount);
                assertEquals(before, amounts(net.stock));
                assertMaterialBalance(plan);
                if (recipes.stream().anyMatch(r -> r.catalyst && crafts(plan, r.pattern) > 0)) {
                    assertEquals(1, plan.usedItems().get(key("tool")), "the witness supplies exactly one catalyst seed");
                }
            } catch (AssertionError | RuntimeException failure) {
                throw new AssertionError("Verified feasible witness for amount " + amount + ", stock " + before
                    + ", but planning failed: " + failure.getMessage() + "\n" + net.describe(), failure);
            }
        }));
    }

    private record Recipe(IPatternDetails pattern, int[] dependencies, int[] units, int output, boolean catalyst) {
    }

    private record Step(IPatternDetails pattern, long times) {
    }
}
