package ae2.helpers.patternmodifier;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PatternModifierWarningTest {
    @Test
    void concurrentFailuresSaturateTheWarningCountAndKeepItSaturated() throws Exception {
        var count = PatternModifierLogic.class.getDeclaredField("reencodeFailureWarningCount");
        count.setAccessible(true);
        var warn = PatternModifierLogic.class.getDeclaredMethod("warnReencodeFailure", String.class, RuntimeException.class);
        warn.setAccessible(true);
        int previous = count.getInt(null);
        count.setInt(null, 8);
        try {
            var start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(8)) {
                var tasks = new ArrayList<Future<?>>();
                for (int i = 0; i < 8; i++) {
                    tasks.add(executor.submit(() -> {
                        start.await();
                        for (int failure = 0; failure < 100; failure++) {
                            warn.invoke(null, "testing", new IllegalStateException("controlled failure"));
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (var task : tasks) {
                    task.get(5, TimeUnit.SECONDS);
                }
            }
            assertEquals(9, count.getInt(null));
            warn.invoke(null, "testing again", new IllegalStateException("controlled failure"));
            assertEquals(9, count.getInt(null));
        } finally {
            count.setInt(null, previous);
        }
    }
}
