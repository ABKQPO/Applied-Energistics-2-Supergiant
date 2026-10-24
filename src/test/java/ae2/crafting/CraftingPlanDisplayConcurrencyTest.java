package ae2.crafting;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import ae2.api.stacks.KeyCounter;
import ae2.integration.data.LiteCraftTreeNode;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingPlanDisplayConcurrencyTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentRequestsShareOneFutureIncludingMaterializationFailure(boolean throughPlan) throws Exception {
        var metrics = new CraftingAttemptMetrics();
        var output = new GenericStack(AEItemKey.of(Items.APPLE), 1);
        Supplier<CompletableFuture<LiteCraftTreeNode>> requestTree;
        if (throughPlan) {
            var plan = new CraftingPlan(output, 0, false, false, new KeyCounter(), new KeyCounter(), new KeyCounter(),
                0, new Object2LongOpenHashMap<>(), null, List.of(), null, metrics, null);
            requestTree = plan::requestDisplayTree;
        } else {
            var display = new CraftingPlanDisplay(output, new Object2LongOpenHashMap<>(), new KeyCounter(),
                new KeyCounter(), null, null, metrics, null);
            requestTree = display::requestTree;
        }
        assertEquals(0, metrics.snapshot().displayMaterializationNanos());
        var start = new CountDownLatch(1);
        CompletableFuture<?> shared;
        try (var executor = Executors.newFixedThreadPool(8)) {
            var requests = new ArrayList<Future<CompletableFuture<?>>>();
            for (int i = 0; i < 8; i++) {
                requests.add(executor.submit(() -> {
                    start.await();
                    return requestTree.get();
                }));
            }
            start.countDown();
            shared = requests.getFirst().get(5, TimeUnit.SECONDS);
            for (var request : requests) {
                assertSame(shared, request.get(5, TimeUnit.SECONDS));
            }
        }
        var failure = assertThrows(ExecutionException.class, () -> shared.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals("Crafting plan has no display source", failure.getCause().getMessage());
        assertTrue(metrics.snapshot().displayMaterializationNanos() > 0);
        assertSame(shared, requestTree.get());
    }
}
