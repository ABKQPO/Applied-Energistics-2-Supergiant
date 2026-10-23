package ae2.container.me.patternencode;

import ae2.me.service.ActivePatternProviderDirectory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderWarningThrottleTest {
    private static Stream<Arguments> warningFields() {
        return Stream.of(
            Arguments.of(PatternProviderUploadService.class, "LAST_PROVIDER_SCAN_WARNING", "shouldLog", true),
            Arguments.of(PatternProviderUploadService.class, "LAST_PROVIDER_UPLOAD_WARNING", "shouldLog", true),
            Arguments.of(ActivePatternProviderDirectory.class, "LAST_DIRECTORY_TEXT_WARNING",
                "shouldLogDirectoryWarning", false));
    }

    @ParameterizedTest
    @MethodSource("warningFields")
    void concurrentWarningsShareTheThrottleAndResumeAfterExpiry(Class<?> owner, String fieldName, String methodName,
                                                                boolean takesHandle) throws Exception {
        var field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        var handle = (VarHandle) field.get(null);
        var method = takesHandle ? owner.getDeclaredMethod(methodName, VarHandle.class)
            : owner.getDeclaredMethod(methodName);
        method.setAccessible(true);
        Object[] arguments = takesHandle ? new Object[]{handle} : new Object[0];
        long previous = (long) handle.getVolatile();
        handle.setVolatile(Long.MIN_VALUE);
        try {
            var start = new CountDownLatch(1);
            int accepted = 0;
            try (var executor = Executors.newFixedThreadPool(8)) {
                var tasks = new ArrayList<Future<Boolean>>();
                for (int i = 0; i < 8; i++) {
                    tasks.add(executor.submit(() -> {
                        start.await();
                        return (boolean) method.invoke(null, arguments);
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
            assertFalse((boolean) method.invoke(null, arguments));
            handle.setVolatile(System.nanoTime() - 10_000_000_001L);
            assertTrue((boolean) method.invoke(null, arguments));
        } finally {
            handle.setVolatile(previous);
        }
    }
}
