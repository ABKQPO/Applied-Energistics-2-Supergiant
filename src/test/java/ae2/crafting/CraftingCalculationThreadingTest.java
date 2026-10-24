package ae2.crafting;

import ae2.api.crafting.IAssemblerPattern;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import ae2.api.stacks.KeyCounter;
import ae2.core.AEConfig;
import ae2.crafting.pattern.AECraftingPattern;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.util.NonNullList;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sun.misc.Unsafe;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
class CraftingCalculationThreadingTest {
    private static final AtomicInteger IDS = new AtomicInteger();
    private Object previousWorld;
    private Object previousPlayer;

    // MinecraftServer.addScheduledTask executes immediately on the server thread; from a worker its Future must
    // wait for a later server task phase. Bound that wait here so the old implementation fails without leaking a job.
    private static <T> T serverTask(Thread tickThread, Supplier<T> action) {
        var reply = new CompletableFuture<T>();
        if (Thread.currentThread() == tickThread) reply.complete(action.get());
        try {
            return reply.get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("Recipe waited for a server task while the server waited for the calculator", e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    private static void addAssembler(CraftingTestNetwork net, Supplier<ItemStack> assemble) {
        var inputs = new IAssemblerPattern.IInput[]{net.input(1, "threading_raw", "threading_alternative")};
        net.addPattern(new IAssemblerPattern() {
            public AEItemKey getDefinition() {
                return AEItemKey.of(Items.PAPER);
            }

            public IInput[] getInputs() {
                return inputs;
            }

            public List<GenericStack> getOutputs() {
                return List.of(new GenericStack(AEItemKey.of(Items.CARROT), 1));
            }

            public boolean canSubstitute() {
                return true;
            }

            public boolean canSubstituteFluids() {
                return false;
            }

            public boolean isSlotEnabled(int slot) {
                return slot == 0;
            }

            public boolean isItemValid(int slot, AEItemKey key, World world) {
                return slot == 0;
            }

            public void fillCraftingGrid(KeyCounter[] table, CraftingGridAccessor grid) {
                grid.set(0, new ItemStack(Items.APPLE));
            }

            public ItemStack assemble(InventoryCrafting grid, World world) {
                return assemble.get();
            }
        }, 0);
        net.store("threading_raw", 1);
    }

    @BeforeEach
    void prepareWorld() throws Exception {
        CraftingTestNetwork.bootstrap();
        var worldField = CraftingEventSimulation.class.getDeclaredField("simulatedWorld");
        var playerField = CraftingEventSimulation.class.getDeclaredField("simulatedPlayer");
        worldField.setAccessible(true);
        playerField.setAccessible(true);
        previousWorld = worldField.get(null);
        previousPlayer = playerField.get(null);
        var unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (Unsafe) unsafeField.get(null);
        var world = (SimulatedCraftingWorld) unsafe.allocateInstance(SimulatedCraftingWorld.class);
        var provider = World.class.getDeclaredField("provider");
        provider.setAccessible(true);
        provider.set(world, new WorldProviderSurface());
        worldField.set(null, world);
        playerField.set(null, unsafe.allocateInstance(FakePlayer.class));
    }

    @AfterEach
    void restoreWorld() throws Exception {
        var worldField = CraftingEventSimulation.class.getDeclaredField("simulatedWorld");
        var playerField = CraftingEventSimulation.class.getDeclaredField("simulatedPlayer");
        worldField.setAccessible(true);
        playerField.setAccessible(true);
        worldField.set(null, previousWorld);
        playerField.set(null, previousPlayer);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    void realRecipeAndLifecycleCallbacksRunOnTickThreadForColdAndWarmPlans(int memo) {
        var tick = Thread.currentThread();
        var calls = new AtomicInteger();
        var created = new AtomicInteger();
        String name = "threading_" + IDS.incrementAndGet();
        var item = new Item() {
            @Override
            public void onCreated(ItemStack stack, World world, EntityPlayer player) {
                serverTask(tick, () -> {
                    assertFalse(CraftingCalculation.isCalculationThread());
                    // Nested dispatch must run directly, rather than waiting on its own tick thread.
                    CraftingCalculation.runOnMainThread(created::incrementAndGet);
                    return null;
                });
            }
        };
        item.setRegistryName("ae2_threading_test", name);
        ForgeRegistries.ITEMS.register(item);
        var recipe = new ShapelessRecipes("", new ItemStack(item), NonNullList.from(Ingredient.EMPTY,
            Ingredient.fromItems(Items.APPLE, Items.GOLDEN_APPLE))) {
            @Override
            public boolean matches(InventoryCrafting inv, World world) {
                return serverTask(tick, () -> {
                    calls.incrementAndGet();
                    return super.matches(inv, world);
                });
            }

            @Override
            public ItemStack getCraftingResult(InventoryCrafting inv) {
                return serverTask(tick, () -> super.getCraftingResult(inv));
            }

            @Override
            public NonNullList<ItemStack> getRemainingItems(InventoryCrafting inv) {
                return serverTask(tick, () -> super.getRemainingItems(inv));
            }
        };
        recipe.setRegistryName("ae2_threading_test", name);
        ForgeRegistries.RECIPES.register(recipe);
        var slots = new ItemStack[9];
        Arrays.fill(slots, ItemStack.EMPTY);
        slots[0] = new ItemStack(Items.APPLE);
        var encoded = new ItemStack(Items.PAPER);
        AECraftingPattern.encode(encoded, recipe, slots, new ItemStack(item), true, false);
        int previousMemo = AEConfig.CRAFTING.memoizationCacheSize;
        AEConfig.CRAFTING.memoizationCacheSize = memo;
        try {
            var net = new CraftingTestNetwork();
            var pattern = new AECraftingPattern(AEItemKey.of(encoded), CraftingTestNetwork.world());
            net.addPattern(pattern, 0);
            // Exercise both the encoded input and the substitution/remainder paths.
            for (var input : List.of(Items.APPLE, Items.GOLDEN_APPLE)) {
                net.stock.clear();
                net.store(AEItemKey.of(input), 2);
                for (int repeat = 0; repeat < 2; repeat++) {
                    int before = created.get();
                    var plan = net.plan(AEItemKey.of(item), 2);
                    assertTrue(plan.missingItems().isEmpty());
                    assertEquals(2, plan.finalOutput().amount());
                    assertEquals(2, plan.usedItems().get(AEItemKey.of(input)));
                    assertEquals(2, plan.patternTimes().getLong(pattern));
                    assertTrue(created.get() > before, "full recipe validation must still run on a memo hit");
                }
            }
            assertTrue(calls.get() > 0);
        } finally {
            AEConfig.CRAFTING.memoizationCacheSize = previousMemo;
        }
    }

    @Test
    void customAssemblerRunsOnTickThreadAndPropagatesCallbackFailure() {
        var tick = Thread.currentThread();
        var net = new CraftingTestNetwork();
        addAssembler(net, () -> serverTask(tick, () -> new ItemStack(Items.CARROT)));
        assertEquals(1, net.plan(AEItemKey.of(Items.CARROT), 1).finalOutput().amount());

        var broken = new CraftingTestNetwork();
        var expected = new IllegalStateException("recipe failure");
        addAssembler(broken, () -> {
            assertSame(tick, Thread.currentThread());
            throw expected;
        });
        var failure = assertThrows(RuntimeException.class, () -> broken.plan(AEItemKey.of(Items.CARROT), 1));
        while (failure.getCause() instanceof RuntimeException cause) failure = cause;
        assertSame(expected, failure);
        assertEquals(1, net.plan(AEItemKey.of(Items.CARROT), 1).finalOutput().amount());
    }

    @Test
    void cancellingActiveCallbackWaitsForItToFinishBeforeWorkerUnwinds() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workerEnded = new CountDownLatch(1);
        var net = new CraftingTestNetwork();
        addAssembler(net, () -> {
            assertFalse(CraftingCalculation.isCalculationThread());
            entered.countDown();
            await(release);
            return new ItemStack(Items.CARROT);
        });
        var job = net.calculation(AEItemKey.of(Items.CARROT), 1);
        var executor = Executors.newFixedThreadPool(2, action -> {
            var thread = new Thread(action, "Crafting cancellation test");
            thread.setDaemon(true);
            return thread;
        });
        var worker = executor.submit(() -> {
            try {
                return job.run();
            } finally {
                workerEnded.countDown();
            }
        });
        var tick = executor.submit(() -> {
            while (!worker.isDone()) job.simulateFor(1_000);
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            job.cancel();
            assertFalse(workerEnded.await(100, TimeUnit.MILLISECONDS), "worker unwound during its callback");
        } finally {
            release.countDown();
            job.cancel();
            executor.shutdown();
        }
        assertTrue(workerEnded.await(5, TimeUnit.SECONDS));
        tick.get(5, TimeUnit.SECONDS);
        assertThrows(ExecutionException.class, () -> worker.get(5, TimeUnit.SECONDS));
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void cancelledCalculationDoesNotStartRecipeCallbacks() throws Exception {
        var calls = new AtomicInteger();
        var net = new CraftingTestNetwork();
        addAssembler(net, () -> {
            calls.incrementAndGet();
            return new ItemStack(Items.CARROT);
        });
        var job = net.calculation(AEItemKey.of(Items.CARROT), 1);
        job.cancel();
        assertFalse(job.simulateFor(1_000));
        assertThrows(InterruptedException.class, job::run);
        Thread.interrupted();
        assertEquals(0, calls.get());
        assertFalse(CraftingCalculation.isCalculationThread());
    }

    @Test
    void interruptingTickWaitCancelsJobAndPreservesInterrupt() {
        var net = new CraftingTestNetwork();
        var job = net.calculation(AEItemKey.of(Items.CARROT), 1);
        try {
            Thread.currentThread().interrupt();
            assertFalse(job.simulateFor(1_000));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertThrows(InterruptedException.class, job::run);
        Thread.interrupted();
        assertFalse(CraftingCalculation.isCalculationThread());
    }

    @Test
    void concurrentCalculationsUseTheSameTickThreadForCallbacks() throws Exception {
        var tick = Thread.currentThread();
        var calls = new AtomicInteger();
        var first = new CraftingTestNetwork();
        var second = new CraftingTestNetwork();
        Supplier<ItemStack> callback = () -> serverTask(tick, () -> {
            calls.incrementAndGet();
            return new ItemStack(Items.CARROT);
        });
        addAssembler(first, callback);
        addAssembler(second, callback);
        var jobs = List.of(first.calculation(AEItemKey.of(Items.CARROT), 1),
            second.calculation(AEItemKey.of(Items.CARROT), 1));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(jobs.get(0)::run);
            var b = executor.submit(jobs.get(1)::run);
            while (!a.isDone() || !b.isDone()) {
                jobs.get(0).simulateFor(1_000);
                jobs.get(1).simulateFor(1_000);
            }
            assertEquals(1, a.get().finalOutput().amount());
            assertEquals(1, b.get().finalOutput().amount());
            assertTrue(calls.get() >= 2);
        } finally {
            jobs.get(0).cancel();
            jobs.get(1).cancel();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
