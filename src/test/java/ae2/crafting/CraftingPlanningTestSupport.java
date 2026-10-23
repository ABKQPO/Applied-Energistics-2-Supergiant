package ae2.crafting;

import ae2.api.networking.crafting.ICraftingPlan;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.KeyCounter;
import ae2.core.AEConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.HashMap;
import java.util.Map;

import static ae2.crafting.CraftingTestNetwork.key;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assertions about planning only; no provider dispatch or machine execution.
 */
abstract class CraftingPlanningTestSupport {
    private int previousCacheSize;

    static Map<AEKey, Long> amounts(KeyCounter counter) {
        var result = new HashMap<AEKey, Long>();
        for (var entry : counter) {
            assertTrue(entry.getLongValue() >= 0, "negative material amount: " + entry);
            if (entry.getLongValue() != 0) result.put(entry.getKey(), entry.getLongValue());
        }
        return result;
    }

    static void materials(KeyCounter counter, Object... pairs) {
        var expected = new HashMap<AEKey, Long>();
        for (int i = 0; i < pairs.length; i += 2) {
            var what = pairs[i] instanceof AEKey k ? k : key((String) pairs[i]);
            long amount = ((Number) pairs[i + 1]).longValue();
            if (amount != 0) expected.merge(what, amount, Long::sum);
        }
        assertEquals(expected, amounts(counter));
    }

    static void complete(CraftingTestNetwork net, ICraftingPlan plan, String output, long amount) {
        complete(net, plan, key(output), amount);
    }

    static void complete(CraftingTestNetwork net, ICraftingPlan plan, AEKey output, long amount) {
        assertEquals(output, plan.finalOutput().what());
        assertEquals(amount, plan.finalOutput().amount());
        materials(plan.missingItems());
        materials(plan.emittedItems());
        assertFalse(plan.simulation(), "a fully supplied plan must be submit-capable");
        withinStock(net, plan);
        for (var entry : plan.patternTimes().object2LongEntrySet()) {
            assertTrue(entry.getLongValue() > 0, "nonpositive craft count: " + entry);
        }
    }

    static void withinStock(CraftingTestNetwork net, ICraftingPlan plan) {
        for (var entry : amounts(plan.usedItems()).entrySet()) {
            assertTrue(entry.getValue() <= net.stock.get(entry.getKey()), "used more than stored: " + entry);
        }
    }

    @BeforeEach
    void saveConfig() {
        CraftingTestNetwork.bootstrap();
        previousCacheSize = AEConfig.CRAFTING.memoizationCacheSize;
    }

    @AfterEach
    void restoreConfig() {
        AEConfig.CRAFTING.memoizationCacheSize = previousCacheSize;
    }

    void memo(int size) {
        AEConfig.CRAFTING.memoizationCacheSize = size;
    }
}
