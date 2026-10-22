/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.api.integrations.recipetransfer;

import ae2.api.stacks.AEKey;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Mutable server-side options for one recipe-transfer request.
 *
 * <p>This type intentionally contains no JEI/HEI layout or GUI classes. A target can customize the source, quantity
 * policy, and recipe identity without implementing an AE container interface.</p>
 */
public final class RecipeTransferContext {
    private RecipeTransferSource source;
    @Nullable
    private Object sourceIdentity;
    @Nullable
    private RecipeTransferInventoryView inventoryView;
    @Nullable
    private RecipeTransferTargetInput targetInput;
    private RecipeTransferQuantityPolicy quantityPolicy = RecipeTransferQuantityPolicy.JEI;
    @Nullable
    private ResourceLocation recipeId;
    private boolean requireCompleteSets;

    public RecipeTransferContext(RecipeTransferSource source) {
        this.source = Objects.requireNonNull(source, "source");
        this.sourceIdentity = source.identity();
    }

    public RecipeTransferSource source() {
        return source;
    }

    public void setSource(RecipeTransferSource source) {
        this.source = Objects.requireNonNull(source, "source");
        this.sourceIdentity = source.identity();
    }

    @Nullable
    public Object sourceIdentity() {
        return sourceIdentity;
    }

    public void setSourceIdentity(Object sourceIdentity) {
        this.sourceIdentity = Objects.requireNonNull(sourceIdentity, "sourceIdentity");
    }

    /**
     * Optional read-only view used by a client preview or a target-specific preflight.
     */
    @Nullable
    public RecipeTransferInventoryView inventoryView() {
        return inventoryView;
    }

    public void setInventoryView(RecipeTransferInventoryView inventoryView) {
        this.inventoryView = Objects.requireNonNull(inventoryView, "inventoryView");
    }

    /**
     * Optional normal input protocol supplied by a target adapter.
     */
    @Nullable
    public RecipeTransferTargetInput targetInput() {
        return targetInput;
    }

    public void setTargetInput(RecipeTransferTargetInput targetInput) {
        this.targetInput = Objects.requireNonNull(targetInput, "targetInput");
    }

    public RecipeTransferQuantityPolicy quantityPolicy() {
        return quantityPolicy;
    }

    public void setQuantityPolicy(RecipeTransferQuantityPolicy quantityPolicy) {
        this.quantityPolicy = Objects.requireNonNull(quantityPolicy, "quantityPolicy");
    }

    @Nullable
    public ResourceLocation recipeId() {
        return recipeId;
    }

    public void setRecipeId(@Nullable ResourceLocation recipeId) {
        this.recipeId = recipeId;
    }

    public boolean requireCompleteSets() {
        return requireCompleteSets;
    }

    @SuppressWarnings("unused")
    public void setRequireCompleteSets(boolean requireCompleteSets) {
        this.requireCompleteSets = requireCompleteSets;
    }

    /**
     * Returns the amount available for the requested key without reserving it.
     */
    public long available(AEKey key) {
        return source.available(key);
    }
}
