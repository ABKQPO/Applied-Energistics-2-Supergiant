package ae2.core.worlddata;

import org.junit.jupiter.api.Test;

import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternProviderWarningConcurrencyTest {
    @Test
    void concurrentWarningsAreThrottledIndependentlyAndResumeAfterInterval() throws Exception {
        var invalidUidField = PatternProviderMappingData.class.getDeclaredField("LAST_INVALID_UID_WARNING");
        var mappingIndexField = PatternProviderMappingData.class.getDeclaredField("LAST_MAPPING_INDEX_WARNING");
        invalidUidField.setAccessible(true);
        mappingIndexField.setAccessible(true);
        var invalidUid = (VarHandle) invalidUidField.get(null);
        var mappingIndex = (VarHandle) mappingIndexField.get(null);
        long previousInvalidUid = (long) invalidUid.getVolatile();
        long previousMappingIndex = (long) mappingIndex.getVolatile();
        var shouldLog = PatternProviderMappingData.class.getDeclaredMethod("shouldLogWarning", VarHandle.class, long.class);
        shouldLog.setAccessible(true);
        invalidUid.setVolatile(Long.MIN_VALUE);
        mappingIndex.setVolatile(Long.MIN_VALUE);
        try {
            long now = 1234;
            var start = new CountDownLatch(1);
            int accepted = 0;
            try (var executor = Executors.newFixedThreadPool(8)) {
                var tasks = new ArrayList<Future<Boolean>>();
                for (int i = 0; i < 8; i++) {
                    tasks.add(executor.submit(() -> {
                        start.await();
                        return (boolean) shouldLog.invoke(null, invalidUid, now);
                    }));
                }
                start.countDown();
                for (var task : tasks) {
                    if (task.get(5, TimeUnit.SECONDS)) {
                        accepted++;
                    }
                }
            }
            assertEquals(1, accepted);
            assertEquals(now, (long) invalidUid.getVolatile());
            assertTrue((boolean) shouldLog.invoke(null, mappingIndex, now));
            assertFalse((boolean) shouldLog.invoke(null, invalidUid, now + 9_999_999_999L));
            assertTrue((boolean) shouldLog.invoke(null, invalidUid, now + 10_000_000_000L));
            assertTrue((boolean) shouldLog.invoke(null, invalidUid, now - 1));
        } finally {
            invalidUid.setVolatile(previousInvalidUid);
            mappingIndex.setVolatile(previousMappingIndex);
        }
    }
}
