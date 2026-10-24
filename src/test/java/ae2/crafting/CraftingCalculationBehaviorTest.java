package ae2.crafting;

import ae2.api.networking.crafting.CalculationStrategy;
import ae2.api.networking.crafting.ICraftingPlan;
import ae2.core.AEConfig;
import ae2.core.localization.PlayerMessages;
import org.junit.jupiter.api.Test;

import static ae2.crafting.CraftingTestNetwork.crafts;
import static ae2.crafting.CraftingTestNetwork.missing;
import static ae2.crafting.CraftingTestNetwork.names;
import static ae2.crafting.CraftingTestNetwork.of;
import static ae2.crafting.CraftingTestNetwork.replay;
import static ae2.crafting.CraftingTestNetwork.total;
import static ae2.crafting.CraftingTestNetwork.used;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the crafting calculation plans, checked against how a crafting CPU runs the plan.
 */
class CraftingCalculationBehaviorTest {

    private static void checkSubstitutedIntermediate(long stock) {
        checkSubstitutedIntermediate(stock, "a|raw");
    }

    private static CraftingTestNetwork substitutionNetwork(int order, String alternatives) {
        var net = new CraftingTestNetwork();
        net.pattern("make-a-" + order, of("raw", 1), of("a", 1, "slag", 1));
        net.pattern("make-b-" + order, of(alternatives, 1), of("b", 1, "slag", 1));
        net.pattern("root-" + order, of("a", 1, "b", 1), of("result", 1));
        return net;
    }

    private static void checkSubstitutedIntermediate(long stock, String alternatives) {
        // Different stable hashes cover different CPU task orders. B may consume A's output before the root does.
        for (int order = 0; order < 16; order++) {
            var net = substitutionNetwork(order, alternatives);
            net.store("raw", stock);

            var plan = net.plan("result", 1);

            assertEquals(0, total(plan.missingItems()),
                "stock " + stock + ", order " + order + ", missing " + names(plan.missingItems()));
            assertTrue(used(plan, "raw") <= stock);
            assertEquals(0, used(plan, "a"));
            assertRuns(plan);
        }
    }

    @Test
    void substitutedIntermediateIsCraftedInsteadOfReportedMissing() {
        checkSubstitutedIntermediate(3);
        checkSubstitutedIntermediate(2);
    }

    @Test
    void substitutedIntermediateIsCraftedWithoutMemoCache() {
        int previous = AEConfig.CRAFTING.memoizationCacheSize;
        AEConfig.CRAFTING.memoizationCacheSize = 0;
        try {
            checkSubstitutedIntermediate(3);
            checkSubstitutedIntermediate(2);
        } finally {
            AEConfig.CRAFTING.memoizationCacheSize = previous;
        }
    }

    @Test
    void unavailableAlternativeDoesNotBecomeAnExtraRequirement() {
        checkSubstitutedIntermediate(2, "a|raw|absent");
    }

    @Test
    void substitutionRepairReportsShortRawMaterialInsteadOfCraftableInputs() {
        for (int order = 0; order < 16; order++) {
            for (long stock = 0; stock <= 1; stock++) {
                var net = substitutionNetwork(order, "a|raw");
                net.store("raw", stock);

                var plan = net.plan("result", 1);

                assertTrue(missing(plan, "raw") > 0);
                assertEquals(missing(plan, "raw"), total(plan.missingItems()));
                assertTrue(used(plan, "raw") <= stock);
                assertRuns(plan);
            }
        }
    }

    private static void assertRuns(ICraftingPlan plan) {
        assertNull(replay(plan), () -> "plan does not run: crafts " + plan.patternTimes() + ", used "
            + names(plan.usedItems()) + ", missing " + names(plan.missingItems()));
    }

    @Test
    void chainTakesStoredItemsAndCraftsTheRest() {
        var net = new CraftingTestNetwork();
        var plank = net.pattern("plank", of("log", 1), of("plank", 1));
        var stick = net.pattern("stick", of("plank", 2), of("stick", 4));
        net.store("log", 10);
        net.store("plank", 2);

        var plan = net.plan("stick", 16);

        assertEquals(4, crafts(plan, stick));
        assertEquals(6, crafts(plan, plank));
        assertEquals(2, used(plan, "plank"));
        assertEquals(6, used(plan, "log"));
        assertEquals(0, total(plan.missingItems()));
        assertRuns(plan);
    }

    @Test
    void shortRawMaterialIsMissing() {
        var net = new CraftingTestNetwork();
        net.pattern("plate", of("ingot", 2), of("plate", 1));
        net.store("ingot", 5);

        var plan = net.plan("plate", 4);

        assertEquals(3, missing(plan, "ingot"));
        assertRuns(plan);
    }

    @Test
    void byproductsCoverLaterCrafts() {
        var net = new CraftingTestNetwork();
        var electrolyze = net.pattern("electrolyze", of("water", 3), of("hydrogen", 2, "oxygen", 1));
        net.pattern("oxygen", of("air", 5), of("oxygen", 1));
        var burn = net.pattern("burn", of("hydrogen", 2, "oxygen", 1), of("energy", 1));
        net.store("water", 300);

        var plan = net.plan("energy", 10);

        assertEquals(10, crafts(plan, burn));
        assertEquals(10, crafts(plan, electrolyze));
        assertEquals(0, used(plan, "air"));
        assertEquals(0, total(plan.missingItems()));
        assertRuns(plan);
    }

    // A catalyst the pattern gives back has to be there for the first craft
    @Test
    void missingCatalystIsReported() {
        var net = new CraftingTestNetwork();
        var cat = net.pattern("cat", of("a", 1, "catalyst", 1), of("b", 1, "catalyst", 1));
        net.store("a", 10);

        var plan = net.plan("b", 5);

        assertEquals(5, crafts(plan, cat));
        assertEquals(1, missing(plan, "catalyst"));
        assertRuns(plan);
    }

    @Test
    void storedCatalystIsTakenOnce() {
        var net = new CraftingTestNetwork();
        var cat = net.pattern("cat", of("a", 1, "catalyst", 1), of("b", 1, "catalyst", 1));
        net.store("a", 1000);
        net.store("catalyst", 1);

        var plan = net.plan("b", 1000);

        assertEquals(1000, crafts(plan, cat));
        assertEquals(1, used(plan, "catalyst"));
        assertEquals(0, total(plan.missingItems()));
        assertRuns(plan);
    }

    @Test
    void craftableCatalystIsCraftedOnce() {
        var net = new CraftingTestNetwork();
        var cat = net.pattern("cat", of("a", 1, "catalyst", 1), of("b", 1, "catalyst", 1));
        var mold = net.pattern("mold", of("dust", 4), of("catalyst", 1));
        net.store("a", 1000);
        net.store("dust", 4);

        var plan = net.plan("b", 1000);

        assertEquals(1000, crafts(plan, cat));
        assertEquals(1, crafts(plan, mold));
        assertEquals(0, total(plan.missingItems()));
        assertRuns(plan);
    }

    /*
     * Consumes two, gives one back: the first craft needs two, every later one needs one more, 11 for 10 crafts. The
     * input is taken in stacks of two, so the batch takes 12 and one comes back.
     */
    @Test
    void partlyConsumedCatalyst() {
        var net = new CraftingTestNetwork();
        var wear = net.pattern("wear", of("a", 1, "lens", 2), of("b", 1, "lens", 1));
        net.store("a", 100);
        net.store("lens", 100);

        var plan = net.plan("b", 10);

        assertEquals(10, crafts(plan, wear));
        assertEquals(12, used(plan, "lens"));
        assertRuns(plan);
    }

    @Test
    void containerIsReusedByLaterCrafts() {
        var net = new CraftingTestNetwork();
        net.container("water_bucket", "bucket");
        var plate = net.pattern("plate", of("ingot", 1, "water_bucket", 1), of("plate", 1));
        var fill = net.pattern("fill", of("bucket", 1, "water", 1000), of("water_bucket", 1));
        net.store("ingot", 1000);
        net.store("water", 1_000_000);
        net.store("bucket", 1);

        var plan = net.plan("plate", 1000);

        assertEquals(1000, crafts(plan, plate));
        assertEquals(1000, crafts(plan, fill));
        assertEquals(1, used(plan, "bucket"));
        assertEquals(0, total(plan.missingItems()));
        assertRuns(plan);
    }

    @Test
    void missingContainerIsReportedOnce() {
        var net = new CraftingTestNetwork();
        net.container("water_bucket", "bucket");
        net.pattern("plate", of("ingot", 1, "water_bucket", 1), of("plate", 1));
        net.pattern("fill", of("bucket", 1, "water", 1000), of("water_bucket", 1));
        net.store("ingot", 100);
        net.store("water", 100_000);

        var plan = net.plan("plate", 100);

        assertEquals(1, missing(plan, "bucket"));
        assertRuns(plan);
    }

    @Test
    void twoPatternCycleStartsFromOneSeed() {
        var net = new CraftingTestNetwork();
        var x2y = net.pattern("x2y", of("x", 1), of("y", 2));
        var y2xz = net.pattern("y2xz", of("y", 1), of("z", 1, "x", 1));
        net.store("x", 1);

        var plan = net.plan("z", 100);

        assertEquals(100, crafts(plan, y2xz));
        assertEquals(50, crafts(plan, x2y));
        assertEquals(1, used(plan, "x"));
        assertEquals(0, total(plan.missingItems()));
        assertRuns(plan);
    }

    // The pattern closing the cycle must not be planned more often than its inputs allow
    @Test
    void threePatternCycleCraftsExactlyWhatIsNeeded() {
        var net = new CraftingTestNetwork();
        var a2b = net.pattern("a2b", of("a", 1, "fuel", 1), of("b", 1));
        var b2c = net.pattern("b2c", of("b", 1), of("c", 1));
        var c2a = net.pattern("c2a", of("c", 1), of("dust", 1, "a", 2));
        net.store("a", 1);
        net.store("fuel", 1000);

        var plan = net.plan("dust", 50);

        assertEquals(50, crafts(plan, c2a));
        assertEquals(50, crafts(plan, b2c));
        assertEquals(50, crafts(plan, a2b));
        assertEquals(0, total(plan.missingItems()));
        assertRuns(plan);
    }

    // A cycle below the request that cannot make an item leaves that item missing, it does not fail the request
    @Test
    void cycleWithoutNetOutputBelowTheRequestLeavesItMissing() {
        var net = new CraftingTestNetwork();
        net.pattern("top", of("a", 10), of("top", 1));
        net.pattern("a", of("b", 1), of("a", 1));
        net.pattern("b", of("a", 2), of("b", 1));
        net.store("b", 5);

        var plan = net.plan("top", 1);

        assertEquals(5, used(plan, "b"));
        assertTrue(total(plan.missingItems()) > 0, () -> "missing " + names(plan.missingItems()));
        assertRuns(plan);
    }

    @Test
    void requestOnlyMadeByACycleWithoutNetOutputFails() {
        var net = new CraftingTestNetwork();
        net.pattern("p2q", of("p", 1), of("q", 1));
        net.pattern("q2p", of("q", 1), of("p", 1));

        var failure = assertThrows(CraftingCalculationFailure.class, () -> net.plan("q", 3));

        assertEquals(PlayerMessages.CraftingNoNetOutput, failure.getMessageKey());
    }

    // Each pattern is used as far as it goes before the next one, in the order of their priorities
    @Test
    void alternativePatternsFollowPriorities() {
        var net = new CraftingTestNetwork();
        var iron = net.pattern("rod", 10, of("iron", 1), of("rod", 3));
        var steel = net.pattern("rod#steel", 5, of("steel", 1), of("rod", 3));
        net.store("iron", 3);
        net.store("steel", 10);

        var plan = net.plan("rod", 10);

        assertEquals(3, crafts(plan, iron));
        assertEquals(1, crafts(plan, steel));
        assertEquals(0, total(plan.missingItems()));
        assertRuns(plan);
    }

    @Test
    void alternativePatternCoversShortageOfThePreferredOne() {
        var net = new CraftingTestNetwork();
        net.pattern("circuit", 10, of("gold", 2), of("circuit", 1));
        var copper = net.pattern("circuit#copper", 5, of("copper", 4), of("circuit", 1));
        net.pattern("board", of("circuit", 2, "plastic", 1), of("board", 1));
        net.store("copper", 400);
        net.store("plastic", 50);

        var plan = net.plan("board", 50);

        assertEquals(100, crafts(plan, copper));
        assertEquals(0, total(plan.missingItems()));
        assertRuns(plan);
    }

    @Test
    void inputVariantsAreMixedLikeTheCpu() {
        var net = new CraftingTestNetwork();
        var pulp = net.pattern("pulp", of("oak|birch", 3), of("pulp", 1));
        net.store("oak", 5);
        net.store("birch", 4);

        var plan = net.plan("pulp", 3);

        assertEquals(3, crafts(plan, pulp));
        assertEquals(9, used(plan, "oak") + used(plan, "birch") + missing(plan, "oak") + missing(plan, "birch"));
        assertRuns(plan);
    }

    @Test
    void craftLessCraftsWhatTheStockAllows() {
        var net = new CraftingTestNetwork();
        var plate = net.pattern("plate", of("ingot", 2), of("plate", 1));
        net.store("ingot", 7);

        var plan = net.plan("plate", 10, CalculationStrategy.CRAFT_LESS);

        assertEquals(3, crafts(plan, plate));
        assertEquals(0, total(plan.missingItems()));
        assertRuns(plan);
    }

    /*
     * A later request for another amount reuses what the legacy tree recorded for the first one. Scaled from 4 to 5
     * crafts, each pattern rounded on its own would give c2 4 crafts making 8, while the 3 crafts of b3 take 9. The
     * byproducts keep these patterns out of the graph.
     */
    @Test
    void reusedResultForAnotherAmountStillRuns() {
        var net = new CraftingTestNetwork();
        var top = net.pattern("top", of("b3", 1), of("top", 1, "slag", 1));
        net.pattern("b3", of("c2", 3), of("b3", 3, "slag", 1));
        net.pattern("c2", of("ore", 1), of("c2", 2, "slag", 1));
        net.store("ore", 1000);

        var first = net.plan("top", 4);
        var second = net.plan("top", 5);

        assertRuns(first);
        assertEquals(5, crafts(second, top));
        assertEquals(0, total(second.missingItems()));
        assertRuns(second);
    }

    @Test
    void repeatedRequestsGiveTheSamePlan() {
        var net = new CraftingTestNetwork();
        net.pattern("wire", of("ingot", 1), of("wire", 2));
        net.pattern("cable", of("wire", 1, "rubber", 1), of("cable", 1));
        net.pattern("circuit", of("cable", 3, "wire", 2, "plate", 1), of("circuit", 1));
        net.pattern("plate", of("ingot", 1), of("plate", 1));
        net.store("ingot", 100);
        net.store("rubber", 30);

        var first = net.plan("circuit", 5);
        var second = net.plan("circuit", 5);
        var third = net.plan("circuit", 7);

        assertEquals(first.patternTimes(), second.patternTimes());
        assertEquals(names(first.usedItems()), names(second.usedItems()));
        assertRuns(third);
        assertEquals(0, total(third.missingItems()));
    }
}
