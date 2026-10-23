package ae2.crafting;

import ae2.core.AEConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static ae2.crafting.CraftingTestNetwork.crafts;
import static ae2.crafting.CraftingTestNetwork.of;
import static ae2.crafting.CraftingTestNetwork.replay;
import static ae2.crafting.CraftingTestNetwork.total;
import static ae2.crafting.CraftingTestNetwork.used;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CraftingPreviewBatchTest {
    @ParameterizedTest
    @CsvSource({"0, 2", "0, 100", "10000, 2", "10000, 100"})
    void smallerPreviewBatchDoesNotConsumeEarlierInputsForTheLargerBatch(int cacheSize, long rawStock) {
        CraftingTestNetwork.bootstrap();
        int previous = AEConfig.CRAFTING.memoizationCacheSize;
        AEConfig.CRAFTING.memoizationCacheSize = cacheSize;
        try {
            var net = new CraftingTestNetwork();
            var part = net.pattern("part", of("raw", 1), of("part", 1));
            var first = net.pattern("first", of("a", 1, "fuel", 1), of("b", 1));
            var second = net.pattern("second", of("b", 1), of("c", 1));
            var last = net.pattern("last", of("c", 1), of("dust", 1, "a", 2));
            var root = net.pattern("root", of("part", 1, "dust", 1), of("result", 1));
            net.store("raw", rawStock);
            net.store("fuel", 100);
            net.store("a", 1);
            for (int attempt = 0; attempt < 2; attempt++) {
                var plan = net.plan("result", 2);
                assertEquals(2, crafts(plan, part));
                assertEquals(2, crafts(plan, first));
                assertEquals(2, crafts(plan, second));
                assertEquals(2, crafts(plan, last));
                assertEquals(2, crafts(plan, root));
                assertEquals(2, used(plan, "raw"));
                assertEquals(2, used(plan, "fuel"));
                assertEquals(1, used(plan, "a"));
                assertEquals(0, total(plan.missingItems()));
                assertNull(replay(plan));
            }
        } finally {
            AEConfig.CRAFTING.memoizationCacheSize = previous;
        }
    }
}
