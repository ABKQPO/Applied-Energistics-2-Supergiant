package ae2.crafting;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import ae2.container.ContainerConstants;
import ae2.crafting.pattern.AECraftingPattern;
import ae2.crafting.pattern.AEProcessingPattern;
import net.minecraft.init.Items;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import sun.misc.Unsafe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static ae2.crafting.CraftingTestNetwork.crafts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingRealPatternPlanningTest extends CraftingPlanningTestSupport {
    private static final AtomicInteger RECIPE_IDS = new AtomicInteger();
    private Object previousSimulatedWorld;
    private Object previousSimulatedPlayer;

    private static AEProcessingPattern processing(List<GenericStack> inputs, List<GenericStack> outputs) {
        var encoded = new ItemStack(Items.PAPER);
        AEProcessingPattern.encode(encoded, inputs, outputs);
        return new AEProcessingPattern(AEItemKey.of(encoded));
    }

    private static AECraftingPattern crafting(boolean substitutes) {
        var ingredient = substitutes ? Ingredient.fromItems(Items.APPLE, Items.GOLDEN_APPLE)
            : Ingredient.fromItems(Items.APPLE);
        var recipe = new ShapelessRecipes("", new ItemStack(Items.CARROT),
            NonNullList.from(Ingredient.EMPTY, ingredient, ingredient));
        recipe.setRegistryName("ae2_planning_test", "pair_" + RECIPE_IDS.getAndIncrement());
        ForgeRegistries.RECIPES.register(recipe);
        var slots = new ItemStack[9];
        Arrays.fill(slots, ItemStack.EMPTY);
        slots[0] = new ItemStack(Items.APPLE);
        slots[1] = new ItemStack(Items.APPLE);
        var encoded = new ItemStack(Items.PAPER);
        AECraftingPattern.encode(encoded, recipe, slots, new ItemStack(Items.CARROT), substitutes, false);
        return new AECraftingPattern(AEItemKey.of(encoded), CraftingTestNetwork.world());
    }

    @BeforeEach
    void prepareRecipeValidationContext() throws Exception {
        // Recipe substitution validates the crafting result. Supply an inert context for those hooks: the fixture's
        // allocated WorldServer has no server/save data from which a real simulated world could be constructed.
        var worldField = CraftingEventSimulation.class.getDeclaredField("simulatedWorld");
        var playerField = CraftingEventSimulation.class.getDeclaredField("simulatedPlayer");
        worldField.setAccessible(true);
        playerField.setAccessible(true);
        previousSimulatedWorld = worldField.get(null);
        previousSimulatedPlayer = playerField.get(null);
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
    void restoreRecipeValidationContext() throws Exception {
        var worldField = CraftingEventSimulation.class.getDeclaredField("simulatedWorld");
        var playerField = CraftingEventSimulation.class.getDeclaredField("simulatedPlayer");
        worldField.setAccessible(true);
        playerField.setAccessible(true);
        worldField.set(null, previousSimulatedWorld);
        playerField.set(null, previousSimulatedPlayer);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    void processingUsesCondensedSparseInputsIncludingFluidsAndMaximumSlots(int cache) {
        memo(cache);
        var net = new CraftingTestNetwork();
        var iron = AEItemKey.of(Items.IRON_INGOT);
        var water = AEFluidKey.of(FluidRegistry.WATER);
        var plate = AEItemKey.of(Items.GOLD_INGOT);
        var dust = AEItemKey.of(Items.REDSTONE);
        var inputs = new ArrayList<GenericStack>(Collections.nCopies(AEProcessingPattern.MAX_INPUT_SLOTS, null));
        inputs.set(0, new GenericStack(iron, 2));
        inputs.set(40, new GenericStack(iron, 3));
        inputs.set(80, new GenericStack(water, 1_000));
        var outputs = new ArrayList<GenericStack>(Collections.nCopies(AEProcessingPattern.MAX_OUTPUT_SLOTS, null));
        outputs.set(0, new GenericStack(plate, 2));
        outputs.set(25, new GenericStack(dust, 1));
        outputs.set(26, new GenericStack(dust, 1));
        var pattern = net.addPattern(processing(inputs, outputs), 0);
        assertEquals(2, pattern.getInputs().length);
        assertEquals(2, pattern.getOutputs().size());
        net.store(iron, 15);
        net.store(water, 3_000);
        for (int attempt = 0; attempt < 2; attempt++) {
            var plan = net.plan(plate, 5);
            complete(net, plan, plate, 5);
            assertEquals(3, crafts(plan, pattern));
            materials(plan.usedItems(), iron, 15, water, 3_000);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    void secondaryProcessingOutputCanIncreaseTheSharedProducerCount(int cache) {
        memo(cache);
        var net = new CraftingTestNetwork();
        var ore = AEItemKey.of(Items.IRON_INGOT);
        var a = AEItemKey.of(Items.GOLD_INGOT);
        var b = AEItemKey.of(Items.REDSTONE);
        var c = AEItemKey.of(Items.REPEATER);
        var result = AEItemKey.of(Items.COMPARATOR);
        var split = net.addPattern(processing(List.of(new GenericStack(ore, 6)),
            List.of(new GenericStack(a, 2), new GenericStack(b, 3))), 0);
        var component = net.addPattern(processing(List.of(new GenericStack(b, 2)), List.of(new GenericStack(c, 1))), 0);
        var root = net.addPattern(processing(List.of(new GenericStack(a, 1), new GenericStack(c, 1)),
            List.of(new GenericStack(result, 1))), 0);
        net.store(ore, 24);
        for (int attempt = 0; attempt < 2; attempt++) {
            var plan = net.plan(result, 6);
            complete(net, plan, result, 6);
            assertEquals(4, crafts(plan, split));
            assertEquals(6, crafts(plan, component));
            assertEquals(6, crafts(plan, root));
            materials(plan.usedItems(), ore, 24);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    void processingKeepsNbtDistinctWhenOnlyTheOtherVariantIsAvailable(int cache) {
        memo(cache);
        var net = new CraftingTestNetwork();
        var requiredStack = new ItemStack(Items.IRON_INGOT);
        var tag = new NBTTagCompound();
        tag.setString("grade", "pure");
        requiredStack.setTagCompound(tag);
        var required = AEItemKey.of(requiredStack);
        var other = AEItemKey.of(Items.IRON_INGOT);
        var output = AEItemKey.of(Items.GOLD_INGOT);
        var pattern = net.addPattern(processing(List.of(new GenericStack(required, 1)),
            List.of(new GenericStack(output, 1))), 0);
        net.store(required, 2);
        net.store(other, 1_000);
        var shortPlan = net.plan(output, 3);
        assertEquals(3, crafts(shortPlan, pattern));
        materials(shortPlan.usedItems(), required, 2);
        materials(shortPlan.missingItems(), required, 1);
        net.store(required, 1);
        var replenished = net.plan(output, 3);
        complete(net, replenished, output, 3);
        materials(replenished.usedItems(), required, 3);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10_000})
    void craftingPatternPlansRepeatedSlotsWithAndWithoutSubstitution(int cache) {
        memo(cache);
        for (boolean substitutes : new boolean[]{false, true}) {
            var net = new CraftingTestNetwork();
            var pattern = net.addPattern(crafting(substitutes), 0);
            var apple = AEItemKey.of(Items.APPLE);
            var golden = AEItemKey.of(Items.GOLDEN_APPLE);
            var carrot = AEItemKey.of(Items.CARROT);
            assertEquals(2, pattern.getInputs()[0].getMultiplier());
            if (substitutes) assertTrue(pattern.getInputs()[0].isValid(golden, CraftingTestNetwork.world()));
            net.store(apple, substitutes ? 3 : 8);
            net.store(golden, 5);
            for (int attempt = 0; attempt < 2; attempt++) {
                var plan = net.plan(carrot, 4);
                complete(net, plan, carrot, 4);
                assertEquals(4, crafts(plan, pattern));
                materials(plan.usedItems(), apple, substitutes ? 3 : 8, golden, substitutes ? 5 : 0);
            }
        }
    }

    @ParameterizedTest(name = "memo={0}, warm={1}")
    @CsvSource({"0, false", "0, true", "10000, false", "10000, true"})
    void fixedInputRecipesStillRequireValidationAfterEncodingAndOnWarmPlans(int cache, boolean warm) {
        memo(cache);
        var enabled = new AtomicBoolean(true);
        var tickThread = Thread.currentThread();
        var recipe = new ShapelessRecipes("", new ItemStack(Items.CARROT),
            NonNullList.from(Ingredient.EMPTY, Ingredient.fromItems(Items.APPLE))) {
            @Override
            public boolean matches(InventoryCrafting input, World world) {
                assertEquals(tickThread, Thread.currentThread(), "fixed recipe callbacks must use the tick thread");
                return enabled.get() && super.matches(input, world);
            }
        };
        recipe.setRegistryName("ae2_planning_test", "fixed_" + RECIPE_IDS.getAndIncrement());
        ForgeRegistries.RECIPES.register(recipe);
        var slots = new ItemStack[9];
        Arrays.fill(slots, ItemStack.EMPTY);
        slots[0] = new ItemStack(Items.APPLE);
        var encoded = new ItemStack(Items.PAPER);
        AECraftingPattern.encode(encoded, recipe, slots, new ItemStack(Items.CARROT), false, false);
        var net = new CraftingTestNetwork();
        var pattern = net.addPattern(new AECraftingPattern(AEItemKey.of(encoded), CraftingTestNetwork.world()), 0);
        var carrot = AEItemKey.of(Items.CARROT);
        var apple = AEItemKey.of(Items.APPLE);
        net.store(apple, 2);
        if (warm) complete(net, net.plan(carrot, 2), carrot, 2);
        enabled.set(false);
        var before = amounts(net.stock);
        assertThrows(CraftingCalculationFailure.class, () -> net.plan(carrot, 2),
            "balanced fixed inputs must not hide a recipe that no longer accepts them");
        assertEquals(before, amounts(net.stock), "rejected planning mutated storage");
        enabled.set(true);
        var recovered = net.plan(carrot, 2);
        complete(net, recovered, carrot, 2);
        assertEquals(2, crafts(recovered, pattern));
        materials(recovered.usedItems(), apple, 2);
    }

    @ParameterizedTest(name = "memo={0}, warm={1}")
    @CsvSource({"0, false", "0, true", "10000, false", "10000, true"})
    void individuallyValidSubstitutionsCannotMakeAnInvalidCombinedRecipe(int cache, boolean warm) {
        memo(cache);
        var ingredients = NonNullList.from(Ingredient.EMPTY,
            Ingredient.fromItems(Items.APPLE, Items.GOLDEN_APPLE),
            Ingredient.fromItems(Items.POTATO, Items.BAKED_POTATO));
        var recipe = new ShapedRecipes("", 2, 1, ingredients, new ItemStack(Items.CARROT)) {
            @Override
            public boolean matches(InventoryCrafting input, World world) {
                if (!super.matches(input, world)) return false;
                boolean golden = false;
                boolean baked = false;
                for (int slot = 0; slot < input.getSizeInventory(); slot++) {
                    golden |= input.getStackInSlot(slot).getItem() == Items.GOLDEN_APPLE;
                    baked |= input.getStackInSlot(slot).getItem() == Items.BAKED_POTATO;
                }
                return !golden || !baked;
            }
        };
        recipe.setRegistryName("ae2_planning_test", "coupled_" + RECIPE_IDS.getAndIncrement());
        ForgeRegistries.RECIPES.register(recipe);
        var slots = new ItemStack[9];
        Arrays.fill(slots, ItemStack.EMPTY);
        slots[0] = new ItemStack(Items.APPLE);
        slots[1] = new ItemStack(Items.POTATO);
        var encoded = new ItemStack(Items.PAPER);
        AECraftingPattern.encode(encoded, recipe, slots, new ItemStack(Items.CARROT), true, false);
        var net = new CraftingTestNetwork();
        var pattern = net.addPattern(new AECraftingPattern(AEItemKey.of(encoded), CraftingTestNetwork.world()), 0);
        var golden = AEItemKey.of(Items.GOLDEN_APPLE);
        var potato = AEItemKey.of(Items.POTATO);
        var baked = AEItemKey.of(Items.BAKED_POTATO);
        var carrot = AEItemKey.of(Items.CARROT);
        // Establish the fixture's distinction: each substitution works against the encoded template, but their pair
        // is impossible. These calls validate the fixture; assertions below inspect planning only.
        assertTrue(Arrays.stream(pattern.getInputs()).anyMatch(input -> input.isValid(golden, CraftingTestNetwork.world())));
        assertTrue(Arrays.stream(pattern.getInputs()).anyMatch(input -> input.isValid(baked, CraftingTestNetwork.world())));
        var invalid = new InventoryCrafting(ContainerConstants.EMPTY_CONTAINER, 3, 3);
        invalid.setInventorySlotContents(0, golden.toStack());
        invalid.setInventorySlotContents(1, baked.toStack());
        assertFalse(recipe.matches(invalid, CraftingTestNetwork.world()));

        net.store(golden, 1);
        if (warm) {
            net.store(potato, 1);
            var validPlan = net.plan(carrot, 1);
            complete(net, validPlan, carrot, 1);
            materials(validPlan.usedItems(), golden, 1, potato, 1);
            net.stock.set(potato, 0);
        }
        net.store(baked, 1);
        var before = amounts(net.stock);
        try {
            var plan = net.plan(carrot, 1);
            assertEquals(new GenericStack(carrot, 1), plan.finalOutput());
            assertTrue(plan.simulation() || !amounts(plan.missingItems()).isEmpty(),
                "individually valid but incompatible inputs must not produce a submit-capable, zero-missing plan");
            withinStock(net, plan);
        } catch (CraftingCalculationFailure expected) {
            // An explicit planning rejection is also a valid outcome for an impossible recipe combination.
        }
        assertEquals(before, amounts(net.stock), "planning mutated storage");
    }
}
