package ae2.crafting;

import ae2.api.crafting.IPatternDetails;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.IntStream;

import static ae2.crafting.CraftingTestNetwork.crafts;
import static ae2.crafting.CraftingTestNetwork.of;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class CraftingPlanningCacheTest extends CraftingPlanningTestSupport {
    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    void deferredDisplayKeepsEarlierAmountsAfterTheCachedGraphWasReused(int cache) {
        memo(cache);
        var net = new CraftingTestNetwork();
        net.pattern("plate", of("ore", 2), of("plate", 1));
        net.pattern("root", of("plate", 3), of("result", 1));
        net.store("ore", 100);
        var first = (CraftingPlan) net.plan("result", 2);
        var graph = net.cachedGraph("result");
        assertNotNull(first.graphDisplayBuilder());
        var later = (CraftingPlan) net.plan("result", 7);
        assertSame(graph, net.cachedGraph("result"));
        var firstSnapshot = first.graphDisplayBuilder().build();
        var laterSnapshot = later.graphDisplayBuilder().build();
        assertEquals(2, firstSnapshot.nodes().getFirst().demandAmount());
        assertEquals(2, firstSnapshot.nodes().getFirst().craftTimes());
        assertEquals(6, firstSnapshot.nodes().get(1).demandAmount());
        assertEquals(6, firstSnapshot.nodes().get(1).craftTimes());
        assertEquals(7, laterSnapshot.nodes().getFirst().demandAmount());
        assertEquals(21, laterSnapshot.nodes().get(1).craftTimes());
        net.plan("result", 1);
        assertEquals(firstSnapshot, first.graphDisplayBuilder().build());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    void missingMaterialsAreRecomputedAfterReplenishmentAndDepletion(int cache) {
        memo(cache);
        var net = new CraftingTestNetwork();
        var plate = net.pattern("plate", new IPatternDetails.IInput[]{net.input(2, "raw")},
            of("plate", 3, "slag", 1));
        assertAll(IntStream.of(3, 4, 2, 6).mapToObj(stock -> (Executable) () -> {
            net.setStock("raw", stock);
            var before = amounts(net.stock);
            var plan = net.plan("plate", 6);
            assertAll("raw stock=" + stock,
                () -> assertEquals(2, crafts(plan, plate)),
                () -> materials(plan.usedItems(), "raw", Math.min(4, stock)),
                () -> materials(plan.missingItems(), "raw", Math.max(0, 4 - stock)),
                () -> assertEquals(before, amounts(net.stock), "planning mutated storage"),
                () -> withinStock(net, plan));
        }));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    void newIntermediateStockIsUsedAfterTheRecipeWasCached(int cache) {
        memo(cache);
        var net = new CraftingTestNetwork();
        var plate = net.pattern("plate", of("ore", 2), of("plate", 1, "slag", 1));
        var root = net.pattern("root", new IPatternDetails.IInput[]{net.input(2, "plate")}, of("result", 1));
        net.store("ore", 20);
        assertAll(IntStream.of(0, 3, 0, 4).mapToObj(stored -> (Executable) () -> {
            net.setStock("plate", stored);
            var plan = net.plan("result", 2);
            complete(net, plan, "result", 2);
            assertEquals(2, crafts(plan, root));
            assertEquals(4 - stored, crafts(plan, plate), "stored plates=" + stored);
            materials(plan.usedItems(), "plate", stored, "ore", 2 * (4 - stored));
        }));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    void alternativesFollowCurrentStockAndCurrentPriority(int cache) {
        memo(cache);
        var net = new CraftingTestNetwork();
        var iron = net.pattern("iron", 10, of("iron", 1), of("rod", 2));
        var copper = net.pattern("copper", 5, of("copper", 2), of("rod", 2));
        net.store("iron", 10);
        net.store("copper", 20);
        var first = net.plan("rod", 5);
        complete(net, first, "rod", 5);
        materials(first.usedItems(), "iron", 3);
        net.setStock("iron", 1);
        var depleted = net.plan("rod", 5);
        complete(net, depleted, "rod", 5);
        assertEquals(1, crafts(depleted, iron));
        assertEquals(2, crafts(depleted, copper));
        materials(depleted.usedItems(), "iron", 1, "copper", 4);
        net.setStock("iron", 10);
        net.priority(copper, 20);
        var reprioritized = net.plan("rod", 5);
        complete(net, reprioritized, "rod", 5);
        materials(reprioritized.usedItems(), "copper", 6);
        assertEquals(0, crafts(reprioritized, iron));
        net.priority(iron, 30);
        materials(net.plan("rod", 5).usedItems(), "iron", 3);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    void removingAndReplacingAProducerInvalidatesItsCachedDependencies(int cache) {
        memo(cache);
        var net = new CraftingTestNetwork();
        var old = net.pattern("old", of("old_raw", 1), of("part", 1));
        net.pattern("root", of("part", 3), of("result", 1));
        net.store("old_raw", 100);
        net.store("new_raw", 100);
        complete(net, net.plan("result", 2), "result", 2);
        assertNotNull(net.cachedGraph("result"));
        net.removePattern(old);
        var absent = net.plan("result", 2);
        materials(absent.missingItems(), "part", 6);
        assertEquals(0, crafts(absent, old));
        var replacement = net.pattern("new", of("new_raw", 2), of("part", 1));
        var restored = net.plan("result", 2);
        complete(net, restored, "result", 2);
        materials(restored.usedItems(), "new_raw", 12);
        assertEquals(0, crafts(restored, old));
        assertEquals(6, crafts(restored, replacement));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    void inputChoicesDoNotRememberAnUnavailableVariant(int cache) {
        memo(cache);
        var net = new CraftingTestNetwork();
        var pattern = net.pattern("mix", of("oak|birch", 1), of("pulp", 1, "slag", 1));
        for (int oak : new int[]{5, 0, 2, 5}) {
            net.setStock("oak", oak);
            net.setStock("birch", 5 - oak);
            var plan = net.plan("pulp", 5);
            complete(net, plan, "pulp", 5);
            assertEquals(5, crafts(plan, pattern));
            materials(plan.usedItems(), "oak", oak, "birch", 5 - oak);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    void changedAmountsRoundEachRecipeAtItsOwnOutputSize(int cache) {
        memo(cache);
        var net = new CraftingTestNetwork();
        var wire = net.pattern("wire", of("ore", 1), of("wire", 3, "slag", 1));
        var root = net.pattern("root", of("wire", 2), of("result", 1));
        net.store("ore", 100);
        assertAll(IntStream.of(4, 1, 5, 7, 4).mapToObj(amount -> (Executable) () -> {
            var plan = net.plan("result", amount);
            complete(net, plan, "result", amount);
            assertEquals(amount, crafts(plan, root));
            assertEquals((2 * amount + 2) / 3, crafts(plan, wire), "requested result=" + amount);
            materials(plan.usedItems(), "ore", (2 * amount + 2) / 3);
        }));
    }
}
