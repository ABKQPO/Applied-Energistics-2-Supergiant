/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.integration.modules.hei;

import ae2.api.implementations.items.WirelessTerminalApi;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import ae2.client.NetworkAmount;
import ae2.container.AEBaseContainer;
import ae2.container.me.common.ContainerMEStorage;
import ae2.container.me.common.GridInventoryEntry;
import ae2.container.me.common.IClientRepo;
import ae2.core.network.InitNetwork;
import ae2.core.network.serverbound.RecipeTransferPacket;
import ae2.crafting.pattern.AEProcessingPattern;
import ae2.mixins.hei.AccessorRecipeLayout;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import mezz.jei.api.gui.IGuiIngredient;
import mezz.jei.api.gui.IGuiIngredientGroup;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IRecipeWrapper;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferInfo;
import mezz.jei.api.recipe.wrapper.ICraftingRecipeWrapper;
import mezz.jei.gui.recipes.RecipeLayout;
import mezz.jei.gui.recipes.RecipeTransferButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Adapter for the normal JEI/HEI slot contract. The adapter only serializes a recipe description; all material
 * ownership and slot mutation happen again on the server through {@link RecipeTransferPacket}.
 */
public final class StandardRecipeTransferHandler<C extends Container> implements IRecipeTransferHandler<C> {
    private static final Map<RecipeLayout, WeakReference<Container>> ACTIVE_RECIPE_LAYOUTS = new WeakHashMap<>();

    private final IRecipeTransferInfo<C> info;
    private final IRecipeTransferHandler<C> fallback;

    public StandardRecipeTransferHandler(IRecipeTransferInfo<C> info, IRecipeTransferHandler<C> fallback) {
        this.info = info;
        this.fallback = fallback;
    }

    private static void trackRecipeLayout(IRecipeLayout recipeLayout, Container container) {
        if (recipeLayout instanceof RecipeLayout concreteLayout) {
            ACTIVE_RECIPE_LAYOUTS.put(concreteLayout, new WeakReference<>(container));
        }
    }

    public static void refreshOpenRecipeTransferButtons() {
        EntityPlayer player = Minecraft.getMinecraft().player;
        if (player == null || player.openContainer == null) {
            return;
        }

        for (var entry : ACTIVE_RECIPE_LAYOUTS.entrySet()) {
            Container trackedContainer = entry.getValue().get();
            if (trackedContainer != player.openContainer) {
                continue;
            }
            RecipeLayout layout = entry.getKey();
            RecipeTransferButton button = layout.getRecipeTransferButton();
            if (button != null) {
                button.init(player.openContainer, player);
            }
        }
    }

    @Nullable
    private static ResourceLocation recipeId(IRecipeLayout recipeLayout) {
        if (!(recipeLayout instanceof AccessorRecipeLayout accessor)) {
            return null;
        }
        IRecipeWrapper wrapper = accessor.ae2$getRecipeWrapper();
        return wrapper instanceof ICraftingRecipeWrapper craftingWrapper ? craftingWrapper.getRegistryName() : null;
    }

    private static void addCandidate(List<AEItemKey> candidates, AEItemKey candidate) {
        if (candidate == null) {
            return;
        }
        for (int i = 0; i < candidates.size(); i++) {
            if (candidate.equals(candidates.get(i))) {
                return;
            }
        }
        candidates.add(candidate);
    }

    private static List<InputIngredient> inputIngredients(
        Map<Integer, ? extends IGuiIngredient<ItemStack>> guiIngredients) {
        var entries = new ObjectArrayList<Map.Entry<Integer, ? extends IGuiIngredient<ItemStack>>>(guiIngredients.size());
        entries.addAll(guiIngredients.entrySet());
        entries.sort(Comparator.comparingInt(Map.Entry::getKey));

        var result = new ObjectArrayList<InputIngredient>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            IGuiIngredient<ItemStack> ingredient = entry.getValue();
            if (ingredient != null && ingredient.isInput()) {
                // Keep empty input positions. They still consume a target recipe slot and must not shift the next
                // ingredient into it.
                result.add(new InputIngredient(entry.getKey(), ingredient));
            }
        }
        return result;
    }

    private static List<Slot> orderedRecipeSlots(List<Slot> slots) {
        boolean ordered = true;
        int previous = Integer.MIN_VALUE;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot == null || slot.slotNumber < previous) {
                ordered = false;
                break;
            }
            previous = slot.slotNumber;
        }
        if (ordered) {
            return slots;
        }
        var sorted = new ObjectArrayList<Slot>(slots.size());
        sorted.addAll(slots);
        sorted.sort(Comparator.comparingInt(slot -> slot.slotNumber));
        return sorted;
    }

    private static boolean hasFluidInputs(IRecipeLayout recipeLayout) {
        IGuiIngredientGroup<?> fluids = recipeLayout.getIngredientsGroup(VanillaTypes.FLUID);
        if (fluids == null || fluids.getGuiIngredients() == null) {
            return false;
        }
        for (var ingredient : fluids.getGuiIngredients().values()) {
            if (ingredient != null && ingredient.isInput()) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack displayedStack(IGuiIngredient<ItemStack> ingredient) {
        ItemStack displayed = ingredient.getDisplayedIngredient();
        if (displayed != null && !displayed.isEmpty()) {
            return displayed;
        }
        List<ItemStack> all = ingredient.getAllIngredients();
        if (all == null) {
            return ItemStack.EMPTY;
        }
        for (int i = 0; i < all.size(); i++) {
            ItemStack stack = all.get(i);
            if (stack != null && !stack.isEmpty()) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static List<GenericStack> pseudoOutputs(IRecipeLayout recipeLayout) {
        var bySlot = GenericIngredientHelper.getIngredients(recipeLayout, false, false,
            AEProcessingPattern.MAX_OUTPUT_SLOTS);
        var result = new ObjectArrayList<GenericStack>(Math.min(bySlot.size(), AEProcessingPattern.MAX_OUTPUT_SLOTS));
        for (int i = 0; i < bySlot.size() && result.size() < AEProcessingPattern.MAX_OUTPUT_SLOTS; i++) {
            var stacks = bySlot.get(i);
            if (!stacks.isEmpty()) {
                result.add(stacks.getFirst());
            }
        }
        return result;
    }

    private static <C extends Container> AEKey2LongMap localAvailability(C container, EntityPlayer player,
                                                                         List<RecipeTransferPacket.Input> inputs) {
        var available = new AEKey2LongMap.SaturatingOpenHashMap();
        for (int i = 0; i < player.inventory.mainInventory.size(); i++) {
            if (container instanceof AEBaseContainer aeContainer && aeContainer.isPlayerInventorySlotLocked(i)) {
                continue;
            }
            ItemStack stack = player.inventory.mainInventory.get(i);
            if (stack == null || stack.isEmpty() || WirelessTerminalApi.ofStack(stack) != null) {
                continue;
            }
            AEItemKey key = AEItemKey.of(stack);
            if (key != null) {
                available.addTo(key, stack.getCount());
            }
        }
        for (int i = 0; i < inputs.size(); i++) {
            Slot slot = findSlot(container, inputs.get(i).targetSlot());
            if (slot == null) {
                continue;
            }
            ItemStack stack = slot.getStack();
            AEItemKey key = stack.isEmpty() ? null : AEItemKey.of(stack);
            if (key != null) {
                available.addTo(key, stack.getCount());
            }
        }
        return available;
    }

    private static <C extends Container> CraftingRecipeTransferAnalysis analyzeAvailability(
        C container, EntityPlayer player, List<RecipeTransferPacket.Input> inputs, List<Integer> guiSlots) {
        AEKey2LongMap available = localAvailability(container, player, inputs);
        var reserved = new AEKey2LongMap.SaturatingOpenHashMap();
        IntSet missing = new IntOpenHashSet();
        IntSet craftable = new IntOpenHashSet();
        IClientRepo repo = container instanceof ContainerMEStorage storage ? storage.getClientRepo() : null;
        if (repo == null) {
            NetworkAmount.refreshWirelessTerminals(player);
        }
        boolean hasAvailableIngredients = false;
        boolean hasPendingNetworkQuery = false;

        for (int i = 0; i < inputs.size(); i++) {
            RecipeTransferPacket.Input input = inputs.get(i);
            Slot target = findSlot(container, input.targetSlot());
            ItemStack existing = target == null ? ItemStack.EMPTY : target.getStack();
            AEItemKey selected = null;
            long bestAvailable = -1;
            boolean bestCraftable = false;
            for (int candidateIndex = 0; candidateIndex < input.candidates().size(); candidateIndex++) {
                AEItemKey candidate = input.candidates().get(candidateIndex);
                if (!existing.isEmpty() && !candidate.equals(AEItemKey.of(existing))) {
                    continue;
                }
                if (repo == null) {
                    NetworkAmount.requestAmount(candidate);
                    hasPendingNetworkQuery |= NetworkAmount.isQueryPending(candidate);
                }
                long networkAmount = NetworkAmount.knownAmount(candidate);
                boolean candidateCraftable = NetworkAmount.knownCraftable(candidate);
                if (repo != null) {
                    GridInventoryEntry entry = repo.getByKey(candidate);
                    if (entry != null) {
                        networkAmount = Math.max(networkAmount, entry.storedAmount());
                        candidateCraftable |= entry.craftable();
                    }
                }
                long total = available.getLong(candidate) - reserved.getLong(candidate);
                if (networkAmount > 0) {
                    total = total > Long.MAX_VALUE - networkAmount ? Long.MAX_VALUE : total + networkAmount;
                }
                if (total > bestAvailable) {
                    selected = candidate;
                    bestAvailable = total;
                    bestCraftable = candidateCraftable;
                }
            }
            if (selected == null) {
                missing.add(guiSlots.get(i).intValue());
                continue;
            }
            long supplied = Math.clamp(bestAvailable, 0, input.amount());
            if (supplied > 0) {
                hasAvailableIngredients = true;
                reserved.addTo(selected, supplied);
            }
            if (supplied < input.amount()) {
                if (bestCraftable) {
                    craftable.add(guiSlots.get(i).intValue());
                } else {
                    missing.add(guiSlots.get(i).intValue());
                }
            }
        }
        return CraftingRecipeTransferAnalysis.analyzeGuiSlots(missing, craftable, inputs.size(),
            hasAvailableIngredients, hasPendingNetworkQuery);
    }

    @Nullable
    private static Slot findSlot(Container container, int slotNumber) {
        for (int i = 0; i < container.inventorySlots.size(); i++) {
            Slot slot = container.inventorySlots.get(i);
            if (slot != null && slot.slotNumber == slotNumber) {
                return slot;
            }
        }
        return null;
    }

    @Override
    public @NotNull Class<C> getContainerClass() {
        return info.getContainerClass();
    }

    @Override
    public IRecipeTransferError transferRecipe(@NotNull C container,
                                               @NotNull IRecipeLayout recipeLayout,
                                               @NotNull EntityPlayer player,
                                               boolean maxTransfer,
                                               boolean doTransfer) {
        if (!info.canHandle(container)) {
            return fallback.transferRecipe(container, recipeLayout, player, maxTransfer, doTransfer);
        }

        if (hasFluidInputs(recipeLayout)) {
            // Fluid and other non-item resources require their original protocol. Silently dropping them would report
            // a successful item transfer for an incomplete recipe.
            return fallback.transferRecipe(container, recipeLayout, player, maxTransfer, doTransfer);
        }

        IGuiIngredientGroup<ItemStack> group = recipeLayout.getIngredientsGroup(VanillaTypes.ITEM);
        if (group == null || group.getGuiIngredients() == null) {
            return fallback.transferRecipe(container, recipeLayout, player, maxTransfer, doTransfer);
        }

        List<InputIngredient> ingredients = inputIngredients(group.getGuiIngredients());
        List<Slot> slots = info.getRecipeSlots(container);
        if (slots == null || ingredients.isEmpty() || slots.isEmpty()) {
            return fallback.transferRecipe(container, recipeLayout, player, maxTransfer, doTransfer);
        }
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i) == null || slots.get(i).slotNumber < 0) {
                return fallback.transferRecipe(container, recipeLayout, player, maxTransfer, doTransfer);
            }
        }
        slots = orderedRecipeSlots(slots);

        var requests = new ObjectArrayList<RecipeTransferPacket.Input>(Math.min(slots.size(), ingredients.size()));
        var guiSlots = new ObjectArrayList<Integer>(Math.min(slots.size(), ingredients.size()));
        int count = Math.min(slots.size(), ingredients.size());
        for (int i = 0; i < count; i++) {
            InputIngredient inputIngredient = ingredients.get(i);
            IGuiIngredient<ItemStack> ingredient = inputIngredient.ingredient();
            ItemStack stack = displayedStack(ingredient);
            if (stack.isEmpty()) {
                continue;
            }

            var candidates = new ObjectArrayList<AEItemKey>();
            addCandidate(candidates, AEItemKey.of(stack));
            List<ItemStack> all = ingredient.getAllIngredients();
            if (all != null) {
                for (int candidateIndex = 0; candidateIndex < all.size(); candidateIndex++) {
                    ItemStack candidateStack = all.get(candidateIndex);
                    if (candidateStack != null && !candidateStack.isEmpty()) {
                        addCandidate(candidates, AEItemKey.of(candidateStack));
                    }
                }
            }
            if (candidates.isEmpty()) {
                continue;
            }

            int amount = Math.max(1, stack.getCount());
            Slot slot = slots.get(i);
            if (slot == null || slot.slotNumber < 0) {
                continue;
            }
            requests.add(new RecipeTransferPacket.Input(slot.slotNumber, amount, candidates));
            guiSlots.add(inputIngredient.guiSlot());
        }

        if (requests.isEmpty()) {
            return fallback.transferRecipe(container, recipeLayout, player, maxTransfer, doTransfer);
        }
        if (!doTransfer) {
            trackRecipeLayout(recipeLayout, container);
            CraftingRecipeTransferAnalysis analysis = analyzeAvailability(container, player, requests, guiSlots);
            return analysis.outcome() == CraftingRecipeTransferAnalysis.Outcome.READY
                ? null : CraftingRecipeTransferUserError.create(recipeLayout, analysis, analysis.canTransfer());
        }

        InitNetwork.sendToServer(new RecipeTransferPacket(container.windowId, UUID.randomUUID(), maxTransfer,
            GuiScreen.isCtrlKeyDown(), info.requireCompleteSets(), recipeId(recipeLayout),
            pseudoOutputs(recipeLayout), requests));
        return null;
    }

    private record InputIngredient(int guiSlot, IGuiIngredient<ItemStack> ingredient) {
    }
}
