package ae2.core.network.clientbound;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CraftingTreeRequestIdTest {
    @Test
    void concurrentIdsRemainUniqueAcrossPositiveRangeWraparound() throws Exception {
        var field = CraftingTreeDataPacket.class.getDeclaredField("requestIds");
        field.setAccessible(true);
        int previous = field.getInt(null);
        field.setInt(null, Integer.MAX_VALUE - 15);
        try {
            var start = new CountDownLatch(1);
            var ids = new HashSet<Integer>();
            try (var executor = Executors.newFixedThreadPool(8)) {
                var tasks = new ArrayList<Future<ArrayList<Integer>>>();
                for (int thread = 0; thread < 8; thread++) {
                    tasks.add(executor.submit(() -> {
                        start.await();
                        var result = new ArrayList<Integer>();
                        for (int i = 0; i < 100; i++) {
                            result.add(CraftingTreeDataPacket.nextRequestId());
                        }
                        return result;
                    }));
                }
                start.countDown();
                for (var task : tasks) {
                    ids.addAll(task.get(5, TimeUnit.SECONDS));
                }
            }
            var expected = new HashSet<Integer>();
            for (int i = 0; i < 16; i++) {
                expected.add(Integer.MAX_VALUE - i);
            }
            for (int i = 0; i < 784; i++) {
                expected.add(i);
            }
            assertEquals(expected, ids);
            assertEquals(784, CraftingTreeDataPacket.nextRequestId());
        } finally {
            field.setInt(null, previous);
        }
    }
}
