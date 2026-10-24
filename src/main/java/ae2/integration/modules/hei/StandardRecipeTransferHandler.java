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
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
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
                if (entry instanceof Int2ObjectMap.Entry<? extends IGuiIngredient<ItemStack>> e) {
                    result.add(new InputIngredient(e.getIntKey(), ingredient));
                } else {
                    result.add(new InputIngredient(entry.getKey(), ingredient));
                }
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
        C container, EntityPlayer player, List<RecipeTransferPacket.Input> inputs, IntList guiSlots) {
        AEKey2LongMap available = localAvailability(container, player, inputs);
        var reservedLocal = new AEKey2LongMap.SaturatingOpenHashMap();
        var reservedNetwork = new AEKey2LongMap.SaturatingOpenHashMap();
        IntSet missing = new IntOpenHashSet();
        IntSet craftable = new IntOpenHashSet();
        IntSet network = new IntOpenHashSet();
        boolean isMeGui = container instanceof ContainerMEStorage;
        IClientRepo repo = isMeGui ? ((ContainerMEStorage) container).getClientRepo() : null;
        if (!isMeGui) {
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
            long bestLocalAvailable = 0;
            long bestNetworkAvailable = 0;
            boolean bestCraftable = false;
            for (int candidateIndex = 0; candidateIndex < input.candidates().size(); candidateIndex++) {
                AEItemKey candidate = input.candidates().get(candidateIndex);
                if (!existing.isEmpty() && !candidate.equals(AEItemKey.of(existing))) {
                    continue;
                }
                if (!isMeGui) {
                    NetworkAmount.requestAmount(candidate);
                    hasPendingNetworkQuery |= NetworkAmount.isQueryPending(candidate);
                }
                long networkAmount = !isMeGui ? Math.max(0, NetworkAmount.knownAmount(candidate)) : 0;
                boolean candidateCraftable = !isMeGui && NetworkAmount.knownCraftable(candidate);
                if (repo != null) {
                    GridInventoryEntry entry = repo.getByKey(candidate);
                    if (entry != null) {
                        networkAmount = Math.max(networkAmount, entry.storedAmount());
                        candidateCraftable |= entry.craftable();
                    }
                }
                long localAvailable = Math.max(0, available.getLong(candidate) - reservedLocal.getLong(candidate));
                long networkAvailable = Math.max(0, networkAmount - reservedNetwork.getLong(candidate));
                long total = localAvailable > Long.MAX_VALUE - networkAvailable
                    ? Long.MAX_VALUE : localAvailable + networkAvailable;
                if (total > bestAvailable) {
                    selected = candidate;
                    bestAvailable = total;
                    bestLocalAvailable = localAvailable;
                    bestNetworkAvailable = networkAvailable;
                    bestCraftable = candidateCraftable;
                }
            }
            if (selected == null) {
                missing.add(guiSlots.getInt(i));
                continue;
            }
            long supplied = Math.clamp(bestAvailable, 0, input.amount());
            if (supplied > 0) {
                long suppliedLocally = Math.min(supplied, bestLocalAvailable);
                hasAvailableIngredients |= suppliedLocally > 0;
                if (suppliedLocally > 0) {
                    reservedLocal.addTo(selected, suppliedLocally);
                }
                long suppliedNetwork = supplied - suppliedLocally;
                if (suppliedNetwork > 0) {
                    reservedNetwork.addTo(selected, suppliedNetwork);
                }
            }
            int guiSlot = guiSlots.getInt(i);
            boolean availableInNetwork = !isMeGui
                && bestNetworkAvailable > 0
                && bestLocalAvailable < input.amount();
            if (availableInNetwork) {
                network.add(guiSlot);
            }
            if (supplied < input.amount()) {
                if (bestCraftable) {
                    craftable.add(guiSlot);
                } else {
                    missing.add(guiSlot);
                }
            }
        }
        return CraftingRecipeTransferAnalysis.analyzeGuiSlots(missing, craftable, network, inputs.size(),
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
        var guiSlots = new IntArrayList(Math.min(slots.size(), ingredients.size()));
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
